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
package gribbit.request.handler;

import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.DATE;
import static io.netty.handler.codec.http.HttpHeaderNames.ETAG;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPIRES;
import static io.netty.handler.codec.http.HttpHeaderNames.LAST_MODIFIED;
import static io.netty.handler.codec.http.HttpHeaderNames.PRAGMA;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderUtil;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class HttpUtils {

    public static void sendHttpErrorResponse(ChannelHandlerContext ctx, HttpRequest req, FullHttpResponse res) {
        // Generate an plaintext error page if response status code is not OK (200).
        if (res.status() != HttpResponseStatus.OK) {
            // Use the HTTP status message as the content of the error page
            ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            buf.release();
            res.headers().set(CONTENT_TYPE, "text/plain;charset=utf-8");
            HttpHeaderUtil.setContentLength(res, res.content().readableBytes());
        }
        ChannelFuture f = ctx.writeAndFlush(res);
        if (req == null || !HttpHeaderUtil.isKeepAlive(req) || res.status() != HttpResponseStatus.OK) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Set Date and cache-related headers.
     * 
     * TODO: read http://www.mobify.com/blog/beginners-guide-to-http-cache-headers/
     * 
     * TODO: read https://www.mnot.net/cache_docs/
     */
    public static void setDateAndCacheHeaders(HttpHeaders httpHeaders, ZonedDateTime timeNow,
            long lastModifiedEpochSeconds, long hashKeyMaxRemainingAgeSeconds, String hashKey) {
        // Date header uses server time, and should use the same clock as Expires and Last-Modified
        httpHeaders.set(DATE, timeNow.format(DateTimeFormatter.RFC_1123_DATE_TIME));

        // Last-Modified is used to determine whether a Not Modified response should be returned on the next request
        if (lastModifiedEpochSeconds > 0) {
            // Add last modified header to cacheable resources. This is needed because Chrome sends
            // "Cache-Control: max-age=0" when the user types in a URL and hits enter, or hits refresh.
            // In these circumstances, sending back "Cache-Control: public, max-age=31536000" does
            // no good, because the browser has already requested the resource rather than relying on
            // its cache. By setting the last modified header for all cacheable resources, we can
            // at least send "Not Modified" as a response if the resource has not been modified,
            // which doesn't save on roundtrips, but at least saves on re-transferring the resources
            // to the browser when they're already in the browser's cache.
            httpHeaders.set(
                    LAST_MODIFIED,
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochSeconds), ZoneId.of("UTC")).format(
                            DateTimeFormatter.RFC_1123_DATE_TIME));
        }

        // Cache hashed URIs forever (or for the specified amount of time)
        if (hashKey != null) {
            // Negative max age => cache indefinitely (although the spec only allows for one year, or 31536000 seconds)
            long maxAgeSeconds = hashKeyMaxRemainingAgeSeconds < 0 ? 31536000 : hashKeyMaxRemainingAgeSeconds;

            // Cache hash URIs for one year
            httpHeaders.set(CACHE_CONTROL, "public, max-age=" + maxAgeSeconds);
            httpHeaders.set(EXPIRES, timeNow.plusSeconds(maxAgeSeconds).format(DateTimeFormatter.RFC_1123_DATE_TIME));
            httpHeaders.set(ETAG, hashKey);
        }

        if (hashKey == null && lastModifiedEpochSeconds == 0) {
            // Disable caching for all resources that don't have a last modified time (all static file
            // resources have a last modified time, so these headers will never be set for files, and 
            // they won't be set for dynamic resources that don't manually set a last modified time).
            // Without these headers, the server will not have a last modified timestamp to check
            // against its own timestamp on subsequent requests, so cannot return Not Modified.
            // This is the minimum necessary set of headers for disabling caching, see http://goo.gl/yXGd2x
            httpHeaders.add(CACHE_CONTROL, "no-cache, no-store, must-revalidate"); // HTTP 1.1
            httpHeaders.add(PRAGMA, "no-cache"); // HTTP 1.0
            httpHeaders.add(EXPIRES, "0"); // Proxies
        }
    }
}
