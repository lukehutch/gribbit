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
import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static io.netty.handler.codec.http.HttpHeaderNames.SERVER;
import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static io.netty.handler.codec.http.HttpHeaderValues.GZIP;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import gribbit.auth.Cookie;
import gribbit.request.Request;
import gribbit.response.ErrorResponse;
import gribbit.response.HTMLPageResponse;
import gribbit.response.Response;
import gribbit.response.exception.BadRequestException;
import gribbit.response.exception.RequestHandlingException;
import gribbit.response.exception.InternalServerErrorException;
import gribbit.response.exception.MethodNotAllowedException;
import gribbit.response.exception.NotFoundException;
import gribbit.response.exception.NotModifiedException;
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
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderUtil;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.zip.GZIPOutputStream;

public class HttpRequestHandler extends SimpleChannelInboundHandler<Object> {

    private Request request;

    // These two
    private boolean closeAfterWrite = false;
    private boolean addKeepAliveHeader = false;

    private HttpPostRequestDecoder decoder;

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

    private void destroyDecoder() {
        if (request != null) {
            try {
                // Release any temporary files
                request.releasePostFileUploadParams();
            } catch (Exception e) {
            }
        }
        if (decoder != null) {
            try {
                decoder.cleanFiles();
                decoder.destroy();
                decoder = null;
            } catch (Exception e) {
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Decode an HTTP message. */
    @Override
    public void messageReceived(ChannelHandlerContext ctx, Object msg) {
        boolean requestComplete = false;
        try {
            // ------------------------------------------------------------------------------
            // Handle WebSocket frames
            // ------------------------------------------------------------------------------

            if (GribbitProperties.ALLOW_WEBSOCKETS && msg instanceof WebSocketFrame) {
                if (webSocketHandler == null) {
                    // Got a web socket frame when no web socket exists
                    HttpUtils.sendHttpErrorResponse(ctx, null, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                            HttpResponseStatus.BAD_REQUEST));
                } else {
                    // Handle the frame
                    webSocketHandler.handleWebsocketFrame(ctx, (WebSocketFrame) msg);
                }
                return;
            }

            // Pick some default values needed to serve a response, in case an unexpected exception is thrown
            boolean isHEAD = false;
            boolean acceptEncodingGzip = false;
            String hashKey = null;
            ZonedDateTime timeNow = null;
            boolean hashTheResponse = false;
            long hashKeyRemainingAgeSeconds = 0L;
            HttpMethod origReqMethod = HttpMethod.GET, finalReqMethod = HttpMethod.GET;
            String requestor = "";
            String reqURLUnhashed = "";
            long reqTimestamp = System.currentTimeMillis();
            Response response = null;

            try {
                // ------------------------------------------------------------------------------
                // Decode HTTP headers
                // ------------------------------------------------------------------------------

                if (msg instanceof HttpRequest) {
                    HttpRequest httpReq = (HttpRequest) msg;

                    // System.out.println("REQUEST: " + httpReq.getUri());

                    // Start a new request.
                    // N.B. if connection is re-used, this will overwrite the previous request object. 
                    request = new Request(httpReq);

                    // Handle expect-100-continue
                    boolean expect100Continue = false;
                    List<CharSequence> allExpectHeaders = httpReq.headers().getAll(EXPECT);
                    for (int i = 0; i < allExpectHeaders.size(); i++) {
                        String h = allExpectHeaders.get(i).toString();
                        if (h.equalsIgnoreCase("100-continue")) {
                            expect100Continue = true;
                            break;
                        }
                    }
                    if (expect100Continue) {
                        ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER));
                        requestComplete = true;
                        return;
                    }

                    closeAfterWrite = !HttpHeaderUtil.isKeepAlive(httpReq);
                    addKeepAliveHeader = !closeAfterWrite && httpReq.protocolVersion().equals(HttpVersion.HTTP_1_0);

                    if (httpReq.method() == HttpMethod.POST) {
                        // Start decoding HttpContent chunks.
                        // N.B. in case connection is re-used, need to free the previous decoder object.
                        decoder.destroy();
                        decoder = new HttpPostRequestDecoder(factory, httpReq);

                    } else {
                        // Non-POST (probably GET) -- start handling the request
                        requestComplete = true;
                    }

                    if (!httpReq.decoderResult().isSuccess()) {
                        // Malformed HTTP headers
                        throw new BadRequestException(request);
                    }
                }

                // ------------------------------------------------------------------------------
                // Decode HTTP POST body
                // ------------------------------------------------------------------------------

                if (msg instanceof HttpContent && request != null && decoder != null) {
                    HttpContent chunk = (HttpContent) msg;
                    // Offer chunk to decoder (this decreases refcount of chunk, so it doesn't have to
                    // be separately released). Decoder is released after message has been handled.
                    decoder.offer(chunk);

                    try {
                        while (decoder.hasNext()) {
                            InterfaceHttpData data = decoder.next();
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
                        // Apparently decoder.hasNext() doesn't actually work
                    }

                    if (chunk instanceof LastHttpContent) {
                        // This is the last chunk of HTTP content -- handle the request below
                        requestComplete = true;
                    }
                }

                if (!requestComplete) {
                    // Wait for more chunks.
                    // (Since requestComplete is false, calling return here will not call destroyDecoder()
                    // in the finally block, so it will still exist when the next chunk is received.)
                    return;
                }

                if (request == null) {
                    // Got something other than a WebSocket frame or an HttpRequest message (or got a WebSocket frame,
                    // but WebSockets are disabled for this server)
                    throw new BadRequestException();
                }

                // ------------------------------------------------------------------------------
                // Figure out how to handle HTTP request
                // ------------------------------------------------------------------------------

                // All POST chunks have been received (or there are no chunks); ready to start handling the request

                // If this is a hash URI, look up original URI whose served resource was hashed to give this hash URI.
                // We only need to serve the resource at a hash URI once per resource per client, since resources served
                // from hash URIs are indefinitely cached in the browser.
                boolean isHashURL = request.isHashURL();
                reqURLUnhashed = request.getURLPathUnhashed();
                hashKey = request.getURLHashKey();

                // Update some other fields from the request
                acceptEncodingGzip = request.acceptEncodingGzip();
                reqTimestamp = request.getReqReceivedTimeEpochMillis();

                InetSocketAddress requestorSocketAddr = (InetSocketAddress) ctx.channel().remoteAddress();
                if (requestorSocketAddr != null) {
                    InetAddress address = requestorSocketAddr.getAddress();
                    if (address != null) {
                        String hostAddress = address.getHostAddress();
                        request.setRequestor(hostAddress);
                        requestor = hostAddress;
                    }
                }

                isHEAD = request.getMethod() == HttpMethod.HEAD;

                // Force the GET method if HEAD is requested (HEAD with POST doesn't make sense)
                origReqMethod = finalReqMethod = request.getMethod();
                if (isHEAD) {
                    finalReqMethod = HttpMethod.GET;
                    request.setMethod(finalReqMethod);
                }

                // Netty changes the URI of the request to "/bad-request" if the HTTP request was malformed
                if (reqURLUnhashed.equals("/bad-request")) {
                    throw new BadRequestException(request);
                }

                // ------------------------------------------------------------------------------
                // Authenticate user
                // ------------------------------------------------------------------------------

                // Call route handlers until one is able to handle the route,
                // or until we run out of handlers
                Route authorizedRoute = null;
                ArrayList<Route> allRoutes = GribbitServer.siteResources.getAllRoutes();
                for (int i = 0, n = allRoutes.size(); i < n; i++) {
                    Route route = allRoutes.get(i);
                    // If the request URI matches this route path
                    if (route.matches(reqURLUnhashed)) {
                        if (!(request.getMethod() == HttpMethod.GET || request.getMethod() == HttpMethod.POST)) {
                            // Only GET and POST are supported
                            throw new MethodNotAllowedException();

                        } else if ((request.getMethod() == HttpMethod.GET && !route.hasGetMethod())
                                || (request.getMethod() == HttpMethod.POST && !route.hasPostMethod())) {
                            // Tried to call an HTTP method that is not defined for this route
                            throw new MethodNotAllowedException();

                        } else {
                            // Call request.lookupUser() to check the session cookies to see if the user is logged in, 
                            // if the route requires users to be logged in. If auth is required, see if the user can
                            // access the requested route.
                            // Throws a RequestHandlingException if not authorized.
                            route.checkAuth(request);

                            // If we reach here, either authorization is not required for the route, or the user is
                            // logged in and they passed all auth tests. OK to handle the request with this route.
                            authorizedRoute = route;
                        }

                        // URI matches, so don't need to search further URIs
                        break;
                    }
                }

                // ------------------------------------------------------------------------------
                // Complete websocket handshake if requested
                // ------------------------------------------------------------------------------

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

                if (GribbitProperties.ALLOW_WEBSOCKETS //
                        && authorizedRoute != null && response == null //
                        && request.isWebSocketUpgradeRequest() && msg instanceof HttpRequest) {
                    this.webSocketHandler = new WebSocketHandler(ctx, (HttpRequest) msg, request.getOrigin(),
                            request.getQueryParam("_csrf"), request.lookupUser(), authorizedRoute);
                    // Finished handling the websocket upgrade request (or returned an error)
                    return;
                }

                // ------------------------------------------------------------------------------
                // Handle static file requests
                // ------------------------------------------------------------------------------

                // If no error has occurred so far, and no route handler matched the request URI, and this is a
                // GET request, then see if the URI points to a static file resource, and if so, serve the file.
                if (response == null && authorizedRoute == null) {

                    File staticResourceFile = GribbitServer.siteResources.getStaticResource(reqURLUnhashed);
                    if (staticResourceFile == null) {

                        // Neither a route handler nor a static resource matched the request URI.
                        // Return 404 Not Found.
                        throw new NotFoundException(request);

                    } else {

                        // Static file requests can only use GET method
                        if (request.getMethod() != HttpMethod.GET) {
                            throw new MethodNotAllowedException();
                        }

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

                                DefaultHttpResponse httpRes = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                                        HttpResponseStatus.OK);
                                httpRes.headers().add("Server", GribbitServer.SERVER_IDENTIFIER);

                                long fileLength = fileToServe.length();
                                httpRes.headers().set(CONTENT_LENGTH, Long.toString(fileLength));
                                WebUtils.setContentTypeHeaders(httpRes.headers(), staticResourceFile.getPath());

                                // If the file contents have changed since the last time the file was hashed,
                                // schedule the file to be hashed in the background so that future references to the
                                // file's URI in a src/href attribute of served HTML templates will include a hash
                                // URI rather than the original URI for the file, allowing the browser to cache the
                                // file indefinitely until it changes.
                                CacheExtension.updateHashURI(reqURLUnhashed, staticResourceFile);

                                // If file was already cached, and the request URI included the hash key, then this is
                                // the first time this client has fetched this file since the browser cache was last
                                // cleared. Mark this resource as indefinitely cached. If the file is not being served
                                // on a hash URI, then at least set the Last-Modified header, so that if the client
                                // requests the same unmodified resource again on the same non-hash URI, the server can
                                // return Not Modified instead of serving the contents of the file.
                                HttpUtils.setDateAndCacheHeaders(httpRes.headers(), ZonedDateTime.now(),
                                        lastModifiedEpochSeconds, //
                                        hashKey != null ? /* cache indefinitely: */-1
                                                : /* ignored if hashKey == null: */0, hashKey);

                                if (addKeepAliveHeader) {
                                    httpRes.headers().add(CONNECTION, KEEP_ALIVE);
                                }

                                // Write HTTP headers to channel
                                ctx.write(httpRes);

                                // For HEAD requests, don't send the body
                                if (isHEAD) {
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
                                    sendFileFuture = ctx.write(new DefaultFileRegion(fileToServe.getChannel(), 0,
                                            fileLength), ctx.newProgressivePromise());
                                    // Write the end marker
                                    lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                                } else {
                                    // Can't use FileRegions / zero-copy with SSL
                                    sendFileFuture = ctx.write(new HttpChunkedInput(new ChunkedFile(fileToServe, 0,
                                            fileLength, 1)), ctx.newProgressivePromise());
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
                                        if (closeAfterWrite) {
                                            future.channel().close();
                                        }
                                        try {
                                            fileToClose.close();
                                        } catch (IOException e) {
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

                            Log.fine(request.getRequestor() + "\t" + origReqMethod + "\t" + reqURLUnhashed
                                    + "\tfile://" + staticResourceFile.getPath() + "\t" + HttpResponseStatus.OK + "\t"
                                    + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis()) + " msec");

                            // Finished request
                            return;
                        }
                    }
                }

                // ------------------------------------------------------------------------------
                // Handle GET or POST requests
                // ------------------------------------------------------------------------------

                // If an error response hasn't yet been generated and this is a (non-static-file) GET or POST request,
                // then call the get() or post() method for the route handler bound to the request URI to obtain the
                // response object.
                if (response == null && authorizedRoute != null) {

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
                    if (isHashURL) {
                        HashInfo hashInfo = CacheExtension.getHashInfo(reqURLUnhashed);
                        if (hashInfo != null) {
                            long lastModifiedEpochSeconds = hashInfo.getLastModifiedEpochSeconds();
                            timeNow = ZonedDateTime.now();
                            long timeNowEpochSeconds = timeNow.toEpochSecond();

                            long maxAgeSeconds = authorizedRoute.getMaxAgeSeconds();
                            hashKeyRemainingAgeSeconds = lastModifiedEpochSeconds + maxAgeSeconds - timeNowEpochSeconds;

                            if (maxAgeSeconds == 0) {
                                // Content is not hash-cached
                                hashKeyRemainingAgeSeconds = 0;
                            }

                            if (maxAgeSeconds > 0 && hashKeyRemainingAgeSeconds <= 0) {
                                // Resource has expired -- call the route handler to generate a new response rather
                                // than serving a Not Modified response, and schedule the response to be hashed or
                                // re-hashed once the response has been generated.
                                hashTheResponse = true;

                                // Reset the expiry time at the requested number of seconds in the future
                                hashKeyRemainingAgeSeconds = maxAgeSeconds;

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

                    // If the response wasn't just set to "Not Modified" above, serve the request
                    if (response == null) {

                        // -----------------------------------------------------------------
                        // Call the route handler for this request, generating the response
                        // -----------------------------------------------------------------

                        // Call the RestHandler for the route. May throw a RequestHandlingException or other Exception
                        response = authorizedRoute.callHandler(request);
                    }
                }
                if (response == null) {
                    // Should not happen
                    throw new InternalServerErrorException("Didn't generate a response");
                }

            } catch (Exception e) {
                // End the request (in case this exception was thrown before the last HTTP chunk was received),
                // so that the decoder is destroyed in the finally block
                requestComplete = true;

                if (e instanceof RequestHandlingException) {
                    // Get the Response object if a RequestHandlingException was thrown
                    response = ((RequestHandlingException) e).getErrorResponse();
                } else {
                    // Return an Internal Server Error response if an unexpected exception was thrown
                    response = new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                    Log.exception("Exception handling request", e);
                }
            }

            // ------------------------------------------------------------------------------------
            // Serve the HTTP response
            // ------------------------------------------------------------------------------------

            HttpResponseStatus status = response.getStatus();

            boolean closeChannelAfterWrite = closeAfterWrite || (status != HttpResponseStatus.OK
            // TODO: In case of redirects (response.getStatus() == HttpResponseStatus.FOUND), should the channel be closed?
                    && status != HttpResponseStatus.FOUND);

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
                    response.setCookie(new Cookie(Cookie.FLASH_COOKIE_NAME, "/", FlashMessage
                            .toCookieString(flashMessages), 60));
                }
            }

            // Get the content of the response as a byte buffer.
            ByteBuf content = response.getContent(request);
            byte[] contentBytes = content.array();
            String contentType = response.getContentType(request);

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
                    Log.exception("Could not gzip content", e);
                    gzippedContent = Unpooled.EMPTY_BUFFER;
                }
            }

            // Create a FullHttpResponse object that wraps the response status and content
            DefaultFullHttpResponse httpRes = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, //
                    gzippedContent != null ? gzippedContent : content);
            httpRes.headers().set(CONTENT_TYPE, contentType);
            httpRes.headers()
                    .set(CONTENT_LENGTH,
                            Integer.toString(gzippedContent != null ? gzippedContent.readableBytes() : content
                                    .readableBytes()));
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
                    hashKeyRemainingAgeSeconds, hashKey);

            // If the response needs hashing, and the response does not have an error status, then schedule the
            // content of the response for hashing, and store a mapping from the original request URI to the
            // hash URI so that future HTML responses that have src/href attributes that contain this request
            // URI will replace this request URI with the hash URI instead. This will mean the client will
            // fetch that hash URI only once until it expires in the cache, so that on subsequent requests,
            // the linked resource won't even be requested from the server.
            if (hashTheResponse && status == HttpResponseStatus.OK) {
                CacheExtension.updateHashURI(request.getURLPathUnhashed(), content,
                        response.getLastModifiedEpochSeconds());
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
                            String deleteCookieStr = ServerCookieEncoder.encode(Cookie.deleteCookie(cookie)
                                    .toNettyCookie());
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
            Log.fine(requestor + "\t" + origReqMethod
                    + (finalReqMethod == origReqMethod ? "" : "\t=>" + finalReqMethod) + "\t" + reqURLUnhashed + "\t"
                    + status + "\t" + (System.currentTimeMillis() - reqTimestamp) + " msec");
            //        }

        } finally {
            if (requestComplete) {
                // Finished request -- destroy the multipart decoder and remove temporary files
                destroyDecoder();
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // Release HTTP decoder resources, including any file uploads that were received in a POST
        // request and stored in /tmp
        destroyDecoder();

        if (cause instanceof NotSslRecordException) {
            ctx.channel().close();
            return;
        }
        if ("Connection reset by peer".equals(cause.getMessage())) {
            // (No need to log the backtrace in this case)
            // Log.info(cause.getMessage());            
        } else {
            // Log exception with backtrace
            Log.exception("Uncaught exception", cause);
        }
        if (ctx.channel().isActive()) {
            HttpUtils.sendHttpErrorResponse(ctx, null, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR));
        }
    }
}
