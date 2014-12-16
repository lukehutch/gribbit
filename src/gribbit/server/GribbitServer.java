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
package gribbit.server;

import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.Database;
import gribbit.server.siteresources.SiteResources;
import gribbit.util.Log;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.TimeUnit;

public class GribbitServer {

    /** The URI the server is running on. */
    public static URI uri;

    public static String SERVER_IDENTIFIER = "Gribbit/1.0";

    public static String appPackageName;

    public static SiteResources siteResources;

    public static EventLoopGroup scheduledTaskGroup;

    private long CLASSPATH_CHANGE_DETECTOR_POLL_INTERVAL_SECONDS = 2;

    // ------------------------------------------------------------------------------------------------------------------------------------------------

    /** Return true if a user is allowed to log in (whitelisted), based on their email address. */
    @FunctionalInterface
    public static interface LoginWhitelistChecker {
        public boolean allowUserToLogin(String emailAddr);
    }

    /** If non-null, check that users are whitelisted before allowing them to log in. */
    public static LoginWhitelistChecker loginWhitelistChecker = null;

    /**
     * If checker is non-null, check that users are whitelisted before allowing them to log in. Otherwise, they are able to log in if they can be authorized with OAuth2 or any
     * other enabled login method.
     */
    public static void setLoginWhitelistChecker(LoginWhitelistChecker checker) {
        loginWhitelistChecker = checker;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------

    static {
        // Initialize logger
        Log.info("Starting Gribbit server");
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Checks to see if a specific port is available. See http://stackoverflow.com/questions/434718/sockets-discover-port-availability-using-java
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

    private void loadSiteResources(String appPackageName, String staticResourceRoot) {
        try {
            long startTime = System.currentTimeMillis();
            siteResources = new SiteResources(appPackageName, staticResourceRoot);
            Log.info("Site resource loading took " + String.format("%.3f", (System.currentTimeMillis() - startTime) * 0.001f) + " sec");

        } catch (Exception e) {
            // Failed to load site resources
            if (siteResources == null) {
                // This is the first time site resources have tried to load, can't start up web server
                Log.exception("Exception during initial attempt to load site resources -- cannot start web server", e);
                Log.error("EXITING");
                System.exit(1);
            } else {
                // Something changed on the classpath and we were trying to reload site resources -- keep using old site resources so the server doesn't shut down  
                Log.exception("Exception while loading site resources -- continuing to use old site resources without shutting down server", e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Create a web server instance, and add all routes and handlers. Call start() to actually start the web server after all routes and handlers have been added.
     */
    public GribbitServer(String appPackageName, String staticResourceRoot) {
        this("localhost", GribbitProperties.PORT, appPackageName, staticResourceRoot);
    }

    /**
     * Create a web server instance, and add all routes and handlers. Call start() to actually start the web server after all routes and handlers have been added.
     */
    public GribbitServer(String domain, int port, String appPackageName, String staticResourceRoot) {
        GribbitServer.appPackageName = appPackageName;

        if (!portAvailable(port)) {
            System.err.println("Port " + port + " is not available -- is server already running?\n\nExiting.");
            System.exit(1);
        }

        // Make sure we can connect to database server
        Database.checkDatabaseIsConnected();

        try {
            scheduledTaskGroup = new NioEventLoopGroup(4);

            // Scan classpath for handlers, templates etc.
            loadSiteResources(appPackageName, staticResourceRoot);

            // Poll for classpath changes, and provide limited hot-reload of changed classes and templates.
            // (Only RestHandler, DataModel and DBModel subclasses are currently reloaded and re-registered.)
            // FIXME: need to implement my own class loader to get this working: http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html
            // (although class reloading works now when running in Eclipse, because Eclipse does hot code swap, and template reloading should already work)
            Runnable classpathChangeDetector = new Runnable() {
                @Override
                public void run() {
                    if (siteResources.classpathContentsModifiedSinceScan()) {
                        Log.info("Classpath contents changed -- reloading site resources");

                        // Reload site resources from classpath if something changed, and atomically replace GribbitServer.siteResources
                        loadSiteResources(appPackageName, staticResourceRoot);
                    }
                    scheduledTaskGroup.schedule(this, CLASSPATH_CHANGE_DETECTOR_POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
                }
            };
            scheduledTaskGroup.schedule(classpathChangeDetector, CLASSPATH_CHANGE_DETECTOR_POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);

            // -----------------------------------------------------------------------------------------------------------------------------------------------------

            // TODO: These SSL classes seem to be absent in Java 8.
            // TODO: Also need to listen on both SSL and non-SSL ports. Don't allow auth-required RestHandler classes to be served on non-https paths, or forms to be submitted to them.
            //            final SslContext sslCtx;
            //            if (SSL) {
            //                SelfSignedCertificate ssc = new SelfSignedCertificate();
            //                sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
            //            } else {
            //                sslCtx = null;
            //            }
            // TODO: add SslHandler to pipeline to support SSL, its presence is tested for by HttpRequestHandler

            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            EventLoopGroup workerGroup = new NioEventLoopGroup();
            EventLoopGroup routeHandlerGroup = new NioEventLoopGroup();

            // Configure the server.
            try {
                ServerBootstrap b = new ServerBootstrap();
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
                                //            if (sslCtx != null) {
                                //                p.addLast(sslCtx.newHandler(ch.alloc()));
                                //            }

                                // p.addLast(new HttpServerCodec());
                                p.addLast(new HttpRequestDecoder());
                                p.addLast(new HttpResponseEncoder());

                                // TODO: I get a 330 error in the browser on static file requests if this is enabled -- see http://stackoverflow.com/questions/14039804/error-330-neterr-content-decoding-failed
                                // p.addLast(new HttpContentCompressor());

                                // TODO can client HTTP reqs be compressed too, and do I just drop this in here?
                                //            p.addLast(new HttpContentDecompressor());

                                p.addLast(routeHandlerGroup, new HttpRequestHandler());
                            }
                        });

                // TODO: test these options suggested in http://stackoverflow.com/questions/8655973/latency-in-netty-due-to-passing-requests-from-boss-thread-to-worker-thread
                // See also http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#11.0
                b.childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 64 * 1024);
                b.childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 64 * 1024);
                b.childOption(ChannelOption.SO_SNDBUF, 1048576);
                b.childOption(ChannelOption.SO_RCVBUF, 1048576);
                // bootstrap.childOption(ChannelOption.TCP_NODELAY, true);

                Channel ch = b.bind(port).sync().channel();

                // TODO: This should be the serving domain, not localhost
                uri = new URI((GribbitProperties.SSL ? "https" : "http") + "://" + domain + ":" + port);

                Log.info("Gribbit web server started at " + uri + '/');

                ch.closeFuture().sync();

            } finally {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }

            // TODO: final StaleConnectionTrackingHandler staleConnectionTrackingHandler = new StaleConnectionTrackingHandler(STALE_CONNECTION_TIMEOUT, executor);
            //            ScheduledExecutorService staleCheckExecutor = Executors.newSingleThreadScheduledExecutor(new NamingThreadFactory(Gribbit.class.getSimpleName()
            //                    + "-stale-connection-check"));
            //            staleCheckExecutor.scheduleWithFixedDelay(new Runnable() {
            //                @Override
            //                public void run() {
            //                    staleConnectionTrackingHandler.closeStaleConnections();
            //                }
            //            }, STALE_CONNECTION_TIMEOUT / 2, STALE_CONNECTION_TIMEOUT / 2, TimeUnit.MILLISECONDS);
            //            executorServices.add(staleCheckExecutor);
            // connectionTrackingHandler = new ConnectionTrackingHandler();

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("\nFailed to start web server");
            System.exit(1);
        }
    }
}
