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
package gribbit.http.response;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.DATE;
import static io.netty.handler.codec.http.HttpHeaderNames.ETAG;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPIRES;
import static io.netty.handler.codec.http.HttpHeaderNames.LAST_MODIFIED;
import static io.netty.handler.codec.http.HttpHeaderNames.PRAGMA;
import static io.netty.handler.codec.http.HttpHeaderNames.SERVER;
import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static io.netty.handler.codec.http.HttpHeaderValues.GZIP;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import gribbit.http.logging.Log;
import gribbit.http.request.Request;
import gribbit.response.exception.RequestHandlingException;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.CacheExtension;
import gribbit.util.WebUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.zip.GZIPOutputStream;

/**
 * The superclass of all response types, containing fields that go into the header of the HTTP response regardless
 * of the response type.
 */
public abstract class Response {
    private Request request;
    private DefaultHttpResponse httpRes;
    protected final HttpResponseStatus status;
    protected String contentType;
    protected ArrayList<Cookie> cookies;
    protected long lastModifiedEpochSeconds, maxAgeSeconds;

    private ZonedDateTime timeNow = ZonedDateTime.now();
    private long timeNowEpochSeconds = timeNow.toEpochSecond();
    private static final long ONE_YEAR_IN_SECONDS = 31536000L;

    /** Generate a response with a specified status and content type. */
    public Response(Request request, HttpResponseStatus status, String contentType) {
        this.request = request;
        this.status = status;
        this.contentType = contentType;
        this.httpRes = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
    }

    /** Generate a response with an "OK" status and specified content type. */
    public Response(Request request, String contentType) {
        this(request, HttpResponseStatus.OK, contentType);
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    // -----------------------------------------------------------------------------------------------------

    /** Get the last modified timestamp for the content. 0 => unknown. */
    public long getLastModifiedEpochSeconds() {
        return lastModifiedEpochSeconds;
    }

    /** Set the last modified timestamp for the content. 0 => unknown. */
    public Response setLastModifiedEpochSeconds(long lastModifiedEpochSeconds) {
        this.lastModifiedEpochSeconds = lastModifiedEpochSeconds;
        return this;
    }

    /** Get the max age that this content can be cached for, or 0 for no caching. */
    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    /**
     * Schedule the content of this response to be hashed for caching purposes, or -1 to cache for a year (the
     * maximum), or 0 for no caching.
     */
    public Response setMaxAgeSeconds(long maxAgeSeconds) {
        // The caching spec only allows for resources to be cached for one year, or 31536000 seconds
        this.maxAgeSeconds = maxAgeSeconds < 0L ? ONE_YEAR_IN_SECONDS : Math
                .min(maxAgeSeconds, ONE_YEAR_IN_SECONDS);
        return this;
    }

    /**
     * Ensure the response is not cached. (This is the default, unless setMaxAgeSeconds() or
     * setLastModifiedEpochSeconds() has been called already.)
     */
    public void notCached() {
        setLastModifiedEpochSeconds(0);
        setMaxAgeSeconds(0);
    }

    /**
     * Ensure the response indefinitely. (Technically only caches for 1 year, which is the max allowed by the spec.)
     */
    public void cacheForever() {
        setLastModifiedEpochSeconds(timeNowEpochSeconds);
        setMaxAgeSeconds(-1);
    }

    // -----------------------------------------------------------------------------------------------------

    public Response addHeader(String key, String value) {
        httpRes.headers().set(key, value);
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Set a cookie in the response. (As per the standard, cookies with a shorter path will be masked in the browser
     * by cookies with a longer path on routes with the longer path as a prefix.)
     */
    public Response setCookie(Cookie cookie) {
        if (cookies == null) {
            cookies = new ArrayList<>();
        }
        cookies.add(cookie);
        return this;
    }

    /**
     * Set a cookie in the response. Note that cookies with a shorter path will be masked by cookies with a longer
     * path on routes with the longer path as a prefix.
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
     *            If true, cookie is inaccessible to Javascript.
     */
    public Response setCookie(String name, String value, String path, long maxAgeSeconds, boolean httpOnly) {
        DefaultCookie cookie = new DefaultCookie(name, value);
        if (path != null) {
            cookie.setPath(path);
        }
        cookie.setMaxAge(maxAgeSeconds < 0 ? ONE_YEAR_IN_SECONDS : maxAgeSeconds);
        cookie.setHttpOnly(httpOnly);
        if (request.isSecure()) {
            cookie.setSecure(true);
        }
        return this;
    }

    /**
     * Set an HTTP-only cookie in the response with the same path as the request, and a max age of 1 year.
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
     * Look through the request for cookies with the given name, and delete any matches in the response. (i.e. can
     * only delete cookies that are actually visible in the request.)
     */
    public Response deleteCookie(String cookieName) {
        for (Cookie cookie : request.getCookiesWithName(cookieName)) {
            setCookie(cookie.name(), /* value = */"", /* path = */cookie.path(), /* maxAgeSeconds = */0, //
                    /* httpOnly = */false);
        }
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    protected void setGeneralHeaders() {
        httpRes.headers().add(SERVER, GribbitServer.SERVER_IDENTIFIER);

        // Date header uses server time, and should use the same clock as Expires and Last-Modified
        httpRes.headers().set(DATE, timeNow.format(DateTimeFormatter.RFC_1123_DATE_TIME));

        // Add an Accept-Encoding: gzip header to the response to let the client know that in future
        // it can send compressed requests. (This header is probably ignored by most clients, because
        // on initial request they don't know yet if the server can accept compressed content, but
        // there must be clients out there that look for this header and compress content on the
        // second and subsequent requests? See http://stackoverflow.com/a/1450163/3950982 )
        httpRes.headers().add(ACCEPT_ENCODING, "gzip");
    }

    protected void setCacheHeaders() {
        boolean cached = false;
        if (this.getStatus() == HttpResponseStatus.OK) {
            // Set caching headers -- see:
            // http://www.mobify.com/blog/beginners-guide-to-http-cache-headers/
            // https://www.mnot.net/cache_docs/

            // Last-Modified is used to determine whether a Not Modified response should be returned on next request.
            // RouteHandlers that want to make use of this value should check the return value of
            // request.cachedVersionIsOlderThan(serverTimestamp), where serverTimestamp was the timestamp at which
            // the value previously changed, and if the return value is false, throw NotModifiedException.
            long lastModifiedEpochSeconds = this.getLastModifiedEpochSeconds();
            if (lastModifiedEpochSeconds > 0L) {
                httpRes.headers().set(LAST_MODIFIED,
                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochSeconds), ZoneId.of("UTC"))
                                .format(DateTimeFormatter.RFC_1123_DATE_TIME));

            } else if (request.isHashURL() && maxAgeSeconds > 0L) {
                // Only URLs that include a hash key (and whose response has a non-zero maxAgeSeconds) can be cached.
                // N.B. can set "Cache-Control: public", since the resource is hashed, so it can be served to other
                // clients that request it (they would have to know the hash URL to request it in the first place).
                httpRes.headers().set(CACHE_CONTROL, "public, max-age=" + maxAgeSeconds);
                httpRes.headers().set(EXPIRES, timeNow.plusSeconds(maxAgeSeconds)
                        .format(DateTimeFormatter.RFC_1123_DATE_TIME));
                httpRes.headers().set(ETAG, request.getURLHashKey());
                cached = true;
            }
        } else if (this.getStatus() == HttpResponseStatus.NOT_FOUND) {
            // Cache 404 messages for 5 minutes to reduce server load
            int cacheTime = 300;
            httpRes.headers().set(CACHE_CONTROL, "max-age=" + cacheTime);
            httpRes.headers().set(EXPIRES, timeNow.plusSeconds(cacheTime).format(DateTimeFormatter.RFC_1123_DATE_TIME));
            cached = true;
        }
        if (!cached) {
            // Disable caching for all URLs that do not contain a hash key. In particular, caching is
            // disabled for error messages, resources that don't have a last modified time, and responses
            // from RouteHandlers that do not set a maxAge (and are therefore not hashed).

            // This is the minimum necessary set of headers for disabling caching, see http://goo.gl/yXGd2x
            httpRes.headers().add(CACHE_CONTROL, "no-cache, no-store, must-revalidate"); // HTTP 1.1
            httpRes.headers().add(PRAGMA, "no-cache"); // HTTP 1.0
            httpRes.headers().add(EXPIRES, "0"); // Proxies
        }
    }

    protected void setCookieHeaders() {
        // Delete cookies in the response matching the requested name (on any cookie path visible to the request)
        HashSet<String> cookiesToDelete = this.getCookiesToDelete();
        if (cookiesToDelete != null) {
            for (String cookieName : cookiesToDelete) {
                // Log.fine("Cookie to delete for req " + reqURI + " : " + cookieName);
                ArrayList<Cookie> allCookiesWithName = request.getAllCookiesWithName(cookieName);
                if (allCookiesWithName != null) {
                    for (Cookie cookie : allCookiesWithName) {
                        // Delete all cookies with the requested name (there may be multiple cookies
                        // with this name but with different paths)
                        httpRes.headers().add(SET_COOKIE, Cookie.deleteCookie(cookie).toString());
                    }
                }
            }
        }

        // Set cookies in the response
        ArrayList<Cookie> cookiesToSet = this.getCookiesToSet();
        if (cookiesToSet != null) {
            for (Cookie cookie : cookiesToSet) {
                if (cookiesToDelete != null && cookiesToDelete.contains(cookie.getName())) {
                    Log.warning("Tried to delete and set the cookie \"" + cookie.getName()
                            + "\" in the same response -- ignoring the set request");
                } else {
                    httpRes.headers().add(SET_COOKIE, cookie.toString());
                }
            }
        }
    }

    /** Send an HTTP response. */
    public void send(Request request, ChannelHandlerContext ctx) throws RequestHandlingException {
        //        // Add flash messages to response template, if any
        //        if (this instanceof HTMLPageResponse) {
        //            // Only complete HTML pages have flash messages
        //            ArrayList<FlashMessage> flashMessages = request.getFlashMessages();
        //            if (flashMessages != null) {
        //                // Render pending flash messages into the HTML page
        //                ((HTMLPageResponse) this).setFlashMessages(flashMessages);
        //                // Clear the flash message cookie
        //                this.deleteCookie(Cookie.FLASH_COOKIE_NAME);
        //            }
        //        } else {
        //            // Store any un-displayed flash messages back in the cookie. This extends the time the flash message
        //            // cookie lasts for, so that the messages should show up on the next full-page response.
        //            ArrayList<FlashMessage> flashMessages = request.getFlashMessages();
        //            if (flashMessages != null) {
        //                this.setCookie(new Cookie(Cookie.FLASH_COOKIE_NAME, "/",
        //                        FlashMessage.toCookieString(flashMessages), 60));
        //            }
        //        }

        // If the response needs hashing, and the response does not have an error status, then schedule the
        // content of the response for hashing, and store a mapping from the original request URI to the
        // hash URI so that future HTML responses that have src/href attributes that contain this request
        // URI will replace this request URI with the hash URI instead. This will mean the client will
        // fetch that hash URI only once until it expires in the cache, so that on subsequent requests,
        // the linked resource won't even be requested from the server.
        long maxAgeSeconds = this.getMaxAgeSeconds();
        if (maxAgeSeconds > 0L && this.getStatus() == HttpResponseStatus.OK) {
            CacheExtension.updateHashURI(request.getURLPathUnhashed(), content, //
                    /* lastModifiedEpochSeconds = */timeNowEpochSeconds);
        }

        // Gzip content if the configuration property is set to allow gzip, and the client supports gzip encoding,
        // and the content size is larger than 1kb, and the content type is compressible 
        ByteBuf contentToUse = content;
        boolean isGzipped = false;
        if (GribbitProperties.CONTENT_GZIP && //
                request.acceptEncodingGzip() //
                && content.readableBytes() > 1024 //
                && WebUtils.isCompressibleContentType(contentType)) {
            // TODO: compare speed to using JZlib.GZIPOutputStream
            byte[] contentBytes = content.array();
            int contentLen = content.readableBytes();
            ByteBuf gzippedContent = Unpooled.buffer(contentLen);
            try {
                GZIPOutputStream gzipStream = new GZIPOutputStream(new ByteBufOutputStream(gzippedContent));
                gzipStream.write(contentBytes, 0, contentLen);
                gzipStream.close();
            } catch (IOException e) {
                // Should not happen
                Log.exception("Could not gzip content", e);
                gzippedContent = Unpooled.EMPTY_BUFFER;
            }
            contentToUse = gzippedContent;
            isGzipped = true;
            // Release the content ByteBuf after last usage
            content.release();
            content = null;
        }

        // Create a FullHttpResponse object that wraps the response status and content
        DefaultFullHttpResponse httpRes = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, this.getStatus(), //
                contentToUse);
        HttpHeaders headers = httpRes.headers();
        httpRes.headers().set(CONTENT_LENGTH, Integer.toString(contentToUse.readableBytes()));
        if (isGzipped) {
            httpRes.headers().set(CONTENT_ENCODING, GZIP);
        }
        httpRes.headers().set(CONTENT_TYPE, contentType);

        // Add any custom headers from the Response object
        if (customHeaders != null) {
            for (Entry<String, String> header : customhttpRes.headers().entrySet()) {
                httpRes.headers().add(header.getKey(), header.getValue());
            }
        }

        // Close the connection after serving the response if the response status is anything other than OK
        boolean keepAlive = request.isKeepAlive() && (this.getStatus() == HttpResponseStatus.OK
        // TODO: In addition to redirects (HttpResponseStatus.FOUND), should the channel be kept alive for other
        // result codes, e.g. NOT_MODIFIED?
                || this.getStatus() != HttpResponseStatus.FOUND);
        if (keepAlive) {
            httpRes.headers().add(CONNECTION, KEEP_ALIVE);
        }

        if (request.isHEADRequest()) {
            // Don't return a body for HEAD requests (but still return the content length).
            httpRes.content().clear();
        }

        if (ctx.channel().isOpen()) {
            // Write the ByteBuffer returned by httpRes.content() back into the pipeline
            // See http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#4.0
            ChannelFuture future = ctx.writeAndFlush(httpRes);

            // Close the connection after the write operation is done if necessary.
            if (!keepAlive) {
                future.addListener(ChannelFutureListener.CLOSE);
            }

        } else {
            // Client already closed the connection, nothing can be sent
            // Log.info("Channel closed by client before response sent");
        }

        // Log the request and response
        //        boolean isFavicoReq = reqURLUnhashed.equals("favicon.ico") || reqURLUnhashed.equals("favicon.png")
        //                || reqURLUnhashed.endsWith("/favicon.ico");
        //        if (status == HttpResponseStatus.OK //
        //                || status == HttpResponseStatus.NOT_MODIFIED //
        //                || status == HttpResponseStatus.FOUND //
        //                || (status == HttpResponseStatus.NOT_FOUND && !isFavicoReq)) {
        Log.fine(request.getRequestor() + "\t" + request.getMethod() + "\t" + request.getURLPathUnhashed() + "\t"
                + this.getStatus() + "\t" + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis())
                + " msec");
        //        }
    }
}
