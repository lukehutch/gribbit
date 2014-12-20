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

import gribbit.auth.CSRF;
import gribbit.auth.User;
import gribbit.exception.BadRequestException;
import gribbit.model.DataModel;
import gribbit.util.AppException;
import gribbit.util.Reflection;
import gribbit.util.WebUtils;
import io.netty.handler.codec.http.HttpMethod;

import java.lang.reflect.Method;

public class Route {
    private String routePath;
    private Class<? extends RestHandler> handler;
    private Method getMethod, postMethod;
    private Class<?>[] getParamTypes;
    private Class<? extends DataModel> postParamType;

    // -----------------------------------------------------------------------------------------------------

    public Route(Class<? extends RestHandler> handler, String routeOverride) {
        this.handler = handler;
        this.routePath = routeOverride != null ? routeOverride : routePathFromClassName(handler);

        // Check for methods get() and post() in the handler class
        for (Method method : handler.getMethods()) {
            String methodName = method.getName();
            Class<?>[] paramTypes = method.getParameterTypes();

            if (methodName.equals("get")) {
                if (method.getReturnType() != Void.TYPE) {
                    throw new RuntimeException("Method " + handler.getName() + "." + methodName
                            + " should have a void return type instead of " + method.getReturnType().getName());
                }

                // Check method parameters are key value pairs, and that the keys are strings, and the
                // values are String or Integer
                for (int j = 0; j < paramTypes.length; j++) {
                    if (paramTypes[j] != String.class && paramTypes[j] != Integer.class)
                        throw new RuntimeException("Method " + handler.getName() + "." + methodName
                                + " has a param of type " + paramTypes[j].getName() + ", needs to be String or Integer");
                }

                if (routePath.equals("/") && paramTypes.length != 0) {
                    // Can't pass URL parameters into the root URL, because the root URL may be a prefix
                    // of another handler's URL (it is the only URL that this is allowed for, and in
                    // general it's not a problem because URLs are determined from the RestHandler's full
                    // classname). If we allowed the root URL to take params, then the params would match
                    // URL path components.
                    throw new RuntimeException("Class " + handler.getName()
                            + " has a route override of \"/\", so it serves the root URL, "
                            + "but the get() method takes one or more parameters. "
                            + "(The root URL cannot take URL parameters, because they "
                            + "would match path components of other URLs.)");
                }

                if (getMethod != null) {
                    // Make sure there is only one method of each name
                    throw new RuntimeException("Class " + handler.getName() + " has two get() methods");
                }
                getMethod = method;
                getParamTypes = paramTypes;

            } else if (methodName.equals("post")) {
                if (method.getReturnType() != Void.TYPE) {
                    throw new RuntimeException("Method " + handler.getName() + "." + methodName
                            + " should have a void return type instead of " + method.getReturnType().getName());
                }

                if (paramTypes.length > 1) {
                    throw new RuntimeException("Method " + handler.getName() + "." + methodName
                            + " needs zero parameters or one parameter of type " + DataModel.class.getSimpleName()
                            + "; takes " + paramTypes.length + " parameters");
                }

                if (paramTypes.length == 1) {
                    if (!DataModel.class.isAssignableFrom(paramTypes[0])) {
                        throw new RuntimeException("The parameter of method " + handler.getName() + "." + methodName
                                + " needs to be a subclass of " + DataModel.class.getName());
                    }
                    @SuppressWarnings("unchecked")
                    Class<? extends DataModel> paramType = (Class<? extends DataModel>) paramTypes[0];
                    postParamType = paramType;
                }

                if (postMethod != null) {
                    // Make sure there is only one method of each name
                    throw new RuntimeException("Class " + handler.getName() + " has two post() methods");
                }
                postMethod = method;
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Convert class name to route path. For example:
     * 
     * app.action.HandleEmailValidationLink -> /app/action/handle-email-validation-link
     */
    private static String routePathFromClassName(Class<? extends RestHandler> handler) {
        StringBuilder buf = new StringBuilder("/");
        String name = handler.getName().replace('.', '/');
        int leaf = name.lastIndexOf('/') + 1;
        buf.append(name.substring(0, leaf));
        for (int i = leaf, n = name.length(); i < n; i++) {
            if (i > leaf && Character.isUpperCase(name.charAt(i)) && !Character.isUpperCase(name.charAt(i - 1)))
                buf.append('-');
            buf.append(Character.toLowerCase(name.charAt(i)));
        }
        if (buf.length() > 2
                && buf.subSequence(1, GribbitServer.appPackageName.length() + 1).equals(GribbitServer.appPackageName))
            return buf.substring(GribbitServer.appPackageName.length() + 1);
        else
            return buf.toString();
    }

    // -----------------------------------------------------------------------------------------------------

    /** Call the RestHandler corresponding to this route, with user set to null. */
    public void callHandler(Request req, Response res) throws Exception {
        callHandler(req, res, null);
    }

    /** Call the RestHandler corresponding to this route. */
    public void callHandler(Request req, Response res, User user) throws Exception {
        // Construct a new RestHandler of the appropriate type for this route
        RestHandler restHandler = handler.newInstance();
        restHandler.req = req;
        restHandler.res = res;
        if (RestHandler.AuthRequired.class.isAssignableFrom(handler)) {
            if (user == null) {
                throw new RuntimeException("Can't call AuthRequired handlers without supplying non-null user object");
            }
            ((RestHandler.AuthRequired) restHandler).user = user;
        }

        // Get param vals for RestHandler method
        Object[] paramVals = null;
        Method javaMethod = null;
        HttpMethod reqMethod = req.getMethod();
        String reqURI = req.getURI();
        if (reqMethod == HttpMethod.GET) {
            javaMethod = getMethod;
            paramVals = getParamTypes.length == 0 ? null : new Object[getParamTypes.length];

            if (!reqURI.startsWith(routePath)) {
                // This is an error handler that has been called to replace the normal route handler;
                // don't try to parse URL params
            } else {
                // GET params come from URL after end of route, e.g. /person/53 for a route of /person
                // gives one Integer-typed param value of 53
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
                            paramVals[i] = Integer.parseInt(uriSegment);
                        } catch (NumberFormatException e) {
                            throw new BadRequestException("Malformed URL parameter, expected integer");
                        }
                    } else {
                        // N.B. the unescape is performed only once here, between '/' characters (the
                        // URI is not unescaped by the caller prior to passing the URI to this method),
                        // and the unescaped string is stored directly as a parameter value. Parameter
                        // values should not be unescaped again after this, to prevent double-encoding
                        // attacks: see https://www.owasp.org/index.php/Double_Encoding
                        paramVals[i] = WebUtils.unescapeURISegment(uriSegment);
                    }
                    slashIdx = nextSlashIdx;
                }

                if (slashIdx < reqURI.length() - 1) {
                    // Still at least one URL param left
                    throw new BadRequestException("Too many URL parameters");
                }
            }

        } else if (reqMethod == HttpMethod.POST) {
            javaMethod = postMethod;

            // For POST requests, check CSRF cookies against CSRF POST param, unless this is an
            // unathenticated route
            if (RestHandler.AuthRequired.class.isAssignableFrom(handler)) {
                String postToken = req.getPostParam(CSRF.CSRF_PARAM_NAME);
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

            // If this is not a post() method that takes no params, then need to bind the param
            // object from POST param vals 
            if (postParamType != null) {
                DataModel postParam;
                try {
                    postParam = Reflection.instantiateWithDefaultConstructor(postParamType);
                } catch (InstantiationException e) {
                    // Should never happen, we already tried instantiating all DataModel subclasses
                    // that are bound to POST request handlers when site resources were loaded
                    throw new AppException("Could not instantiate POST parameter of type " + postParamType.getName(), e);
                }

                // Bind POST param object from request
                postParam.bindFromPost(req);
                paramVals = new Object[1];
                paramVals[0] = postParam;
            }
        }

        // User has sufficient authorization to run this method and parameters are of the right type.
        // Call Java method corresponding to the requested HTTP method on this route.
        // This method will set a response in restHandler.res.
        javaMethod.invoke(restHandler, paramVals);
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Get the route annotated with the given HTTP method on the given RestHandler, substituting the key/value pairs
     * into the params in the URL template.
     * 
     * Can call this method with no urlParams if the handler takes no params for this httpMethod, otherwise list URL
     * params in the varargs. URL params can be any type, and their toString() method will be called. Param values will
     * be URL-escaped (turned into UTF-8, then byte-escaped if the bytes are not safe).
     */
    private static String forMethod(HttpMethod httpMethod, Class<? extends RestHandler> handlerClass,
            Object... urlParams) {
        Route handler = GribbitServer.siteResources.routeForHandler(handlerClass);

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
    public static String forPost(Class<? extends RestHandler> handlerClass) {
        return Route.forMethod(HttpMethod.POST, handlerClass);
    }

    /**
     * Return the route path for the GET method handler of a RestHandler class, appending URL params.
     */
    public static String forGet(Class<? extends RestHandler> handlerClass, Object... urlParams) {
        return Route.forMethod(HttpMethod.GET, handlerClass, (Object[]) urlParams);
    }

    // -----------------------------------------------------------------------------------------------------

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

    public Class<? extends RestHandler> getHandler() {
        return handler;
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

    // -----------------------------------------------------------------------------------------------------

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
