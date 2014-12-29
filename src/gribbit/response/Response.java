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
import java.util.HashSet;

/**
 * The superclass of all response types, containing fields that go into the header of the HTTP response regardless of
 * the response type.
 */
public abstract class Response extends DataModel {

    protected HttpResponseStatus status = HttpResponseStatus.OK;
    protected String mimeType;
    protected ArrayList<Cookie> cookiesToSet = null;
    protected HashSet<String> cookiesToDelete = null;
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

    public abstract ByteBuf getContent();
}
