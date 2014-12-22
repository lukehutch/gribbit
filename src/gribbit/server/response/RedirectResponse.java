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
package gribbit.server.response;

import gribbit.auth.Cookie;
import gribbit.server.RestHandler;
import gribbit.server.Route;
import io.netty.handler.codec.http.HttpResponseStatus;

public class RedirectResponse extends TextResponse {

    private String redirectToURI;

    /**
     * Redirect to a raw redirect URL string. Not recommended for site-local URLs; it's better to use one of the other
     * constructors that takes a RestHandler subclass as a parameter. If redirectFromURI is non-null, then the
     * RestHandler.REDIRECT_ORIGIN_COOKIE_NAME cookie will be set to the URI that the user was redirected from.
     */
    public RedirectResponse(String redirectFromURI, String redirectToURI) {
        super(HttpResponseStatus.FOUND, "");
        addCustomHeader("Location", redirectToURI);
        this.redirectToURI = redirectToURI;

        if (redirectFromURI != null) {
            setCookie(Cookie.REDIRECT_ORIGIN_COOKIE_NAME, redirectFromURI, "/", 60);
        } else {
            deleteCookie(Cookie.REDIRECT_ORIGIN_COOKIE_NAME);
        }
    }

    /**
     * Redirect to a raw redirect URL string. Not recommended for site-local URLs; it's better to use one of the other
     * constructors that takes a RestHandler subclass as a parameter.
     */
    public RedirectResponse(String redirectToURI) {
        this(null, redirectToURI);
    }

    /**
     * Redirect to the URL that the given RestHandler is annotated with (using the GET HTTP method), substituting URL
     * params into the URL for the handler. Can call with zero urlParams if the handler takes no URI params. If
     * redirectFromURI is non-null, then the RestHandler.REDIRECT_ORIGIN_COOKIE_NAME cookie will be set to the URI that
     * the user was redirected from.
     */
    public RedirectResponse(String redirectFromURI, Class<? extends RestHandler> redirectToHandler, Object... urlParams) {
        this(redirectFromURI, Route.forGet(redirectToHandler, (Object[]) urlParams));
    }

    /**
     * Redirect to the URL that the given RestHandler is annotated with (using the GET HTTP method), substituting URL
     * params into the URL for the handler. Can call with zero urlParams if the handler takes no URI params.
     */
    public RedirectResponse(Class<? extends RestHandler> redirectToHandler, Object... urlParams) {
        this(null, Route.forGet(redirectToHandler, (Object[]) urlParams));
    }

    /**
     * Redirect to a given route, substituting URL params into the URL for the handler. Can call with zero urlParams if
     * the handler takes no URI params.
     */
    public RedirectResponse(Route redirectToRoute, Object... urlParams) {
        this(null, Route.forGet(redirectToRoute.getHandler(), (Object[]) urlParams));
    }

    /**
     * Redirect to a given route, substituting URL params into the URL for the handler. Can call with zero urlParams if
     * the handler takes no URI params.
     */
    public RedirectResponse(String redirectFromURI, Route redirectToRoute, Object... urlParams) {
        this(redirectFromURI, Route.forGet(redirectToRoute.getHandler(), (Object[]) urlParams));
    }

    public String getRedirectURI() {
        return redirectToURI;
    }
}
