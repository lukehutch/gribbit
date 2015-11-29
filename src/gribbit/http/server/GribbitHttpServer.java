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
import gribbit.server.config.GribbitProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
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
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.SSLException;

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

    /*
     * See:
     * github.com/netty/netty/blob/master/example/src/main/java/io/netty/example/http2/tiles/Http2OrHttpHandler.java
     */
    class Http2OrHttpHandler extends ApplicationProtocolNegotiationHandler {
        private EventLoopGroup requestDecoderGroup;
        private HttpRequestDecoder requestDecoder;

        // TODO: determine this value
        private static final int MAX_CONTENT_LENGTH = 1024 * 100;

        protected Http2OrHttpHandler(EventLoopGroup requestDecoderGroup, HttpRequestDecoder requestDecoder) {
            super(ApplicationProtocolNames.HTTP_1_1);
            this.requestDecoderGroup = requestDecoderGroup;
            this.requestDecoder = requestDecoder;
        }

        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                DefaultHttp2Connection connection = new DefaultHttp2Connection(true);
                InboundHttp2ToHttpAdapter listener = new InboundHttp2ToHttpAdapter.Builder(connection)
                        .propagateSettings(true).validateHttpHeaders(false).maxContentLength(MAX_CONTENT_LENGTH)
                        .build();

                ctx.pipeline().addLast(
                        new HttpToHttp2ConnectionHandler.Builder().frameListener(listener).build(connection));
                if (nettyLogLevel != null) {
                    ctx.pipeline().addLast(new LoggingHandler(nettyLogLevel));
                }
                ctx.pipeline().addLast(new WebSocketServerCompressionHandler()); // TODO: needed for HTTP2?
                ctx.pipeline().addLast(requestDecoderGroup, requestDecoder);

            } else if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
                ctx.pipeline().addLast( //
                        new HttpContentDecompressor(), //
                        new HttpServerCodec(), //
                        // TODO: We're currently doing manual aggregation of chunked requests (without limiting len) 
                        /* new HttpObjectAggregator(MAX_CONTENT_LENGTH), */
                        new WebSocketServerCompressionHandler());
                ctx.pipeline().addLast(requestDecoderGroup, requestDecoder);

            } else {
                throw new IllegalStateException("Unsupported protocol: " + protocol);
            }
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
        Log.info("Starting " + GribbitHttpServer.class.getName() + " on port " + port);

        if (!portAvailable(port)) {
            throw new IllegalArgumentException("Port " + port + " is not available -- is server already running?");
        }

        // Configure and start up the server. (We do this in a different thread, passing back any exception that
        // occurs in a (single-element) blocking queue, so that we can use Autocloseable with the thread groups.)
        LinkedBlockingQueue<Exception> startupException = new LinkedBlockingQueue<>();
        new Thread() {
            public void run() {
                // TODO: allow the number of threads to be configurable?
                try (EventLoopGroup bossGroup = new NioEventLoopGroup(1);
                        EventLoopGroup workerGroup = new NioEventLoopGroup();
                        EventLoopGroup requestDecoderGroup = new NioEventLoopGroup()) {

                    ServerBootstrap b = new ServerBootstrap();
                    // http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#14.0
                    b.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

                    final SslContext sslCtx = GribbitProperties.useTLS ? configureTLS() : null;
                    b.option(ChannelOption.SO_BACKLOG, 1024);
                    b.group(bossGroup, workerGroup) //
                            .channel(NioServerSocketChannel.class) //
                            // .handler(new LoggingHandler(LogLevel.INFO)) //
                            .childHandler(new ChannelInitializer<SocketChannel>() {
                                // Create an HTTP decoder/encoder and request handler for each connection,
                                // so that the request can be handled in a stateful way
                                @Override
                                public void initChannel(SocketChannel ch) {
                                    ChannelPipeline p = ch.pipeline();
                                    HttpRequestDecoder httpRequestDecoder = new HttpRequestDecoder(
                                            httpRequestHandlers, webSocketHandlers, errorHandlers);
                                    if (sslCtx != null) {
                                        p.addLast(sslCtx.newHandler(ch.alloc()), new Http2OrHttpHandler(
                                                requestDecoderGroup, httpRequestDecoder));
                                    } else {
                                        p.addLast(requestDecoderGroup, httpRequestDecoder);
                                    }
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

    // From github.com/netty/netty/blob/master/example/src/main/java/io/netty/example/http2/tiles/Http2Server.java
    private static SslContext configureTLS() throws CertificateException, SSLException {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        ApplicationProtocolConfig apn = new ApplicationProtocolConfig(Protocol.ALPN,
                // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                SelectorFailureBehavior.NO_ADVERTISE,
                // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                SelectedListenerFailureBehavior.ACCEPT, ApplicationProtocolNames.HTTP_2,
                ApplicationProtocolNames.HTTP_1_1);

        return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey(), null)
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .applicationProtocolConfig(apn).build();
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
