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

import gribbit.handler.error.BadRequest;
import gribbit.handler.error.EmailNotValidated;
import gribbit.handler.error.InternalServerError;
import gribbit.handler.error.NotFound;
import gribbit.handler.error.Unauthorized;
import gribbit.handler.error.annotation.On404NotFound;
import gribbit.handler.error.annotation.OnBadRequest;
import gribbit.handler.error.annotation.OnEmailNotValidated;
import gribbit.handler.error.annotation.OnInternalServerError;
import gribbit.handler.error.annotation.OnUnauthorized;
import gribbit.handler.route.annotation.Disabled;
import gribbit.handler.route.annotation.RouteOverride;
import gribbit.model.DataModel;
import gribbit.util.Log;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Pattern;

public class RouteMapping {

    // Routes
    private ArrayList<RouteInfo> allRoutes = new ArrayList<>();
    private HashMap<Class<? extends Route>, RouteInfo> routeForHandler = new HashMap<>();
    private HashMap<String, RouteInfo> routeForRoutePath = new HashMap<>();

    private HashMap<Class<? extends DataModel>, RouteInfo> formModelToRoute = new HashMap<>();

    // Route handlers for each error type. If left null, then default handlers are called.
    private RouteInfo internalServerErrorRoute;
    private RouteInfo badRequestRoute;
    private RouteInfo notFoundRoute;
    private RouteInfo unauthorizedRoute;
    private RouteInfo emailNotValidatedRoute;

    private static final Pattern VALID_ROUTE_OVERRIDE = Pattern.compile("^(/|(/[a-zA-Z0-9\\-_]+)+)$");

    // -----------------------------------------------------------------------------------------------------

    public ArrayList<RouteInfo> getAllRoutes() {
        return allRoutes;
    }

    public RouteInfo getInternalServerErrorRoute() {
        return internalServerErrorRoute != null ? internalServerErrorRoute : routeForHandler(InternalServerError.class);
    }

    public RouteInfo getBadRequestRoute() {
        return badRequestRoute != null ? badRequestRoute : routeForHandler(BadRequest.class);
    }

    public RouteInfo getNotFoundRoute() {
        return notFoundRoute != null ? notFoundRoute : routeForHandler(NotFound.class);
    }

    public RouteInfo getUnauthorizedRoute() {
        return unauthorizedRoute != null ? unauthorizedRoute : routeForHandler(Unauthorized.class);
    }

    public RouteInfo getEmailNotValidatedRoute() {
        return emailNotValidatedRoute != null ? emailNotValidatedRoute : routeForHandler(EmailNotValidated.class);
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Get the Route corresponding to a given RestHandler class.
     */
    public RouteInfo routeForHandler(Class<? extends Route> handlerClass) {
        RouteInfo route = routeForHandler.get(handlerClass);
        if (route == null) {
            throw new RuntimeException("Class " + handlerClass.getName() + " is not registered as a handler");
        }
        return route;
    }

    /**
     * Get the Route that accepts the given DataModel as a parameter to its post() method.
     */
    public RouteInfo routeForFormDataModel(Class<? extends DataModel> formModelClass) {
        RouteInfo route = formModelToRoute.get(formModelClass);
        if (route == null) {
            throw new RuntimeException("Class " + formModelClass.getName()
                    + " is not registered as a parameter to a post() method of any known subclass of "
                    + Route.class.getName());
        }
        return route;
    }

    /** Get the set of all DataModels that are bound to POST handlers. */
    public Set<Class<? extends DataModel>> getAllFormDataModels() {
        return formModelToRoute.keySet();
    }

    // -----------------------------------------------------------------------------------------------------

    public void removeRoute(RouteInfo route) {
        if (route != null) {
            routeForHandler.remove(route.getHandler());
            routeForRoutePath.remove(route.getRoutePath());
            allRoutes.remove(route);
            route.setRoutePath(null);
        }
    }

    /** Register a RestHandler route. */
    public void registerRoute(Class<? extends Route> handler) {
        if (handler.getAnnotation(Disabled.class) != null) {
            // Log.info("Found disabled handler: " + handler.getName());

        } else if (handler == Route.class || handler == Route.AuthNotRequired.class
                || handler == Route.AuthRequired.class || handler == Route.AuthAndValidatedEmailRequired.class) {
            // Don't register handler for generic super-interfaces 

        } else {
            // Check if route has been overridden for this handler
            RouteOverride routeOverrideAnnotation = handler.getAnnotation(RouteOverride.class);
            String routeOverride = routeOverrideAnnotation == null ? null : routeOverrideAnnotation.value();
            if (routeOverride != null) {
                if (!VALID_ROUTE_OVERRIDE.matcher(routeOverride).matches()) {
                    throw new RuntimeException(RouteOverride.class.getName() + " annotation on class "
                            + handler.getName() + " has value \"" + routeOverride + "\" which is not a valid route");
                }
            }

            if (!handler.isInterface()) {
                throw new RuntimeException(handler.getName() + " is a class that extends " + Route.class.getName()
                        + "; should instead be an interface that extends " + Route.class.getName());
            }

            RouteInfo route = new RouteInfo(handler, routeOverride);

            // Check for error handler annotations
            for (Annotation ann : handler.getAnnotations()) {
                Class<? extends Annotation> annType = ann.annotationType();

                boolean hasErrHandlerAnnotation = false;
                RouteInfo existingErrHandler = null;
                if (annType == OnInternalServerError.class) {
                    hasErrHandlerAnnotation = true;
                    existingErrHandler = internalServerErrorRoute;
                    internalServerErrorRoute = route;
                } else if (annType == OnBadRequest.class) {
                    hasErrHandlerAnnotation = true;
                    existingErrHandler = badRequestRoute;
                    badRequestRoute = route;
                } else if (annType == On404NotFound.class) {
                    hasErrHandlerAnnotation = true;
                    existingErrHandler = notFoundRoute;
                    notFoundRoute = route;
                } else if (annType == OnUnauthorized.class) {
                    hasErrHandlerAnnotation = true;
                    existingErrHandler = unauthorizedRoute;
                    unauthorizedRoute = route;

                } else if (annType == OnEmailNotValidated.class) {
                    hasErrHandlerAnnotation = true;
                    existingErrHandler = emailNotValidatedRoute;
                    emailNotValidatedRoute = route;
                }
                if (existingErrHandler != null) {
                    // Can't have two non-default error handlers with an error handler annotation, because
                    // classpath traversal order is somewhat arbitrary 
                    throw new RuntimeException("Both " + existingErrHandler.getHandler().getName() + " and "
                            + handler.getName() + " have the annotation @" + annType.getSimpleName()
                            + " -- you cannot have two error handlers with the same annotation");
                }
                if (hasErrHandlerAnnotation && (!route.hasGetMethod() || route.getNumGetParams() > 0)) {
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
            RouteInfo existing = routeForRoutePath.put(route.getRoutePath(), route);
            if (existing != null) {
                throw new RuntimeException("Two handlers have the same route: " + existing.getRoutePath() + " , "
                        + route.getRoutePath());
            }
            if (routeForHandler.put(handler, route) != null) {
                // Should not happen, objects on classpath should only be scanned once
                throw new RuntimeException("Handler added twice: " + handler.getName());
            }
            allRoutes.add(route);

            // Check type of parameter of any post() method is handled by only one handler (this is required
            // because the "submit" attribute of the form is filled in with the route of the handler that
            // handles the POST request)
            Class<? extends DataModel> postParamType = route.getPostParamType();
            if (postParamType != null) {
                RouteInfo prev = formModelToRoute.put(postParamType, route);
                if (prev != null) {
                    throw new RuntimeException(DataModel.class.getName() + " subclass " + postParamType.getName()
                            + " is handled by two different post() methods, in classes " + route.getHandler().getName()
                            + ", " + prev.getHandler().getName());
                }
            }
            Log.fine("Registering route handler: " + handler.getName() + " -> " + route.getRoutePath());
        }
    }
}