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
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import static io.netty.handler.codec.http.HttpHeaderValues.GZIP;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import gribbit.http.logging.Log;
import gribbit.http.request.Request;
import gribbit.http.response.exception.InternalServerErrorException;
import gribbit.http.response.exception.NotFoundException;
import gribbit.http.response.exception.NotModifiedException;
import gribbit.http.response.exception.ResponseException;
import gribbit.server.GribbitServer;
import gribbit.server.siteresources.CacheExtension;
import gribbit.util.WebUtils;
import gribbit.util.thirdparty.UTF8;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * The superclass of all response types, containing fields that go into the header of the HTTP response regardless
 * of the response type.
 */
public abstract class Response {
    protected final Request request;
    protected final HttpResponseStatus status;
    protected final String contentType;
    protected final DefaultHttpResponse httpResponse;

    protected boolean keepAlive;
    protected HashMap<String, Cookie> cookies;

    protected ZonedDateTime timeNow = ZonedDateTime.now();
    protected long timeNowEpochSeconds = timeNow.toEpochSecond();
    protected static final long ONE_YEAR_IN_SECONDS = 31536000L;

    protected long lastModifiedEpochSeconds;
    protected long maxAgeSeconds;

    protected boolean compressStreamIfPossible = true;

    /** Generate a response with a specified status and content type. */
    public Response(Request request, HttpResponseStatus status, String contentType) {
        this.request = request;
        this.status = status;
        this.contentType = contentType;
        this.httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);

        // Close connection after serving response if response status is Bad Request or Internal Server Error.
        // TODO: Do we need to close connection on error? (e.g. does it help mitigate DoS attacks?)
        this.keepAlive = request.isKeepAlive() && (this.getStatus() != HttpResponseStatus.BAD_REQUEST //
                || this.getStatus() != HttpResponseStatus.INTERNAL_SERVER_ERROR);
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
     * Disable gzip-compression of content streams. (By default, streams with compressible mimetypes like text, HTML
     * and Javascript are compressed by gzip as they are sent.)
     */
    public void disableCompression() {
        this.compressStreamIfPossible = false;
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
        for (Cookie cookie : request.getCookiesWithName(cookieName)) {
            setCookie(cookie.name(), /* value = */"", /* path = */cookie.path(), /* maxAgeSeconds = */0, //
                    /* httpOnly = */false);
        }
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    public Response addHeader(CharSequence key, CharSequence value) {
        httpResponse.headers().set(key, value);
        return this;
    }

    protected void setGeneralHeaders() {
        HttpHeaders headers = httpResponse.headers();
        headers.add(SERVER, GribbitServer.SERVER_IDENTIFIER);

        // Date header uses server time, and should use the same clock as Expires and Last-Modified
        headers.set(DATE, timeNow.format(DateTimeFormatter.RFC_1123_DATE_TIME));

        // Add an Accept-Encoding: gzip header to the response to let the client know that in future
        // it can send compressed requests. (This header is probably ignored by most clients, because
        // on initial request they don't know yet if the server can accept compressed content, but
        // there must be clients out there that look for this header and compress content on the
        // second and subsequent requests? See http://stackoverflow.com/a/1450163/3950982 )
        headers.add(ACCEPT_ENCODING, "gzip");

        // Set HTTP2 stream ID in response if present in request
        if (request.getStreamId() != null) {
            headers.set(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), request.getStreamId());
        }

        if (keepAlive) {
            httpResponse.headers().add(CONNECTION, KEEP_ALIVE);
        }

        // Content type is passed into constructor, so it is always available
        headers.set(CONTENT_TYPE, contentType);
    }

    protected void setCacheHeaders() throws ResponseException {
        boolean cached = false;
        HttpHeaders headers = httpResponse.headers();
        if (status == HttpResponseStatus.OK) {
            // Set caching headers -- see:
            // http://www.mobify.com/blog/beginners-guide-to-http-cache-headers/
            // https://www.mnot.net/cache_docs/

            // Last-Modified is used to determine whether a Not Modified response should be returned on next request.
            // RouteHandlers that want to make use of this value should check the return value of
            // request.cachedVersionIsOlderThan(serverTimestamp), where serverTimestamp was the timestamp at which
            // the value previously changed, and if the return value is false, throw NotModifiedException.
            if (lastModifiedEpochSeconds > 0L) {
                headers.set(LAST_MODIFIED,
                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochSeconds), ZoneId.of("UTC"))
                                .format(DateTimeFormatter.RFC_1123_DATE_TIME));

            } else if (request.isHashURL() && maxAgeSeconds > 0L) {
                // TODO: Move cache busting code out of http package

                // Only URLs that include a hash key (and whose response has a non-zero maxAgeSeconds) can be cached.
                // N.B. can set "Cache-Control: public", since the resource is hashed, so it can be served to other
                // clients that request it (they would have to know the hash URL to request it in the first place).
                headers.set(CACHE_CONTROL, "public, max-age=" + maxAgeSeconds);
                headers.set(EXPIRES, timeNow.plusSeconds(maxAgeSeconds)
                        .format(DateTimeFormatter.RFC_1123_DATE_TIME));
                headers.set(ETAG, request.getURLHashKey());
                cached = true;
            }

        } else if (this.getStatus() == HttpResponseStatus.NOT_MODIFIED) {
            // For NOT_MODIFIED, need to return the same last modified time as was passed in the request
            if (request.getIfModifiedSince() != null) {
                headers.set(LAST_MODIFIED, request.getIfModifiedSince());
            } else {
                throw new InternalServerErrorException("Cannot send a Not Modified response if the request does "
                        + "not have an If-Modified-Since header");
            }
            cached = true;

        } else if (this.getStatus() == HttpResponseStatus.NOT_FOUND) {
            // Cache 404 messages for 5 minutes to reduce server load
            int cacheTime = 60 * 5;
            headers.set(CACHE_CONTROL, "max-age=" + cacheTime);
            headers.set(EXPIRES, timeNow.plusSeconds(cacheTime).format(DateTimeFormatter.RFC_1123_DATE_TIME));
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
    }

    protected void setCookieHeaders() {
        // Set cookies in the response
        for (String cookieStr : ServerCookieEncoder.STRICT.encode(cookies.values())) {
            httpResponse.headers().add(SET_COOKIE, cookieStr);
        }
    }

    /**
     * Overridden by subclasses to generate the content of the response. Call sendContentResponse(),
     * sendHeadResponse(), or sendFileResponse() once the content has been located and/or generated.
     */
    protected abstract ChannelFuture generateAndSendContent(boolean isHeadRequest) throws ResponseException;

    protected ChannelFuture sendContentResponse(String content, ChannelHandlerContext ctx) {
        ByteBuf contentBytes = ctx.alloc().buffer(content.length() * 2);
        contentBytes.writeBytes(UTF8.stringToUTF8(content));
        return sendContentResponse(contentBytes, ctx);
    }

    protected ChannelFuture sendContentResponse(ByteBuf content, ChannelHandlerContext ctx) {
        // Gzip content if the configuration property is set to allow gzip, and the client supports gzip encoding,
        // and the content size is larger than 1kb, and the content type is compressible 
        HttpHeaders headers = httpResponse.headers();
        if (this.compressStreamIfPossible && //
                request.acceptEncodingGzip() //
                && content.readableBytes() > 1024 //
                && WebUtils.isCompressibleContentType(contentType)) {
            byte[] contentBytes = content.array();
            int contentLen = content.readableBytes();
            ByteBuf gzippedContent = ctx.alloc().buffer(contentLen);
            try {
                // TODO: compare speed to using JZlib.GZIPOutputStream
                GZIPOutputStream gzipStream = new GZIPOutputStream(new ByteBufOutputStream(gzippedContent));
                gzipStream.write(contentBytes, 0, contentLen);
                gzipStream.close();
            } catch (IOException e) {
                // Should not happen
                Log.exception("Could not gzip content", e);
                gzippedContent = Unpooled.EMPTY_BUFFER;
            }
            // Release the content ByteBuf after last usage, and then use gzipped content instead
            content.release();
            content = gzippedContent;
            httpResponse.headers().set(CONTENT_ENCODING, GZIP);
        }
        headers.set(CONTENT_LENGTH, Integer.toString(content.readableBytes()));

        // Write headers, content, and end marker
        ctx.write(httpResponse);
        ctx.write(content);
        return ctx.write(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    protected ChannelFuture sendHeadResponse(int contentLen, ChannelHandlerContext ctx) {
        httpResponse.headers().set(CONTENT_LENGTH, Integer.toString(contentLen));
        ctx.write(httpResponse);
        return ctx.write(LastHttpContent.EMPTY_LAST_CONTENT);
    }
   
    /**
     * Send a file on a given path. It is the caller's responsibility to ensure that the path has been properly
     * sanitized so that it only exposes file content that should be exposed.
     * 
     * If a file does not exist at this path (or the object at the path is not a regular file), throws
     * NotFoundException.
     */
    protected ChannelFuture sendFileResponse(Path path) throws ResponseException {
        File file = path.toFile();
        if (!file.exists() || !file.isFile() || file.isHidden()) {
            throw new NotFoundException();
        }
        // Create new RandomAccessFile (which allows us to find file length etc.)
        try (RandomAccessFile fileToServe = new RandomAccessFile(file, "r")) {
            HttpHeaders headers = httpResponse.headers();
            // Check last-modified timestamp against the If-Modified-Since header timestamp in the request
            // (resolution is 1 sec)
            long lastModifiedEpochSeconds = file.lastModified() / 1000;
            if (!request.contentModified(lastModifiedEpochSeconds)) {
                // File has not been modified since it was last cached -- return Not Modified
                throw new NotModifiedException();
            }

            long fileLength = fileToServe.length();
            headers.set(CONTENT_LENGTH, Long.toString(fileLength));
            WebUtils.setContentTypeHeaders(headers, file.getPath());

            // If the file contents have changed since the last time the file was hashed,
            // schedule the file to be hashed in the background so that future references to the
            // file's URI in a src/href attribute of served HTML templates will include a hash
            // URI rather than the original URI for the file, allowing the browser to cache the
            // file indefinitely until it changes.
            CacheExtension.updateHashURI(request.getURLPathUnhashed(), file);

            // If file was already cached, and the request URI included the hash key, then this is
            // the first time this client has fetched this file since the browser cache was last
            // cleared. Mark this resource as indefinitely cached. If the file is not being served
            // on a hash URI, then at least set the Last-Modified header, so that if the client
            // requests the same unmodified resource again on the same non-hash URI, the server can
            // return Not Modified instead of serving the contents of the file.

            // Date header uses server time, and should use the same clock as Expires and Last-Modified
            ZonedDateTime timeNow = ZonedDateTime.now();
            headers.set(DATE, timeNow.format(DateTimeFormatter.RFC_1123_DATE_TIME));

            // Last-Modified is used to determine whether a Not Modified response should be returned on next request
            // Add last modified header to cacheable resources. This is needed because Chrome sends
            // "Cache-Control: max-age=0" when the user types in a URL and hits enter, or hits refresh.
            // In these circumstances, sending back "Cache-Control: public, max-age=31536000" does
            // no good, because the browser has already requested the resource rather than relying on
            // its cache. By setting the last modified header for all cacheable resources, we can
            // at least send "Not Modified" as a response if the resource has not been modified,
            // which doesn't save on roundtrips, but at least saves on re-transferring the resources
            // to the browser when they're already in the browser's cache.
            headers.set(LAST_MODIFIED,
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochSeconds), ZoneId.of("UTC"))
                            .format(DateTimeFormatter.RFC_1123_DATE_TIME));

            String hashKey = request.getURLHashKey();
            if (hashKey != null) {
                // File was requested on a hash URL => cache the most recent version of the file indefinitely
                // at the hash URL. If the file contents no longer match the hash (i.e. the contents have
                // changed since the hash for the request URI was looked up by the RouteHandler), it's no big
                // deal, we can just serve the newer file contents at the old hash, and the client will still
                // get the newest content, just cached against the old hash URL.
                int indefinitely = 31536000; // 1 year (max according to spec)
                headers.set(CACHE_CONTROL, "public, max-age=" + indefinitely);
                headers.set(EXPIRES, timeNow.plusSeconds(indefinitely).format(DateTimeFormatter.RFC_1123_DATE_TIME));
                headers.set(ETAG, hashKey);
            }

            if (request.isKeepAlive()) {
                headers.add(CONNECTION, KEEP_ALIVE);
            }

            // FileRegions cannot be used with SSL, have to use chunked content
            boolean isChunked = ctx.pipeline().get(SslHandler.class) != null;
            if (isChunked) {
                headers.add(TRANSFER_ENCODING, CHUNKED);
            }

            // Write HTTP headers to channel
            ctx.write(httpRes);

            // For HEAD requests, don't send the body
            if (request.isHEADRequest()) {
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                return;
            }

            // TODO: when a file is requested, if it's a compressible type, schedule it to be gzipped on disk, and
            // return the gzipped version instead of the original version, as long as the gzipped version has a
            // newer timestamp.

            // Write file content to channel.
            // Both methods will close fileToServe after sending the file, see:
            // https://github.com/netty/netty/issues/2474#issuecomment-117905496
            @SuppressWarnings("unused")
            ChannelFuture sendFileFuture;
            ChannelFuture lastContentFuture;
            if (!isChunked) {
                // Use FileRegions if possible, which supports zero-copy / mmio.
                sendFileFuture = ctx.write(new DefaultFileRegion(fileToServe.getChannel(), 0, fileLength),
                        ctx.newProgressivePromise());
                // Write the end marker
                lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                // Can't use FileRegions / zero-copy with SSL
                // HttpChunkedInput will write the end marker (LastHttpContent) for us, see:
                // https://github.com/netty/netty/commit/4ba2ce3cbbc55391520cfc98a7d4227630fbf978
                lastContentFuture = sendFileFuture = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(
                        fileToServe, 0, fileLength, 1)), ctx.newProgressivePromise());
            }

            // Possibly close the connection after the last chunk has been sent.
            if (!request.isKeepAlive()) {
                lastContentFuture.addListener(ChannelFutureListener.CLOSE);
            }

            //    // Can add ChannelProgressiveFutureListener to sendFileFuture if we need to track
            //    // progress (e.g. to update user's UI over a web socket to show download progress.)
            //    sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
            //        @Override
            //        public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
            //            if (total < 0) { // Total unknown
            //                System.err.println(future.channel() + " Progress: " + progress);
            //            } else {
            //                System.err.println(future.channel() + " Progress: " + progress + " / " + total);
            //            }
            //        }
            //    
            //        @Override
            //        public void operationComplete(ChannelProgressiveFuture future) {
            //            System.err.println(future.channel() + " Transfer complete.");
            //        }
            //    });

            Log.fine(request.getRequestor() + "\t" + request.getURLPathUnhashed() + "\tfile://" + file.getPath()
                    + "\t" + HttpResponseStatus.OK + "\t"
                    + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis()) + " msec");
        } catch (FileNotFoundException e) {
            // 404 Not Found
            throw new NotFoundException(request);
        } catch (IOException e1) {
            // Should only be thrown by Autocloseable if close() fails
            throw new InternalServerErrorException(e1);
        }
    }

    /** Send an HTTP response. */
    public void send(Request request, ChannelHandlerContext ctx) throws ResponseException {
        // TODO
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

        // TODO
        //        // If the response needs hashing, and the response does not have an error status, then schedule the
        //        // content of the response for hashing, and store a mapping from the original request URI to the
        //        // hash URI so that future HTML responses that have src/href attributes that contain this request
        //        // URI will replace this request URI with the hash URI instead. This will mean the client will
        //        // fetch that hash URI only once until it expires in the cache, so that on subsequent requests,
        //        // the linked resource won't even be requested from the server.
        //        long maxAgeSeconds = this.getMaxAgeSeconds();
        //        if (maxAgeSeconds > 0L && this.getStatus() == HttpResponseStatus.OK) {
        //            CacheExtension.updateHashURI(request.getURLPathUnhashed(), content, //
        //                    /* lastModifiedEpochSeconds = */timeNowEpochSeconds);
        //        }

        if (request.isHEADRequest()) {
            // Don't return a body for HEAD requests (but still return the content length).
            httpResponse.content().clear();
        }

        ctx.flush();
        if (!keepAlive) {
            ctx.newPromise().addListener(ChannelFutureListener.CLOSE);
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
