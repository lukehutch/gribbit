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

import gribbit.auth.CSRF;
import gribbit.auth.User;
import gribbit.exception.BadRequestException;
import gribbit.model.DataModel;
import gribbit.request.Request;
import gribbit.response.ErrorResponse;
import gribbit.response.Response;
import gribbit.server.GribbitServer;
import gribbit.util.AppException;
import gribbit.util.Log;
import gribbit.util.Reflection;
import gribbit.util.WebUtils;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.ZonedDateTime;

/** The metadata about a Route. */
public class RouteInfo {
    private String routePath;
    private Class<? extends Route> routeClass;
    private Method getMethod, postMethod;
    private Class<?>[] getParamTypes;
    private Class<? extends DataModel> postParamType;

    private ClassLoader routeClassLoader;
    private Class<?>[] proxyParams;

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * See comments in:
     * https://rmannibucau.wordpress.com/2014/03/27/java-8-default-interface-methods-and-jdk-dynamic-proxies
     */
    private static final Constructor<MethodHandles.Lookup> constructor;
    static {
        try {
            constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
        } catch (NoSuchMethodException | SecurityException e) {
            throw new RuntimeException(e);
        }
        if (!constructor.isAccessible()) {
            constructor.setAccessible(true);
        }
    }

    /**
     * InvocationHandler for dynamically invoking get()/post() methods in Route objects. See:
     * https://rmannibucau.wordpress.com/2014/03/27/java-8-default-interface-methods-and-jdk-dynamic-proxies
     * 
     * Wraps the Request and User objects so that Route.getRequest() and Route.getUser() methods can be intercepted and
     * have the right values returned for the current request.
     */
    private class MethodInvocationHandler implements InvocationHandler {
        Request request;
        User user;

        public MethodInvocationHandler(Request request, User user) {
            this.request = request;
            this.user = user;
        }

        /**
         * Invoke a method in a Route subinterface, intercepting getRequest() and getUser() method calls to return the
         * appropriate values.
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if (methodName.equals("getRequest")) {
                return request;
            } else if (methodName.equals("getUser")) {
                return user;
            } else {
                // Call default implementation of interface method with the given args
                if (!method.isDefault()) {
                    throw new RuntimeException("Method " + method.getName() + " in interface "
                            + proxy.getClass().getName() + " is not a default method");
                }
                final Class<?> declaringClass = method.getDeclaringClass();
                if (method.isDefault()) {
                    return constructor.newInstance(declaringClass, MethodHandles.Lookup.PRIVATE)
                            .unreflectSpecial(method, declaringClass).bindTo(proxy).invokeWithArguments(args);
                } else {
                    return MethodHandles.lookup().in(declaringClass).unreflectSpecial(method, declaringClass)
                            .bindTo(proxy).invokeWithArguments(args);
                }
            }
        }
    }

    /** Invoke a default method in a Route subinterface. */
    private Response invokeMethod(Request request, User user, Method method, Object[] methodParamVals) {
        // Create InvocationHandler and proxy instance for proxying the dynamic method call -- see
        // https://rmannibucau.wordpress.com/2014/03/27/java-8-default-interface-methods-and-jdk-dynamic-proxies/
        InvocationHandler invocationHandler = new MethodInvocationHandler(request, user);
        Route proxy = (Route) Proxy.newProxyInstance(routeClassLoader, proxyParams, invocationHandler);

        try {
            // Invoke the method
            Response response = (Response) invocationHandler.invoke(proxy, method, methodParamVals);

            // The Response object should not be null, but if it is, respond with No Content
            if (response == null) {
                Log.warning(routeClass.getName() + "." + method.getName()
                        + " returned a null response -- responding with 204: No Content");
                response = new ErrorResponse(HttpResponseStatus.NO_CONTENT, "");

            } else {
                // If last modified time was not filled in in the response, fill it in with the current time,
                // but only if the result code was OK (so that the last modified header is not set on error
                // responses)
                if (response.getStatus() == HttpResponseStatus.OK) {
                    if (response.getLastModifiedEpochMillis() == 0) {
                        response.setLastModifiedEpochMillis(ZonedDateTime.now().toInstant().toEpochMilli());
                    }
                } else {
                    // For errors, clear the last modified time field in case it was accidentally set
                    if (response.getLastModifiedEpochMillis() > 0) {
                        response.setLastModifiedEpochMillis(0);
                    }
                }
            }

            // TODO: set a "max_age" field in the response for classes with a @Cached(max_age = N) annotation

            return response;

        } catch (Throwable e) {
            throw new RuntimeException("Exception while invoking the method " + routeClass.getName() + "."
                    + method.getName(), e);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    public RouteInfo(Class<? extends Route> handlerClass, String routePath) {
        this.routeClass = handlerClass;
        this.routeClassLoader = routeClass.getClassLoader();
        this.proxyParams = new Class[] { routeClass };
        this.routePath = routePath;

        // Check for methods get() and post() in the handler class
        for (Method method : handlerClass.getMethods()) {
            String methodName = method.getName();
            Class<?>[] paramTypes = method.getParameterTypes();
            boolean isGet = methodName.equals("get"), isPost = methodName.equals("post");

            // Check method modifiers
            if ((isGet || isPost) && !method.isDefault()) {
                throw new RuntimeException("Method " + handlerClass.getName() + "." + methodName
                        + " needs to be default");
            } else if (!(isGet || isPost) && !method.isDefault() && (method.getModifiers() | Modifier.STATIC) == 0) {
                throw new RuntimeException("Method " + handlerClass.getName() + "." + methodName
                        + " needs to be default or static");
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
                    if (paramTypes[j] != String.class && paramTypes[j] != Integer.class) {
                        throw new RuntimeException("Method " + handlerClass.getName() + "." + methodName
                                + " has a param of type " + paramTypes[j].getName() + ", needs to be String or Integer");
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

    /** If a post() method takes exactly one parameter, then bind the param value from the POST data. */
    private Object[] bindPostParamFromPOSTData(Request request) throws AppException {
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
                throw new AppException("Could not instantiate POST parameter of type " + postParamType.getName(), e);
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
    private Object[] bindGetParamsFromURI(String reqURI) throws BadRequestException {
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
                        throw new BadRequestException("Insufficient URL parameters, expected " + getParamTypes.length
                                + ", got " + i);
                    }
                    String uriSegment = reqURI.substring(slashIdx + 1, nextSlashIdx);
                    if (getParamTypes[i] == Integer.class) {
                        try {
                            // Specifically parse integers for int-typed method parameters 
                            getParamVals[i] = Integer.parseInt(uriSegment);
                        } catch (NumberFormatException e) {
                            throw new BadRequestException("Malformed URL parameter, expected integer for URI parameter");
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
                    throw new BadRequestException("Too many URL parameters");
                }
            }
            return getParamVals;
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Call the get() or post() method for the Route subinterface corresponding to the request URI.
     * 
     * Assumes user is sufficiently authorized to call this handler, i.e. assumes login checks have been performed etc.
     */
    public Response callHandler(Request request, User user) throws Exception {
        Response response;
        if (AuthRequiredRoute.class.isAssignableFrom(routeClass) && user == null) {
            // Should not happen (the user object should only be non-null if the user is authorized),
            // but just to be safe, double check that user is authorized if they are calling an
            // authorization-required handler
            Log.error("Tried to call a " + AuthRequiredRoute.class.getName() + " handler with a null user object");
            response = new ErrorResponse(HttpResponseStatus.UNAUTHORIZED, "Not authorized");

        } else {
            // Determine param vals for method

            HttpMethod reqMethod = request.getMethod();
            if (reqMethod == HttpMethod.GET) {

                // Bind URI params
                Object[] getParamVals = bindGetParamsFromURI(request.getURI());

                // Invoke the get() method with URI params
                response = invokeMethod(request, user, getMethod, getParamVals);

            } else if (reqMethod == HttpMethod.POST) {

                // For POST requests, check CSRF cookies against CSRF POST param, unless this is an
                // unathenticated route
                if (AuthRequiredRoute.class.isAssignableFrom(routeClass)) {
                    String postToken = request.getPostParam(CSRF.CSRF_PARAM_NAME);
                    if (postToken == null || postToken.isEmpty()) {
                        throw new AppException("Missing CSRF token in POST request");
                    }
                    String userCSRFToken = null;
                    if (user != null && user.csrfTok != null) {
                        // If user is logged in and this is an authenticated route, use the CSRF token
                        // saved along with the session in the User object
                        userCSRFToken = user.csrfTok;
                    } else {
                        throw new AppException("User not logged in, could not check CSRF token");
                    }
                    if (!userCSRFToken.equals(postToken) || postToken.equals(CSRF.CSRF_TOKEN_PLACEHOLDER)
                            || postToken.equals(CSRF.CSRF_TOKEN_UNKNOWN)) {
                        throw new AppException("CSRF token mismatch");
                    }
                }

                // Bind the post() method's single parameter (if it has one) from the POST data in the request
                Object[] postParamVal = bindPostParamFromPOSTData(request);

                // Invoke the post() method
                response = invokeMethod(request, user, postMethod, postParamVal);

            } else {
                // Method not allowed
                response = new ErrorResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed");
            }
        }

        return response;
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
    private static String forMethod(HttpMethod httpMethod, Class<? extends Route> handlerClass, Object... urlParams) {
        RouteInfo handler = GribbitServer.siteResources.routeForClass(handlerClass);

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
                buf.append(WebUtils.escapeURISegment(urlParams[i].toString()));
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
    public static String forPost(Class<? extends Route> handlerClass) {
        return RouteInfo.forMethod(HttpMethod.POST, handlerClass);
    }

    /**
     * Return the route path for the GET method handler of a RestHandler class, appending URL params.
     */
    public static String forGet(Class<? extends Route> handlerClass, Object... urlParams) {
        return RouteInfo.forMethod(HttpMethod.GET, handlerClass, (Object[]) urlParams);
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

    public Class<? extends Route> getHandler() {
        return routeClass;
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

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Check if the passed URI matches this route, i.e. returns true if the handler corresponding to this route should
     * handle the request.
     */
    public boolean matches(String reqURI) {
        // Check if reqURI.equals(routePath) || reqURI.startsWith(routePath + "/")
        return reqURI.equals(routePath)
                || (reqURI.startsWith(routePath) && reqURI.length() > routePath.length() && reqURI.charAt(routePath
                        .length()) == '/');
    }
}
