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
package gribbit.response;

import gribbit.route.Route;
import gribbit.route.RouteInfo;
import io.netty.handler.codec.http.HttpResponseStatus;

public class RedirectResponse extends TextResponse {

    private String redirectToURI;

    /**
     * Redirect to a raw redirect URL string. Not recommended for site-local URLs; it's better to use one of the other
     * constructors that takes a Route as a parameter.
     */
    public RedirectResponse(String redirectToURI) {
        super(HttpResponseStatus.FOUND, "");
        addCustomHeader("Location", redirectToURI);
        this.redirectToURI = redirectToURI;
    }

    /**
     * Redirect to a specific route (i.e. performs a redirect with reverse routing).
     */
    public RedirectResponse(Class<? extends Route> redirectToHandler) {
        this(RouteInfo.forGet(redirectToHandler));
    }

    /**
     * Redirect to a specific route (i.e. performs a redirect with reverse routing).
     */
    public RedirectResponse(RouteInfo redirectToRoute) {
        this(redirectToRoute.getRoutePath());
    }

    /**
     * Redirect to the URL that the given RestHandler is annotated with (using the GET HTTP method), substituting URL
     * params into the URL for the handler. Can call with zero urlParams if the handler takes no URI params.
     */
    public static RedirectResponse redirectWithParams(Class<? extends Route> redirectToHandler, Object... urlParams) {
        return new RedirectResponse(RouteInfo.forGet(redirectToHandler, (Object[]) urlParams));
    }

    /**
     * Redirect to a given route, substituting URL params into the URL for the handler. Can call with zero urlParams if
     * the handler takes no URI params.
     */
    public static RedirectResponse redirectWithParams(RouteInfo redirectToRoute, Object... urlParams) {
        return new RedirectResponse(RouteInfo.forGet(redirectToRoute.getHandler(), (Object[]) urlParams));
    }

    public String getRedirectURI() {
        return redirectToURI;
    }
}
