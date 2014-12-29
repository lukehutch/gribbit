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
package gribbit.request;

import static io.netty.handler.codec.http.HttpHeaders.Names.ACCEPT;
import static io.netty.handler.codec.http.HttpHeaders.Names.ACCEPT_CHARSET;
import static io.netty.handler.codec.http.HttpHeaders.Names.ACCEPT_LANGUAGE;
import static io.netty.handler.codec.http.HttpHeaders.Names.COOKIE;
import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static io.netty.handler.codec.http.HttpHeaders.Names.IF_MODIFIED_SINCE;
import static io.netty.handler.codec.http.HttpHeaders.Names.ORIGIN;
import static io.netty.handler.codec.http.HttpHeaders.Names.REFERER;
import static io.netty.handler.codec.http.HttpHeaders.Names.USER_AGENT;
import gribbit.auth.Cookie;
import gribbit.auth.Cookie.EncodingType;
import gribbit.response.flashmsg.FlashMessage;
import gribbit.response.flashmsg.FlashMessage.FlashType;
import gribbit.util.StringUtils;
import io.netty.handler.codec.http.CookieDecoder;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.FileUpload;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Request {
    private long reqReceivedTimeEpochMillis;
    private HttpMethod method;
    private String requestor;
    private String host;
    private String path;
    private String accept;
    private String acceptCharset;
    private String acceptLanguage;
    private String referer;
    private String userAgent;
    private long ifModifiedSinceEpochSecond = 0;

    private HashMap<String, Cookie> cookieNameToCookie;
    private HashMap<String, String> postParamToValue;
    private HashMap<String, FileUpload> postParamToFileUpload;
    private Map<String, List<String>> queryParamToVals;

    /**
     * Header for CORS.
     * 
     * See http://en.wikipedia.org/wiki/Cross-origin_resource_sharing
     **/
    private String origin;

    /**
     * Header for CSRF protection of AJAX requests (regular GETs and POSTs don't allow for header manipulation.)
     * 
     * See https://nealpoole.com/blog/2010/11/preventing-csrf-attacks-with-ajax-and-http-headers/
     */
    private String xRequestedWith;

    /**
     * If set to true by appending "?_getmodel=1" to the URL, then return the data model backing an HTML page, not the
     * rendered page itself.
     */
    private boolean isGetModelRequest;

    /** Flash messages, in cookie string format */
    private String flashMessageCookieString;

    // -----------------------------------------------------------------------------------------------------

    public Request(HttpRequest httpReq) {
        this.reqReceivedTimeEpochMillis = System.currentTimeMillis();
        HttpHeaders headers = httpReq.headers();

        // Parse and decode/decrypt cookies
        for (String cookieHeader : headers.getAll(COOKIE)) {
            for (io.netty.handler.codec.http.Cookie nettyCookie : CookieDecoder.decode(cookieHeader)) {
                if (this.cookieNameToCookie == null) {
                    this.cookieNameToCookie = new HashMap<>();
                }
                this.cookieNameToCookie.put(nettyCookie.getName(), new Cookie(nettyCookie));
            }
        }

        this.method = httpReq.getMethod();

        this.host = headers.get(HOST);

        this.xRequestedWith = headers.get("X-Requested-With");
        this.accept = headers.get(ACCEPT);
        this.acceptCharset = headers.get(ACCEPT_CHARSET);
        this.acceptLanguage = headers.get(ACCEPT_LANGUAGE);
        this.origin = headers.get(ORIGIN);
        this.referer = headers.get(REFERER);
        this.userAgent = headers.get(USER_AGENT);

        String cacheDateHeader = headers.get(IF_MODIFIED_SINCE);
        if (cacheDateHeader != null && !cacheDateHeader.isEmpty()) {
            this.ifModifiedSinceEpochSecond =
                    ZonedDateTime.parse(cacheDateHeader, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
        }

        // Decode the path.
        QueryStringDecoder decoder = new QueryStringDecoder(httpReq.getUri());
        this.path = decoder.path();
        this.queryParamToVals = decoder.parameters();

        // Look for _getmodel=1 query parameter setting
        String getModel = this.getQueryParam("_getmodel");
        if (getModel != null) {
            this.isGetModelRequest = getModel.equals("1");
            // Remove _getmodel param so that user doesn't see it
            this.queryParamToVals.remove("_getmodel");
        }

        // Get flash messages from cookie, if any
        this.flashMessageCookieString = getCookieValue(Cookie.FLASH_COOKIE_NAME);
    }

    // -----------------------------------------------------------------------------------------------------------------

    public String getPostParam(String paramName) {
        if (postParamToValue == null) {
            return null;
        } else {
            return postParamToValue.get(paramName);
        }
    }

    public void setPostParam(String name, String value) {
        if (postParamToValue == null) {
            postParamToValue = new HashMap<>();
        }
        postParamToValue.put(name, value);
    }

    public Set<String> getPostParamNames() {
        if (postParamToValue == null) {
            return null;
        } else {
            return postParamToValue.keySet();
        }
    }

    public void setPostFileUploadParam(String name, FileUpload fileUpload) {
        if (postParamToFileUpload == null) {
            postParamToFileUpload = new HashMap<>();
        }
        FileUpload old = postParamToFileUpload.put(name, fileUpload);
        if (old != null) {
            // Shouldn't happen, but just in case there are two file upload params with the same
            // param name, free the first, since we're overwriting it
            old.release();
        }
    }

    public FileUpload getPostFileUploadParam(String name) {
        if (postParamToFileUpload == null) {
            return null;
        }
        return postParamToFileUpload.get(name);
    }

    public void releasePostFileUploadParams() {
        if (postParamToFileUpload != null) {
            for (FileUpload fileUpload : postParamToFileUpload.values()) {
                fileUpload.release();
            }
            postParamToFileUpload = null;
        }
    }

    void setPostParams(HashMap<String, String> postParamToValue) {
        this.postParamToValue = postParamToValue;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Return all URL parameters matching the given name, or null if none. */
    public List<String> getQueryParams(String paramName) {
        if (queryParamToVals == null) {
            return null;
        } else {
            return queryParamToVals.get(paramName);
        }
    }

    /** Return the first URL parameter matching the given name, or null if none. */
    public String getQueryParam(String paramName) {
        List<String> list = getQueryParams(paramName);
        if (list == null || list.isEmpty()) {
            return null;
        } else {
            return list.get(0);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    public Collection<Cookie> getCookies() {
        if (cookieNameToCookie == null) {
            return null;
        } else {
            return cookieNameToCookie.values();
        }
    }

    public Cookie getCookie(String cookieName) {
        if (cookieNameToCookie == null) {
            return null;
        } else {
            return cookieNameToCookie.get(cookieName);
        }
    }

    public String getCookieValue(String cookieName) {
        Cookie cookie = getCookie(cookieName);
        if (cookie == null) {
            return null;
        } else {
            return cookie.getValue();
        }
    }

    /** Add a cookie to serve later in the response. */
    public void setCookie(Cookie cookie) {
        if (cookieNameToCookie == null) {
            cookieNameToCookie = new HashMap<>();
        }
        cookieNameToCookie.put(cookie.getName(), cookie);
    }

    /** Add a cookie to delete later in the response. */
    public void deleteCookie(String cookieName) {
        setCookie(Cookie.deleteCookie(cookieName));
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Compare timestamp in the If-Modified-Since request header, if present, to the given resource timestamp to see if
     * the resource is newer than any cached version. If the If-Modified-Since header is not set, the timestamp of the
     * cached version is assumed to be zero (the beginning of the Epoch), so this method will return true. If the passed
     * resource timestamp is zero, the resource timestamp is assumed to be invalid or unavailable, so we assume the
     * resource is not cached and return true, indicating that the cached version is out of date and should be served
     * (or served again).
     */
    public boolean cachedVersionIsOlderThan(long resourceTimestampEpochSeconds) {
        if (resourceTimestampEpochSeconds == 0) {
            // If the resource timestamp is zero, it's not valid, so don't assume this resource is cached,
            // by returning true indicating that the cached version is out of date. 
            return true;
        } else {
            // Otherwise return true if the resource timestamp is later than the cached version timestamp
            // (including when the cached version timestamp is zero.)
            // Note that the HTTP If-Modified-Since header only has single-second granularity.
            return resourceTimestampEpochSeconds > ifModifiedSinceEpochSecond;
        }
    }

    public long getReqReceivedTimeEpochMillis() {
        return reqReceivedTimeEpochMillis;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Add a flash message (a message that will be popped up at the top of a webpage the next time a page is served. */
    public void addFlashMessage(FlashType flashType, String strongText, String flashMessage) {
        String newFlashMsgCookieStr = FlashMessage.toCookieString(flashType, strongText, flashMessage);
        if (flashMessageCookieString == null) {
            flashMessageCookieString = newFlashMsgCookieStr;
        } else {
            flashMessageCookieString = flashMessageCookieString + "\n" + newFlashMsgCookieStr;
        }
    }

    /** Clear flash messages. */
    public void clearFlashMessages() {
        flashMessageCookieString = null;
        deleteCookie(Cookie.FLASH_COOKIE_NAME);
    }

    /** Get any flash message cookie value produced by calling addFlashMessage() during the handling of this request. */
    public ArrayList<FlashMessage> getFlashMessages() {
        if (flashMessageCookieString == null) {
            return null;
        } else {
            ArrayList<FlashMessage> flashMessages = new ArrayList<>();
            for (String flashMessageStr : StringUtils.split(flashMessageCookieString, "\n")) {
                FlashMessage flashMessage = FlashMessage.fromCookieString(flashMessageStr);
                if (flashMessage != null) {
                    // As long as message format was valid, add flash message
                    flashMessages.add(flashMessage);
                }
            }
            return flashMessages.isEmpty() ? null : flashMessages;
        }
    }

    /**
     * Transfer any pending flash messages back into the flash cookie so that they can be served in subsequent
     * responses.
     */
    public void saveFlashMessagesInCookie() {
        if (flashMessageCookieString != null) {
            setCookie(new Cookie(Cookie.FLASH_COOKIE_NAME, flashMessageCookieString, "/", 60, EncodingType.PLAIN));
            flashMessageCookieString = null;
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    public String getRequestor() {
        return requestor == null ? "" : requestor;
    }

    public void setRequestor(String requestor) {
        this.requestor = requestor;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public String getURI() {
        return path;
    }

    public void setURI(String uri) {
        this.path = uri;
    }

    public String getHost() {
        return host;
    }

    public String getAccept() {
        return accept;
    }

    public String getAcceptCharset() {
        return acceptCharset;
    }

    public String getAcceptLanguage() {
        return acceptLanguage;
    }

    public String getReferer() {
        return referer;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getOrigin() {
        return origin;
    }

    public String getXRequestedWith() {
        return xRequestedWith;
    }

    /**
     * True if the request URL contained the query parameter "?_getmodel=1", in which case return the DataModel backing
     * an HTML page, and not the rendered page itself.
     */
    public boolean isGetModelRequest() {
        return isGetModelRequest;
    }
}
