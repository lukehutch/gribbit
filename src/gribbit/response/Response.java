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
import gribbit.model.DataModel;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * The superclass of all response types, containing fields that go into the header of the HTTP response regardless of
 * the response type.
 */
public abstract class Response extends DataModel {

    protected HttpResponseStatus status = HttpResponseStatus.OK;
    protected String mimeType;
    protected ArrayList<Cookie> cookies = null, cookiesPathSpecific = null;
    protected long lastModifiedEpochSeconds, maxAgeSeconds;
    protected HashMap<String, String> customHeaders;
    protected String csrfTok;

    public Response(HttpResponseStatus status, String mimeType) {
        this.status = status;
        this.mimeType = mimeType;
    }

    public long getLastModifiedEpochSeconds() {
        return lastModifiedEpochSeconds;
    }

    public void setLastModifiedEpochSeconds(long lastModifiedEpochSeconds) {
        this.lastModifiedEpochSeconds = lastModifiedEpochSeconds;
    }

    public String getCsrfTok() {
        return csrfTok;
    }

    public void setCsrfTok(String csrfTok) {
        this.csrfTok = csrfTok;
    }

    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    public void setMaxAgeSeconds(long maxAgeSeconds) {
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    public String getContentType() {
        return mimeType;
    }

    public void setStatus(HttpResponseStatus status) {
        this.status = status;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public void addCustomHeader(String key, String value) {
        if (customHeaders == null) {
            customHeaders = new HashMap<>();
        }
        customHeaders.put(key, value);
    }

    public HashMap<String, String> getCustomHeaders() {
        return customHeaders;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Set a cookie in the response with a specific path (this allows there to be multiple cookies set with different
     * paths).
     * 
     * @return this
     */
    public Response setCookiePathSpecific(Cookie cookie) {
        if (cookiesPathSpecific == null) {
            cookiesPathSpecific = new ArrayList<>();
        }
        cookiesPathSpecific.add(cookie);
        return this;
    }

    /** Set a cookie in the response, deleting any existing cookies with the same name but a different path. */
    public Response setCookie(Cookie cookie) {
        if (cookies == null) {
            cookies = new ArrayList<>();
        }
        cookies.add(cookie);
        return this;
    }

    /**
     * Delete a cookie in the response with a matching specific path (this allows there to be multiple cookies set with
     * different paths).
     * 
     * @return this
     */
    public Response deleteCookiePathSpecific(String cookieName, String path) {
        if (cookiesPathSpecific == null) {
            cookiesPathSpecific = new ArrayList<>();
        }
        cookiesPathSpecific.add(Cookie.deleteCookie(cookieName, path));
        return this;
    }

    /** Set a cookie in the response, deleting any existing cookies with the same name and any path. */
    public Response deleteCookieAllPaths(String cookieName) {
        if (cookies == null) {
            cookies = new ArrayList<>();
        }
        cookies.add(Cookie.deleteCookie(cookieName, "/"));
        return this;
    }

    /** Called by HttpRequestHandler when the response is served. */
    public ArrayList<Cookie> getCookiesToSet() {
        return cookies;
    }

    /** Called by HttpRequestHandler when the response is served. */
    public ArrayList<Cookie> getCookiesToSetPathSpecific() {
        return cookiesPathSpecific;
    }

    // -----------------------------------------------------------------------------------------------------

    public abstract ByteBuf getContent();
}
