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

import gribbit.auth.Authorizer;
import gribbit.auth.CSRF;
import gribbit.auth.User;
import gribbit.handler.route.annotation.Auth;
import gribbit.handler.route.annotation.Cached;
import gribbit.handler.route.annotation.NoAuth;
import gribbit.model.DataModel;
import gribbit.request.Request;
import gribbit.response.ErrorResponse;
import gribbit.response.HTMLResponse;
import gribbit.response.Response;
import gribbit.response.exception.BadRequestException;
import gribbit.response.exception.InternalServerErrorException;
import gribbit.response.exception.RegistrationNotYetCompletedException;
import gribbit.response.exception.RequestHandlingException;
import gribbit.response.exception.UnauthorizedException;
import gribbit.server.GribbitServer;
import gribbit.util.Log;
import gribbit.util.Reflection;
import gribbit.util.WebUtils;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.ZonedDateTime;

/** The metadata about a Route. */
public class Route {
    private String routePath;
    private Class<? extends RouteHandler> handlerClass;

    private Method getMethod;
    private Class<?>[] getParamTypes;
    private long maxAgeSeconds;

    private Method postMethod;
    private Class<? extends DataModel> postParamType;

    private Authorizer authorizer;

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * The default route authorizer. Routes must explicitly use a NoAuth annotation or set their own Authorizer using
     * the Auth annotation if they want to override this. All other routes require the user to be logged in -- if the
     * user is not logged in, an UnauthorizedException is thrown before this handler is even called. If the user is
     * logged in, this default handler only throws a RegistrationNotYetCompletedException if
     * user.registrationIsComplete() is false.
     */
    private static class LoggedInAuthorizer implements Authorizer {
        @Override
        public void throwExceptionIfNotAuthorized(Request request, Route route) throws RequestHandlingException {
            if (!request.lookupUser().registrationIsComplete()) {
                // User is logged in, but registration is not yet completed (e.g. they haven't verified their
                // email address).
                throw new RegistrationNotYetCompletedException(request);
            }
        }
    }

    /**
     * Call the Authorizer for this route, if any. Returns with no effect if the route does not require authorization,
     * or if the authorization test passes. Throws a RequestHandlingException if the route requires authorization and
     * the user is not logged in or is not authorized for the route.
     */
    public void throwExceptionIfNotAuthorized(Request request) throws RequestHandlingException {
        if (authorizer != null) {
            // There is an Authorizer specified, so the user must be logged in.
            // Look up the User object based on the session cookies in the request. 
            if (request.lookupUser() == null) {
                // If there are no valid session cookies, the user is not logged in, so throw UnauthorizedException.
                throw new UnauthorizedException(request);
            }
            // If the user is logged in, check if they can access this Route by calling the associated Authorizer.
            // Will throw a RequestHandlingException of some sort if the user is not authorized for this route.
            authorizer.throwExceptionIfNotAuthorized(request, this);
        }
        // If we get to here, the user is authorized for the route.
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Invoke a default method in a Route subinterface. */
    private Response invokeMethod(Request request, Method method, Object[] methodParamVals)
            throws RequestHandlingException {
        // Create a handler instance
        RouteHandler instance;
        try {
            instance = handlerClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new InternalServerErrorException(request, "Exception while creating instance of handler class "
                    + handlerClass.getName(), e);
        }

        // Set the request field
        instance.request = request;

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

                // Add the user's CSRF token to the response, if user is logged in
                if (authorizer != null) {
                    User user = request.lookupUser();
                    response.setCsrfTok(user != null ? user.csrfTok : null);
                }

                // If last modified time was not filled in in the response, fill it in with the current time
                if (response.getLastModifiedEpochSeconds() == 0) {
                    response.setLastModifiedEpochSeconds(ZonedDateTime.now().toEpochSecond());
                }

                // If handlers return HTML content, their response cannot be indefinitely cached, otherwise the
                // hash URIs of their linked resources cannot be updated when their linked resources change.
                if (maxAgeSeconds > 0 && response instanceof HTMLResponse) {
                    Log.warning(handlerClass.getName() + " has annotation " + Cached.class.getName()
                            + " but returns HTML content, which cannot be indefinitely cached -- ignoring annotation");
                } else {
                    if (maxAgeSeconds > 0) {
                        response.setMaxAgeSeconds(maxAgeSeconds);
                    }
                }

            } else {
                // For errors, clear the cache time fields in case they were accidentally set
                response.setLastModifiedEpochSeconds(0);
                response.setMaxAgeSeconds(0);
            }

            return response;

        } catch (InvocationTargetException e) {
            // If method.invoke() throws a RequestHandlingException, it gets wrapped in an InvocationTargetException,
            // Unwrap it and re-throw it.
            Throwable cause = e.getCause();
            if (cause instanceof RequestHandlingException) {
                throw (RequestHandlingException) cause;
            } else if (cause instanceof Exception) {
                throw new InternalServerErrorException(request, "Exception while invoking the method "
                        + handlerClass.getName() + "." + method.getName(), (Exception) cause);
            } else {
                throw new InternalServerErrorException(request, "Exception while invoking the method "
                        + handlerClass.getName() + "." + method.getName() + ": caused by " + cause.getMessage());
            }
        } catch (Exception e) {
            throw new InternalServerErrorException(request, "Exception while invoking the method "
                    + handlerClass.getName() + "." + method.getName(), e);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    public Route(Class<? extends RouteHandler> handlerClass, String routePath) {
        this.handlerClass = handlerClass;
        this.routePath = routePath;

        Authorizer authorizer = null;
        Auth auth = handlerClass.getAnnotation(Auth.class);
        if (auth != null) {
            Class<? extends Authorizer> authorizerClass = auth.authorizer();
            try {
                // Try instantiating the user-provided Authorizer class
                authorizer = authorizerClass.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                // Critical error -- don't start server if Authorizer can't be instantiated,
                // it could lead to a serious vulnerability.
                throw new RuntimeException("Could not instantiate class " + authorizerClass.getName(), e);
            }
        }
        if (authorizer == null) {
            NoAuth noAuth = handlerClass.getAnnotation(NoAuth.class);
            if (noAuth != null) {
                // If there's no @Auth annotation, but there is a @NoAuth annotation, don't require authorization --
                // leave authorizer set to null.
            } else {
                // If no @Auth or @NoAuth annotation, require the user to be logged in.
                authorizer = new LoggedInAuthorizer();
            }
        }

        // Check for a Cached annotation on the handler subinterface
        Cached cachedAnnotation = handlerClass.getAnnotation(Cached.class);
        if (cachedAnnotation != null) {
            this.maxAgeSeconds = Math
                    .min(cachedAnnotation.maxAgeSeconds() < 0L ? Long.MAX_VALUE : cachedAnnotation.maxAgeSeconds(),
                            // Negative maxAgeSeconds => 1 year (effectively cached indefinitely)
                            31536000);
        }

        // Check for methods get() and post() in the handler subinterface
        for (Method method : handlerClass.getMethods()) {
            String methodName = method.getName();
            Class<?>[] paramTypes = method.getParameterTypes();
            boolean isGet = methodName.equals("get"), isPost = methodName.equals("post");

            // Check method modifiers
            if ((isGet || isPost) && (method.getModifiers() & Modifier.STATIC) != 0) {
                throw new RuntimeException("Method " + handlerClass.getName() + "." + methodName
                        + " should not be static");
            }

            // Check return type
            if ((isGet || isPost) && !Response.class.isAssignableFrom(method.getReturnType())) {
                throw new RuntimeException("Method " + handlerClass.getName() + "." + methodName
                        + " should have a return type of " + Response.class.getName() + " or a subclass, instead of "
                        + method.getReturnType().getName());
            }

            // Get method parameter types
            if (isGet) {
                // Check method parameters are key value pairs, and that the keys are strings, and the
                // values are String or Integer
                for (int j = 0; j < paramTypes.length; j++) {
                    if (paramTypes[j] != String.class && paramTypes[j] != Integer.class
                            && paramTypes[j] != Integer.TYPE) {
                        throw new RuntimeException("Method " + handlerClass.getName() + "." + methodName
                                + " has a param of type " + paramTypes[j].getName()
                                + ", needs to be String, int or Integer");
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
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * If a post() method takes exactly one parameter, then bind the param value from the POST data.
     * 
     * @param user
     */
    private Object[] bindPostParamFromPOSTData(Request request) throws RequestHandlingException {
        if (postParamType == null) {
            // post() takes no params
            return null;

        } else {
            // post() takes one param
            DataModel postParam;
            try {
                postParam = Reflection.instantiateWithDefaultConstructor(postParamType);
            } catch (InstantiationException e) {
                // Should never happen, we already tried instantiating all DataModel subclasses
                // that are bound to POST request handlers when site resources were loaded
                throw new InternalServerErrorException(request, "Could not instantiate POST parameter of type "
                        + postParamType.getName(), e);
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
     * route's base URI, e.g. /person/53 for a route of /person gives one Integer-typed param value of 53
     */
    private Object[] bindGetParamsFromURI(Request request) throws RequestHandlingException {
        String reqURI = request.getURLPathUnhashed();
        if (getParamTypes.length == 0) {
            // get() takes no params
            return null;

        } else {
            // get() takes one or more params
            Object[] getParamVals = new Object[getParamTypes.length];
            if (!reqURI.startsWith(routePath)) {
                // This is an error handler that has been called to replace the normal route handler;
                // don't try to parse URL params (leave them all as null)

            } else {
                // Parse URI params
                int slashIdx = routePath.length();
                for (int i = 0; i < getParamTypes.length; i++) {
                    int nextSlashIdx = slashIdx < reqURI.length() - 1 ? reqURI.indexOf('/', slashIdx + 1) : -1;
                    if (nextSlashIdx < 0) {
                        nextSlashIdx = reqURI.length();
                    }
                    if (nextSlashIdx - slashIdx < 2) {
                        throw new BadRequestException(request, "Insufficient URL parameters, expected "
                                + getParamTypes.length + ", got " + i);
                    }
                    String uriSegment = reqURI.substring(slashIdx + 1, nextSlashIdx);
                    if (getParamTypes[i] == Integer.class || getParamTypes[i] == Integer.TYPE) {
                        try {
                            // Specifically parse integers for int-typed method parameters 
                            getParamVals[i] = Integer.parseInt(uriSegment);
                        } catch (NumberFormatException e) {
                            throw new BadRequestException(request,
                                    "Malformed URL parameter, expected integer for URI parameter");
                        }
                    } else {
                        // N.B. the unescape is performed only once here, between '/' characters (the
                        // URI is not unescaped by the caller prior to passing the URI to this method),
                        // and the unescaped string is stored directly as a parameter value. Parameter
                        // values should not be unescaped again after this, to prevent double-encoding
                        // attacks: see https://www.owasp.org/index.php/Double_Encoding
                        getParamVals[i] = WebUtils.unescapeURISegment(uriSegment);
                    }
                    slashIdx = nextSlashIdx;
                }
                if (slashIdx < reqURI.length() - 1) {
                    // Still at least one URL param left
                    throw new BadRequestException(request, "Too many URL parameters");
                }
            }
            return getParamVals;
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Call the get() or post() method for the Route corresponding to the request URI.
     * 
     * Assumes user is sufficiently authorized to call this handler, i.e. assumes login checks have been performed etc.
     */
    public Response callHandler(Request request) throws RequestHandlingException {
        Response response;
        // Determine param vals for method
        HttpMethod reqMethod = request.getMethod();

        if (reqMethod == HttpMethod.GET) {
            // Bind URI params
            Object[] getParamVals = bindGetParamsFromURI(request);

            // Invoke the get() method with URI params
            response = invokeMethod(request, getMethod, getParamVals);

        } else if (reqMethod == HttpMethod.POST) {
            // For POST requests on @NoAuth handlers, don't need to check CSRF cookies.
            // For POST requests on non-@NoAuth handlers, check CSRF cookies against CSRF POST param.
            if (authorizer != null) {
                User user = request.lookupUser();
                if (user == null) {
                    // Should not happen -- checkAuth() has already determined that user is not null if
                    // authorizer is not null
                    throw new BadRequestException(request, "User not logged in, could not check CSRF token. "
                            + "POST requests are only accepted from logged-in users.");
                } else {
                    if (!CSRF.csrfTokMatches(request.getPostParam(CSRF.CSRF_PARAM_NAME), user)) {
                        throw new BadRequestException(request, "Missing or incorrect CSRF token in POST request");
                    }
                }
            }

            // Bind the post() method's single parameter (if it has one) from the POST data in the request
            Object[] postParamVal = bindPostParamFromPOSTData(request);

            // Invoke the post() method
            response = invokeMethod(request, postMethod, postParamVal);

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

    /**
     * Call the get() method for an error handler Route. (Does not do any authentication.)
     */
    public Response callErrorHandler(Request request) throws RequestHandlingException {
        // Bind any URI params; invoke the get() method with URI params, and return the Response.
        Object[] getParamVals = bindGetParamsFromURI(request);
        return invokeMethod(request, getMethod, getParamVals);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Get the route annotated with the given HTTP method on the given RestHandler, substituting the key/value pairs
     * into the params in the URL template.
     * 
     * Can call this method with no urlParams if the handler takes no params for this httpMethod, otherwise list URL
     * params in the varargs. URL params can be any type, and their toString() method will be called. Param values will
     * be URL-escaped (turned into UTF-8, then byte-escaped if the bytes are not safe).
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
                throw new RuntimeException("Wrong number of URL params for method " + handlerClass.getName() + ".get()");
            }
            // Build new fully-specified route from route stem and URL params 
            StringBuilder buf = new StringBuilder(handler.routePath);
            for (int i = 0; i < urlParams.length; i++) {
                buf.append('/');
                WebUtils.escapeURISegment(urlParams[i].toString(), buf);
            }
            return buf.toString();

        } else if (httpMethod == HttpMethod.POST) {
            if (handler.postMethod == null) {
                throw new RuntimeException("Handler " + handlerClass.getName() + " does not have a post() method");
            }
            if (urlParams.length != 0) {
                throw new RuntimeException("Tried to pass URL params to a POST method, " + handlerClass.getName()
                        + ".post()");
            }
            return handler.routePath;

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

    public String getRoutePath() {
        return routePath;
    }

    @Override
    public String toString() {
        return routePath;
    }

    public void setRoutePath(String routePath) {
        this.routePath = routePath;
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
     * Returns the post() method DataModel param object type. Returns null if this route handler does not have a post()
     * method, or if the handler does have a post() method but the method doesn't take any parameters.
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

    /** Get the maxAgeSeconds parameter from the Cached annotation on the handler class, or 0 if none. */
    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Check if the passed URI matches this route, i.e. returns true if the handler corresponding to this route should
     * handle the request.
     */
    public boolean matches(String reqURI) {
        // Check if reqURI.equals(routePath) || reqURI.startsWith(routePath + "/"), without creating an intermediate
        // object for (routePath + "/")
        return reqURI.startsWith(routePath) && //
                (reqURI.length() == routePath.length() || //
                (reqURI.length() > routePath.length() && reqURI.charAt(routePath.length()) == '/'));
    }
}
