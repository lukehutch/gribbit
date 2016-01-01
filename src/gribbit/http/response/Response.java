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
import static io.netty.handler.codec.http.HttpHeaderNames.EXPIRES;
import static io.netty.handler.codec.http.HttpHeaderNames.LAST_MODIFIED;
import static io.netty.handler.codec.http.HttpHeaderNames.PRAGMA;
import static io.netty.handler.codec.http.HttpHeaderNames.SERVER;
import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import static io.netty.handler.codec.http.HttpHeaderValues.GZIP;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import gribbit.http.request.Request;
import gribbit.http.request.decoder.HttpRequestDecoder;
import gribbit.http.response.exception.InternalServerErrorException;
import gribbit.http.response.exception.ResponseException;
import gribbit.http.utils.ContentTypeUtils;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http2.HttpConversionUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * The superclass of all response types, containing fields that go into the header of the HTTP response regardless
 * of the response type.
 */
public abstract class Response implements AutoCloseable {
    public static String SERVER_IDENTIFIER = "Gribbit";

    protected final Request request;
    protected final HttpResponseStatus status;
    protected boolean keepAlive;

    protected String contentType;
    protected long contentLength;
    protected boolean isChunked;
    protected boolean contentEncodingGzip;

    protected HashMap<String, Cookie> cookies;

    protected ZonedDateTime timeNow = ZonedDateTime.now();
    protected long timeNowEpochSeconds = timeNow.toEpochSecond();
    protected static final long ONE_YEAR_IN_SECONDS = 31536000L;

    protected long lastModifiedEpochSeconds;
    protected long maxAgeSeconds;

    private ArrayList<CustomHeader> customHeaders;

    public Response(Request request, HttpResponseStatus status, String contentType) {
        this.request = request;
        this.status = status;

        // Close connection after serving response if response status is Bad Request or Internal Server Error.
        // TODO: Do we need to close connection on error? (e.g. does it help mitigate DoS attacks?)
        this.keepAlive = request.isKeepAlive() && (status != HttpResponseStatus.BAD_REQUEST //
                || this.status != HttpResponseStatus.INTERNAL_SERVER_ERROR);

        this.contentType = contentType;
    }

    public Response(Request request, HttpResponseStatus status) {
        this(request, status, null);
    }

    /** Generate a response with an "OK" status and specified content type. */
    public Response(Request request, String contentType) {
        this(request, HttpResponseStatus.OK, contentType);
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    public long getContentLength() {
        return contentLength;
    }

    public boolean getIsChunked() {
        return isChunked;
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
     * Ensure the response is not cached. (This is the default, unless setMaxAgeSeconds(),
     * setLastModifiedEpochSeconds() or cacheForever() has been called already.)
     */
    public void doNotCache() {
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

    /**
     * Set a cookie in the response.
     * 
     * (As per RFC6295, the server can only return one cookie with a given name per response. We arbitrarily choose
     * the last value the cookie is set to as the one that is sent in the response, even if setCookie is called
     * multiple times for a given cookie name with different paths.)
     */
    public Response setCookie(Cookie cookie) {
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
        return setCookie(cookie);
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
     * Look through the request for cookies with the given name, and delete any matches in the response. (i.e. can
     * only delete cookies that are actually visible in the request.) Note that per RFC6295, the client should be
     * sending cookies in order of decreasing path length, and also the server can only send one Set-Cookie header
     * per cookie name, so if there are multiple matches, only the last match (the one with the shortest path) will
     * be deleted when the response is set, and you'll need to return multiple responses with the same deleteCookie
     * action applied to delete them all.
     */
    public Response deleteCookie(String cookieName) {
        ArrayList<Cookie> reqCookies = request.getCookies(cookieName);
        if (reqCookies != null) {
            Cookie firstCookie = reqCookies.iterator().next();
            setCookie(firstCookie.name(), /* value = */"", /* path = */firstCookie.path(), /* maxAgeSeconds = */
                    0, /* httpOnly = */false);
        }
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(UTC);

    protected void sendHeaders(ChannelHandlerContext ctx) {

        // Set general headers ---------------------------------------------------------------------------------------

        DefaultHttpResponse httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        HttpHeaders headers = httpResponse.headers();
        headers.add(SERVER, SERVER_IDENTIFIER);

        // Date header uses server time, and should use the same clock as Expires and Last-Modified
        headers.add(DATE, dateTimeFormatter.format(timeNow));

        // Add an Accept-Encoding: gzip header to the response to let the client know that in future
        // it can send compressed requests. (This header is probably ignored by most clients, because
        // on initial request they don't know yet if the server can accept compressed content, but
        // there must be clients out there that look for this header and compress content on the
        // second and subsequent requests? See http://stackoverflow.com/a/1450163/3950982 )
        headers.add(ACCEPT_ENCODING, "gzip");

        // Set HTTP2 stream ID in response if present in request
        if (request.getStreamId() != null) {
            headers.add(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), request.getStreamId());
        }

        if (keepAlive) {
            headers.add(CONNECTION, KEEP_ALIVE);
        }

        if (customHeaders != null) {
            for (CustomHeader c : customHeaders) {
                httpResponse.headers().add(c.key, c.value);
            }
        }

        // Set cookies in the response
        if (cookies != null) {
            for (String cookieStr : ServerCookieEncoder.STRICT.encode(cookies.values())) {
                headers.add(SET_COOKIE, cookieStr);
            }
        }

        // Set cache headers ---------------------------------------------------------------------------------------

        boolean cached = false;
        if (status == HttpResponseStatus.OK) {
            // Set caching headers -- see:
            // http://www.mobify.com/blog/beginners-guide-to-http-cache-headers/
            // https://www.mnot.net/cache_docs/

            // Last-Modified is used to determine whether a Not Modified response should be returned on next request.
            // RouteHandlers that want to make use of this value should check the return value of
            // request.cachedVersionIsOlderThan(serverTimestamp), where serverTimestamp was the timestamp at which
            // the value previously changed, and if the return value is false, throw NotModifiedException.
            if (lastModifiedEpochSeconds > 0L) {
                headers.add(
                        LAST_MODIFIED,
                        dateTimeFormatter.format(ZonedDateTime.ofInstant(
                                Instant.ofEpochSecond(lastModifiedEpochSeconds), UTC)));
            }

            //            if (request.isHashURL() && maxAgeSeconds != 0L) {
            //                // TODO: Move cache busting code out of http package
            //
            //                // Treat negative maxAgeSeconds as "cache forever" (according to spec, max is 1 year). 
            //                long maxAge = maxAgeSeconds < 0 ? ONE_YEAR_IN_SECONDS : maxAgeSeconds;
            //
            //                // Only URLs that include a hash key (and whose response has a non-zero maxAgeSeconds) can be cached.
            //                // N.B. can set "Cache-Control: public", since the resource is hashed, so it can be served to other
            //                // clients that request it (they would have to know the hash URL to request it in the first place).
            //                headers.add(CACHE_CONTROL, "public, max-age=" + maxAge);
            //                headers.add(EXPIRES, dateTimeFormatter.format(timeNow.plusSeconds(maxAge)));
            //                headers.add(ETAG, request.getURLHashKey());
            //                cached = true;
            //            }

        } else if (this.getStatus() == HttpResponseStatus.NOT_MODIFIED) {
            // For NOT_MODIFIED, need to return the same last modified time as was passed in the request
            if (request != null && request.getIfModifiedSince() != null) {
                headers.add(LAST_MODIFIED, request.getIfModifiedSince());
            }
            cached = true;

        } else if (this.getStatus() == HttpResponseStatus.NOT_FOUND) {
            // Cache 404 messages for 5 minutes to reduce server load
            int cacheTime = 60 * 5;
            headers.add(CACHE_CONTROL, "max-age=" + cacheTime);
            headers.add(EXPIRES, dateTimeFormatter.format(timeNow.plusSeconds(cacheTime)));
            cached = true;
        }

        if (!cached) {
            // Disable caching for all URLs that do not contain a hash key. In particular, caching is
            // disabled for error messages, resources that don't have a last modified time, and responses
            // from RouteHandlers that do not set a maxAge (and are therefore not hashed).

            // This is the minimum necessary set of headers for disabling caching, see http://goo.gl/yXGd2x
            headers.add(CACHE_CONTROL, "no-cache, no-store, must-revalidate"); // HTTP 1.1
            headers.add(PRAGMA, "no-cache"); // HTTP 1.0
            headers.add(EXPIRES, "0"); // Proxies
        }

        // Set content headers -------------------------------------------------------------------------------------

        headers.add(CONTENT_TYPE, contentType != null ? contentType : "application/octet-stream");
        if (isChunked) {
            // "Transfer-Encoding: chunked" is used in place of "Content-Length" header
            headers.add(TRANSFER_ENCODING, CHUNKED);
        } else {
            if (contentLength >= 0) {
                headers.add(CONTENT_LENGTH, Long.toString(contentLength));
            }
        }

        // This header is only typically for .svgz files, which are supposed to be served with a content type of
        // "image/svg+xml" but with a "Content-Encoding: gzip" header. For auto-compressed content, this header
        // will be added automatically by HttpContentCompressor (below).
        if (contentEncodingGzip) {
            headers.add(CONTENT_ENCODING, GZIP);
        }

        // Dynamically add compression for the response content if necessary ---------------------------------------

        // TODO: compression is disabled for now, see: http://andreas.haufler.info/2014/01/making-http-content-compression-work-in.html
        //        if (request.acceptEncodingGzip() && (isChunked || contentLength > 0)
        //                && ContentTypeUtils.isCompressibleContentType(contentType)) {
        //            ctx.pipeline().addBefore(HttpRequestDecoder.NAME_IN_PIPELINE, "HttpContentCompressor",
        //                    new HttpContentCompressor(1));
        //        }

        // Send headers --------------------------------------------------------------------------------------------

        ctx.write(httpResponse);
    }

    /** Send the response. Should call sendHeaders(ctx), followed by calling ctx.writeAndFlush(content). */
    protected abstract void writeResponse(ChannelHandlerContext ctx) throws Exception;

    /** Send the response. */
    public void send(ChannelHandlerContext ctx) throws ResponseException {
        try {
            writeResponse(ctx);
        } catch (Exception e) {
            if (e instanceof ResponseException) {
                throw (ResponseException) e;
            } else {
                throw new InternalServerErrorException(e);
            }
        } finally {
            if (!keepAlive) {
                ctx.newPromise().addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    @Override
    public abstract void close();

    @Override
    protected void finalize() throws Throwable {
        close();
    }

}
