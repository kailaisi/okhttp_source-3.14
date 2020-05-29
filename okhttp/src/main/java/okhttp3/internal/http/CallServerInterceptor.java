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

import java.io.IOException;
import java.net.ProtocolException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Util;
import okhttp3.internal.connection.Exchange;
import okio.BufferedSink;
import okio.Okio;

/** This is the last interceptor in the chain. It makes a network call to the server. */
//责任链中的最后一个拦截器，用于向服务器发送网络请求。
//在 ConnectInterceptor 拦截器的功能就是负责与服务器建立 Socket 连接，并且创建了一个 HttpStream 它包括通向服务器的输入流和输出流。
// 而 CallServerInterceptor 拦截器的功能使用 HttpStream 与服务器进行数据的读写操作的。
public final class CallServerInterceptor implements Interceptor {
  private final boolean forWebSocket;

  //是否是websocket
  public CallServerInterceptor(boolean forWebSocket) {
    this.forWebSocket = forWebSocket;
  }

  @Override
  public Response intercept(Chain chain) throws IOException {
    RealInterceptorChain realChain = (RealInterceptorChain) chain;
    Exchange exchange = realChain.exchange();
    //获取请求。这里的请求已经不是最初始的request了，而是经过了前面的层层封装处理之后的request信息
    Request request = realChain.request();

    long sentRequestMillis = System.currentTimeMillis();
    //向服务器端写请求头信息
    exchange.writeRequestHeaders(request);

    boolean responseHeadersStarted = false;
    Response.Builder responseBuilder = null;
    if (HttpMethod.permitsRequestBody(request.method()) && request.body() != null) {
      // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
      // Continue" response before transmitting the request body. If we don't get that, return
      // what we did get (such as a 4xx response) without ever transmitting the request body.
      //用于客户端在发送POST请求数据之前，征询服务器情况，看服务器是否处理POST的数据，如果不处理，客户端则不上传POST数据，如果处理，则POST上传数据。
      //判断服务器是否允许发送body
      if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
        exchange.flushRequest();
        responseHeadersStarted = true;
        //获取返回的头信息
        exchange.responseHeadersStart();
        //读取返回的头信息，如果服务器接收RequestBody，会返回null
        responseBuilder = exchange.readResponseHeaders(true);
      }
      //如果RequestBuilder为null，说明Expect不为100-continue或者服务器同意接收RequestBody
      if (responseBuilder == null) {
        //向服务器发送body
        if (request.body().isDuplex()) {
          // Prepare a duplex body so that the application can send a request body later.
          exchange.flushRequest();
          //写入请求体
          BufferedSink bufferedRequestBody = Okio.buffer(exchange.createRequestBody(request, true));
          request.body().writeTo(bufferedRequestBody);
        } else {
          // Write the request body if the "Expect: 100-continue" expectation was met.
          BufferedSink bufferedRequestBody = Okio.buffer(exchange.createRequestBody(request, false));
          request.body().writeTo(bufferedRequestBody);
          bufferedRequestBody.close();
        }
      } else {
        exchange.noRequestBody();
        if (!exchange.connection().isMultiplexed()) {
          // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
          // from being reused. Otherwise we're still obligated to transmit the request body to
          // leave the connection in a consistent state.
          exchange.noNewExchangesOnConnection();
        }
      }
    } else {
      exchange.noRequestBody();
    }

    if (request.body() == null || !request.body().isDuplex()) {
      //请求结束
      exchange.finishRequest();
    }

    if (!responseHeadersStarted) {
      exchange.responseHeadersStart();
    }
    //读取相应的header
    if (responseBuilder == null) {
      //读取相应头
      responseBuilder = exchange.readResponseHeaders(false);
    }
    //通过Builder模式构造返回response
    Response response = responseBuilder
        .request(request)
        .handshake(exchange.connection().handshake())
        .sentRequestAtMillis(sentRequestMillis)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .build();

    int code = response.code();
    if (code == 100) {
      //100的状态码的处理继续发送请求，继续接收数据
      // server sent a 100-continue even though we did not request one. try again to read the actual response
      response = exchange.readResponseHeaders(false)
          .request(request)
          .handshake(exchange.connection().handshake())
          .sentRequestAtMillis(sentRequestMillis)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .build();
      code = response.code();
    }

    exchange.responseHeadersEnd(response);

    if (forWebSocket && code == 101) {
      // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
      //但对于websocket，返回的是空的body体
      response = response.newBuilder()
          .body(Util.EMPTY_RESPONSE)
          .build();
    } else {
      //读取body体
      response = response.newBuilder()
          .body(exchange.openResponseBody(response))
          .build();
    }

    if ("close".equalsIgnoreCase(response.request().header("Connection"))
        || "close".equalsIgnoreCase(response.header("Connection"))) {
      exchange.noNewExchangesOnConnection();
    }
    //返回为空的数据处理
    if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
      throw new ProtocolException("HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
    }

    return response;
  }
}
