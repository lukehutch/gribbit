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
package gribbit.http.request;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_CHARSET;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_LANGUAGE;
import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpHeaderNames.IF_MODIFIED_SINCE;
import static io.netty.handler.codec.http.HttpHeaderNames.ORIGIN;
import static io.netty.handler.codec.http.HttpHeaderNames.REFERER;
import static io.netty.handler.codec.http.HttpHeaderNames.USER_AGENT;
import gribbit.auth.User;
import gribbit.http.request.route.RequestURL;
import gribbit.http.response.exception.BadRequestException;
import gribbit.http.response.exception.ResponseException;
import gribbit.response.flashmsg.FlashMessage;
import gribbit.server.config.GribbitProperties;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.SslHandler;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class Request {
    private String rawURL;
    private String normalizedURL;
    private HttpRequest httpRequest;
    private String streamId;

    private String httpVersion;
    private long reqReceivedTimeEpochMillis;
    private HttpMethod method;
    private boolean isSecure;
    private boolean isHEADRequest;
    private boolean isKeepAlive;
    private String requestor;
    private String host;

    //    private Route authorizedRoute;

    //    private String urlHashKey;

    private CharSequence accept;
    private CharSequence acceptCharset;
    private CharSequence acceptLanguage;
    private boolean acceptEncodingGzip;
    private CharSequence referer;
    private CharSequence userAgent;

    private CharSequence ifModifiedSince;
    private long ifModifiedSinceEpochSecond;

    private HashMap<String, ArrayList<Cookie>> cookieNameToCookies;
    private HashMap<String, String> postParamToValue;
    private HashMap<String, FileUpload> postParamToFileUpload;
    private Map<String, List<String>> queryParamToVals;

    /**
     * The logged-in user, if the user is logged in (has a valid session cookie) and this request is for a route
     * that requires authentication. Note that even if this field is set, the user still may be denied access to one
     * or more routes, depending on route authentication requirements.
     */
    private User user;

    /**
     * Header for CORS, and for protecting against CSWSH. See:
     * 
     * http://en.wikipedia.org/wiki/Cross-origin_resource_sharing
     * 
     * http://www.christian-schneider.net/CrossSiteWebSocketHijacking.html
     * 
     * FIXME: make use of this field when setting up websockets
     **/
    private CharSequence origin;

    /**
     * Header for CSRF protection of AJAX requests (regular GETs and POSTs don't allow for header manipulation.)
     * 
     * See https://nealpoole.com/blog/2010/11/preventing-csrf-attacks-with-ajax-and-http-headers/
     */
    private CharSequence xRequestedWith;

    /**
     * If set to true by appending "?_getmodel=1" to the URL, then return the data model backing an HTML page, not
     * the rendered page itself.
     */
    private boolean isGetModelRequest;

    /** Flash messages. */
    private ArrayList<FlashMessage> flashMessages;

    // -----------------------------------------------------------------------------------------------------

    public Request(ChannelHandlerContext ctx, HttpRequest httpReq) throws ResponseException {
        this.reqReceivedTimeEpochMillis = System.currentTimeMillis();

        this.httpRequest = httpReq;
        HttpHeaders headers = httpReq.headers();

        // Netty changes the URI of the request to "/bad-request" if the HTTP request was malformed
        this.rawURL = httpReq.uri();
        if (rawURL.equals("/bad-request")) {
            throw new BadRequestException();
        } else if (rawURL.isEmpty()) {
            rawURL = "/";
        }

        // Decode the URL
        RequestURL requestURL = new RequestURL(rawURL);
        this.normalizedURL = requestURL.getNormalizedPath();
        this.queryParamToVals = requestURL.getQueryParams();

        // TODO: figure out how to detect HTTP/2 connections
        this.httpVersion = httpReq.protocolVersion().toString();

        // Get HTTP2 stream ID
        this.streamId = headers.getAsString(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());

        this.isSecure = ctx.pipeline().get(SslHandler.class) != null; // TODO: is this correct for HTTP2?

        // Decode cookies
        try {
            for (CharSequence cookieHeader : headers.getAll(COOKIE)) {
                for (Cookie cookie : ServerCookieDecoder.STRICT.decode(cookieHeader.toString())) {
                    // Log.fine("Cookie in request: " + nettyCookie);
                    if (cookieNameToCookies == null) {
                        cookieNameToCookies = new HashMap<>();
                    }
                    String cookieName = cookie.name();

                    // Multiple cookies may be present in the request with the same name but with different paths
                    ArrayList<Cookie> cookiesWithThisName = cookieNameToCookies.get(cookieName);
                    if (cookiesWithThisName == null) {
                        cookieNameToCookies.put(cookieName, cookiesWithThisName = new ArrayList<>());
                    }
                    cookiesWithThisName.add(cookie);
                }
            }
        } catch (IllegalArgumentException e) {
            // Malformed cookies cause ServerCookieDecoder to throw IllegalArgumentException
            // Log.info("Malformed cookie in request");
            throw new BadRequestException();
        }
        // Sort cookies into decreasing order of path length, in case client doesn't conform to RFC6295,
        // delivering the cookies in this order itself. This allows us to get the most likely single
        // cookie for a given cookie name by reading the first cookie in a list for a given name.
        if (cookieNameToCookies != null) {
            for (Entry<String, ArrayList<Cookie>> ent : cookieNameToCookies.entrySet()) {
                Collections.sort(ent.getValue(), COOKIE_COMPARATOR);
            }
        }

        this.method = httpReq.method();

        // Force the GET method if HEAD is requested
        this.isHEADRequest = this.method == HttpMethod.HEAD;
        if (this.isHEADRequest) {
            this.method = HttpMethod.GET;
        }

        this.isKeepAlive = HttpUtil.isKeepAlive(httpReq) && httpReq.protocolVersion().equals(HttpVersion.HTTP_1_0);

        CharSequence host = headers.get(HOST);
        this.host = host == null ? null : host.toString();

        this.xRequestedWith = headers.get("X-Requested-With");
        this.accept = headers.get(ACCEPT);
        this.acceptCharset = headers.get(ACCEPT_CHARSET);
        this.acceptLanguage = headers.get(ACCEPT_LANGUAGE);
        this.origin = headers.get(ORIGIN);
        this.referer = headers.get(REFERER);
        this.userAgent = headers.get(USER_AGENT);

        InetSocketAddress requestorSocketAddr = (InetSocketAddress) ctx.channel().remoteAddress();
        if (requestorSocketAddr != null) {
            InetAddress address = requestorSocketAddr.getAddress();
            if (address != null) {
                this.requestor = address.getHostAddress();
            }
        }

        CharSequence acceptEncoding = headers.get(ACCEPT_ENCODING);
        this.acceptEncodingGzip = acceptEncoding != null
                && acceptEncoding.toString().toLowerCase().contains("gzip");

        this.ifModifiedSince = headers.get(IF_MODIFIED_SINCE);
        if (this.ifModifiedSince != null && this.ifModifiedSince.length() > 0) {
            this.ifModifiedSinceEpochSecond = ZonedDateTime.parse(this.ifModifiedSince,
                    DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
        }

        //        // If this is a hash URL, look up original URL whose served resource was hashed to give this hash URL.
        //        // We only need to serve the resource at a hash URL once per resource per client, since resources served
        //        // from hash URLs are indefinitely cached in the browser.
        //        // TODO: Move cache-busting out of http package
        //        this.urlHashKey = CacheExtension.getHashKey(this.urlPath);
        //        this.urlPathUnhashed = this.urlHashKey != null ? CacheExtension.getOrigURL(this.urlPath) : this.urlPath;

        //        // Get flash messages from cookie, if any
        //        this.flashMessages = FlashMessage.fromCookieString(getCookieValue(Cookie.FLASH_COOKIE_NAME));
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Sort cookies into decreasing order of path length, breaking ties by sorting into decreasing chronological
     * order of creation time (similar to order recommended by RFC 6265, except that the tiebreaking order is
     * reversed, since newer cookies should be preferred over older cookies). This handles path and timestamp
     * masking in situations where some older clients take the first cookie with a matching name.
     * 
     * See also: http://stackoverflow.com/questions/4056306/how-to-handle-multiple-cookies-with-the-same-name
     */
    private static final Comparator<Cookie> COOKIE_COMPARATOR = new Comparator<Cookie>() {
        @Override
        public int compare(Cookie c1, Cookie c2) {
            String path1 = c1.path();
            String path2 = c2.path();
            // Cookies with unspecified path default to the path of the request. We don't
            // know the request path here, but we assume that the length of an unspecified
            // path is longer than any specified path, because setting cookies with a path
            // longer than the request path is of limited use.
            int len1 = path1 == null ? Integer.MAX_VALUE : path1.length();
            int len2 = path2 == null ? Integer.MAX_VALUE : path2.length();
            int diff = len2 - len1;
            if (diff != 0) {
                return diff;
            }
            // Reverse order in cases where cookies have same path length 
            return 1;
        }
    };

    // -----------------------------------------------------------------------------------------------------------------

    //    /**
    //     * Looks up the route for the request, and ensures the user is authorized to access the route, otherwise a
    //     * RequestHandlingException is thrown.
    //     * 
    //     * This is performed in a separate step from the constructor so that a non-null Request object can still exist
    //     * if any of the checks here fail and a RequestHandlingException is thrown, so that the constructor to the
    //     * exception can accept a non-null Request object with the details of the request.
    //     */
    //    public void matchRoute() throws ResponseException {
    //        // Check for _getmodel=1 query parameter
    //        this.isGetModelRequest = "1".equals(this.getQueryParam("_getmodel"));
    //        if (this.isGetModelRequest) {
    //            this.queryParamToVals.remove("_getmodel");
    //        }
    //
    //        // ------------------------------------------------------------------------------
    //        // Find the Route corresponding to the request URI, and authenticate user
    //        // ------------------------------------------------------------------------------
    //
    //        // Call route handlers until one is able to handle the route,
    //        // or until we run out of handlers
    //        ArrayList<Route> allRoutes = GribbitServer.siteResources.getAllRoutes();
    //        for (int i = 0, n = allRoutes.size(); i < n; i++) {
    //            Route route = allRoutes.get(i);
    //            // If the request URI matches this route path
    //            if (route.matches(this.urlPathUnhashed)) {
    //                if (!(this.method == HttpMethod.GET || this.method == HttpMethod.POST)) {
    //                    // Only GET and POST are supported
    //                    throw new MethodNotAllowedException();
    //
    //                } else if ((this.method == HttpMethod.GET && !route.hasGetMethod())
    //                        || (this.method == HttpMethod.POST && !route.hasPostMethod())) {
    //                    // Tried to call an HTTP method that is not defined for this route
    //                    throw new MethodNotAllowedException();
    //
    //                } else {
    //                    // Call request.lookupUser() to check the session cookies to see if the user is logged in, 
    //                    // if the route requires users to be logged in. If auth is required, see if the user can
    //                    // access the requested route.
    //                    // Throws a RequestHandlingException if not authorized.
    //                    route.throwExceptionIfNotAuthorized(this);
    //
    //                    // If we reach here, either authorization is not required for the route, or the user is
    //                    // logged in and they passed all auth tests. OK to handle the request with this route.
    //                    this.authorizedRoute = route;
    //                }
    //
    //                // URI matches, so don't need to search further URIs
    //                break;
    //            }
    //        }
    //
    //        // ------------------------------------------------------------------------------
    //        // Try to match static resource requests if no Route matched
    //        // ------------------------------------------------------------------------------
    //
    //        if (this.authorizedRoute == null) {
    //            if (this.method == HttpMethod.GET) {
    //                // Set the static file to be served, if one matches the requested URL
    //                this.staticResourceFile = GribbitServer.siteResources.getStaticResource(this.urlPathUnhashed);
    //                if (this.staticResourceFile == null) {
    //                    // Neither a route handler nor a static resource matched the request URI. Throw 404 Not Found.
    //                    throw new NotFoundException(this);
    //                }
    //            } else {
    //                // Tried to post to a non-existent Route
    //                throw new NotFoundException(this);
    //            }
    //        }
    //    }

    //    /**
    //     * Call the GET or POST handler for the Route corresponding to the requested URL path.
    //     */
    //    public GeneralResponse callRouteHandler() throws ResponseException {
    //        if (authorizedRoute == null) {
    //            // Shouldn't happen, the caller should only call this method if the request URL matched an authorized route
    //            throw new InternalServerErrorException("Unexpected: authorizedRoute is null");
    //        }
    //        return authorizedRoute.callHandler(this);
    //    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Get the URL, normalized to handle ".." and ".". */
    public String getURL() {
        return normalizedURL;
    }

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

    /**
     * Get a collection of lists of cookies -- each list in the collection consists of one or more cookies, where
     * all cookies in a list have the same name but different paths. Cookie lists are ordered into decreasing order
     * of path length to conform to RFC6295.
     */
    public Collection<ArrayList<Cookie>> getCookies() {
        if (cookieNameToCookies == null) {
            return null;
        } else {
            return cookieNameToCookies.values();
        }
    }

    /**
     * Get all cookies with the given name, or null if there are no cookies with this name. Cookies are ordered into
     * decreasing order of path length to conform to RFC6295.
     */
    public ArrayList<Cookie> getCookies(String cookieName) {
        if (cookieNameToCookies == null) {
            return null;
        } else {
            return cookieNameToCookies.get(cookieName);
        }
    }

    /**
     * Get a cookie by name, or null if there are no cookies with this name. If there is more than one cookie with
     * the same name, returns the cookie without a specified path, or if all cookies have paths, returns the cookie
     * with the longest path.
     */
    public Cookie getCookie(String cookieName) {
        ArrayList<Cookie> cookieList = getCookies(cookieName);
        if (cookieList == null) {
            return null;
        } else {
            return cookieList.get(0);
        }
    }

    /**
     * Get the value of a named cookie, or null if there are no cookies with this value. If there is more than one
     * cookie with the same name, returns the value of the cookie without a specified path, or if all cookies have
     * paths, returns the value of the cookie with the longest path.
     */
    public String getCookieValue(String cookieName) {
        Cookie cookie = getCookie(cookieName);
        if (cookie == null) {
            return null;
        } else {
            return cookie.value();
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Compare timestamp in the If-Modified-Since request header, if present, to the given resource timestamp to see
     * if the resource is newer than any cached version, returning true if so.
     * 
     * If the If-Modified-Since header is not set, or the provided timestamp is zero, this method will return true,
     * indicating that the cached version is out of date and should be served (or served again).
     */
    public boolean contentModified(long contentLastModifiedEpochSeconds) {
        if (contentLastModifiedEpochSeconds == 0 || ifModifiedSinceEpochSecond == 0) {
            return true;
        } else {
            // Otherwise return true if the resource timestamp is later than the cached version timestamp
            // (including when the cached version timestamp is zero.)
            // Note that the HTTP If-Modified-Since header only has single-second granularity.
            return contentLastModifiedEpochSeconds > ifModifiedSinceEpochSecond;
        }
    }

    /** Return the If-Modified-Since header value from the request, or null if none. */
    public CharSequence getIfModifiedSince() {
        return ifModifiedSince;
    }

    public long getReqReceivedTimeEpochMillis() {
        return reqReceivedTimeEpochMillis;
    }

    // -----------------------------------------------------------------------------------------------------------------

    public String getStreamId() {
        return streamId;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Add a flash message (a message that will be popped up at the top of a webpage the next time a page is served.
     */
    public void addFlashMessage(FlashMessage flashMessage) {
        if (flashMessages == null) {
            flashMessages = new ArrayList<>();
        }
        flashMessages.add(flashMessage);
    }

    /** Clear the flash messages. */
    public void clearFlashMessage() {
        flashMessages = null;
    }

    public ArrayList<FlashMessage> getFlashMessages() {
        return flashMessages;
    }

    // -----------------------------------------------------------------------------------------------------------------

    //    /**
    //     * Returns the request URL. May include a hash code of the form /_/HASHCODE/path . These hash codes are used for
    //     * cache extension, to allow indefinite caching of hashed resources in the browser. The original resource can be
    //     * fetched without caching using the path returned by getURLPathUnhashed().
    //     */
    //    public String getURLPathPossiblyHashed() {
    //        return urlPath.toString();
    //    }
    //
    //    /** Returns /path if this request was for a hash URL of the form /_/HASHCODE/path */
    //    public String getURLPathUnhashed() {
    //        return urlPathUnhashed;
    //    }
    //
    //    /** Returns HASHCODE if this request was for a hash URL of the form /_/HASHCODE/path */
    //    public String getURLHashKey() {
    //        return urlHashKey;
    //    }
    //
    //    /** Returns true if this request was for a hash URL of the form /_/HASHCODE/path */
    //    public boolean isHashURL() {
    //        return urlHashKey != null;
    //    }

    public String getRequestor() {
        return requestor == null ? "" : requestor;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public Object getRawURL() {
        return rawURL;
    }

    public boolean isHEADRequest() {
        return isHEADRequest;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public String getHttpVersion() {
        return httpVersion;
    }

    public CharSequence getHost() {
        return host;
    }

    public CharSequence getAccept() {
        return accept;
    }

    public CharSequence getAcceptCharset() {
        return acceptCharset;
    }

    public CharSequence getAcceptLanguage() {
        return acceptLanguage;
    }

    public boolean acceptEncodingGzip() {
        return acceptEncodingGzip;
    }

    public CharSequence getReferer() {
        return referer;
    }

    public CharSequence getUserAgent() {
        return userAgent;
    }

    public CharSequence getOrigin() {
        return origin;
    }

    public CharSequence getXRequestedWith() {
        return xRequestedWith;
    }

    public boolean isSecure() {
        return isSecure;
    }

    /**
     * True if the request URL contained the query parameter "?_getmodel=1", in which case return the DataModel
     * backing an HTML page, and not the rendered page itself.
     */
    public boolean isGetModelRequest() {
        return GribbitProperties.ALLOW_GET_MODEL && isGetModelRequest;
    }

    public boolean isKeepAlive() {
        return isKeepAlive;
    }

    /**
     * Set the user field based on the session cookie in the request. Performs a database lookup, so this is
     * deferred so that routes that do not require authorization do not perform this lookup. Returns the User object
     * for the user, if they are logged in. Caches the result across calls, so only performs the database lookup on
     * the first call.
     */
    public User lookupUser() {
        if (this.user == null) {
            this.user = User.getLoggedInUser(this);
        }
        return this.user;
    }

    public HttpRequest getHttpRequest() {
        return httpRequest;
    }
}
