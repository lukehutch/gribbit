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
import gribbit.auth.Cookie;
import gribbit.auth.User;
import gribbit.http.request.handler.WebSocketHandler;
import gribbit.http.response.Response;
import gribbit.response.exception.BadRequestException;
import gribbit.response.exception.InternalServerErrorException;
import gribbit.response.exception.MethodNotAllowedException;
import gribbit.response.exception.NotFoundException;
import gribbit.response.exception.RequestHandlingException;
import gribbit.response.exception.UnauthorizedException;
import gribbit.response.flashmsg.FlashMessage;
import gribbit.route.Route;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.CacheExtension;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.FileUpload;

import java.io.File;
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
import java.util.Set;

public class Request {
    private ChannelHandlerContext ctx;
    private HttpRequest httpReq;

    private long reqReceivedTimeEpochMillis;
    private HttpMethod method;
    private boolean isHEADRequest;
    private boolean isKeepAlive;
    private String requestor;
    private String host;
    private String urlPath;
    private String urlHashKey;
    private String urlPathUnhashed;
    private File staticResourceFile;
    private Route authorizedRoute;

    private CharSequence accept;
    private CharSequence acceptCharset;
    private CharSequence acceptLanguage;
    private boolean acceptEncodingGzip;
    private CharSequence referer;
    private CharSequence userAgent;
    private long ifModifiedSinceEpochSecond = 0;

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

    /** The websocket handler. Appending "?_ws=1" to the URL to upgrade the connection to a websocket. */
    private WebSocketHandler webSocketHandler;

    /** Flash messages. */
    private ArrayList<FlashMessage> flashMessages;

    // -----------------------------------------------------------------------------------------------------

    public Request(ChannelHandlerContext ctx, HttpRequest httpReq) {
        this.ctx = ctx;
        this.httpReq = httpReq;
        this.reqReceivedTimeEpochMillis = System.currentTimeMillis();

        // Decode the path.
        QueryStringDecoder decoder = new QueryStringDecoder(httpReq.uri());
        this.urlPath = decoder.path();
        this.queryParamToVals = decoder.parameters();

        HttpHeaders headers = httpReq.headers();

        // Parse and decode/decrypt cookies
        this.cookieNameToCookies = Cookie.decodeCookieHeaders(headers.getAll(COOKIE));

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

        CharSequence cacheDateHeader = headers.get(IF_MODIFIED_SINCE);
        if (cacheDateHeader != null && cacheDateHeader.length() > 0) {
            this.ifModifiedSinceEpochSecond = ZonedDateTime.parse(cacheDateHeader,
                    DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
        }

        // If this is a hash URL, look up original URL whose served resource was hashed to give this hash URL.
        // We only need to serve the resource at a hash URL once per resource per client, since resources served
        // from hash URLs are indefinitely cached in the browser.
        this.urlHashKey = CacheExtension.getHashKey(this.urlPath);
        this.urlPathUnhashed = this.urlHashKey != null ? CacheExtension.getOrigURL(this.urlPath) : this.urlPath;

        // Get flash messages from cookie, if any
        this.flashMessages = FlashMessage.fromCookieString(getCookieValue(Cookie.FLASH_COOKIE_NAME));
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Looks up the route for the request, and ensures the user is authorized to access the route, otherwise a
     * RequestHandlingException is thrown.
     * 
     * This is performed in a separate step from the constructor so that a non-null Request object can still exist
     * if any of the checks here fail and a RequestHandlingException is thrown, so that the constructor to the
     * exception can accept a non-null Request object with the details of the request.
     */
    public void matchRoute() throws RequestHandlingException {

        // Netty changes the URI of the request to "/bad-request" if the HTTP request was malformed
        if (this.urlPath.equals("/bad-request")) {
            throw new BadRequestException(this);
        }

        // Check for _getmodel=1 query parameter
        this.isGetModelRequest = "1".equals(this.getQueryParam("_getmodel"));
        if (this.isGetModelRequest) {
            this.queryParamToVals.remove("_getmodel");
        }

        // ------------------------------------------------------------------------------
        // Find the Route corresponding to the request URI, and authenticate user
        // ------------------------------------------------------------------------------

        // Call route handlers until one is able to handle the route,
        // or until we run out of handlers
        ArrayList<Route> allRoutes = GribbitServer.siteResources.getAllRoutes();
        for (int i = 0, n = allRoutes.size(); i < n; i++) {
            Route route = allRoutes.get(i);
            // If the request URI matches this route path
            if (route.matches(this.urlPathUnhashed)) {
                if (!(this.method == HttpMethod.GET || this.method == HttpMethod.POST)) {
                    // Only GET and POST are supported
                    throw new MethodNotAllowedException();

                } else if ((this.method == HttpMethod.GET && !route.hasGetMethod())
                        || (this.method == HttpMethod.POST && !route.hasPostMethod())) {
                    // Tried to call an HTTP method that is not defined for this route
                    throw new MethodNotAllowedException();

                } else {
                    // Call request.lookupUser() to check the session cookies to see if the user is logged in, 
                    // if the route requires users to be logged in. If auth is required, see if the user can
                    // access the requested route.
                    // Throws a RequestHandlingException if not authorized.
                    route.throwExceptionIfNotAuthorized(this);

                    // If we reach here, either authorization is not required for the route, or the user is
                    // logged in and they passed all auth tests. OK to handle the request with this route.
                    this.authorizedRoute = route;
                }

                // URI matches, so don't need to search further URIs
                break;
            }
        }

        // ------------------------------------------------------------------------------
        // Handle websocket upgrade requests
        // ------------------------------------------------------------------------------

        boolean isWebSocketUpgradeRequest = "1".equals(this.getQueryParam("_ws"));
        if (isWebSocketUpgradeRequest) {
            if (!GribbitProperties.ALLOW_WEBSOCKETS) {
                throw new BadRequestException();
            }
            if (this.authorizedRoute == null) {
                throw new UnauthorizedException(this);
            }
            if (this.method != HttpMethod.GET) {
                throw new MethodNotAllowedException();
            }
            // Create a new WebSocketHandler, and upgrade the connection
            this.webSocketHandler = new WebSocketHandler(ctx, httpReq, this.origin, getQueryParam("_csrf"),
                    lookupUser(), this.authorizedRoute);
            // Remove the _ws query parameter
            this.queryParamToVals.remove("_ws");
        }

        // ------------------------------------------------------------------------------
        // Try to match static resource requests if no Route matched
        // ------------------------------------------------------------------------------

        if (this.authorizedRoute == null) {
            if (this.method == HttpMethod.GET) {
                // Set the static file to be served, if one matches the requested URL
                this.staticResourceFile = GribbitServer.siteResources.getStaticResource(this.urlPathUnhashed);
                if (this.staticResourceFile == null) {
                    // Neither a route handler nor a static resource matched the request URI. Throw 404 Not Found.
                    throw new NotFoundException(this);
                }
            } else {
                // Tried to post to a non-existent Route
                throw new NotFoundException(this);
            }
        }
    }

    /**
     * If non-null, the request URL contained the query parameter "?_ws=1", which upgrades the connection to a
     * websocket.
     */
    public WebSocketHandler getWebSocketHandler() {
        return webSocketHandler;
    }

    /** If non-null, the request was for a static file. */
    public File getStaticResourceFile() {
        return staticResourceFile;
    }

    /**
     * Call the GET or POST handler for the Route corresponding to the requested URL path.
     */
    public Response callRouteHandler() throws RequestHandlingException {
        if (authorizedRoute == null) {
            // Shouldn't happen, the caller should only call this method if the request URL matched an authorized route
            throw new InternalServerErrorException("Unexpected: authorizedRoute is null");
        }
        return authorizedRoute.callHandler(this);
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

    /** Used for sorting cookies into decreasing order of path length */
    private static final Comparator<Cookie> cookiePathLengthComparator = new Comparator<Cookie>() {
        @Override
        public int compare(Cookie o1, Cookie o2) {
            return o2.getPath().length() - o1.getPath().length();
        }
    };

    /**
     * Get a collection of lists of cookies -- each list in the collection consists of one or more cookies, where
     * all cookies in a list have the same name but different paths. (It is possible to receive multiple cookies
     * with the same name in a request.) Cookie lists are ordered into decreasing order of path length to conform to
     * a "SHOULD" clause in the HTTP header spec.
     * 
     * See http://stackoverflow.com/questions/4056306/how-to-handle-multiple-cookies-with-the-same-name
     */
    public Collection<ArrayList<Cookie>> getAllCookies() {
        if (cookieNameToCookies == null) {
            return null;
        } else {
            Collection<ArrayList<Cookie>> cookieLists = cookieNameToCookies.values();
            for (ArrayList<Cookie> cookieList : cookieLists) {
                Collections.sort(cookieList, cookiePathLengthComparator);
            }
            return cookieLists;
        }
    }

    /**
     * Get all cookies with the given name, or null if there are no cookies with this name. (There may be multiple
     * cookies with the same name but with different paths.) The returned list is ordered into decreasing order of
     * path length to conform to a "SHOULD" clause in the HTTP header spec.
     * 
     * See http://stackoverflow.com/questions/4056306/how-to-handle-multiple-cookies-with-the-same-name
     */
    public ArrayList<Cookie> getAllCookiesWithName(String cookieName) {
        if (cookieNameToCookies == null) {
            return null;
        } else {
            ArrayList<Cookie> cookieList = cookieNameToCookies.get(cookieName);
            if (cookieList != null) {
                Collections.sort(cookieList, cookiePathLengthComparator);
            }
            return cookieList;
        }
    }

    /**
     * Get a cookie by name, or null if there are no cookies with this name. If there is more than one cookie with
     * the same name, return the cookie with the longest path.
     * 
     * See http://stackoverflow.com/questions/4056306/how-to-handle-multiple-cookies-with-the-same-name
     */
    public Cookie getCookie(String cookieName) {
        ArrayList<Cookie> cookieList = getAllCookiesWithName(cookieName);
        if (cookieList == null) {
            return null;
        } else {
            return cookieList.get(0);
        }
    }

    /**
     * Get the string value of a named cookie, or null if there are no cookies with this name. If there is more than
     * one cookie with the same name, return the value of the one with the longest path.
     * 
     * See http://stackoverflow.com/questions/4056306/how-to-handle-multiple-cookies-with-the-same-name
     */
    public String getCookieValue(String cookieName) {
        Cookie cookie = getCookie(cookieName);
        if (cookie == null) {
            return null;
        } else {
            return cookie.getValue();
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

    public long getReqReceivedTimeEpochMillis() {
        return reqReceivedTimeEpochMillis;
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

    /**
     * Returns the request URL. May include a hash code of the form /_/HASHCODE/path . These hash codes are used for
     * cache extension, to allow indefinite caching of hashed resources in the browser. The original resource can be
     * fetched without caching using the path returned by getURLPathUnhashed().
     */
    public String getURLPathPossiblyHashed() {
        return urlPath.toString();
    }

    /** Returns /path if this request was for a hash URL of the form /_/HASHCODE/path */
    public String getURLPathUnhashed() {
        return urlPathUnhashed;
    }

    /** Returns HASHCODE if this request was for a hash URL of the form /_/HASHCODE/path */
    public String getURLHashKey() {
        return urlHashKey;
    }

    /** Returns true if this request was for a hash URL of the form /_/HASHCODE/path */
    public boolean isHashURL() {
        return urlHashKey != null;
    }

    public String getRequestor() {
        return requestor == null ? "" : requestor;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public boolean isHEADRequest() {
        return isHEADRequest;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public void setURI(String uri) {
        this.urlPath = uri;
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
}
