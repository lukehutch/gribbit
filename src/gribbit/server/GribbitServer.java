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

import gribbit.http.logging.Log;
import gribbit.http.request.Request;
import gribbit.http.request.handler.HttpRequestHandler;
import gribbit.http.response.FileResponse;
import gribbit.http.response.GeneralResponse;
import gribbit.http.response.exception.ResponseException;
import gribbit.http.server.GribbitHttpServer;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.Database;
import gribbit.server.siteresources.SiteResources;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import java.io.File;
import java.util.concurrent.TimeUnit;

public class GribbitServer {
    public static GribbitHttpServer server;

    public static String appPackageName;

    public static SiteResources siteResources;

    /** Task group for handling background tasks, like classpath scanning, file hashing, sending emails etc. */
    public static EventLoopGroup backgroundTaskGroup;

    public static final int BACKGROUND_TASK_THREADS = 4;

    public static String SERVER_IDENTIFIER = "Gribbit/1.0";

    // -----------------------------------------------------------------------------------------------------

    /** Return true if a user is allowed to log in (whitelisted), based on their email address. */
    @FunctionalInterface
    public static interface LoginWhitelistChecker {
        public boolean allowUserToLogin(String emailAddr);
    }

    /** If non-null, check that users are whitelisted before allowing them to log in. */
    public static LoginWhitelistChecker loginWhitelistChecker = null;

    /**
     * If checker is non-null, check that users are whitelisted before allowing them to log in. Otherwise, they are
     * able to log in if they can be authorized with OAuth2 or any other enabled login method.
     */
    public static void setLoginWhitelistChecker(LoginWhitelistChecker checker) {
        loginWhitelistChecker = checker;
    }

    // -----------------------------------------------------------------------------------------------------

    private static void loadSiteResources(String appPackageName) {
        try {
            long startTime = System.currentTimeMillis();
            GribbitServer.siteResources = new SiteResources(appPackageName);
            Log.info("Site resource loading took "
                    + String.format("%.3f", (System.currentTimeMillis() - startTime) * 0.001f) + " sec");

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
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Create a web server instance, and add all routes and handlers. Call start() to actually start the web server
     * after all routes and handlers have been added.
     */
    public void init(String domain, int port, String appPackageName) {
        // Initialize logger
        Log.info("Initializing Gribbit");

        HttpRequestHandler requestHandler = new HttpRequestHandler() {
            @Override
            public GeneralResponse handle(Request request) throws ResponseException {
                // TODO: factor out request.matchRoute() and request.callRouteHandler()
                
                // Look up the route (or static file) based on the URL and HTTP method of the request.
                // Throws an UnauthorizedException if the user is not authorized for the requested route.
                // Throws NotFoundException if the requested path doesn't match any known Route or static resource.
                // Upgrades the connection to a websocket connection if requested.
                request.matchRoute();

                File staticResourceFile = request.getStaticResourceFile();
                if (staticResourceFile != null) {
                    // Serve a static file
                    return new FileResponse(staticResourceFile);
                } else {
                    // No more chunks to receive; handle the request.
                    // Call the RestHandler for the route. May throw a RequestHandlingException.
                    GeneralResponse response = request.callRouteHandler();
                    return response;
                }
            }
        };

        GribbitServer.server = new GribbitHttpServer().httpRequestHandler(requestHandler).domain(domain).port(port)
                .useTLS(GribbitProperties.useTLS);

        GribbitServer.appPackageName = appPackageName;

        // Make sure we can connect to database server
        Log.info("Setting up database connection");
        Database.checkDatabaseIsConnected();

        // Task group for handling background tasks
        GribbitServer.backgroundTaskGroup = new NioEventLoopGroup(BACKGROUND_TASK_THREADS);

        try {
            // Scan classpath for handlers, templates etc.
            loadSiteResources(appPackageName);

            // Poll for classpath changes, and provide hot-reload of changed HTML/JS/CSS/image resources.
            // We also support hot-reload of inline HTML templates in static final "_template" fields of
            // DataModel classes by means of FastClasspathScanner's support of reading constants directly
            // from the constant pool of a classfile.
            // If running in the Eclipse debugger, hot reload of RestHandler classes also works in the
            // usual way. Full hot-reloading of classes is difficult to perform outside the Eclipse
            // debugger, see: 
            // http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html
            if (GribbitProperties.CLASSPATH_CHANGE_DETECTION_POLL_INTERVAL_MS > 0) {
                Runnable classpathChangeDetector = new Runnable() {
                    @Override
                    public void run() {
                        if (siteResources.classpathContentsModifiedSinceScan()) {
                            Log.info("Classpath contents changed -- reloading site resources");

                            // Reload site resources from classpath if something changed, and atomically
                            // replace GribbitServer.siteResources
                            loadSiteResources(appPackageName);
                        }
                        GribbitServer.backgroundTaskGroup.schedule(this,
                                GribbitProperties.CLASSPATH_CHANGE_DETECTION_POLL_INTERVAL_MS,
                                TimeUnit.MILLISECONDS);
                    }
                };
                GribbitServer.backgroundTaskGroup.schedule(classpathChangeDetector,
                        GribbitProperties.CLASSPATH_CHANGE_DETECTION_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("\nFailed to load site resources, cannot initialize web server");
            System.exit(1);
        }
        
        server.start();
        
        // TODO: test server.shutdown()
    }
}
