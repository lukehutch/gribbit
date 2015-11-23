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
package gribbit.response.exception;

import gribbit.response.ErrorResponse;
import gribbit.route.Route;
import gribbit.route.RouteHandler;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * This exception is thrown to redirect a user to another URL. This RequestHandlingException is special in that the
 * constructor does not optionally take an exception message, instead it takes the URI to redirect to, or a route
 * handler to redirect to, in the from of a Class<? extends RouteHandler> or RouteInfo object, optionally (in the
 * static factory methods) with parameters to append to the get() method's URI.
 */
public class RedirectException extends RequestHandlingException {

    /**
     * Redirect to a raw URL. Not recommended for site-local URLs; it's better to use one of the other constructors
     * that takes a Route as a parameter.
     */
    public RedirectException(String redirectToURI) {
        super(new ErrorResponse(HttpResponseStatus.FOUND, "") //
                .addCustomHeader("Location", redirectToURI));
    }

    /**
     * Redirect to a specific route (i.e. performs a redirect with reverse routing).
     */
    public RedirectException(Class<? extends RouteHandler> redirectToHandler) {
        this(Route.forGet(redirectToHandler));
    }

    /**
     * Redirect to a specific route (i.e. performs a redirect with reverse routing).
     */
    public RedirectException(Route redirectToRoute) {
        this(redirectToRoute.getRoutePath());
    }

    /**
     * Redirect to the URL that the given RestHandler is annotated with (using the GET HTTP method), substituting
     * URL params into the URL for the handler. Can call with zero urlParams if the handler takes no URI params.
     */
    public static void throwRedirectWithParams(Class<? extends RouteHandler> redirectToHandler, Object... urlParams)
            throws RedirectException {
        throw new RedirectException(Route.forGet(redirectToHandler, (Object[]) urlParams));
    }

    /**
     * Redirect to a given route, substituting URL params into the URL for the handler. Can call with zero urlParams
     * if the handler takes no URI params.
     */
    public static void throwRedirectWithParams(Route redirectToRoute, Object... urlParams) throws RedirectException {
        throw new RedirectException(Route.forGet(redirectToRoute.getHandler(), (Object[]) urlParams));
    }

    /**
     * Don't pay the cost of filling in the stack trace -- see
     * http://java-performance.info/throwing-an-exception-in-java-is-very-slow/
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}