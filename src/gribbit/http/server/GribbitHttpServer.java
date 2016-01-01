/**
 * This file is part of the Gribbit Web Framework.
 * 
 *     https://github.com/lukehutch/gribbit
 * 
 * @author Luke Hutchison
 * 
 * --
 * 
 * @license Apache 2.0 
 * 
 * Copyright 2015 Luke Hutchison
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gribbit.http.server;

import gribbit.http.logging.Log;
import gribbit.http.request.decoder.HttpRequestDecoder;
import gribbit.http.request.handler.HttpErrorHandler;
import gribbit.http.request.handler.HttpRequestHandler;
import gribbit.http.request.handler.WebSocketHandler;
import gribbit.http.response.exception.ResponseException;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;

import javax.net.ssl.SSLException;

import com.sun.mail.iap.Protocol;

public class GribbitHttpServer {
    public static final int NUM_BLOCKING_WORKER_THREADS = 256;
    public Vertx vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(NUM_BLOCKING_WORKER_THREADS));
    
    private String domain = "localhost";
    private Integer port = null;
    private boolean useTLS = false;

    public enum LogLevel {
        DEBUG, INFO, WARN, ERROR;
    }

    private LogLevel logLevel = null;

    private static final String serverName = GribbitHttpServer.class.getSimpleName();
    public String serverIdentifier = serverName;

    public URI uri;
    public URI wsUri;
    
    private ArrayList<HttpRequestHandler> httpRequestHandlers;
    private ArrayList<WebSocketHandler> webSocketHandlers;
    private HashMap<Class<? extends ResponseException>, //
    HttpErrorHandler<? extends ResponseException>> errorHandlers;

    // -----------------------------------------------------------------------------------------------------

    public GribbitHttpServer() {
    }

    // -------------------------------------------------------------------------------------------------------------

    public GribbitHttpServer domain(String domain) {
        this.domain = domain;
        return this;
    }

    public GribbitHttpServer port(int port) {
        this.port = port;
        return this;
    }

    public GribbitHttpServer useTLS(boolean useTLS) {
        this.useTLS = useTLS;
        return this;
    }

    public GribbitHttpServer enableNettyLogging(LogLevel nettyLogLevel) {
        this.logLevel = nettyLogLevel;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Add an HTTP request handler. Handlers are called in order until one of them returns a non-null response. */
    public GribbitHttpServer addHttpRequestHandler(HttpRequestHandler handler) {
        if (httpRequestHandlers == null) {
            httpRequestHandlers = new ArrayList<>();
        }
        httpRequestHandlers.add(handler);
        return this;
    }

    /**
     * Add an WebSocket handler. Handlers are called in order until one of them handles the WebSocket upgrade
     * request.
     */
    public GribbitHttpServer addWebSocketHandler(WebSocketHandler handler) {
        if (webSocketHandlers == null) {
            webSocketHandlers = new ArrayList<>();
        }
        webSocketHandlers.add(handler);
        return this;
    }

    /** Add an error handler that overrides a default plain text error response. */
    public <E extends ResponseException> GribbitHttpServer addHttpErrorHandler(Class<E> exceptionType,
            HttpErrorHandler<E> errorHandler) {
        if (errorHandlers == null) {
            errorHandlers = new HashMap<>();
        }
        errorHandlers.put(exceptionType, errorHandler);
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Checks to see if a specific port is available. See
     * http://stackoverflow.com/questions/434718/sockets-discover-port-availability-using-java
     */
    private static boolean portAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port); DatagramSocket ds = new DatagramSocket(port)) {
            ss.setReuseAddress(true);
            ds.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Start the HTTP server.
     * 
     * @throws IllegalArgumentException
     *             if port is already in use, or the server cannot be started for some other reason.
     */
    public GribbitHttpServer start() {
        if (channel != null) {
            throw new IllegalArgumentException(serverName + " has already been started");
        }

        if (port == null) {
            port = useTLS ? 8443 : 8080;
        }

        // Initialize logger
        Log.info("Starting " + serverName + " on port " + port);

        if (!portAvailable(port)) {
            throw new IllegalArgumentException("Port " + port + " is not available -- is server already running?");
        }

        // TODO: allow the number of threads to be configurable?
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        EventLoopGroup requestDecoderGroup = new NioEventLoopGroup();
        try {
            final SslContext sslCtx = useTLS ? configureTLS() : null;

            ServerBootstrap b = new ServerBootstrap();
            // http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#14.0
            //b.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

            //b.option(ChannelOption.SO_BACKLOG, 1024);
            b.group(bossGroup, workerGroup) //
                    .channel(NioServerSocketChannel.class) //
                    .handler(new LoggingHandler(LogLevel.DEBUG)) //
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        // Create an HTTP decoder/encoder and request handler for each connection,
                        // so that the request can be handled in a stateful way
                        @Override
                        public void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            HttpRequestDecoder httpRequestDecoder = new HttpRequestDecoder(httpRequestHandlers,
                                    webSocketHandlers, errorHandlers);
                            if (sslCtx != null) {
                                p.addLast(sslCtx.newHandler(ch.alloc()), new Http2OrHttpHandler(
                                        requestDecoderGroup, httpRequestDecoder)); // TODO: correct for HTTP2?
                            } else {
                                // TODO: unify this with HTTP 1.1 treatment in http2OrHttpHandler

                                p.addLast(new HttpServerCodec());
                                p.addLast(new HttpContentDecompressor());
                                p.addLast(new HttpObjectAggregator(65536));
                                p.addLast(new ChunkedWriteHandler());
                                // The name is needed in the last handler, because it is used to dynamically add in
                                // HttpContentCompressor if the content in the response needs to be compressed.
                                p.addLast(HttpRequestDecoder.NAME_IN_PIPELINE, httpRequestDecoder);

                                //                                // p.addLast(new HttpContentDecompressor());
                                //                                p.addLast(new HttpServerCodec());
                                //                                // TODO: We're currently doing manual aggregation of chunked requests
                                //                                // (without limiting len) 
                                //                                p.addLast(new HttpObjectAggregator(65536));
                                //                                p.addLast(new ChunkedWriteHandler());
                                //                                // p.addLast(new WebSocketServerCompressionHandler());
                                //                                p.addLast(/*requestDecoderGroup, */ httpRequestDecoder);
                            }
                        }
                    });

            //    // TODO: test these options suggested in http://goo.gl/AHvjmq
            //    // See also http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#11.0
            //    b.childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 64 * 1024);
            //    b.childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 64 * 1024);
            //    b.childOption(ChannelOption.SO_SNDBUF, 1048576);
            //    b.childOption(ChannelOption.SO_RCVBUF, 1048576);
            //    // bootstrap.childOption(ChannelOption.TCP_NODELAY, true);

            // TODO: Apache closes KeepAlive connections after a few seconds, see
            //       http://en.wikipedia.org/wiki/HTTP_persistent_connection
            // TODO: implement a stale connection tracker
            // final StaleConnectionTrackingHandler staleConnectionTrackingHandler = 
            //          new StaleConnectionTrackingHandler(STALE_CONNECTION_TIMEOUT, executor);
            //            ScheduledExecutorService staleCheckExecutor = 
            //               Executors.newSingleThreadScheduledExecutor(
            //                 new NamingThreadFactory(Gribbit.class.getSimpleName()
            //                    + "-stale-connection-check"));
            //            staleCheckExecutor.scheduleWithFixedDelay(new Runnable() {
            //                @Override
            //                public void run() {
            //                    staleConnectionTrackingHandler.closeStaleConnections();
            //                }
            //            }, STALE_CONNECTION_TIMEOUT / 2, STALE_CONNECTION_TIMEOUT / 2,
            //                TimeUnit.MILLISECONDS);
            //            executorServices.add(staleCheckExecutor);
            // connectionTrackingHandler = new ConnectionTrackingHandler();

            String domainAndPort = domain + ((!useTLS && port == 80) || (useTLS && port == 443) ? "" : ":" + port);
            uri = new URI((useTLS ? "https" : "http") + "://" + domainAndPort);
            wsUri = new URI((useTLS ? "wss" : "ws") + "://" + domainAndPort);

            // Set up channel
            channel = b.bind(port).sync().channel();

            Log.info(serverName + " started at " + uri + "/");

            // Wait (possibly indefinitely) for channel to close via call to this.shutdown()
            channel.closeFuture().sync();
            channel = null;

            Log.info(serverName + " successfully shut down");

        } catch (Exception e) {
            throw new RuntimeException("Could not start server", e);

        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            requestDecoderGroup.shutdownGracefully();
        }
        return this;
    }

    /** Shut down the HTTP server. (It may be restarted again once it has been shut down.) */
    public GribbitHttpServer shutdown() {
        if (channel != null) {
            Log.info("Shutting down " + serverName);
            try {
                channel.flush();
                channel.close();
                channel = null;
            } catch (Exception e) {
            }
        }
        return this;
    }
}
