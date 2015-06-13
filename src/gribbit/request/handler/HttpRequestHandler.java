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
import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.DATE;
import static io.netty.handler.codec.http.HttpHeaderNames.ETAG;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPIRES;
import static io.netty.handler.codec.http.HttpHeaderNames.LAST_MODIFIED;
import static io.netty.handler.codec.http.HttpHeaderNames.PRAGMA;
import static io.netty.handler.codec.http.HttpHeaderNames.SERVER;
import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static io.netty.handler.codec.http.HttpHeaderValues.GZIP;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import gribbit.auth.Cookie;
import gribbit.request.Request;
import gribbit.response.HTMLPageResponse;
import gribbit.response.Response;
import gribbit.response.exception.BadRequestException;
import gribbit.response.exception.InternalServerErrorException;
import gribbit.response.exception.NotModifiedException;
import gribbit.response.exception.RequestHandlingException;
import gribbit.response.flashmsg.FlashMessage;
import gribbit.route.Route;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.CacheExtension;
import gribbit.server.siteresources.CacheExtension.HashInfo;
import gribbit.util.Log;
import gribbit.util.WebUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderUtil;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.ServerCookieEncoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.zip.GZIPOutputStream;

public class HttpRequestHandler extends SimpleChannelInboundHandler<Object> {

    private ChannelHandlerContext ctx;
    private Request request;

    private HttpPostRequestDecoder postRequestDecoder;
    private WebSocketHandler webSocketHandler;

    // -----------------------------------------------------------------------------------------------------------------

    // Use disk for HTTP data if size >16kB 
    private static final HttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);

    static {
        // Delete temporary files on exit
        DiskFileUpload.deleteOnExitTemporaryFile = true;
        DiskAttribute.deleteOnExitTemporaryFile = true;
        // Use system temp directory
        DiskFileUpload.baseDirectory = null;
        DiskAttribute.baseDirectory = null;
    }

    // -----------------------------------------------------------------------------------------------------------------

    private void destroyPostRequestDecoder() {
        if (postRequestDecoder != null) {
            try {
                postRequestDecoder.cleanFiles();
                postRequestDecoder.destroy();
                postRequestDecoder = null;
                // Release any temporary files
                request.releasePostFileUploadParams();
            } catch (Exception e) {
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Decode an HTTP message. */
    @Override
    public void messageReceived(ChannelHandlerContext ctx, Object msg) {
        this.ctx = ctx;
        boolean expectMoreChunks = false;
        try {

            // ------------------------------------------------------------------------------
            // Handle WebSocket frames
            // ------------------------------------------------------------------------------

            if (msg instanceof WebSocketFrame) {
                if (!GribbitProperties.ALLOW_WEBSOCKETS || webSocketHandler == null) {
                    throw new BadRequestException();
                }
                webSocketHandler.handleWebsocketFrame(ctx, (WebSocketFrame) msg);
                return;
            }

            // ------------------------------------------------------------------------------
            // Decode HTTP headers
            // ------------------------------------------------------------------------------

            if (msg instanceof HttpRequest) {
                HttpRequest httpReq = (HttpRequest) msg;
                if (!httpReq.decoderResult().isSuccess()) {
                    // Malformed HTTP headers
                    throw new BadRequestException(request);
                }

                // Parse the HttpRequest fields. 
                // Throws an UnauthorizedException if the user is not authorized for the requested route.
                // Throws NotFoundException if the requested path doesn't match any known Route or static resource. 
                request = new Request(ctx, httpReq);

                // Complete websocket handshake if requested.

                // Websockets have the same authorization requirements as the routes they are requested on,
                // i.e. if the route /app/handler is of type RouteHandlerAuthRequired, then the WS request
                // /app/handler?_ws=1 also requires the user to be logged in first. If we get to here and
                // response is still null but authorizedRoute is non-null, then there was no error response
                // such as Unauthorized, and the user is authorized for this route (so they are also
                // authorized for the WebSocket attached to the route).

                // Note that currently, the WebSocketHandler constructor below will always fail if the user
                // is not logged in (i.e. when request.lookupUser() returns null), to mitigate DoS attacks
                // on un-authenticated sockets.

                // TODO: Read WS routes from class annotations to see if WS is allowed?
                // TODO: Or always allow WS connections so that GET/POST can be submitted via WS?
                // TODO: Throttle the number of websockets requests per second per user (and have one pool
                // TODO: of requests for anonymous users); limit the total number of websockets that may
                // TODO: be opened by one user

                if (request.isWebSocketUpgradeRequest()) {
                    this.webSocketHandler = request.getWebSocketHandler();
                    // Websocket upgrade request has already been handled by the Request constructor
                    return;
                }

                // Handle expect-100-continue
                List<CharSequence> allExpectHeaders = httpReq.headers().getAll(EXPECT);
                for (int i = 0; i < allExpectHeaders.size(); i++) {
                    String h = allExpectHeaders.get(i).toString();
                    if (h.equalsIgnoreCase("100-continue")) {
                        ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER));
                        return;
                    }
                }

                if (httpReq.method() == HttpMethod.POST) {
                    // Start decoding HttpContent chunks.
                    destroyPostRequestDecoder();
                    postRequestDecoder = new HttpPostRequestDecoder(factory, httpReq);
                    // TODO: verify that we'll always get at least one chunk with all POST requests
                    expectMoreChunks = true;
                }

            } else if (request == null) {
                // Failed to get a valid HttpRequest to start the request
                throw new BadRequestException();
            }

            // ------------------------------------------------------------------------------
            // Decode HTTP POST body
            // ------------------------------------------------------------------------------

            if (msg instanceof HttpContent && request != null && postRequestDecoder != null) {
                HttpContent chunk = (HttpContent) msg;
                // Offer chunk to decoder (this decreases refcount of chunk, so it doesn't have to
                // be separately released). Decoder is released after message has been handled.
                postRequestDecoder.offer(chunk);

                try {
                    while (postRequestDecoder.hasNext()) {
                        InterfaceHttpData data = postRequestDecoder.next();
                        if (data != null) {
                            HttpDataType httpDataType = data.getHttpDataType();
                            if (httpDataType == HttpDataType.Attribute) {
                                try {
                                    Attribute attribute = (Attribute) data;
                                    // Respect attribute's charset when decoding
                                    Charset encoding = attribute.getCharset() == null //
                                    ? Charset.forName("UTF-8") //
                                            : attribute.getCharset();
                                    String attributeValue;
                                    try {
                                        // Get attribute value. This can throw an IOException because the content of
                                        // the POST data is saved in temporary files.
                                        // TODO: impose a size limit rather than loading this all in RAM, so that
                                        // the server isn't vulnerable to OOM attacks from large POST requests.
                                        attributeValue = attribute.getString(encoding);
                                    } catch (IOException e) {
                                        throw new BadRequestException(request);
                                    }
                                    request.setPostParam(attribute.getName(), attributeValue);
                                } finally {
                                    // Decrease refcount, freeing data
                                    data.release();
                                }

                            } else if (httpDataType == HttpDataType.FileUpload) {
                                FileUpload fileUpload = (FileUpload) data;
                                // TODO: impose size limit and returning 413 (Request Entity Too Large)
                                // once the amount of data that has been sent hits the limit
                                if (fileUpload.isCompleted()) {
                                    // Save the FileUpload object (which wraps a DiskFileUpload in /tmp).
                                    // Need to release this resource later.
                                    request.setPostFileUploadParam(fileUpload.getName(), fileUpload);
                                }
                            } else {
                                Log.warning("Got unknown data chunk type: " + httpDataType);
                            }
                        }
                    }
                } catch (EndOfDataDecoderException e) {
                    // decoder.hasNext() doesn't actually work, this exception gets thrown
                }

                if (!(chunk instanceof LastHttpContent)) {
                    // This is not the last chunk of HTTP content
                    expectMoreChunks = true;
                }
            }

            File staticResourceFile = request.getStaticResourceFile();
            if (staticResourceFile != null) {
                // Serve a static file
                serveStaticResourceFile(staticResourceFile);
            } else {
                if (!expectMoreChunks) {
                    // No more chunks to receive, handle the request
                    handleGetOrPostRequest();
                }
            }

        } catch (Exception e) {
            expectMoreChunks = false;
            if (e instanceof RequestHandlingException) {
                // RequestHandlingException -- get the Response object
                sendResponse(((RequestHandlingException) e).getErrorResponse());
            } else {
                // Unexpected exception
                sendResponse(new InternalServerErrorException(e).getErrorResponse());
                Log.exception("Exception handling request", e);
            }

        } finally {
            if (!expectMoreChunks) {
                // Finished request -- destroy the multipart decoder and remove temporary files.
                // FIXME: need to call destroyDecoder() in cases where the connection goes stale, or is closed early
                destroyPostRequestDecoder();
                request = null;
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Serve a static file. */
    private void serveStaticResourceFile(File staticResourceFile) throws RequestHandlingException {
        // A static resource matched the request URI, check last-modified timestamp
        // against the If-Modified-Since header timestamp in the request.
        long lastModifiedEpochSeconds = staticResourceFile.lastModified() / 1000;
        if (!request.cachedVersionIsOlderThan(lastModifiedEpochSeconds)) {

            // File has not been modified since it was last cached -- return Not Modified
            throw new NotModifiedException(lastModifiedEpochSeconds);

        } else {

            // If file is newer than what is in the browser cache, or is not in cache, serve the file
            RandomAccessFile fileToServe = null;
            try {
                // Create new RandomAccessFile (which allows us to find file length etc.)
                fileToServe = new RandomAccessFile(staticResourceFile, "r");

                // -----------------------------------------
                // Serve a static file (not authenticated)
                // -----------------------------------------

                DefaultHttpResponse httpRes = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                HttpHeaders headers = httpRes.headers();
                headers.add("Server", GribbitServer.SERVER_IDENTIFIER);

                long fileLength = fileToServe.length();
                headers.set(CONTENT_LENGTH, Long.toString(fileLength));
                WebUtils.setContentTypeHeaders(headers, staticResourceFile.getPath());

                // If the file contents have changed since the last time the file was hashed,
                // schedule the file to be hashed in the background so that future references to the
                // file's URI in a src/href attribute of served HTML templates will include a hash
                // URI rather than the original URI for the file, allowing the browser to cache the
                // file indefinitely until it changes.
                CacheExtension.updateHashURI(request.getURLPathUnhashed(), staticResourceFile);

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
                    // File is hashed => cache indefinitely
                    // (although the spec only allows for one year, or 31536000 seconds)
                    int maxAge = 31536000;
                    headers.set(CACHE_CONTROL, "public, max-age=" + maxAge);
                    headers.set(EXPIRES, timeNow.plusSeconds(maxAge).format(DateTimeFormatter.RFC_1123_DATE_TIME));
                    headers.set(ETAG, hashKey);
                }

                if (request.isKeepAlive()) {
                    headers.add(CONNECTION, KEEP_ALIVE);
                }

                // Write HTTP headers to channel
                ctx.write(httpRes);

                // For HEAD requests, don't send the body
                if (request.isHEADRequest()) {
                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                    fileToServe.close();
                    return;
                }

                // Write file content to channel.
                // Can add ChannelProgressiveFutureListener to sendFileFuture if we need to track
                // progress (e.g. to update user's UI over a web socket to show download progress.)
                ChannelFuture sendFileFuture;
                ChannelFuture lastContentFuture;
                if (ctx.pipeline().get(SslHandler.class) == null) {
                    // Use FileRegions if possible, which supports zero-copy / mmio
                    sendFileFuture = ctx.write(new DefaultFileRegion(fileToServe.getChannel(), 0, fileLength),
                            ctx.newProgressivePromise());
                    // Write the end marker
                    lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                } else {
                    // Can't use FileRegions / zero-copy with SSL
                    sendFileFuture = ctx.write(new HttpChunkedInput(new ChunkedFile(fileToServe, 0, fileLength, 1)),
                            ctx.newProgressivePromise());
                    // HttpChunkedInput will write the end marker (LastHttpContent) for us.
                    // See https://github.com/netty/netty/commit/4ba2ce3cbbc55391520cfc98a7d4227630fbf978
                    lastContentFuture = sendFileFuture;
                }

                //    sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
                //        @Override
                //        public void operationProgressed(ChannelProgressiveFuture future,
                //                long progress, long total) {
                //            if (total < 0) { // Total unknown
                //                System.err.println(future.channel() + " Progress: " + progress);
                //            } else {
                //                System.err.println(future.channel() + " Progress: " + progress
                //                        + " / " + total);
                //            }
                //        }
                //
                //        @Override
                //        public void operationComplete(ChannelProgressiveFuture future) {
                //            System.err.println(future.channel() + " Transfer complete.");
                //        }
                //    });

                // Close connection after flush if needed, and close file after flush 
                final RandomAccessFile fileToClose = fileToServe;
                lastContentFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        try {
                            fileToClose.close();
                        } catch (IOException e) {
                        }
                        if (!request.isKeepAlive()) {
                            future.channel().close();
                        }
                    }
                });

            } catch (Exception e) {
                if (fileToServe != null) {
                    try {
                        fileToServe.close();
                    } catch (IOException e1) {
                    }
                }
                throw new InternalServerErrorException(request, "Exception serving static file", e);
            }

            Log.fine(request.getRequestor() + "\t" + request.getURLPathUnhashed() + "\tfile://"
                    + staticResourceFile.getPath() + "\t" + HttpResponseStatus.OK + "\t"
                    + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis()) + " msec");
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Handle GET or POST requests. */
    private void handleGetOrPostRequest() throws RequestHandlingException {
        Route authorizedRoute = request.getAuthorizedRoute();

        // ----------------------------------
        // See if response should be hashed
        // ----------------------------------

        // For hashed *non-file* URIs, the actual last modified timestamp of dynamically-served
        // content can't be read directly, so read the last modified timestamp stored for the
        // previously hashed version in the CacheExtension class, as long as the max age of the
        // cached version hasn't been exceeded, and see if the last modified timestamp is more
        // recent than the version cached in the browser.
        //
        // The important ramification of this is that when the resource identified by the non-file
        // URI changes, the CacheExtension class must be notified of that change (including in cases
        // where the database is modified by another database client) if the modified version should
        // start being served at a new hash URI immediately, otherwise the web client connected to
        // this web server will continue to serve old resources until the max age of the cached
        // content is exceeded.

        boolean hashTheResponse = false;
        if (request.isHashURL()) {
            HashInfo hashInfo = CacheExtension.getHashInfo(request.getURLPathUnhashed());
            if (hashInfo != null) {
                long lastModifiedEpochSeconds = hashInfo.getLastModifiedEpochSeconds();
                ZonedDateTime timeNow = ZonedDateTime.now();
                long timeNowEpochSeconds = timeNow.toEpochSecond();

                long maxAgeSeconds = authorizedRoute.getMaxAgeSeconds();

                // Content is not hash-cached
                long hashKeyRemainingAgeSeconds = maxAgeSeconds == 0L ? 0L //
                        : lastModifiedEpochSeconds + maxAgeSeconds - timeNowEpochSeconds;

                if (maxAgeSeconds > 0 && hashKeyRemainingAgeSeconds <= 0) {
                    // Resource has expired -- call the route handler to generate a new response rather
                    // than serving a Not Modified response, and schedule the response to be hashed or
                    // re-hashed once the response has been generated.
                    hashTheResponse = true;

                } else if (!request.cachedVersionIsOlderThan(lastModifiedEpochSeconds)) {
                    // Resource has not expired in cache, but client has requested it anyway.
                    // However, resource has not been modified since it was last hashed --
                    // return Not Modified.
                    throw new NotModifiedException(lastModifiedEpochSeconds);

                } else {
                    // Resource has not expired in cache, but client has requested it anyway.
                    // Resource *has* been modified since it was last hashed -- serve it the
                    // normal way using the route handler, but don't hash the response, since
                    // it has not expired yet.
                }
            } else {
                // There is no original URI matching this hash URI, so the hash key was stale
                // (i.e. a URI whose hashcode has been spoofed, or a very old hashcode from
                // the previous time the server was run), but we still got a valid request URI
                // by stripping away the hash code, so that is served below in the normal way.
            }
        }

        // Call the RestHandler for the route. May throw a RequestHandlingException.
        Response response = authorizedRoute.callHandler(request);
        if (hashTheResponse) {
            response.scheduleForHashing();
        }

        // Send the response
        sendResponse(response);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Send an HTTP response. */
    private void sendResponse(Response response) {
        boolean closeChannelAfterWrite = !request.isKeepAlive() || (response.getStatus() != HttpResponseStatus.OK
        // TODO: In case of redirects (response.getStatus() == HttpResponseStatus.FOUND), should the channel be closed?
                && response.getStatus() != HttpResponseStatus.FOUND);

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
            // Store any un-displayed flash messages back in the cookie. This extends the time the flash message
            // cookie lasts for, so that the messages should show up on the next full-page response.
            ArrayList<FlashMessage> flashMessages = request.getFlashMessages();
            if (flashMessages != null) {
                response.setCookie(new Cookie(Cookie.FLASH_COOKIE_NAME, "/",
                        FlashMessage.toCookieString(flashMessages), 60));
            }
        }

        // Get the content of the response as a byte buffer.
        ByteBuf content = response.getContent(request);

        // If the response needs hashing, and the response does not have an error status, then schedule the
        // content of the response for hashing, and store a mapping from the original request URI to the
        // hash URI so that future HTML responses that have src/href attributes that contain this request
        // URI will replace this request URI with the hash URI instead. This will mean the client will
        // fetch that hash URI only once until it expires in the cache, so that on subsequent requests,
        // the linked resource won't even be requested from the server.
        if (response.isScheduledForHashing() && response.getStatus() == HttpResponseStatus.OK) {
            CacheExtension.updateHashURI(request.getURLPathUnhashed(), content, response.getLastModifiedEpochSeconds());
        }

        byte[] contentBytes = content.array();
        String contentType = response.getContentType(request);

        // Gzip content if the configuration property is set to allow gzip, and the client supports gzip encoding,
        // and the content size is larger than 1kb, and the content type is compressible 
        ByteBuf contentToUse = content;
        boolean isGzipped = false;
        if (GribbitProperties.CONTENT_GZIP && //
                request.acceptEncodingGzip() //
                && content.readableBytes() > 1024 //
                && WebUtils.isCompressibleContentType(contentType)) {
            // TODO: compare speed to using JZlib.GZIPOutputStream
            ByteBuf gzippedContent = Unpooled.buffer(/* initialCapacity = */content.readableBytes());
            try {
                GZIPOutputStream gzipStream = new GZIPOutputStream(new ByteBufOutputStream(gzippedContent));
                gzipStream.write(contentBytes);
                gzipStream.close();
            } catch (IOException e) {
                // Should not happen
                Log.exception("Could not gzip content", e);
                gzippedContent = Unpooled.EMPTY_BUFFER;
            }
            contentToUse = gzippedContent;
            // Release the content ByteBuf after last usage
            content.release();
            content = null;
        }

        // Create a FullHttpResponse object that wraps the response status and content
        DefaultFullHttpResponse httpRes = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, response.getStatus(), //
                contentToUse);
        HttpHeaders headers = httpRes.headers();
        headers.set(CONTENT_LENGTH, Integer.toString(contentToUse.readableBytes()));
        if (isGzipped) {
            headers.set(CONTENT_ENCODING, GZIP);
        }
        headers.set(CONTENT_TYPE, contentType);

        headers.add(SERVER, GribbitServer.SERVER_IDENTIFIER);

        // Add an Accept-Encoding: gzip header to the response to let the client know that in future
        // it can send compressed requests. (This header is probably ignored by most clients, because
        // on initial request they don't know yet if the server can accept compressed content, but
        // there must be clients out there that look for this header and compress content on the
        // second and subsequent requests? See http://stackoverflow.com/a/1450163/3950982 )
        headers.add(ACCEPT_ENCODING, "gzip");

        // Date header uses server time, and should use the same clock as Expires and Last-Modified
        ZonedDateTime timeNow = ZonedDateTime.now();
        headers.set(DATE, timeNow.format(DateTimeFormatter.RFC_1123_DATE_TIME));

        // Set caching headers -- see:
        // http://www.mobify.com/blog/beginners-guide-to-http-cache-headers/
        // https://www.mnot.net/cache_docs/

        // Last-Modified is used to determine whether a Not Modified response should be returned on next request.
        // RouteHandlers that want to make use of this value should check the return value of
        // request.cachedVersionIsOlderThan(serverTimestamp), where serverTimestamp was the timestamp at which
        // the value previously changed, and if the return value is false, throw NotModifiedException.
        long lastModifiedEpochSeconds = response.getLastModifiedEpochSeconds();
        if (lastModifiedEpochSeconds > 0L) {
            headers.set(
                    LAST_MODIFIED,
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochSeconds), ZoneId.of("UTC")).format(
                            DateTimeFormatter.RFC_1123_DATE_TIME));
        } else {
            // If no last modified time is set, see if the route included a hash key
            String hashKey = request.getURLHashKey();
            if (hashKey != null) {
                // File is hashed => cache indefinitely
                // (although the spec only allows for one year, or 31536000 seconds)
                int maxAge = 31536000;
                headers.set(CACHE_CONTROL, "public, max-age=" + maxAge);
                headers.set(EXPIRES, timeNow.plusSeconds(maxAge).format(DateTimeFormatter.RFC_1123_DATE_TIME));
                headers.set(ETAG, hashKey);
            } else {
                // Disable caching for all resources that are don't have a last modified time and are not hashed.
                // Without these headers, the server will not have a last modified timestamp to check against its
                // own timestamp on subsequent requests, so cannot return Not Modified.
                // This is the minimum necessary set of headers for disabling caching, see http://goo.gl/yXGd2x
                headers.add(CACHE_CONTROL, "no-cache, no-store, must-revalidate"); // HTTP 1.1
                headers.add(PRAGMA, "no-cache"); // HTTP 1.0
                headers.add(EXPIRES, "0"); // Proxies
            }
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
                        String deleteCookieStr = ServerCookieEncoder
                                .encode(Cookie.deleteCookie(cookie).toNettyCookie());
                        headers.add(SET_COOKIE, deleteCookieStr);
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
                    headers.add(SET_COOKIE, setCookieStr);
                }
            }
        }

        // Add any custom headers from the Response object
        if (response.getCustomHeaders() != null) {
            for (Entry<String, String> header : response.getCustomHeaders().entrySet()) {
                headers.add(header.getKey(), header.getValue());
            }
        }

        // Close the connection after serving the response if the response status is anything other than OK
        if (request.isKeepAlive() && response.getStatus() == HttpResponseStatus.OK) {
            headers.add(CONNECTION, KEEP_ALIVE);
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
            // TODO: Apache closes KeepAlive connections after a few seconds, see
            //       http://en.wikipedia.org/wiki/HTTP_persistent_connection
            // TODO: implement a stale connection tracker
            if (closeChannelAfterWrite) {
                future.addListener(ChannelFutureListener.CLOSE);
            }

        } else {
            // Client already closed the connection, nothing can be sent
            // Log.info("Channel closed by client before response sent");
        }

        // ------------------------------------------------------------------------------------
        // Log the request and response
        // ------------------------------------------------------------------------------------

        //        boolean isFavicoReq = reqURLUnhashed.equals("favicon.ico") || reqURLUnhashed.equals("favicon.png")
        //                || reqURLUnhashed.endsWith("/favicon.ico");
        //        if (status == HttpResponseStatus.OK //
        //                || status == HttpResponseStatus.NOT_MODIFIED //
        //                || status == HttpResponseStatus.FOUND //
        //                || (status == HttpResponseStatus.NOT_FOUND && !isFavicoReq)) {
        Log.fine(request.getRequestor() + "\t" + request.getMethod() + "\t" + request.getURLPathUnhashed() + "\t"
                + response.getStatus() + "\t" + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis())
                + " msec");
        //        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Basic plaintext Internal Server Error page.
     * 
     * This is sent if an exception is thrown in the catch clause of the messageReceived method, i.e. if there was an
     * exception while a response was being sent due to a RequestHandlingException or other exception being thrown.
     * 
     * This is the fallback if sendResponse() is failing for some reason.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // Release any resources
        destroyPostRequestDecoder();

        if (cause instanceof NotSslRecordException) {
            ctx.channel().close();
            return;
        }
        try {
            if ("Connection reset by peer".equals(cause.getMessage())) {
                // (No need to log the backtrace in this case)
                // Log.info(cause.getMessage());            
            } else {
                // Log exception with backtrace
                Log.exception("Uncaught exception", cause);
            }
            if (ctx.channel().isActive()) {
                FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR);
                res.content().writeBytes("Internal Server Error".getBytes("UTF-8"));
                res.headers().set(CONTENT_TYPE, "text/plain;charset=utf-8");
                HttpHeaderUtil.setContentLength(res, res.content().readableBytes());
                ChannelFuture f = ctx.writeAndFlush(res);
                f.addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Exception e) {
            ctx.channel().close();
        }
    }
}
