/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import java.net.Socket;
import java.util.List;

import okhttp3.Address;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Route;
import okhttp3.internal.Util;
import okhttp3.internal.http.ExchangeCodec;

import static okhttp3.internal.Util.closeQuietly;

/**
 * Attempts to find the connections for a sequence of exchanges. This uses the following strategies:
 *
 * <ol>
 * <li>If the current call already has a connection that can satisfy the request it is used.
 * Using the same connection for an initial exchange and its follow-ups may improve locality.
 *
 * <li>If there is a connection in the pool that can satisfy the request it is used. Note that
 * it is possible for shared exchanges to make requests to different host names! See {@link
 * RealConnection#isEligible} for details.
 *
 * <li>If there's no existing connection, make a list of routes (which may require blocking DNS
 * lookups) and attempt a new connection them. When failures occur, retries iterate the list
 * of available routes.
 * </ol>
 *
 * <p>If the pool gains an eligible connection while DNS, TCP, or TLS work is in flight, this finder
 * will prefer pooled connections. Only pooled HTTP/2 connections are used for such de-duplication.
 *
 * <p>It is possible to cancel the finding process.
 */
//负责连接的创建，把创建好的连接放入连接池，如果连接池中已经有该连接，就直接取出复用
final class ExchangeFinder {
    private final Transmitter transmitter;
    private final Address address;
    //连接池
    private final RealConnectionPool connectionPool;
    private final Call call;
    private final EventListener eventListener;

    private RouteSelector.Selection routeSelection;

    // State guarded by connectionPool.
    private final RouteSelector routeSelector;
    private RealConnection connectingConnection;
    private boolean hasStreamFailure;
    private Route nextRouteToTry;

    ExchangeFinder(Transmitter transmitter, RealConnectionPool connectionPool,
                   Address address, Call call, EventListener eventListener) {
        this.transmitter = transmitter;
        this.connectionPool = connectionPool;
        this.address = address;
        this.call = call;
        this.eventListener = eventListener;
        this.routeSelector = new RouteSelector(
                address, connectionPool.routeDatabase, call, eventListener);
    }

    public ExchangeCodec find(OkHttpClient client, Interceptor.Chain chain, boolean doExtensiveHealthChecks) {
        int connectTimeout = chain.connectTimeoutMillis();
        int readTimeout = chain.readTimeoutMillis();
        int writeTimeout = chain.writeTimeoutMillis();
        int pingIntervalMillis = client.pingIntervalMillis();
        //客户端设置的失败后是否重连
        boolean connectionRetryEnabled = client.retryOnConnectionFailure();

        try {
            //获取到一个连接
            RealConnection resultConnection = findHealthyConnection(connectTimeout, readTimeout, writeTimeout, pingIntervalMillis, connectionRetryEnabled, doExtensiveHealthChecks);
            //返回一个http的编码解码器
            return resultConnection.newCodec(client, chain);
        } catch (RouteException e) {
            trackFailure();
            throw e;
        } catch (IOException e) {
            trackFailure();
            throw new RouteException(e);
        }
    }

    /**
     * Finds a connection and returns it if it is healthy. If it is unhealthy the process is repeated
     * until a healthy connection is found.
     */
    //获取到一个可用的连接，如果获取到的连接不可用，那么会循环直到获取到结果
    private RealConnection findHealthyConnection(int connectTimeout, int readTimeout,
                                                 int writeTimeout, int pingIntervalMillis, boolean connectionRetryEnabled,
                                                 boolean doExtensiveHealthChecks) throws IOException {
        while (true) {
            RealConnection candidate = findConnection(connectTimeout, readTimeout, writeTimeout, pingIntervalMillis, connectionRetryEnabled);

            // If this is a brand new connection, we can skip the extensive health checks.
            //如果返回的连接是新创建的话，直接跳过各种检测
            synchronized (connectionPool) {
                if (candidate.successCount == 0 && !candidate.isMultiplexed()) {
                    return candidate;
                }
            }

            // Do a (potentially slow) check to confirm that the pooled connection is still good. If it
            // isn't, take it out of the pool and start again.
            //
            if (!candidate.isHealthy(doExtensiveHealthChecks)) {
                candidate.noNewExchanges();
                continue;
            }

            return candidate;
        }
    }

    /**
     * Returns a connection to host a new stream. This prefers the existing connection if it exists, then the pool, finally building a new connection.
     */
    //返回一个持有新的流（即输入输出流）连接。如果连接已存在则从池中获取，否则就创建一个新的连接
    private RealConnection findConnection(int connectTimeout, int readTimeout, int writeTimeout, int pingIntervalMillis, boolean connectionRetryEnabled) throws IOException {
        //标识是否是从池中获取的连接
        boolean foundPooledConnection = false;
        //返回的结果
        RealConnection result = null;
        Route selectedRoute = null;
        //之前创建的连接
        RealConnection releasedConnection;
        Socket toClose;
        synchronized (connectionPool) {
            if (transmitter.isCanceled()) throw new IOException("Canceled");
            hasStreamFailure = false; // This is a fresh attempt.

            // Attempt to use an already-allocated connection. We need to be careful here because our
            // already-allocated connection may have been restricted from creating new exchanges.
            //获取已经创建过的连接。
            releasedConnection = transmitter.connection;
            //创建过的连接可能不允许创建新的流。如果不允许创建新的流，则需要将其对应的socket关闭
            toClose = transmitter.connection != null && transmitter.connection.noNewExchanges
                    ? transmitter.releaseConnectionNoEvents()
                    : null;

            if (transmitter.connection != null) {
                // We had an already-allocated connection and it's good.
                //证明之前创建的连接是可以使用的。这里就使用之前的结果。那么连接不需要释放了
                result = transmitter.connection;
                releasedConnection = null;
            }

            if (result == null) {
                //如果连接不可用，则尝试从连接池获取到一个连接
                // Attempt to get a connection from the pool.
                if (connectionPool.transmitterAcquirePooledConnection(address, transmitter, null, false)) {
                    foundPooledConnection = true;
                    result = transmitter.connection;
                } else if (nextRouteToTry != null) {
                    selectedRoute = nextRouteToTry;
                    nextRouteToTry = null;
                } else if (retryCurrentRoute()) {
                    selectedRoute = transmitter.connection.route();
                }
            }
        }
        //关闭之前的连接
        closeQuietly(toClose);

        if (releasedConnection != null) {
            eventListener.connectionReleased(call, releasedConnection);
        }
        if (foundPooledConnection) {
            eventListener.connectionAcquired(call, result);
        }
        if (result != null) {
            //已经找到一个可以使用的连接（之前申请的或者从池中获取到的），则直接返回
            // If we found an already-allocated or pooled connection, we're done.
            return result;
        }

        // If we need a route selection, make one. This is a blocking operation.
        //看看是否需要路由选择，多IP操作
        boolean newRouteSelection = false;
        if (selectedRoute == null && (routeSelection == null || !routeSelection.hasNext())) {
            newRouteSelection = true;
            routeSelection = routeSelector.next();
        }

        List<Route> routes = null;
        synchronized (connectionPool) {
            if (transmitter.isCanceled()) throw new IOException("Canceled");

            if (newRouteSelection) {
                // Now that we have a set of IP addresses, make another attempt at getting a connection from
                // the pool. This could match due to connection coalescing.
                //使用多IP，从连接池中尝试获取
                routes = routeSelection.getAll();
                if (connectionPool.transmitterAcquirePooledConnection(address, transmitter, routes, false)) {
                    foundPooledConnection = true;
                    result = transmitter.connection;
                }
            }
            //在连接池没有找到可用的连接
            if (!foundPooledConnection) {
                if (selectedRoute == null) {
                    selectedRoute = routeSelection.next();
                }

                // Create a connection and assign it to this allocation immediately. This makes it possible
                // for an asynchronous cancel() to interrupt the handshake we're about to do.
                //创建一个新的连接
                result = new RealConnection(connectionPool, selectedRoute);
                connectingConnection = result;
            }
        }

        // If we found a pooled connection on the 2nd time around, we're done.
        //连接池中找到了，则直接返回
        if (foundPooledConnection) {
            eventListener.connectionAcquired(call, result);
            return result;
        }

        // Do TCP + TLS handshakes. This is a blocking operation.
        //进入这里证明是新创建的连接，调用connect方法进行连接。进行TCP+TLS握手。这是一种阻塞操作
        result.connect(connectTimeout, readTimeout, writeTimeout, pingIntervalMillis,connectionRetryEnabled, call, eventListener);
        connectionPool.routeDatabase.connected(result.route());

        Socket socket = null;
        synchronized (connectionPool) {
            connectingConnection = null;
            // Last attempt at connection coalescing, which only occurs if we attempted multiple
            // concurrent connections to the same host.
            //如果我们刚刚创建了同一地址的多路复用连接，释放这个连接并获取那个连接
            if (connectionPool.transmitterAcquirePooledConnection(address, transmitter, routes, true)) {
                // We lost the race! Close the connection we created and return the pooled connection.
                result.noNewExchanges = true;
                socket = result.socket();
                result = transmitter.connection;

                // It's possible for us to obtain a coalesced connection that is immediately unhealthy. In
                // that case we will retry the route we just successfully connected with.
                nextRouteToTry = selectedRoute;
            } else {
                //将连接放入到连接池
                connectionPool.put(result);
                //把刚刚新建的连接保存到Transmitter的connection字段
                transmitter.acquireConnectionNoEvents(result);
            }
        }
        closeQuietly(socket);

        eventListener.connectionAcquired(call, result);
        return result;
    }

    RealConnection connectingConnection() {
        assert (Thread.holdsLock(connectionPool));
        return connectingConnection;
    }

    void trackFailure() {
        assert (!Thread.holdsLock(connectionPool));
        synchronized (connectionPool) {
            hasStreamFailure = true; // Permit retries.
        }
    }

    /**
     * Returns true if there is a failure that retrying might fix.
     */
    boolean hasStreamFailure() {
        synchronized (connectionPool) {
            return hasStreamFailure;
        }
    }

    /**
     * Returns true if a current route is still good or if there are routes we haven't tried yet.
     */
    boolean hasRouteToTry() {
        synchronized (connectionPool) {
            if (nextRouteToTry != null) {
                return true;
            }
            if (retryCurrentRoute()) {
                // Lock in the route because retryCurrentRoute() is racy and we don't want to call it twice.
                nextRouteToTry = transmitter.connection.route();
                return true;
            }
            return (routeSelection != null && routeSelection.hasNext())
                    || routeSelector.hasNext();
        }
    }

    /**
     * Return true if the route used for the current connection should be retried, even if the
     * connection itself is unhealthy. The biggest gotcha here is that we shouldn't reuse routes from
     * coalesced connections.
     */
    private boolean retryCurrentRoute() {
        return transmitter.connection != null
                && transmitter.connection.routeFailureCount == 0
                && Util.sameConnection(transmitter.connection.route().address().url(), address.url());
    }
}
