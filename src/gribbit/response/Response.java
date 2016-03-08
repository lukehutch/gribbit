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

import java.util.ArrayList;
import java.util.HashMap;

import gribbit.auth.User;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.RoutingContext;

/**
 * The superclass of all response types, containing fields that go into the header of the HTTP response regardless
 * of the response type.
 */
public abstract class Response {
    protected final HttpResponseStatus status;
    protected HashMap<String, DefaultCookie> cookies;
    private ArrayList<CustomHeader> customHeaders;
    private boolean logOut;

    public Response(HttpResponseStatus status) {
        this.status = status;
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    // -----------------------------------------------------------------------------------------------------

    private static class CustomHeader {
        CharSequence key;
        CharSequence value;

        public CustomHeader(CharSequence key, CharSequence value) {
            this.key = key;
            this.value = value;
        }
    }

    public Response addHeader(CharSequence key, CharSequence value) {
        if (customHeaders == null) {
            customHeaders = new ArrayList<>();
        }
        customHeaders.add(new CustomHeader(key, value));
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Set a cookie value.
     * 
     * (As per RFC6295, the server can only return one cookie with a given name per response. We arbitrarily choose
     * the last value the cookie is set to as the one that is sent in the response, even if setCookie is called
     * multiple times for a given cookie name with different paths.)
     */
    private Response setCookie(DefaultCookie cookie) {
        if (cookies == null) {
            cookies = new HashMap<>();
        }
        cookies.put(cookie.name(), cookie);
        return this;
    }

    /**
     * Set a cookie in the response.
     * 
     * (As per RFC6295, the server can only return one cookie with a given name per response. We arbitrarily choose
     * the last value the cookie is set to as the one that is sent in the response, even if setCookie is called
     * multiple times for a given cookie name with different paths.)
     * 
     * If the request was made over HTTPS, then the cookie is also set to be visible only over HTTPS.
     * 
     * @param name
     *            The name of the cookie.
     * @param path
     *            The path, or if null, defaults (in the browser) to the path of the request.
     * @param value
     *            The value of the cookie.
     * @param maxAgeSeconds
     *            The max age of the cookie. If 0, causes the cookie to be deleted. If negative, causes the cookie
     *            to "never" expire (actually sets expiration date to a year from now).
     * @param httpOnly
     *            If true, cookie is inaccessible to Javascript. (Recommended.)
     */
    public Response setCookie(String name, String value, String path, long maxAgeSeconds, boolean httpOnly) {
        DefaultCookie cookie = new DefaultCookie(name, value);
        if (path != null) {
            cookie.setPath(path);
        }
        long ONE_YEAR_IN_SECONDS = 31536000L;
        cookie.setMaxAge(maxAgeSeconds < 0 ? ONE_YEAR_IN_SECONDS : maxAgeSeconds);
        cookie.setHttpOnly(httpOnly);
        setCookie(cookie);
        return this;
    }

    /**
     * Set an HTTP-only cookie in the response with the same path as the request, and a max age of 1 year.
     * 
     * (As per RFC6295, the server can only return one cookie with a given name per response. We arbitrarily choose
     * the last value the cookie is set to as the one that is sent in the response, even if setCookie is called
     * multiple times for a given cookie name with different paths.)
     * 
     * If the request was made over HTTPS, then the cookie is also set to be visible only over HTTPS.
     * 
     * @param name
     *            The name of the cookie.
     * @param value
     *            The value of the cookie.
     */
    public Response setCookie(String name, String value) {
        setCookie(name, value, /* path = */null, /* maxAgeSeconds = */-1, /* httpOnly = */true);
        return this;
    }

    /**
     * Delete a cookie.
     */
    public Response deleteCookie(String name, String path, boolean httpOnly) {
        setCookie(name, null, path, 0L, httpOnly);
        return this;
    }

    /**
     * Delete a cookie (assumes httpOnly is true).
     */
    public Response deleteCookie(String name, String path) {
        setCookie(name, null, path, 0L, true);
        return this;
    }

    /**
     * Log out user when response is sent.
     */
    public Response logOut() {
        this.logOut = true;
        return this;
    }
    
    // -----------------------------------------------------------------------------------------------------

    protected void sendHeaders(RoutingContext routingContext, String contentType) {
        HttpServerRequest request = routingContext.request();
        HttpServerResponse response = request.response();
        MultiMap headers = response.headers();

        headers.add("Content-Type", contentType);
        
        if (customHeaders != null) {
            for (CustomHeader c : customHeaders) {
                headers.add(c.key, c.value);
            }
        }

        if (cookies != null) {
            for (DefaultCookie cookie : cookies.values()) {
                // If the request is over SSL, set the cookie to be only visible over SSL
                cookie.setSecure(request.isSSL());
                routingContext.addCookie(Cookie.cookie(cookie));
            }
        }
        
        if (logOut) {
            User.logOut(routingContext.session());
        }
    }

    /** Send the response. Should call sendHeaders() first. */
    public abstract void send(RoutingContext routingContext);
}
