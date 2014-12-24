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
import gribbit.auth.Cookie.EncodingType;
import gribbit.auth.User;
import gribbit.model.DataModel;
import gribbit.util.Log;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;

/**
 * The superclass of all response types, containing fields that go into the header of the HTTP response regardless of
 * the response type.
 */
public abstract class Response extends DataModel {

    protected HttpResponseStatus status = HttpResponseStatus.OK;
    protected String mimeType;
    protected HashMap<String, Cookie> cookies = null;
    protected long lastModifiedEpochMillis;
    protected HashMap<String, String> customHeaders;

    public Response(HttpResponseStatus status, String mimeType) {
        this.status = status;
        this.mimeType = mimeType;
    }

    public long getLastModifiedEpochMillis() {
        return lastModifiedEpochMillis;
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setStatus(HttpResponseStatus status) {
        this.status = status;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public void setLastModifiedEpochMillis(long lastModifiedEpochMillis) {
        this.lastModifiedEpochMillis = lastModifiedEpochMillis;
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
     * Add a possibly-base64-encoded cookie to the response.
     */
    public Response setCookie(String cookieName, String val, String path, int maxAgeInSeconds, EncodingType encodingType) {
        if (cookies == null)
            cookies = new HashMap<>();
        try {
            Cookie oldVal = cookies.put(cookieName, new Cookie(cookieName, val, path, maxAgeInSeconds, encodingType));
            if (oldVal != null) {
                Log.warning("Cookie \"" + cookieName + "\" written twice during a single request");
            }

        } catch (Exception e) {
            Log.error("Could not add cookie: " + e.getMessage());
        }
        return this;
    }

    /**
     * Add base64-encoded cookie to the response.
     */
    public Response setCookie(String key, String val, String path, int maxAgeInSeconds) {
        return setCookie(key, val, path, maxAgeInSeconds, EncodingType.BASE64_ENCODED);
    }

    /**
     * Add a base64-encoded cookie to the response.
     */
    public Response setCookie(String key, String val, String path, Instant expiry, EncodingType encodingType) {
        long secondsLeft = Math.max(0, ChronoUnit.SECONDS.between(Instant.now(), expiry));
        secondsLeft = secondsLeft < 0 ? 0 : secondsLeft > Integer.MAX_VALUE ? Integer.MAX_VALUE : secondsLeft;
        return setCookie(key, val, path, (int) secondsLeft, encodingType);
    }

    /**
     * Add base64-encoded cookie to the response.
     */
    public Response setCookie(String key, String val, String path, Instant expiry) {
        return setCookie(key, val, path, expiry, EncodingType.BASE64_ENCODED);
    }

    /** Delete a cookie from the response */
    public Response deleteCookie(String cookieName) {
        if (cookies == null)
            cookies = new HashMap<>();
        try {
            Cookie oldVal = cookies.put(cookieName, Cookie.deleteCookie(cookieName));
            if (oldVal != null) {
                // Log.warning("Cookie \"" + cookieName + "\" written then deleted during the same request");
            }

        } catch (Exception e) {
            Log.error("Could not delete cookie: " + e.getMessage());
        }
        return this;
    }

    public HashMap<String, Cookie> getCookies() {
        return cookies;
    }

    // -----------------------------------------------------------------------------------------------------

    public abstract ByteBuf getContent(User user, boolean isGetModelRequest);
}
