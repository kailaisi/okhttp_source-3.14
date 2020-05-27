/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.http;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import javax.annotation.Nullable;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.connection.Exchange;
import okhttp3.internal.connection.RouteException;
import okhttp3.internal.connection.Transmitter;
import okhttp3.internal.http2.ConnectionShutdownException;

import static java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.Util.sameConnection;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

/**
 * This interceptor recovers from failures and follows redirects as necessary. It may throw an
 * {@link IOException} if the call was canceled.
 */
//当接收到失败或者重定向的错误信息时，进行处理
public final class RetryAndFollowUpInterceptor implements Interceptor {
  /**
   * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects; Firefox,
   * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
   */
  private static final int MAX_FOLLOW_UPS = 20;

  private final OkHttpClient client;

  public RetryAndFollowUpInterceptor(OkHttpClient client) {
    this.client = client;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    //获取请求。这里的请求已经不是最初始的request了，而是经过了前面的层层封装处理之后的request信息
    Request request = chain.request();
    RealInterceptorChain realChain = (RealInterceptorChain) chain;
    Transmitter transmitter = realChain.transmitter();

    int followUpCount = 0;
    Response priorResponse = null;
    while (true) {
      transmitter.prepareToConnect(request);

      if (transmitter.isCanceled()) {
        throw new IOException("Canceled");
      }

      Response response;
      boolean success = false;
      try {
        //执行责任链的proceed方法，其实是下一个拦截器的intercept方法
        response = realChain.proceed(request, transmitter, null);
        success = true;
      } catch (RouteException e) {
        //如果通过某个route连接失败则根据具体的情况尝试恢复。
        // The attempt to connect via a route failed. The request will not have been sent.
        if (!recover(e.getLastConnectException(), transmitter, false, request)) {
          throw e.getFirstConnectException();
        }
        continue;
      } catch (IOException e) {
        // An attempt to communicate with a server failed. The request may have been sent.
        //如果发生IO异常，则根据具体的情况尝试恢复。
        boolean requestSendStarted = !(e instanceof ConnectionShutdownException);
        if (!recover(e, transmitter, requestSendStarted, request)) throw e;
        continue;
      } finally {
        // The network call threw an exception. Release any resources.
        if (!success) {
          //释放资源
          transmitter.exchangeDoneDueToException();
        }
      }

      // Attach the prior response if it exists. Such responses never have a body.
      //如果发生过重定向的话，重定向的返回信息是priorResponse
      // 这里会将第一次重定向返回的数据，封装到response中。保证用户知道发生了重定向
      if (priorResponse != null) {
        response = response.newBuilder()
            .priorResponse(priorResponse.newBuilder()
                    .body(null)
                    .build())
            .build();
      }

      Exchange exchange = Internal.instance.exchange(response);
      Route route = exchange != null ? exchange.connection().route() : null;
      //判断返回的信息是否是重定向，
      // 如果是重定向的话，会生成新的请求信息，url指向了新的地址，body信息则根据实际情况进行处理，header也会根据需要进行移除
      Request followUp = followUpRequest(response, route);
      //如果非重定向，则返回
      if (followUp == null) {
        if (exchange != null && exchange.isDuplex()) {
          transmitter.timeoutEarlyExit();
        }
        return response;
      }
      //如果是重定向，则获取返回的重定向的请求body
      RequestBody followUpBody = followUp.body();
      //神秘方法isOneShot是一个可以覆写的方法，如果返回true，那么就消息体最多一次对{@link #writeTo}的调用，并且可以最多一次传输
      if (followUpBody != null && followUpBody.isOneShot()) {
        return response;
      }
      //关闭
      closeQuietly(response.body());
      if (transmitter.hasExchange()) {
        exchange.detachWithViolence();
      }
      //重定向次数不能超过设置的上限（20次）
      if (++followUpCount > MAX_FOLLOW_UPS) {
        throw new ProtocolException("Too many follow-up requests: " + followUpCount);
      }
      //将请求变化为重定向的那个请求，然后通过while循环去执行
      request = followUp;
      //将本次的返回信息保存到priorResponse中
      priorResponse = response;
    }
  }

  /**
   * Report and attempt to recover from a failure to communicate with a server. Returns true if
   * {@code e} is recoverable, or false if the failure is permanent. Requests with a body can only
   * be recovered if the body is buffered or if the failure occurred before the request has been
   * sent.
   */
  //判断发生的异常情况是否需要尝试恢复。只有当请求体被缓冲或者请求体在请求发送之前发生故障时，才可以恢复带有请求体的请求。
  // true: 可以尝试进行恢复的话，
  private boolean recover(IOException e, Transmitter transmitter, boolean requestSendStarted, Request userRequest) {
    // The application layer has forbidden retries.
    //如果应用层设置了不允许失败后重试，则返回false
    if (!client.retryOnConnectionFailure()) return false;

    // We can't send the request body again.
    //如果请求体已经开始发送了，但是请求体只允许写一次，则返回false
    if (requestSendStarted && requestIsOneShot(e, userRequest)) return false;

    // This exception is fatal.
    //异常是致命的 返回false
    if (!isRecoverable(e, requestSendStarted)) return false;

    // No more routes to attempt.
    //没有更多的route可供尝试 返回fasle
    if (!transmitter.canRetry()) return false;

    // For failure recovery, use the same route selector with a new connection.
    return true;
  }

  private boolean requestIsOneShot(IOException e, Request userRequest) {
    RequestBody requestBody = userRequest.body();
    return (requestBody != null && requestBody.isOneShot())
        || e instanceof FileNotFoundException;
  }

  private boolean isRecoverable(IOException e, boolean requestSendStarted) {
    // If there was a protocol problem, don't recover.
    if (e instanceof ProtocolException) {
      return false;
    }

    // If there was an interruption don't recover, but if there was a timeout connecting to a route
    // we should try the next route (if there is one).
    if (e instanceof InterruptedIOException) {
      return e instanceof SocketTimeoutException && !requestSendStarted;
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    if (e instanceof SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (e.getCause() instanceof CertificateException) {
        return false;
      }
    }
    if (e instanceof SSLPeerUnverifiedException) {
      // e.g. a certificate pinning error.
      return false;
    }

    // An example of one we might want to retry with a different route is a problem connecting to a
    // proxy and would manifest as a standard IOException. Unless it is one we know we should not
    // retry, we return true and try a new route.
    return true;
  }

  /**
   * Figures out the HTTP request to make in response to receiving {@code userResponse}. This will
   * either add authentication headers, follow redirects or handle a client request timeout. If a
   * follow-up is either unnecessary or not applicable, this returns null.
   */
  private Request followUpRequest(Response userResponse, @Nullable Route route) throws IOException {
    if (userResponse == null) throw new IllegalStateException();
    //返回的网络码
    int responseCode = userResponse.code();
    //请求的类型（POST，GET，HEAD，DELETE）
    final String method = userResponse.request().method();
    switch (responseCode) {
      case HTTP_PROXY_AUTH://407错误码
        Proxy selectedProxy = route != null
            ? route.proxy()
            : client.proxy();
        if (selectedProxy.type() != Proxy.Type.HTTP) {//如果没有使用代理，但是返回了407，那么直接抛出异常
          throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
        }
        return client.proxyAuthenticator().authenticate(route, userResponse);//代理验证

      case HTTP_UNAUTHORIZED:
        return client.authenticator().authenticate(route, userResponse);//身份认证

      case HTTP_PERM_REDIRECT://308错误码  重定向
      case HTTP_TEMP_REDIRECT://307错误码  临时重定向
        // "If the 307 or 308 status code is received in response to a request other than GET
        // or HEAD, the user agent MUST NOT automatically redirect the request"
        if (!method.equals("GET") && !method.equals("HEAD")) {//307、308 两种code不对 GET、HEAD 以外的请求重定向
          return null;
        }
        // fall-through
      case HTTP_MULT_CHOICE://300
      case HTTP_MOVED_PERM://301
      case HTTP_MOVED_TEMP://302
      case HTTP_SEE_OTHER://303
        // Does the client allow redirects?
        //如果客户端不允许重定向，则直接返回失败。
        if (!client.followRedirects()) return null;
        //获取header中的Location重定向的url信息
        String location = userResponse.header("Location");
        if (location == null) return null;
        //校验返回的url地址的合法性
        HttpUrl url = userResponse.request().url().resolve(location);

        // Don't follow redirects to unsupported protocols.
        if (url == null) return null;

        // If configured, don't follow redirects between SSL and non-SSL.
        //如果配置了不允许SSL(https)和non-SSL(http)之间重定向，则返回空
        boolean sameScheme = url.scheme().equals(userResponse.request().url().scheme());
        //如果不允许followSslRedirects重定向，但是重定向的地址是ssl
        if (!sameScheme && !client.followSslRedirects()) return null;

        // Most redirects don't include a request body.
        //大部分的重定向是没有requestbody的，所以需要使用原来的请求中的request信息
        Request.Builder requestBuilder = userResponse.request().newBuilder();
        if (HttpMethod.permitsRequestBody(method)) {//如果原来的请求支持requestbody
          //是否带body进行重定向
          final boolean maintainBody = HttpMethod.redirectsWithBody(method);
          if (HttpMethod.redirectsToGet(method)) {
            requestBuilder.method("GET", null);
          } else {
            RequestBody requestBody = maintainBody ? userResponse.request().body() : null;
            requestBuilder.method(method, requestBody);
          }
          if (!maintainBody) {//移除原来请求中的header信息
            requestBuilder.removeHeader("Transfer-Encoding");
            requestBuilder.removeHeader("Content-Length");
            requestBuilder.removeHeader("Content-Type");
          }
        }

        // When redirecting across hosts, drop all authentication headers. This
        // is potentially annoying to the application layer since they have no
        // way to retain them.
        if (!sameConnection(userResponse.request().url(), url)) {
          requestBuilder.removeHeader("Authorization");
        }

        return requestBuilder.url(url).build();

      case HTTP_CLIENT_TIMEOUT://408 实际很少用到，一般需要重复发送一个相同的请求
        // 408's are rare in practice, but some servers like HAProxy use this response code. The
        // spec says that we may repeat the request without modifications. Modern browsers also
        // repeat the request (even non-idempotent ones.)
        if (!client.retryOnConnectionFailure()) {
          // The application layer has directed us not to retry the request.
          return null;
        }

        RequestBody requestBody = userResponse.request().body();
        if (requestBody != null && requestBody.isOneShot()) {
          return null;
        }

        if (userResponse.priorResponse() != null
            && userResponse.priorResponse().code() == HTTP_CLIENT_TIMEOUT) {
          // We attempted to retry and got another timeout. Give up.
          return null;
        }

        if (retryAfter(userResponse, 0) > 0) {
          return null;
        }

        return userResponse.request();

      case HTTP_UNAVAILABLE:
        if (userResponse.priorResponse() != null
            && userResponse.priorResponse().code() == HTTP_UNAVAILABLE) {
          // We attempted to retry and got another timeout. Give up.
          return null;
        }

        if (retryAfter(userResponse, Integer.MAX_VALUE) == 0) {
          // specifically received an instruction to retry without delay
          return userResponse.request();
        }

        return null;

      default:
        return null;
    }
  }

  private int retryAfter(Response userResponse, int defaultDelay) {
    String header = userResponse.header("Retry-After");

    if (header == null) {
      return defaultDelay;
    }

    // https://tools.ietf.org/html/rfc7231#section-7.1.3
    // currently ignores a HTTP-date, and assumes any non int 0 is a delay
    if (header.matches("\\d+")) {
      return Integer.valueOf(header);
    }

    return Integer.MAX_VALUE;
  }
}
