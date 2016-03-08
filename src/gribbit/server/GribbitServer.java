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

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;

import gribbit.request.handler.HttpErrorHandler;
import gribbit.response.Response;
import gribbit.response.exception.InternalServerErrorException;
import gribbit.response.exception.NotFoundException;
import gribbit.response.exception.ResponseException;
import gribbit.route.ParsedURL;
import gribbit.route.Route;
import gribbit.server.siteresources.Database;
import gribbit.server.siteresources.SiteResources;
import gribbit.util.Log;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;

public class GribbitServer {
    public static final int NUM_WORKER_THREADS = 256;

    /** Keep-Alive connections are closed if idle for longer than this */
    public static final int IDLE_TIMEOUT_SECONDS = 30;

    /** Delay after session expires before session reaper deletes session variables. */
    private static final long SESSION_REAPER_TIMEOUT_MILLIS = 10000;

    private static final String gribbitServerPackageName = GribbitServer.class.getPackage().getName();
    private static final String basePackageName = gribbitServerPackageName.substring(0,
            gribbitServerPackageName.lastIndexOf('.'));

    public static final Vertx vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(NUM_WORKER_THREADS));

    private HttpServer server;
    private Router router;

    private String domain = "localhost";
    private Integer port = null;
    private boolean useTLS = false;

    public URI uri;
    public URI wsUri;

    // private ArrayList<WebSocketHandler> webSocketHandlers; // TODO
    private HashMap<Class<? extends ResponseException>, HttpErrorHandler> errorHandlers;

    public static SiteResources siteResources;
    // private static boolean shutdown = false;

    public static String SERVER_IDENTIFIER = "Gribbit/1.0";

    // -----------------------------------------------------------------------------------------------------

    private static boolean firstResourceLoad = true;

    private void loadSiteResources(Vertx vertx, String basePackageName) {
        try {
            long startTime = System.currentTimeMillis();
            GribbitServer.siteResources = new SiteResources(basePackageName);
            if (firstResourceLoad) {
                Log.info("Site resource loading took "
                        + String.format("%.3f", (System.currentTimeMillis() - startTime) * 0.001f) + " sec");
                firstResourceLoad = false;
            }

        } catch (Exception e) {
            // Failed to load site resources
            if (GribbitServer.siteResources == null) {
                // This is the first time site resources have tried to load, can't start up web server
                Log.exception("Exception during initial attempt to load site resources -- cannot start web server",
                        e);
                Log.error("EXITING");
                System.exit(1);
            } else {
                // Something changed on the classpath and we were trying to reload site resources -- 
                // keep using old site resources so the server doesn't shut down  
                Log.exception("Exception while loading site resources -- "
                        + "continuing to use old site resources without shutting down server", e);
            }
        }

        //        // Scan periodically, if polling interval is greater than zero, and provide hot-reload of changed
        //        // HTML/JS/CSS/image resources. We also support hot-reload of inline HTML templates in static final
        //        // String fields named "_template" in DataModel classes, by means of FastClasspathScanner's support
        //        // for reading constants directly from the constant pool of a classfile.
        //        // 
        //        // If running in the Eclipse debugger, hot reload of RestHandler classes also works in the
        //        // usual way. Full hot-reloading of classes is difficult to perform outside the Eclipse
        //        // debugger, see: 
        //        // http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html
        //        if (!shutdown && SiteResources.CLASSPATH_CHANGE_DETECTION_POLL_INTERVAL_MS > 0) {
        //            vertx.setTimer(SiteResources.CLASSPATH_CHANGE_DETECTION_POLL_INTERVAL_MS, id -> {
        //                loadSiteResources(vertx, basePackageName);
        //            });
        //        }
    }

    // -----------------------------------------------------------------------------------------------------

    public GribbitServer() {
        // Initialize logger
        Log.info("Initializing " + SERVER_IDENTIFIER);

        // Make sure we can connect to database server
        Log.info("Setting up database connection");
        Database.checkDatabaseIsConnected();

        // Scan classpath for handlers, templates etc.
        loadSiteResources(vertx, basePackageName);
    }

    public GribbitServer domain(String domain) {
        this.domain = domain;
        return this;
    }

    public GribbitServer port(int port) {
        this.port = port;
        return this;
    }

    public GribbitServer useTLS(boolean useTLS) {
        this.useTLS = useTLS;
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    //    /**
    //     * Add an WebSocket handler. Handlers are called in order until one of them handles the WebSocket upgrade
    //     * request.
    //     */
    //    public GribbitServer addWebSocketHandler(WebSocketHandler handler) {
    //        // TODO
    //        if (webSocketHandlers == null) {
    //            webSocketHandlers = new ArrayList<>();
    //        }
    //        webSocketHandlers.add(handler);
    //        return this;
    //    }

    /** Add an error handler that overrides a default plain text error response. */
    public <E extends ResponseException> GribbitServer addHttpErrorHandler(Class<E> exceptionType,
            HttpErrorHandler errorHandler) {
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
    public GribbitServer start() {
        if (port == null) {
            port = useTLS ? 8443 : 8080;
        }
        if (!portAvailable(port)) {
            throw new IllegalArgumentException("Port " + port + " is not available -- is server already running?");
        }

        String domainAndPort = domain + ((!useTLS && port == 80) || (useTLS && port == 443) ? "" : ":" + port);
        try {
            uri = new URI((useTLS ? "https" : "http") + "://" + domainAndPort);
            wsUri = new URI((useTLS ? "wss" : "ws") + "://" + domainAndPort);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        HttpServerOptions serverOptions = new HttpServerOptions() //
                .setIdleTimeout(IDLE_TIMEOUT_SECONDS) //
                .setHandle100ContinueAutomatically(true) //
                .setSsl(useTLS);
        server = vertx.createHttpServer(serverOptions);
        router = Router.router(vertx);

        // Automatically serve files in "webroot/static/*" (relative to current directory, or in classpath).
        // Handles range requests automatically, and if files are in a jar in the classpath, they are
        // transparently extracted to a temporary cache directory on disk. Also handles Content-Range requests.
        StaticHandler staticHandler = StaticHandler.create() //
                .setWebRoot("webroot") //
                .setIncludeHidden(false) //
                .setDirectoryListing(false);
        router.route("/static/*").handler(staticHandler);

        // TODO: switch to clustered session store in clustered environment
        SessionStore store = LocalSessionStore.create(vertx, basePackageName, SESSION_REAPER_TIMEOUT_MILLIS);
        router.route().handler(CookieHandler.create()); // SessionHandler requires CookieHandler
        router.route().handler(SessionHandler.create(store));

        router.route().handler(routingContext -> {
            // Execute all requests on worker threads, so that they can block
            vertx.executeBlocking(future -> {
                Response response = null;
                HttpServerRequest request = routingContext.request();
                ParsedURL reqURL = new ParsedURL(request.uri());
                try {
                    // RequestURL reqURL = new RequestURL(request.absoluteURI());  // TODO
                    boolean isWSUpgrade = false;
                    //                    if (webSocketHandlers != null) {
                    //                        for (WebSocketHandler handler : webSocketHandlers) {
                    //                            if (handler.isWebSocketUpgradeURL(request.absoluteURI())) {
                    //                                isWSUpgrade = true;
                    //                                ServerWebSocket websocket = request.upgrade();
                    //                                throw new RuntimeException("TODO"); // TODO
                    //                            }
                    //                        }
                    //                    }
                    if (!isWSUpgrade) {
                        // Try each route in turn
                        for (Route route : siteResources.getAllRoutes()) {
                            if (reqURL.startsWith(route.getRoutePath())) {
                                response = route.callHandler(routingContext);
                                if (response != null) {
                                    // Stop calling handlers after the first response
                                    break;
                                }
                            }
                        }
                        if (response == null) {
                            // No route matched => 404
                            response = new NotFoundException().generateErrorResponse();
                        }
                    }
                } catch (Exception e) {
                    // Convert Exception to InternalServerErrorException if it's not already a ResponseException 
                    ResponseException re;
                    if (e instanceof ResponseException) {
                        re = (ResponseException) e;
                    } else {
                        re = new InternalServerErrorException(e);
                    }
                    try {
                        // See if there's a custom override for this error type
                        HttpErrorHandler errorHandler = errorHandlers.get(e.getClass());
                        if (errorHandler != null) {
                            // If so, generate a custom response
                            response = errorHandler.generateResponse(request, re);
                        } else {
                            // Otherwise, use the default response for this error type
                            response = re.generateErrorResponse();
                        }
                    } catch (Exception e2) {
                        // Generate a generic InternalServerErrorException response if an exception was thrown
                        // while generating a response
                        response = new InternalServerErrorException(e).generateErrorResponse();
                    }
                }
                try {
                    // Send response
                    response.send(routingContext);
                } catch (Exception e) {
                    // Failure while sending response, connection was probably closed
                }
                future.complete();
            }, //
                    // From the docs:
                    // "By default, if executeBlocking is called several times from the same context (e.g. the
                    // same verticle instance) then the different executeBlocking are executed serially (i.e.
                    // one after another). If you don’t care about ordering you can call executeBlocking
                    // specifying false as the argument to ordered. In this case any executeBlocking may be
                    // executed in parallel on the worker pool."
                    /* ordered = */ false,

                    //
                    // Async result handler
                    future -> {
                    });
        });

        server.requestHandler(router::accept);

        Log.info("Starting " + SERVER_IDENTIFIER + " on port " + port);
        server.listen(port);
        Log.info(SERVER_IDENTIFIER + " started at " + uri + "/");
        return this;
    }

    //    /**
    //     * Close all connections and shut down the HTTP server. (This call is asynchronous, so the server may not shut
    //     * down for some time if connections are being handled.)
    //     */
    //    public GribbitServer stop() {
    //        shutdown = true;
    //        server.close();
    //        return this;
    //    }
}
