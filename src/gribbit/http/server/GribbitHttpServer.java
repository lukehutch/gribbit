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
import gribbit.response.exception.RequestHandlingException;
import gribbit.server.config.GribbitProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class GribbitHttpServer {
    private String domain = "localhost";
    private Integer port = null;
    private boolean useTLS = false;
    private LogLevel nettyLogLevel = null;

    private static final String serverName = GribbitHttpServer.class.getSimpleName();
    public String serverIdentifier = serverName;

    public URI uri;
    public URI wsUri;

    public Channel channel;

    private ArrayList<HttpRequestHandler> httpRequestHandlers;
    private ArrayList<WebSocketHandler> webSocketHandlers;
    private HashMap<Class<? extends RequestHandlingException>, //
    HttpErrorHandler<? extends RequestHandlingException>> errorHandlers;

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
        this.nettyLogLevel = nettyLogLevel;
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
    public <E extends RequestHandlingException> GribbitHttpServer addHttpErrorHandler(Class<E> exceptionType,
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
            port = useTLS ? 443 : 80;
        }

        // Initialize logger
        Log.info("Starting " + GribbitHttpServer.class.getName() + " on port " + port);

        if (!portAvailable(port)) {
            throw new IllegalArgumentException("Port " + port + " is not available -- is server already running?");
        }

        // Configure and start up the server. (We do this in a different thread, passing back any exception that
        // occurs in a (single-element) blocking queue, so that we can use Autocloseable with the thread groups.)
        LinkedBlockingQueue<Exception> startupException = new LinkedBlockingQueue<>();
        final GribbitHttpServer server = this;
        new Thread() {
            public void run() {
                // TODO: allow the number of threads to be configurable?
                try (EventLoopGroup bossGroup = new NioEventLoopGroup(1);
                        EventLoopGroup workerGroup = new NioEventLoopGroup();
                        EventLoopGroup routeHandlerGroup = new NioEventLoopGroup()) {
                    ServerBootstrap b = new ServerBootstrap();

                    // Set up SSL. See:
                    // http://maxrohde.com/2013/09/07/setting-up-ssl-with-netty/
                    // http://blog.hintcafe.com/post/33709433256/https-server-in-java-using-netty-and-keystore
                    // https://www.sslshopper.com/ssl-converter.html

                    SslContext sslCtx;
                    if (GribbitProperties.useTLS) {
                        SelfSignedCertificate ssc = new SelfSignedCertificate();
                        // TODO: netty-tcnative seems to always throw this error if added to the .pom
                        if (OpenSsl.isAvailable()) {
                            try {
                                // Use OpenSSL if the netty-tcnative Maven artifact is available (it is 30% faster than JDK)
                                // TODO: Replaced by SslContextBuilder
                                sslCtx = SslContext.newServerContext(SslProvider.OPENSSL, ssc.certificate(),
                                        ssc.privateKey());

                                //                        certChainFile an X.509 certificate chain file in PEM format
                                //                        keyFile a PKCS#8 private key file in PEM format
                            } catch (Exception | Error e) {
                                throw new RuntimeException("Could not link with OpenSSL libraries");
                            }
                        } else {
                            Log.warning("OpenSSL libraries are not available; falling back to the slower SslProvider.JDK. "
                                    + "Please add the appropriate netty-tcnative maven artifact for your platform. "
                                    + "See also: http://netty.io/wiki/forked-tomcat-native.html");
                            // TODO: Replaced by SslContextBuilder
                            sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
                        }
                    } else {
                        sslCtx = null;
                    }

                    // http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#14.0
                    b.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

                    // b.option(ChannelOption.SO_BACKLOG, 1024);
                    final SslContext sslCtxFinal = sslCtx;
                    b.group(bossGroup, workerGroup) //
                            .channel(NioServerSocketChannel.class) //
                            // .handler(new LoggingHandler(LogLevel.INFO)) //
                            .childHandler(new ChannelInitializer<SocketChannel>() {
                                // Create an HTTP decoder/encoder and request handler for each connection,
                                // so that the request can be handled in a stateful way
                                @Override
                                public void initChannel(SocketChannel ch) {
                                    ChannelPipeline p = ch.pipeline();
                                    if (sslCtxFinal != null) {
                                        p.addLast(sslCtxFinal.newHandler(ch.alloc()));
                                    }

                                    if (nettyLogLevel != null) {
                                        p.addLast(new LoggingHandler(nettyLogLevel));
                                    }

                                    p.addLast(new HttpContentDecompressor());

                                    p.addLast(new HttpServerCodec());

                                    p.addLast(new WebSocketServerCompressionHandler());

                                    p.addLast(routeHandlerGroup, new HttpRequestDecoder(httpRequestHandlers,
                                            webSocketHandlers, errorHandlers));
                                }
                            });

                    //                // TODO: test these options suggested in http://goo.gl/AHvjmq
                    //                // See also http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#11.0
                    //                b.childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 64 * 1024);
                    //                b.childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 64 * 1024);
                    //                b.childOption(ChannelOption.SO_SNDBUF, 1048576);
                    //                b.childOption(ChannelOption.SO_RCVBUF, 1048576);
                    //                // bootstrap.childOption(ChannelOption.TCP_NODELAY, true);

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

                    String domainAndPort = domain
                            + ((!useTLS && port == 80) || (useTLS && port == 443) ? "" : ":" + port);
                    uri = new URI((useTLS ? "https" : "http") + "://" + domainAndPort);
                    wsUri = new URI((useTLS ? "wss" : "ws") + "://" + domainAndPort);

                    // Set up channel
                    channel = b.bind(port).sync().channel();

                    // Successfully started up (no startup exception) -- cause start() to exit,
                    // but keep this thread running, waiting on channel close..
                    startupException.add(null);
                    Log.info(serverName + " started at " + uri + "/");

                    // Wait (possibly indefinitely) for channel to close via call to this.shutdown()
                    channel.closeFuture().sync();
                    channel = null;

                    Log.info(serverName + " successfully shut down");

                } catch (Exception e) {
                    startupException.add(e);
                    try {
                        channel.flush();
                        channel.close();
                    } catch (Exception e2) {
                    }
                }
            };
        }.start();

        // Throw IllegalArgumentException if anything went wrong setting up channel
        Exception e = null;
        try {
            e = startupException.take();
        } catch (InterruptedException e2) {
            e = e2;
        }
        if (e != null) {
            throw new IllegalArgumentException("Exception while starting up " + serverName, e);
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
