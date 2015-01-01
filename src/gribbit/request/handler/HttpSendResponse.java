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

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.SERVER;
import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static io.netty.handler.codec.http.HttpHeaderValues.GZIP;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import gribbit.auth.Cookie;
import gribbit.request.Request;
import gribbit.response.HTMLPageResponse;
import gribbit.response.HTMLResponse;
import gribbit.response.Response;
import gribbit.response.flashmsg.FlashMessage;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.CacheExtension;
import gribbit.util.Log;
import gribbit.util.WebUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.ServerCookieEncoder;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.zip.GZIPOutputStream;

public class HttpSendResponse {

    /** Serve an HTTP response (anything other than a static file). */
    public static void sendResponse(String reqURI, Request request, Response response, boolean isHEAD,
            boolean acceptEncodingGzip, ZonedDateTime timeNow, boolean hashTheResponse,
            long hashKeyMaxRemainingAgeSeconds, String hashKey, boolean addKeepAliveHeader, boolean closeAfterWrite,
            ChannelHandlerContext ctx) {

        // Add any pending flash messages to the response, if the response is an HTML page
        if (response instanceof HTMLPageResponse) {
            // Add flash messages to response template, if any
            ArrayList<FlashMessage> flashMessages = request.getFlashMessages();
            if (flashMessages != null) {
                // Render pending flash messages into the HTML page
                ((HTMLPageResponse) response).setFlashMessages(flashMessages);
                // Clear the flash message cookie
                response.deleteCookie(Cookie.FLASH_COOKIE_NAME);
            }
        } else {
            // Store any un-displayed flash messages back in the cookie
            ArrayList<FlashMessage> flashMessages = request.getFlashMessages();
            if (flashMessages != null) {
                response.setCookie(new Cookie(Cookie.FLASH_COOKIE_NAME, "/",
                        FlashMessage.toCookieString(flashMessages), 60));
            }
        }

        // Get the content of the response as a byte buffer.
        ByteBuf content;
        String contentType;
        if (response instanceof HTMLResponse) {
            // If "?_getmodel=1" is appended to the request URL of a request that returns an HTML response,
            // then the data model backing the response will be returned as JSON instead of rendered HTML.
            boolean isGetModelRequest = GribbitProperties.ALLOW_GET_MODEL && request.isGetModelRequest();
            content = ((HTMLResponse) response).getContent(isGetModelRequest);
            contentType = isGetModelRequest ? "application/json;charset=utf-8" : response.getContentType();
        } else {
            // Not a "getmodel" request, just get the content from the response
            content = response.getContent();
            contentType = response.getContentType();
        }
        byte[] contentBytes = content.array();

        // Gzip content if the configuration property is set to allow gzip, and the client supports gzip encoding,
        // and the content size is larger than 1kb, and the content type is compressible 
        ByteBuf gzippedContent = null;
        if (GribbitProperties.CONTENT_GZIP && //
                acceptEncodingGzip //
                && content.readableBytes() > 1024 //
                && WebUtils.isCompressibleContentType(contentType)) {
            gzippedContent = Unpooled.buffer(/* initialCapacity = */content.readableBytes());
            // TODO: compare speed to using JZlib.GZIPOutputStream
            try {
                GZIPOutputStream gzipStream = new GZIPOutputStream(new ByteBufOutputStream(gzippedContent));
                gzipStream.write(contentBytes);
                gzipStream.close();
            } catch (IOException e) {
                // Should not happen
                throw new RuntimeException("Could not gzip content", e);
            }
        }

        // Create a FullHttpResponse object that wraps the response status and content
        HttpResponseStatus status = response.getStatus();
        DefaultFullHttpResponse httpRes = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, //
                gzippedContent != null ? gzippedContent : content);
        httpRes.headers().set(CONTENT_TYPE, contentType);
        httpRes.headers().set(CONTENT_LENGTH,
                Integer.toString(gzippedContent != null ? gzippedContent.readableBytes() : content.readableBytes()));
        if (gzippedContent != null) {
            httpRes.headers().set(CONTENT_ENCODING, GZIP);
        }

        httpRes.headers().add(SERVER, GribbitServer.SERVER_IDENTIFIER);

        // Add an Accept-Encoding: gzip header to the response to let the client know that in future
        // it can send compressed requests. (This header is probably ignored by most clients, because
        // on initial request they don't know yet if the server can accept compressed content, but
        // there must be clients out there that look for this header and compress content on the
        // second and subsequent requests? See http://stackoverflow.com/a/1450163/3950982 )
        httpRes.headers().add(ACCEPT_ENCODING, "gzip");

        // Set date and cache headers
        HttpUtils.setDateAndCacheHeaders(httpRes.headers(), timeNow, response.getLastModifiedEpochSeconds(),
                hashKeyMaxRemainingAgeSeconds, hashKey);

        // If the response needs hashing, and the response does not have an error status, then schedule the
        // content of the response for hashing, and store a mapping from the original request URI to the
        // hash URI so that future HTML responses that have src/href attributes that contain this request
        // URI will replace this request URI with the hash URI instead. This will mean the client will
        // fetch that hash URI only once until it expires in the cache, so that on subsequent requests,
        // the linked resource won't even be requested from the server.
        if (hashTheResponse && status == HttpResponseStatus.OK) {
            CacheExtension.updateHashURI(reqURI, content, response.getLastModifiedEpochSeconds());
        }

        // Release the content ByteBuf after last usage if gzippedContent is being used instead
        if (gzippedContent != null) {
            content.release();
        }

        if (isHEAD) {
            // Don't return a body for HEAD requests (but still return the content length,
            // set in the header above).
            // TODO: is this the right thing to do?
            httpRes.content().clear();
        }

        // Delete requested cookies in the response
        HashSet<String> cookiesToDelete = response.getCookiesToDelete();
        if (cookiesToDelete != null) {
            for (String cookieName : cookiesToDelete) {
                // Log.fine("Cookie to delete for req " + reqURI + " : " + cookieName);
                ArrayList<Cookie> allCookiesWithName = request.getAllCookiesWithName(cookieName);
                if (allCookiesWithName != null) {
                    for (Cookie cookie : allCookiesWithName) {
                        // Delete all cookies with the requested name (there may be multiple cookies
                        // with this name but with different paths)
                        String deleteCookieStr =
                                ServerCookieEncoder.encode(Cookie.deleteCookie(cookie).toNettyCookie());
                        httpRes.headers().add(SET_COOKIE, deleteCookieStr);
                    }
                }
            }
        }

        // Set requested cookies in the response
        ArrayList<Cookie> cookiesToSet = response.getCookiesToSet();
        if (cookiesToSet != null) {
            for (Cookie cookie : cookiesToSet) {
                if (cookiesToDelete != null && cookiesToDelete.contains(cookie.getName())) {
                    Log.warning("Tried to delete and set the cookie \"" + cookie.getName()
                            + "\" in the same response -- ignoring the set request");
                } else {
                    String setCookieStr = ServerCookieEncoder.encode(cookie.toNettyCookie());
                    httpRes.headers().add(SET_COOKIE, setCookieStr);
                }
            }
        }

        // Add any custom headers from the Response object
        if (response.getCustomHeaders() != null) {
            for (Entry<String, String> header : response.getCustomHeaders().entrySet()) {
                httpRes.headers().add(header.getKey(), header.getValue());
            }
        }

        // Close the connection after serving the response if the response status is anything other than OK
        if (addKeepAliveHeader && status == HttpResponseStatus.OK) {
            httpRes.headers().add(CONNECTION, KEEP_ALIVE);
        }

        // closeAfterWrite = true;  // FIXME: test this, it doesn't seem to work ====================================================================

        if (ctx.channel().isOpen()) {
            // Write the ByteBuffer returned by httpRes.content() back into the pipeline
            // See http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#4.0
            ChannelFuture future = ctx.writeAndFlush(httpRes);

            // Close the connection after the write operation is done if necessary.
            // TODO: Apache closes KeepAlive connections after a few seconds, see
            //       http://en.wikipedia.org/wiki/HTTP_persistent_connection
            // TODO: implement a stale connection tracker
            if (closeAfterWrite || status != HttpResponseStatus.OK) { // FIXME: should I close the channel for redirects? (probably not...)
                future.addListener(ChannelFutureListener.CLOSE);
            }

        } else {
            // Client already closed the connection, nothing can be sent
            // Log.info("Channel closed by client before response sent");
        }
    }
}
