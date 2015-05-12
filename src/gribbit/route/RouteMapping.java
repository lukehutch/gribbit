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
package gribbit.route;

import gribbit.handler.route.annotation.Disabled;
import gribbit.handler.route.annotation.On404NotFound;
import gribbit.handler.route.annotation.OnBadRequest;
import gribbit.handler.route.annotation.OnInternalServerError;
import gribbit.handler.route.annotation.OnUnauthorized;
import gribbit.handler.route.annotation.OnUnauthorizedEmailNotValidated;
import gribbit.handler.route.annotation.RoutePath;
import gribbit.model.DataModel;
import gribbit.server.GribbitServer;
import gribbit.util.Log;
import gribbit.util.Reflection;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Pattern;

public class RouteMapping {

    // Routes
    private ArrayList<Route> allRoutes = new ArrayList<>();
    private HashMap<Class<? extends RouteHandler>, Route> routeForHandler = new HashMap<>();
    private HashMap<String, Route> routeForRoutePath = new HashMap<>();

    private HashMap<Class<? extends DataModel>, Route> formModelToRoute = new HashMap<>();

    // Route handlers for each error type. If left null, then default handlers are called.
    private Route internalServerErrorRoute;
    private Route badRequestRoute;
    private Route notFoundRoute;
    private Route unauthorizedRoute;
    private Route unauthorizedEmailNotValidatedRoute;
    private Route emailNotValidatedRoute;

    private static final Pattern VALID_ROUTE_OVERRIDE = Pattern.compile("^(/|(/[a-zA-Z0-9\\-_]+)+)$");

    // -----------------------------------------------------------------------------------------------------

    public ArrayList<Route> getAllRoutes() {
        return allRoutes;
    }

    public Route getInternalServerErrorRoute() {
        return internalServerErrorRoute;
    }

    public Route getBadRequestRoute() {
        return badRequestRoute;
    }

    public Route getNotFoundRoute() {
        return notFoundRoute;
    }

    public Route getUnauthorizedRoute() {
        return unauthorizedRoute;
    }

    public Route getUnauthorizedEmailNotValidatedRoute() {
        return unauthorizedEmailNotValidatedRoute;
    }

    public Route getEmailNotValidatedRoute() {
        return emailNotValidatedRoute;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Get the Route corresponding to a given RestHandler class.
     */
    public Route routeForHandler(Class<? extends RouteHandler> handlerClass) {
        Route route = routeForHandler.get(handlerClass);
        if (route == null) {
            throw new RuntimeException("Class " + handlerClass.getName() + " is not registered as a handler");
        }
        return route;
    }

    /**
     * Get the Route that accepts the given DataModel as a parameter to its post() method.
     */
    public Route routeForFormDataModel(Class<? extends DataModel> formModelClass) {
        Route route = formModelToRoute.get(formModelClass);
        if (route == null) {
            throw new RuntimeException("Class " + formModelClass.getName()
                    + " is not registered as a parameter to a post() method of any known subclass of "
                    + RouteHandler.class.getName());
        }
        return route;
    }

    /** Get the set of all DataModels that are bound to POST handlers. */
    public Set<Class<? extends DataModel>> getAllFormDataModels() {
        return formModelToRoute.keySet();
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Convert class name to route path. For example:
     * 
     * app.action.HandleEmailValidationLink -> /app/action/handle-email-validation-link
     */
    private static String routePathFromClassName(Class<? extends RouteHandler> handler) {
        StringBuilder buf = new StringBuilder("/");
        String name = handler.getName().replace('$', '.').replace('.', '/');
        int leaf = name.lastIndexOf('/') + 1;
        buf.append(name.substring(0, leaf));
        for (int i = leaf, n = name.length(); i < n; i++) {
            if (i > leaf && Character.isUpperCase(name.charAt(i)) && !Character.isUpperCase(name.charAt(i - 1)))
                buf.append('-');
            buf.append(Character.toLowerCase(name.charAt(i)));
        }
        String path;
        if (buf.length() > 2
                && buf.subSequence(1, GribbitServer.appPackageName.length() + 1).equals(GribbitServer.appPackageName)) {
            path = buf.substring(GribbitServer.appPackageName.length() + 1);
        } else {
            path = buf.toString();
        }
        // In case of uppercase packagenames or inner classes, convert all to lowercase
        path = path.toLowerCase();
        return path;
    }

    // -----------------------------------------------------------------------------------------------------------------

    public void removeRoute(Route route) {
        if (route != null) {
            routeForHandler.remove(route.getHandler());
            routeForRoutePath.remove(route.getRoutePath());
            allRoutes.remove(route);
            route.setRoutePath(null);
        }
    }

    /** Register a RestHandler route. */
    public void registerRoute(Class<? extends RouteHandler> handler) {
        if (handler.getAnnotation(Disabled.class) != null) {
            // Log.info("Found disabled handler: " + handler.getName());

        } else if (handler == RouteHandler.class || handler == RouteHandlerAuthNotRequired.class
                || handler == RouteHandlerAuthRequired.class
                || handler == RouteHandlerAuthAndValidatedEmailRequired.class) {
            // Don't register handler for generic super-interfaces 

        } else {
            // Check if route has been overridden for this handler
            RoutePath routeOverrideAnnotation = handler.getAnnotation(RoutePath.class);
            String routeOverride = routeOverrideAnnotation == null ? null : routeOverrideAnnotation.value();
            if (routeOverride != null) {
                if (!VALID_ROUTE_OVERRIDE.matcher(routeOverride).matches()) {
                    throw new RuntimeException(RoutePath.class.getName() + " annotation on class "
                            + handler.getName() + " has value \"" + routeOverride + "\" which is not a valid route");
                }
            }

            String routePath = routeOverride != null ? routeOverride : routePathFromClassName(handler);
            if (routePath.startsWith("/_/")) {
                throw new RuntimeException("Cannot register routes with URIs starting with \"/_/\", "
                        + "these route prefixes are reserved for hash URIs. Got URI: " + routePath);
            }

            Route routeInfo = new Route(handler, routePath);

            // Check for error handler annotations
            for (Annotation ann : handler.getAnnotations()) {
                Class<? extends Annotation> annType = ann.annotationType();

                boolean hasErrHandlerAnnotation = false;
                Route existingErrHandler = null;
                if (annType == OnInternalServerError.class) {
                    hasErrHandlerAnnotation = true;
                    existingErrHandler = internalServerErrorRoute;
                    internalServerErrorRoute = routeInfo;
                } else if (annType == OnBadRequest.class) {
                    hasErrHandlerAnnotation = true;
                    existingErrHandler = badRequestRoute;
                    badRequestRoute = routeInfo;
                } else if (annType == On404NotFound.class) {
                    hasErrHandlerAnnotation = true;
                    existingErrHandler = notFoundRoute;
                    notFoundRoute = routeInfo;
                } else if (annType == OnUnauthorized.class) {
                    hasErrHandlerAnnotation = true;
                    existingErrHandler = unauthorizedRoute;
                    unauthorizedRoute = routeInfo;
                } else if (annType == OnUnauthorizedEmailNotValidated.class) {
                    hasErrHandlerAnnotation = true;
                    existingErrHandler = unauthorizedEmailNotValidatedRoute;
                    unauthorizedEmailNotValidatedRoute = routeInfo;
                }
                if (existingErrHandler != null) {
                    // Can't have two non-default error handlers with an error handler annotation, because
                    // classpath traversal order is somewhat arbitrary 
                    throw new RuntimeException("Both " + existingErrHandler.getHandler().getName() + " and "
                            + handler.getName() + " have the annotation @" + annType.getSimpleName()
                            + " -- you cannot have two error handlers with the same annotation");
                }
                if (hasErrHandlerAnnotation && (!routeInfo.hasGetMethod() || routeInfo.getNumGetParams() > 0)) {
                    // All errors are served using GET
                    throw new RuntimeException("Handler " + handler.getName() + " has an error handler "
                            + "annotation, but does not have a get() method that takes zero params");
                }
            }

            // If this route has been overridden, need to compare against all other routes to make sure it
            // is not a prefix of another route or vice versa (this is not possible with the default routes
            // derived from classnames). The only route that is allowed to be a prefix of others is "/".
            if (routeOverride != null && !routeOverride.equals("/")) {
                for (String otherRoute : routeForRoutePath.keySet()) {
                    if (otherRoute.startsWith(routeOverride)) {
                        if (routeOverride.length() == otherRoute.length()
                                || otherRoute.charAt(routeOverride.length()) == '/')
                            throw new RuntimeException("Manually-overriden route " + routeOverride + " in handler "
                                    + handler.getName() + " matches or is a prefix of another route " + otherRoute);
                    } else if (routeOverride.startsWith(otherRoute)) {
                        if (routeOverride.length() == otherRoute.length()
                                || routeOverride.charAt(otherRoute.length()) == '/')
                            throw new RuntimeException("Already-added route " + otherRoute
                                    + " matches or is a prefix of manually-overriden route " + routeOverride
                                    + " in handler " + handler.getName());
                    }
                }
            }

            // Make sure route is unique
            Route existing = routeForRoutePath.put(routeInfo.getRoutePath(), routeInfo);
            if (existing != null) {
                throw new RuntimeException("Two handlers have the same route: " + existing.getRoutePath() + " , "
                        + routeInfo.getRoutePath());
            }
            if (routeForHandler.put(handler, routeInfo) != null) {
                // Should not happen, objects on classpath should only be scanned once
                throw new RuntimeException("Handler added twice: " + handler.getName());
            }
            allRoutes.add(routeInfo);

            // Check type of parameter of any post() method is handled by only one handler (this is required
            // because the "submit" attribute of the form is filled in with the route of the handler that
            // handles the POST request)
            Class<? extends DataModel> postParamType = routeInfo.getPostParamType();
            if (postParamType != null) {
                // Try instantiating DataModel with default constructor to make sure there will be no problems
                // instantiating it later 
                try {
                    Reflection.instantiateWithDefaultConstructor(postParamType);
                } catch (Exception e) {
                    throw new RuntimeException("Could not instantiate " + DataModel.class.getSimpleName()
                            + " subclass " + postParamType.getName()
                            + " -- it needs to have a default (zero-argument) constructor if there "
                            + "is any other non-default constructor defined, and the class must be "
                            + "static if it is an inner class");
                }

                Route prev = formModelToRoute.put(postParamType, routeInfo);
                if (prev != null) {
                    throw new RuntimeException(DataModel.class.getName() + " subclass " + postParamType.getName()
                            + " is handled by two different post() methods, in classes "
                            + routeInfo.getHandler().getName() + ", " + prev.getHandler().getName());
                }
            }
            Log.fine("Registering route handler: " + handler.getName() + " -> " + routeInfo.getRoutePath());
        }
    }
}
