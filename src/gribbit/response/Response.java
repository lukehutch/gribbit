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

import gribbit.auth.Cookie;
import gribbit.auth.User;
import gribbit.request.Request;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * The superclass of all response types, containing fields that go into the header of the HTTP response regardless of
 * the response type.
 */
public abstract class Response {

    protected HttpResponseStatus status = HttpResponseStatus.OK;
    protected ArrayList<Cookie> cookiesToSet = null;
    protected HashSet<String> cookiesToDelete = null;
    protected long lastModifiedEpochSeconds, maxAgeSeconds;
    protected HashMap<String, String> customHeaders;
    protected String csrfTok;

    public Response(HttpResponseStatus status) {
        this.status = status;
    }

    public long getLastModifiedEpochSeconds() {
        return lastModifiedEpochSeconds;
    }

    public Response setLastModifiedEpochSeconds(long lastModifiedEpochSeconds) {
        this.lastModifiedEpochSeconds = lastModifiedEpochSeconds;
        return this;
    }

    public String getCsrfTok() {
        return csrfTok;
    }

    public Response setCsrfTok(String csrfTok) {
        this.csrfTok = csrfTok;
        return this;
    }

    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    public Response setMaxAgeSeconds(long maxAgeSeconds) {
        this.maxAgeSeconds = maxAgeSeconds;
        return this;
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    public Response setStatus(HttpResponseStatus status) {
        this.status = status;
        return this;
    }

    public Response addCustomHeader(String key, String value) {
        if (customHeaders == null) {
            customHeaders = new HashMap<>();
        }
        customHeaders.put(key, value);
        return this;
    }

    public HashMap<String, String> getCustomHeaders() {
        return customHeaders;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Set a cookie in the response. Note that cookies with a shorter path will be masked by cookies with a longer path
     * on routes with the longer path as a prefix.
     * 
     * @return this
     */
    public Response setCookie(Cookie cookie) {
        if (cookiesToSet == null) {
            cookiesToSet = new ArrayList<>();
        }
        cookiesToSet.add(cookie);
        return this;
    }

    /**
     * Delete all cookies (with all paths) matching the given name in the response. If this is called on the same
     * response as setCookie() with the same cookie name, the setCookie() will be ignored.
     * 
     * @return this
     */
    public Response deleteCookie(String cookieName) {
        if (cookiesToDelete == null) {
            cookiesToDelete = new HashSet<>();
        }
        cookiesToDelete.add(cookieName);
        return this;
    }

    /** Called by HttpRequestHandler when the response is served. */
    public ArrayList<Cookie> getCookiesToSet() {
        return cookiesToSet;
    }

    /** Called by HttpRequestHandler when the response is served. */
    public HashSet<String> getCookiesToDelete() {
        return cookiesToDelete;
    }

    // -----------------------------------------------------------------------------------------------------

    /** Delete login cookies in the user's browser. */
    public Response logOutUser() {
        User.removeLoginCookies(this);
        return this;
    }

    /**
     * Log out the currently logged-in user, if the request contains cookies that indicate a valid user is logged in:
     * delete login cookies in the user's browser, and invalidate the user's login tokens in the database.
     */
    public Response logOutUser(Request request) {
        if (request == null) {
            logOutUser();
        } else {
            User.logOutUser(request, this);
        }
        return this;
    }

    /**
     * Log out the currently logged-in user: delete login cookies in the user's browser, and invalidate the user's login
     * tokens in the database.
     */
    public Response logOutUser(User user) {
        if (user == null) {
            logOutUser();
        } else {
            user.logOut(this);
        }
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    /** Get the content of the response as a ByteBuf of UTF8 bytes. */
    public abstract ByteBuf getContent(Request request);

    /** Get the MIME type of the content. */
    public abstract String getContentType(Request request);
}
