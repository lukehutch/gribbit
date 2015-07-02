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
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
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
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import gribbit.auth.Cookie;
import gribbit.request.Request;
import gribbit.response.HTMLPageResponse;
import gribbit.response.Response;
import gribbit.response.exception.BadRequestException;
import gribbit.response.exception.InternalServerErrorException;
import gribbit.response.exception.NotModifiedException;
import gribbit.response.exception.RequestHandlingException;
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
    private boolean skipRestOfRequest;

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
            // If an exception was thrown partway through a request, skip rest of messages until LastHttpContent
            if (skipRestOfRequest) {
                if (msg instanceof LastHttpContent) {
                    skipRestOfRequest = false;
                }
                return;
            }
            
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
                    throw new BadRequestException(null);
                }

                // Parse the HttpRequest fields. 
                request = new Request(ctx, httpReq);
                skipRestOfRequest = false;

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

                this.webSocketHandler = request.getWebSocketHandler();
                if (this.webSocketHandler != null) {
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
                }

                // There will always be at least one message following an HttpRequest, an EmptyLastHttpContent
                expectMoreChunks = true;

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
            }

            if (!(msg instanceof LastHttpContent)) {
                // All requests, including GET requests, end with an EmptyLastHttpContent message
                expectMoreChunks = true;
            } else {

                // ------------------------------------------------------------------------------
                // Generate and send the response
                // ------------------------------------------------------------------------------

                // Look up the route (or static file) based on the URL and HTTP method of the request.
                // Throws an UnauthorizedException if the user is not authorized for the requested route.
                // Throws NotFoundException if the requested path doesn't match any known Route or static resource.
                // Upgrades the connection to a websocket connection if requested.
                request.matchRoute();

                File staticResourceFile = request.getStaticResourceFile();
                if (staticResourceFile != null) {
                    // Serve a static file
                    sendFile(staticResourceFile);
                } else {
                    // No more chunks to receive; handle the request.
                    // Call the RestHandler for the route. May throw a RequestHandlingException.
                    Response response = request.callRouteHandler();
                    sendResponse(response);
                }
            }

        } catch (Exception e) {

            // ------------------------------------------------------------------------------
            // Send an error page or a redirect if an exception was thrown
            // ------------------------------------------------------------------------------

            // Destroy the POST decoder in the finally block
            expectMoreChunks = false;
            if (!(msg instanceof LastHttpContent)) {
                // Skip rest of messages up to the next LastHttpContent message
                skipRestOfRequest = true;
            }

            // If there is no valid request object, can't generate a normal response page,
            // because ErrorResponse requires a non-null request object to be able to call getContent() 
            if (request == null) {
                try {
                    // Return plaintext error page
                    exceptionCaught(ctx, e);
                } catch (Exception e2) {
                    Log.exception("Exception while calling fallback exception handler", e2);
                }
            } else {
                // Return error page through the normal sendResponse() mechanism 
                Response response;
                if (e instanceof InternalServerErrorException) {
                    response = ((InternalServerErrorException) e).getErrorResponse();
                    Log.exception("Unexpected exception handling request", e);
                } else if (e instanceof RequestHandlingException) {
                    // RequestHandlingException -- get the Response object
                    response = ((RequestHandlingException) e).getErrorResponse();
                } else {
                    // Unexpected exception
                    response = new InternalServerErrorException(e).getErrorResponse();
                    Log.exception("Unexpected exception handling request", e);
                }
                sendResponse(response);
            }

        } finally {
            if (!expectMoreChunks) {
                // Finished the request -- destroy the multipart decoder and remove temporary files.
                // FIXME: need to call destroyDecoder() in cases where the connection goes stale, or is closed early
                destroyPostRequestDecoder();
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Serve a static file.
     * 
     * TODO: File serving is not authenticated. Set up separate authenticated and non-authenticated resource paths?
     */
    private void sendFile(File staticResourceFile) throws RequestHandlingException {
        // A static resource matched the request URI, check last-modified timestamp
        // against the If-Modified-Since header timestamp in the request.
        long lastModifiedEpochSeconds = staticResourceFile.lastModified() / 1000;
        if (!request.contentModified(lastModifiedEpochSeconds)) {

            // File has not been modified since it was last cached -- return Not Modified
            throw new NotModifiedException(lastModifiedEpochSeconds);

        } else {

            // If file is newer than what is in the browser cache, or is not in cache, serve the file
            RandomAccessFile fileToServe = null;
            try {
                // Create new RandomAccessFile (which allows us to find file length etc.)
                fileToServe = new RandomAccessFile(staticResourceFile, "r");

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
                    fileToServe.close();
                    return;
                }

                // TODO: when a file is requested, if it's a compressible type, schedule it to be gzipped on disk, and
                // return the gzipped version instead of the original version, as long as the gzipped version has a
                // newer timestamp.

                // Write file content to channel.
                // Can add ChannelProgressiveFutureListener to sendFileFuture if we need to track
                // progress (e.g. to update user's UI over a web socket to show download progress.)
                ChannelFuture lastContentFuture;
                if (!isChunked) {
                    // Use FileRegions if possible, which supports zero-copy / mmio
                    ctx.write(new DefaultFileRegion(fileToServe.getChannel(), 0, fileLength),
                            ctx.newProgressivePromise());
                    // Write the end marker
                    lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                } else {
                    // Can't use FileRegions / zero-copy with SSL
                    // HttpChunkedInput will write the end marker (LastHttpContent) for us.
                    // See https://github.com/netty/netty/commit/4ba2ce3cbbc55391520cfc98a7d4227630fbf978
                    lastContentFuture = ctx.write(new HttpChunkedInput(new ChunkedFile(fileToServe, 0, fileLength, 1)),
                            ctx.newProgressivePromise());
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

                // Close the file and possibly the connection after the last chunk has been sent.
                // We can't close the file in a finally block, because the file writing is asynchronous, and
                // the file shouldn't be closed until the last chunk has been written. 
                final RandomAccessFile fileToClose = fileToServe;
                lastContentFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        try {
                            fileToClose.close();
                        } catch (IOException e) {
                        }
                        future.channel().flush();
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

    /** Send an HTTP response. */
    private void sendResponse(Response response) {
        // Add flash messages to response template, if any
        if (response instanceof HTMLPageResponse) {
            // Only complete HTML pages have flash messages
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
        byte[] contentBytes = content.array();
        String contentType = response.getContentType(request);

        // If the response needs hashing, and the response does not have an error status, then schedule the
        // content of the response for hashing, and store a mapping from the original request URI to the
        // hash URI so that future HTML responses that have src/href attributes that contain this request
        // URI will replace this request URI with the hash URI instead. This will mean the client will
        // fetch that hash URI only once until it expires in the cache, so that on subsequent requests,
        // the linked resource won't even be requested from the server.
        ZonedDateTime timeNow = ZonedDateTime.now();
        long maxAgeSeconds = response.getMaxAgeSeconds();
        if (maxAgeSeconds > 0L && response.getStatus() == HttpResponseStatus.OK) {
            CacheExtension.updateHashURI(request.getURLPathUnhashed(), content, //
                    /* lastModifiedEpochSeconds = */timeNow.toEpochSecond());
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
            isGzipped = true;
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
        headers.set(DATE, timeNow.format(DateTimeFormatter.RFC_1123_DATE_TIME));

        boolean cached = false;
        if (response.getStatus() == HttpResponseStatus.OK) {
            // Set caching headers -- see:
            // http://www.mobify.com/blog/beginners-guide-to-http-cache-headers/
            // https://www.mnot.net/cache_docs/

            // Last-Modified is used to determine whether a Not Modified response should be returned on next request.
            // RouteHandlers that want to make use of this value should check the return value of
            // request.cachedVersionIsOlderThan(serverTimestamp), where serverTimestamp was the timestamp at which
            // the value previously changed, and if the return value is false, throw NotModifiedException.
            long lastModifiedEpochSeconds = response.getLastModifiedEpochSeconds();
            if (lastModifiedEpochSeconds > 0L) {
                headers.set(LAST_MODIFIED,
                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochSeconds), ZoneId.of("UTC"))
                                .format(DateTimeFormatter.RFC_1123_DATE_TIME));

            } else if (request.isHashURL() && maxAgeSeconds > 0L) {
                // Only URLs that include a hash key (and whose response has a non-zero maxAgeSeconds) can be cached.
                cached = true;
                headers.set(CACHE_CONTROL, "public, max-age=" + maxAgeSeconds);
                headers.set(EXPIRES, timeNow.plusSeconds(maxAgeSeconds).format(DateTimeFormatter.RFC_1123_DATE_TIME));
                headers.set(ETAG, request.getURLHashKey());
            }
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
        boolean keepAlive = request.isKeepAlive() && (response.getStatus() == HttpResponseStatus.OK
        // TODO: In case of redirects (HttpResponseStatus.FOUND), should the channel be closed?
                || response.getStatus() != HttpResponseStatus.FOUND);
        if (keepAlive) {
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
            if (!keepAlive) {
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
     * Basic plaintext Internal Server Error page. This is sent if an exception is thrown in the catch clause of the
     * messageReceived method, i.e. if there was an exception while a response was being sent. This is the fallback if
     * sendResponse() is failing for some reason.
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
                HttpHeaders headers = res.headers();
                headers.set(CONTENT_TYPE, "text/plain;charset=utf-8");
                HttpHeaderUtil.setContentLength(res, res.content().readableBytes());

                // Disable caching
                headers.add(CACHE_CONTROL, "no-cache, no-store, must-revalidate"); // HTTP 1.1
                headers.add(PRAGMA, "no-cache"); // HTTP 1.0
                headers.add(EXPIRES, "0"); // Proxies

                ChannelFuture f = ctx.writeAndFlush(res);
                f.addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Exception e) {
            ctx.channel().close();
        }
    }
}
