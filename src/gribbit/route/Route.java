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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import gribbit.auth.CSRF;
import gribbit.auth.User;
import gribbit.handler.route.annotation.Roles;
import gribbit.model.DataModel;
import gribbit.response.ErrorResponse;
import gribbit.response.HTMLPageResponse;
import gribbit.response.HTMLResponse;
import gribbit.response.Response;
import gribbit.response.exception.BadRequestException;
import gribbit.response.exception.InternalServerErrorException;
import gribbit.response.exception.ResponseException;
import gribbit.response.exception.UnauthorizedException;
import gribbit.server.GribbitServer;
import gribbit.util.Log;
import gribbit.util.Reflection;
import gribbit.util.URLUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;

/** The metadata about a Route. */
public class Route {
    private ParsedURL routePath;
    private Class<? extends RouteHandler> handlerClass;
    private Roles getRoles, postRoles;

    private Method getMethod;
    private Class<?>[] getParamTypes;

    private Method postMethod;
    private Class<? extends DataModel> postParamType;

    // -----------------------------------------------------------------------------------------------------------------

    public Route(Class<? extends RouteHandler> handlerClass, String routePath) {
        this.handlerClass = handlerClass;
        this.routePath = new ParsedURL(routePath);
        Roles classRoles = handlerClass.getAnnotation(Roles.class);

        // Check for methods get() and post() in the handler subinterface
        for (Method method : handlerClass.getMethods()) {
            String methodName = method.getName();
            Class<?>[] paramTypes = method.getParameterTypes();
            boolean isGet = methodName.equals("get"), isPost = methodName.equals("post");

            if (isGet || isPost) {
                // Check method modifiers
                if ((method.getModifiers() & Modifier.STATIC) != 0) {
                    throw new RuntimeException(
                            "Method " + handlerClass.getName() + "." + methodName + " should not be static");
                }
                // Check return type
                if (!Response.class.isAssignableFrom(method.getReturnType())) {
                    throw new RuntimeException("Method " + handlerClass.getName() + "." + methodName
                            + " should have a return type of " + Response.class.getName()
                            + " or a subclass, instead of " + method.getReturnType().getName());
                }
            }

            if (isGet) {
                // Check method parameters are key value pairs, and that the keys are strings, and the
                // values are String or Integer
                for (int j = 0; j < paramTypes.length; j++) {
                    if (paramTypes[j] != String.class && paramTypes[j] != Integer.class
                            && paramTypes[j] != Integer.TYPE) {
                        throw new RuntimeException(
                                "Method " + handlerClass.getName() + "." + methodName + " has a param of type "
                                        + paramTypes[j].getName() + ", needs to be String, int or Integer");
                    }
                }

                if (routePath.equals("/") && paramTypes.length != 0) {
                    // Can't pass URL parameters into the root URL, because the root URL may be a prefix
                    // of another handler's URL (it is the only URL that this is allowed for, and in
                    // general it's not a problem because URLs are determined from the RestHandler's full
                    // classname). If we allowed the root URL to take params, then the params would match
                    // URL path components.
                    throw new RuntimeException("Interface " + handlerClass.getName()
                            + " has a route override of \"/\", so it serves the root URL, "
                            + "but the get() method takes one or more parameters. "
                            + "(The root URL cannot take URL parameters, because they "
                            + "would match path components of other URLs.)");
                }

                if (getMethod != null) {
                    // Make sure there is only one method of each name
                    throw new RuntimeException("Class " + handlerClass.getName() + " has two get() methods");
                }
                getMethod = method;
                getParamTypes = paramTypes;
                getRoles = method.getAnnotation(Roles.class);
                if (getRoles == null) {
                    getRoles = classRoles;
                }

            } else if (isPost) {
                if (paramTypes.length > 1) {
                    throw new RuntimeException("Method " + handlerClass.getName() + "." + methodName
                            + " needs zero parameters or one parameter of type " + DataModel.class.getSimpleName()
                            + "; takes " + paramTypes.length + " parameters");
                }

                if (paramTypes.length == 1) {
                    if (!DataModel.class.isAssignableFrom(paramTypes[0])) {
                        throw new RuntimeException("The single parameter of method " + handlerClass.getName() + "."
                                + methodName + " needs to be a subclass of " + DataModel.class.getName());
                    }
                    @SuppressWarnings("unchecked")
                    Class<? extends DataModel> paramType = (Class<? extends DataModel>) paramTypes[0];
                    postParamType = paramType;
                }

                if (postMethod != null) {
                    // Make sure there is only one method of each name
                    throw new RuntimeException("Interface " + handlerClass.getName() + " has two post() methods");
                }
                postMethod = method;
                postRoles = method.getAnnotation(Roles.class);
                if (postRoles == null) {
                    postRoles = classRoles;
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * If a post() method takes exactly one parameter, then bind the param value from the POST data.
     * 
     * @param user
     */
    private Object[] bindPostParamFromPOSTData(HttpServerRequest request, ParsedURL reqURL)
            throws ResponseException {
        if (reqURL.getUnescapedURLParts().size() != routePath.getUnescapedURLParts().size()) {
            throw new BadRequestException("POST requests should not have URL parameters");
        }
        if (postParamType == null) {
            // post() takes no params
            return null;

        } else {
            // post() takes one param, which is the form model to bind
            DataModel postParam;
            try {
                postParam = Reflection.instantiateWithDefaultConstructor(postParamType);
            } catch (InstantiationException e) {
                // Should never happen, we already tried instantiating all DataModel subclasses
                // that are bound to POST request handlers when site resources were loaded
                throw new InternalServerErrorException(
                        "Could not instantiate POST parameter of type " + postParamType.getName(), e);
            }

            // Bind POST param object from request
            postParam.bindFromPost(request);

            // Wrap bound object Object[1]
            Object[] paramVal = new Object[1];
            paramVal[0] = postParam;
            return paramVal;
        }
    }

    /**
     * If a get() method takes one or more parameters, bind the parameters from URI segments after the end of the
     * route's base URI, e.g. /person/53 for a route of /person gives one Integer-typed param value of 53.
     * 
     * FIXME: Use Vertx' URL binding syntax, e.g. "/person/:id" puts the URL value into the param "id". FIXME: Move
     * binding code into the RequestURL class.
     */
    private Object[] bindGetParamsFromURI(ParsedURL reqURL) throws ResponseException {
        if (getParamTypes.length == 0) {
            // get() takes no params
            int numUrlParams = reqURL.getUnescapedURLParts().size();
            int expectedNumUrlParams = routePath.getUnescapedURLParts().size();
            if (numUrlParams != expectedNumUrlParams) {
                throw new BadRequestException(
                        "Wrong number of URL parameters: expected 0, got " + (numUrlParams - expectedNumUrlParams));
            }
            return null;

        } else {
            // get() takes one or more params
            Object[] getParamVals = new Object[getParamTypes.length];
            if (!reqURL.startsWith(routePath)) {
                // This is an error handler that has been called to replace the normal route handler;
                // don't try to parse URL params (leave them all as null)

            } else {
                // Parse URL params
                List<String> urlParams = reqURL.getUnescapedURLParts(routePath.getNumURLParts());
                if (urlParams.size() != getParamTypes.length) {
                    throw new BadRequestException("Wrong number of URL parameters: expected " + getParamTypes.length
                            + ", got " + urlParams.size());
                }
                for (int i = 0; i < getParamTypes.length; i++) {
                    String uriSegment = urlParams.get(i);
                    if (getParamTypes[i] == Integer.class || getParamTypes[i] == Integer.TYPE) {
                        try {
                            // Specifically parse integers for int-typed method parameters 
                            getParamVals[i] = Integer.parseInt(uriSegment);
                        } catch (NumberFormatException e) {
                            throw new BadRequestException(
                                    "Malformed URL parameter, expected integer for URI parameter");
                        }
                    } else {
                        // N.B. parameter values should not be unescaped again after this, to prevent
                        // double-encoding attacks: see https://www.owasp.org/index.php/Double_Encoding
                        getParamVals[i] = uriSegment;
                    }
                }
            }
            return getParamVals;
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Invoke a default method in a Route subinterface. */
    private Response invokeMethod(RoutingContext routingContext, Method method, Object[] methodParamVals,
            Roles methodRoles, boolean checkAuthorized, boolean checkCSRFTok) throws ResponseException {
        // Create a handler instance
        RouteHandler instance;
        try {
            instance = handlerClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new InternalServerErrorException(
                    "Exception while creating instance of handler class " + handlerClass.getName(), e);
        }
        instance.routingContext = routingContext;
        instance.session = routingContext.session();
        if (instance.session != null) {
            instance.user = User.fromSession(instance.session);
        }

        if (checkCSRFTok) {
            if (!CSRF.csrfTokMatches(routingContext.request().getParam(CSRF.CSRF_PARAM_NAME), instance.user)) {
                throw new BadRequestException("Missing or incorrect CSRF token in POST request");
            }
        }

        // Check if user can call method
        if (checkAuthorized && !User.userIsAuthorized(instance.user, methodRoles)) {
            throw new UnauthorizedException();
        }

        try {
            // Invoke the method
            Response response = (Response) method.invoke(instance, methodParamVals);

            // The Response object should not be null, but if it is, respond with No Content
            if (response == null) {
                Log.warning(handlerClass.getName() + "." + method.getName()
                        + " returned a null response -- responding with 204: No Content");
                response = new ErrorResponse(HttpResponseStatus.NO_CONTENT, "");
            }

            // For non-error responses
            if (response.getStatus() == HttpResponseStatus.OK) {
                // Add the user's CSRF token to the response if user is logged in
                if (instance.user != null) {
                    String csrfTok = instance.user.csrfTok;
                    if (csrfTok != null) {
                        CSRF.setCsrfCookie(csrfTok, "/", response);
                        if (response instanceof HTMLResponse) {
                            ((HTMLResponse) response).setCsrfTok(csrfTok);
                        }
                    }
                }
                // Add any flash messages to response
                if (response instanceof HTMLPageResponse) {
                    ((HTMLPageResponse) response).setFlashMessages(routingContext.session());
                }
            }
            return response;

        } catch (InvocationTargetException e) {
            // If method.invoke() throws a ResponseException, it gets wrapped in an InvocationTargetException.
            // Unwrap it and re-throw it.
            Throwable cause = e.getCause();
            if (cause instanceof ResponseException) {
                throw (ResponseException) cause;
            } else if (cause instanceof Exception) {
                throw new InternalServerErrorException(
                        "Exception while invoking the method " + handlerClass.getName() + "." + method.getName(),
                        (Exception) cause);
            } else {
                throw new InternalServerErrorException("Exception while invoking the method "
                        + handlerClass.getName() + "." + method.getName() + ": caused by " + cause.getMessage());
            }
        } catch (Exception e) {
            throw new InternalServerErrorException(
                    "Exception while invoking the method " + handlerClass.getName() + "." + method.getName(), e);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Call the get() or post() method for the Route corresponding to the request URI.
     * 
     * @param reqURL
     */
    public Response callHandler(RoutingContext routingContext, ParsedURL reqURL) throws ResponseException {
        Response response;
        // Determine param vals for method
        HttpServerRequest request = routingContext.request();
        HttpMethod reqMethod = request.method();

        if (reqMethod == HttpMethod.GET) {
            // Bind URI params
            Object[] getParamVals = bindGetParamsFromURI(reqURL);

            // Invoke the get() method with URI params
            response = invokeMethod(routingContext, getMethod, getParamVals, getRoles, /* checkAuthorized = */ true,
                    /* checkCSRFTok = */ false);

        } else if (reqMethod == HttpMethod.POST) {
            // Bind the post() method's single parameter (if it has one) from the POST data in the request
            Object[] postParamVal = bindPostParamFromPOSTData(request, reqURL);

            // Invoke the post() method
            response = invokeMethod(routingContext, postMethod, postParamVal, postRoles,
                    /* checkAuthorized = */ true, /* checkCSRFTok = */ true);

        } else {
            // Method not allowed
            response = new ErrorResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed");
        }

        if (response == null) {
            // Should not happen
            throw new InternalServerErrorException("Didn't generate a response");
        }

        return response;
    }

    /** Call the get() method of an error handler. */
    public Response callErrorHandler(RoutingContext routingContext) {
        try {
            Response response = invokeMethod(routingContext, getMethod, /* getParamVals = */ null, getRoles,
                    /* checkAuthorized = */ false, /* checkCSRFTok = */ false);
            if (response == null) {
                // Should not happen
                throw new RuntimeException("Error handler didn't generate a response");
            }
            return response;
        } catch (ResponseException e) {
            throw new RuntimeException("Exception in error handler", e);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Get the route annotated with the given HTTP method on the given RestHandler, substituting the key/value pairs
     * into the params in the URL template.
     * 
     * Can call this method with no urlParams if the handler takes no params for this httpMethod, otherwise list URL
     * params in the varargs. URL params can be any type, and their toString() method will be called. Param values
     * will be URL-escaped (turned into UTF-8, then byte-escaped if the bytes are not safe).
     */
    private static String forMethod(HttpMethod httpMethod, Class<? extends RouteHandler> handlerClass,
            Object... urlParams) {
        Route handler = GribbitServer.siteResources.routeForClass(handlerClass);

        if (httpMethod == HttpMethod.GET) {
            if (handler.getMethod == null) {
                throw new RuntimeException("Handler " + handlerClass.getName() + " does not have a get() method");
            }

            for (Object param : urlParams) {
                if (param == null) {
                    throw new RuntimeException("null values are not allowed in URL params");
                }
            }
            if (urlParams.length != handler.getParamTypes.length) {
                throw new RuntimeException(
                        "Wrong number of URL params for method " + handlerClass.getName() + ".get()");
            }
            // Build new fully-specified route from route stem and URL params 
            StringBuilder buf = new StringBuilder(handler.routePath.getNormalizedPath());
            for (int i = 0; i < urlParams.length; i++) {
                buf.append('/');
                URLUtils.escapeURLSegment(urlParams[i].toString(), buf);
            }
            return buf.toString();

        } else if (httpMethod == HttpMethod.POST) {
            if (handler.postMethod == null) {
                throw new RuntimeException("Handler " + handlerClass.getName() + " does not have a post() method");
            }
            if (urlParams.length != 0) {
                throw new RuntimeException(
                        "Tried to pass URL params to a POST method, " + handlerClass.getName() + ".post()");
            }
            return handler.routePath.getNormalizedPath();

        } else {
            throw new RuntimeException("Bad HTTP method: " + httpMethod);
        }
    }

    /**
     * Return the route path for the POST method handler of a RestHandler class.
     */
    public static String forPost(Class<? extends RouteHandler> handlerClass) {
        return Route.forMethod(HttpMethod.POST, handlerClass);
    }

    /**
     * Return the route path for the GET method handler of a RestHandler class, appending URL params.
     */
    public static String forGet(Class<? extends RouteHandler> handlerClass, Object... urlParams) {
        return Route.forMethod(HttpMethod.GET, handlerClass, (Object[]) urlParams);
    }

    // -----------------------------------------------------------------------------------------------------------------

    public ParsedURL getRoutePath() {
        return routePath;
    }

    @Override
    public String toString() {
        return routePath.getNormalizedPath();
    }

    public Class<? extends RouteHandler> getHandler() {
        return handlerClass;
    }

    public boolean hasGetMethod() {
        return getMethod != null;
    }

    public boolean hasPostMethod() {
        return postMethod != null;
    }

    /**
     * Returns the post() method DataModel param object type. Returns null if this route handler does not have a
     * post() method, or if the handler does have a post() method but the method doesn't take any parameters.
     */
    public Class<? extends DataModel> getPostParamType() {
        return postParamType;
    }

    /**
     * Returns the number of parameters accepted by the get() method, or 0 if there are no params (or if there is no
     * get() method).
     */
    public int getNumGetParams() {
        return getMethod == null ? 0 : getParamTypes.length;
    }

    public boolean matches(ParsedURL reqURL) {
        if (routePath.getNumURLParts() == 0) {
            // Special case: only "/" should match a routePath of "/", "/something" should not match
            return reqURL.getNumURLParts() == 0;
        }
        return reqURL.startsWith(routePath);
    }
}
