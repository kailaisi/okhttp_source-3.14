/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection;

import okhttp3.Address;
import okhttp3.Route;
import okhttp3.internal.Util;
import okhttp3.internal.connection.Transmitter.TransmitterReference;
import okhttp3.internal.platform.Platform;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.ref.Reference;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static okhttp3.internal.Util.closeQuietly;

//连接池
public final class RealConnectionPool {
    /**
     * Background threads are used to cleanup expired connections. There will be at most a single
     * thread running per connection pool. The thread pool executor permits the pool itself to be
     * garbage collected.
     */
    //线程池
    private static final Executor executor = new ThreadPoolExecutor(0 /* corePoolSize */,
            Integer.MAX_VALUE /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Util.threadFactory("OkHttp ConnectionPool", true));

    /**
     * The maximum number of idle connections for each address.
     */
    //最大空闲连接数
    private final int maxIdleConnections;
    //保持连接的时间
    private final long keepAliveDurationNs;
    //用于清理连接的任务，需要在线程池executor去调用
    //连接使用完毕，长时间不释放，也会造成资源的浪费，所以进行定时的清理
    private final Runnable cleanupRunnable = () -> {
        while (true) {
            //执行连接的清理工作
            long waitNanos = cleanup(System.nanoTime());
            if (waitNanos == -1) return;
            if (waitNanos > 0) {
                long waitMillis = waitNanos / 1000000L;
                waitNanos -= (waitMillis * 1000000L);
                synchronized (RealConnectionPool.this) {
                    try {
                        //等待多少秒以后再次执行清理
                        RealConnectionPool.this.wait(waitMillis, (int) waitNanos);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    };
    //保存的连接队列。因为连接比较耗时，所以使用连接池技术
    private final Deque<RealConnection> connections = new ArrayDeque<>();
    final RouteDatabase routeDatabase = new RouteDatabase();
    //是否正在执行连接清理
    boolean cleanupRunning;

    public RealConnectionPool(int maxIdleConnections, long keepAliveDuration, TimeUnit timeUnit) {
        this.maxIdleConnections = maxIdleConnections;
        this.keepAliveDurationNs = timeUnit.toNanos(keepAliveDuration);

        // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
        if (keepAliveDuration <= 0) {
            throw new IllegalArgumentException("keepAliveDuration <= 0: " + keepAliveDuration);
        }
    }

    //空闲连接数
    public synchronized int idleConnectionCount() {
        int total = 0;
        for (RealConnection connection : connections) {
            if (connection.transmitters.isEmpty()) total++;
        }
        return total;
    }

    public synchronized int connectionCount() {
        return connections.size();
    }

    /**
     * Attempts to acquire a recycled connection to {@code address} for {@code transmitter}. Returns
     * true if a connection was acquired.
     *
     * <p>If {@code routes} is non-null these are the resolved routes (ie. IP addresses) for the
     * connection. This is used to coalesce related domains to the same HTTP/2 connection, such as
     * {@code square.com} and {@code square.ca}.
     */
    boolean transmitterAcquirePooledConnection(Address address, Transmitter transmitter,
                                               @Nullable List<Route> routes, boolean requireMultiplexed) {
        assert (Thread.holdsLock(this));
        for (RealConnection connection : connections) {
            if (requireMultiplexed && !connection.isMultiplexed()) continue;
            if (!connection.isEligible(address, routes)) continue;
            transmitter.acquireConnectionNoEvents(connection);
            return true;
        }
        return false;
    }

    void put(RealConnection connection) {
        assert (Thread.holdsLock(this));
        if (!cleanupRunning) {
            cleanupRunning = true;
            executor.execute(cleanupRunnable);
        }
        connections.add(connection);
    }

    /**
     * Notify this pool that {@code connection} has become idle. Returns true if the connection has
     * been removed from the pool and should be closed.
     */
    boolean connectionBecameIdle(RealConnection connection) {
        assert (Thread.holdsLock(this));
        if (connection.noNewExchanges || maxIdleConnections == 0) {
            connections.remove(connection);
            return true;
        } else {
            notifyAll(); // Awake the cleanup thread: we may have exceeded the idle connection limit.
            return false;
        }
    }

    public void evictAll() {
        List<RealConnection> evictedConnections = new ArrayList<>();
        synchronized (this) {
            for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
                RealConnection connection = i.next();
                if (connection.transmitters.isEmpty()) {
                    connection.noNewExchanges = true;
                    evictedConnections.add(connection);
                    i.remove();
                }
            }
        }

        for (RealConnection connection : evictedConnections) {
            closeQuietly(connection.socket());
        }
    }

    /**
     * Performs maintenance on this pool, evicting the connection that has been idle the longest if
     * either it has exceeded the keep alive limit or the idle connections limit.
     *
     * <p>Returns the duration in nanos to sleep until the next scheduled call to this method. Returns
     * -1 if no further cleanups are required.
     */
    //主要是在线程池中执行。如果连接超过了keep alive或者空闲连接数。那么就会关闭连接
    //返回下一次要清理的时间
    long cleanup(long now) {
        int inUseConnectionCount = 0;
        int idleConnectionCount = 0;
        RealConnection longestIdleConnection = null;
        long longestIdleDurationNs = Long.MIN_VALUE;

        // Find either a connection to evict, or the time that the next eviction is due.
        synchronized (this) {
            for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
                RealConnection connection = i.next();

                // If the connection is in use, keep searching.
                //当前连接正在使用，直接跳过
                if (pruneAndGetAllocationCount(connection, now) > 0) {
                    //正在使用的线程数+1
                    inUseConnectionCount++;
                    continue;
                }
                //空闲连接数+1
                idleConnectionCount++;

                // If the connection is ready to be evicted, we're done.
                //记录keepalive时间最长的那个空闲连接
                long idleDurationNs = now - connection.idleAtNanos;
                if (idleDurationNs > longestIdleDurationNs) {
                    longestIdleDurationNs = idleDurationNs;
                    longestIdleConnection = connection;
                }
            }
            //超过了最大连接时长 或者 最大空闲连接数超了。则移除空闲最长的那个连接
            if (longestIdleDurationNs >= this.keepAliveDurationNs || idleConnectionCount > this.maxIdleConnections) {
                // We've found a connection to evict. Remove it from the list, then close it below (outside
                // of the synchronized block).
                connections.remove(longestIdleConnection);
            } else if (idleConnectionCount > 0) {
                // A connection will be ready to evict soon.
                //返回时间最长空闲连接的连接需要清理的时间点
                return keepAliveDurationNs - longestIdleDurationNs;
            } else if (inUseConnectionCount > 0) {
                // All connections are in use. It'll be at least the keep alive duration 'til we run again.
                //所有的连接都处于使用之中。
                return keepAliveDurationNs;
            } else {
                // No connections, idle or in use.
                //没有连接
                cleanupRunning = false;
                return -1;
            }
        }
        //走到这里说明移除了空闲时间最长的那个连接。将连接关闭。这里为什么弄出来啊？很好奇
        closeQuietly(longestIdleConnection.socket());

        // Cleanup again immediately.
        return 0;
    }

    /**
     * Prunes any leaked transmitters and then returns the number of remaining live transmitters on
     * {@code connection}. Transmitters are leaked if the connection is tracking them but the
     * application code has abandoned them. Leak detection is imprecise and relies on garbage
     * collection.
     */
    private int pruneAndGetAllocationCount(RealConnection connection, long now) {
        List<Reference<Transmitter>> references = connection.transmitters;
        for (int i = 0; i < references.size(); ) {
            Reference<Transmitter> reference = references.get(i);

            if (reference.get() != null) {
                i++;
                continue;
            }

            // We've discovered a leaked transmitter. This is an application bug.
            TransmitterReference transmitterRef = (TransmitterReference) reference;
            String message = "A connection to " + connection.route().address().url()
                    + " was leaked. Did you forget to close a response body?";
            Platform.get().logCloseableLeak(message, transmitterRef.callStackTrace);

            references.remove(i);
            connection.noNewExchanges = true;

            // If this was the last allocation, the connection is eligible for immediate eviction.
            if (references.isEmpty()) {
                connection.idleAtNanos = now - keepAliveDurationNs;
                return 0;
            }
        }

        return references.size();
    }

    /**
     * Track a bad route in the route database. Other routes will be attempted first.
     */
    public void connectFailed(Route failedRoute, IOException failure) {
        // Tell the proxy selector when we fail to connect on a fresh connection.
        if (failedRoute.proxy().type() != Proxy.Type.DIRECT) {
            Address address = failedRoute.address();
            address.proxySelector().connectFailed(
                    address.url().uri(), failedRoute.proxy().address(), failure);
        }

        routeDatabase.failed(failedRoute);
    }
}
