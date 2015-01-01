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

import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import gribbit.auth.User;
import gribbit.request.Request;
import gribbit.response.Response;
import gribbit.response.exception.BadRequestException;
import gribbit.response.exception.ExceptionResponse;
import gribbit.response.exception.InternalServerErrorException;
import gribbit.response.exception.MethodNotAllowedException;
import gribbit.response.exception.NotFoundException;
import gribbit.response.exception.NotModifiedException;
import gribbit.response.exception.UnauthorizedEmailNotValidatedException;
import gribbit.response.exception.UnauthorizedException;
import gribbit.route.RouteHandler;
import gribbit.route.RouteHandlerAuthAndValidatedEmailRequired;
import gribbit.route.RouteHandlerAuthRequired;
import gribbit.route.RouteInfo;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.CacheExtension;
import gribbit.server.siteresources.CacheExtension.HashInfo;
import gribbit.util.Log;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderUtil;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class HttpRequestHandler extends SimpleChannelInboundHandler<Object> {

    private Request request;

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

        boolean requestComplete = false;
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
                    ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE,
                            Unpooled.EMPTY_BUFFER));
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
                    throw new BadRequestException();
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
                                        throw new BadRequestException();
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

            String origReqURI = request.getURI();

            // If this is a hash URI, look up original URI whose served resource was hashed to give this hash URI.
            // We only need to serve the resource at a hash URI once per resource per client, since resources served
            // from hash URIs are indefinitely cached in the browser.
            String hashKey = CacheExtension.getHashKey(origReqURI);
            boolean isHashURI = hashKey != null;
            String reqURI = isHashURI ? CacheExtension.getOrigURI(origReqURI) : origReqURI;

            InetSocketAddress requestor = (InetSocketAddress) ctx.channel().remoteAddress();
            if (requestor != null) {
                InetAddress address = requestor.getAddress();
                if (address != null) {
                    request.setRequestor(address.getHostAddress());
                }
            }

            boolean isHEAD = request.getMethod() == HttpMethod.HEAD;

            // Run the GET method if HEAD is requested, just don't return a body.
            HttpMethod origReqMethod = request.getMethod();
            if (isHEAD) {
                request.setMethod(HttpMethod.GET);
            }

            // Netty changes the URI of the request to "/bad-request" if the HTTP request was malformed
            if (reqURI.equals("/bad-request")) {
                throw new BadRequestException();
            }

            // ------------------------------------------------------------------------------
            // Authenticate user
            // ------------------------------------------------------------------------------

            // The response object generated by a RestHandler
            Response response = null;

            // Call route handlers until one is able to handle the route,
            // or until we run out of handlers
            User user = null;
            RouteInfo authorizedRoute = null;
            ArrayList<RouteInfo> allRoutes = GribbitServer.siteResources.getAllRoutes();
            for (int i = 0, n = allRoutes.size(); i < n; i++) {
                RouteInfo route = allRoutes.get(i);
                // If the request URI matches this route path
                if (route.matches(reqURI)) {
                    Class<? extends RouteHandler> handler = route.getHandler();

                    if (!(request.getMethod() == HttpMethod.GET || request.getMethod() == HttpMethod.POST)) {

                        // We only support GET and POST at this point
                        throw new MethodNotAllowedException();

                    } else if ((request.getMethod() == HttpMethod.GET && !route.hasGetMethod())
                            || (request.getMethod() == HttpMethod.POST && !route.hasPostMethod())) {

                        // Tried to call an HTTP method that is not defined for this route
                        throw new MethodNotAllowedException();

                    } else if (RouteHandlerAuthRequired.class.isAssignableFrom(handler)) {

                        // This handler requires authentication -- check if user is logged in
                        user = User.getLoggedInUser(request);
                        if (user == null) {

                            // User is not logged in, and route requires them to be
                            throw new UnauthorizedException(reqURI);

                        } else if (RouteHandlerAuthAndValidatedEmailRequired.class.isAssignableFrom(handler)
                                && !user.emailIsValidated()) {

                            // User is logged in, but their email address has not yet been validated
                            throw new UnauthorizedEmailNotValidatedException(reqURI);

                        } else {

                            // Authorization required and user logged in: OK to handle request
                            // with this route
                            authorizedRoute = route;
                        }
                    } else {

                        // Authorization not required -- OK to handle request with this route
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

            // TODO: Read WS routes from class annotations to see if WS is allowed?
            // TODO: Or always allow WS connections so that GET/POST can be submitted via WS?
            // TODO: Throttle the number of websockets requests per second per user (and have one pool
            // TODO: of requests for anonymous users); limit the total number of websockets that may
            // TODO: be opened by one user

            if (GribbitProperties.ALLOW_WEBSOCKETS //
                    && authorizedRoute != null && response == null //
                    && request.isWebSocketUpgradeRequest() && msg instanceof HttpRequest) {
                this.webSocketHandler =
                        new WebSocketHandler(ctx, (HttpRequest) msg, request.getOrigin(),
                                request.getQueryParam("_csrf"), user, authorizedRoute);
                // Finished handling the websocket upgrade request (or returned an error)
                return;
            }

            // ------------------------------------------------------------------------------
            // Handle static file requests
            // ------------------------------------------------------------------------------

            // If no error has occurred so far, and no route handler matched the request URI, and this is a
            // GET request, then see if the URI points to a static file resource, and if so, serve the file.
            if (response == null && authorizedRoute == null) {

                File staticResourceFile = GribbitServer.siteResources.getStaticResource(reqURI);
                if (staticResourceFile == null) {

                    // Neither a route handler nor a static resource matched the request URI.
                    // Return 404 Not Found.
                    throw new NotFoundException(reqURI);

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
                        HttpSendStaticFile.sendStaticFile(reqURI, isHEAD, hashKey, staticResourceFile,
                                lastModifiedEpochSeconds, addKeepAliveHeader, closeAfterWrite, ctx);

                        Log.fine(request.getRequestor() + "\t" + origReqMethod + "\t" + reqURI + "\tfile://"
                                + staticResourceFile.getPath() + "\t" + HttpResponseStatus.OK + "\t"
                                + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis()) + " msec");

                        // Finished request
                        return;
                    }
                }
            }

            // ------------------------------------------------------------------------------
            // Handle GET or POST requests
            // ------------------------------------------------------------------------------

            ZonedDateTime timeNow = null;

            // If an error response hasn't yet been generated and this is a (non-static-file) GET or POST request,
            // then call the get() or post() method for the route handler bound to the request URI to obtain the
            // response object.
            boolean hashTheResponse = false;
            long hashKeyRemainingAgeSeconds = 0;
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
                if (isHashURI) {
                    HashInfo hashInfo = CacheExtension.getHashInfo(reqURI);
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

                    try {
                        // Call the RestHandler for the route
                        response = authorizedRoute.callHandler(request, user);

                    } catch (ExceptionResponse e) {

                        Log.info("Caught method exception response " + e.getClass().getSimpleName() + " for " + reqURI);

                        // If there was an ExceptionResponse type exception, use the provided response object
                        // as the response from the handler
                        response = e.getResponse();

                    } catch (Exception e) {
                        // FIXME: search for all instances of RuntimeException and throw InternalServerErrorException instead
                        throw new InternalServerErrorException("Exception while handling URI " + request.getURI(), e);
                    }
                }
            }
            if (response == null) {
                // Should not happen
                throw new InternalServerErrorException("Didn't generate a response");
            }

            // ------------------------------------------------------------------------------------
            // Serve an HTTP response
            // ------------------------------------------------------------------------------------

            // Turn the Response object into an HttpResponse object and serve it to the user over Netty.
            if (timeNow == null) {
                timeNow = ZonedDateTime.now();
            }

            // Serve the response to the client 
            HttpSendResponse.sendResponse(reqURI, request, response, isHEAD, request.acceptEncodingGzip(), timeNow,
                    hashTheResponse, hashKeyRemainingAgeSeconds, hashKey, addKeepAliveHeader, closeAfterWrite, ctx);

            // Log the request and response
            HttpResponseStatus status = response.getStatus();
            String logMsg =
                    request.getRequestor() + "\t" + origReqMethod + "\t" + reqURI
                            + (request.getMethod() == origReqMethod ? "" : "\t" + request.getMethod()) + "\t" + status
                            + "\t" + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis()) + " msec";
            if (status == HttpResponseStatus.OK //
                    || status == HttpResponseStatus.NOT_MODIFIED //
                    || status == HttpResponseStatus.FOUND //
                    || (status == HttpResponseStatus.NOT_FOUND //
                    && (reqURI.equals("favicon.ico") || reqURI.endsWith("/favicon.ico")))) {
                // Log at level "fine" for non-errors, or 404 for favicon
                Log.fine(logMsg);
            } else {
                // Log at level "warning" for errors, or 404 for non-favicon
                Log.warningWithoutCallerRef(logMsg);
            }

        } catch (ExceptionResponse e) {

            // Call destroyDecoder() in the finally block
            requestComplete = true;

            if (request == null) {
                // Didn't get an HttpRequest message
                HttpUtils.sendHttpErrorResponse(ctx, null, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.BAD_REQUEST));

                return;

            } else {
                String reqURI = request.getURI();

                // Get the Response object generated by the ExceptionResponse that was thrown
                Response exceptionResponse = e.getResponse();

                HttpResponseStatus status = exceptionResponse.getStatus();
                boolean isFavicoReq = reqURI.equals("favicon.ico") || reqURI.endsWith("/favicon.ico");
                if (status == HttpResponseStatus.OK //
                        || status == HttpResponseStatus.NOT_MODIFIED //
                        || status == HttpResponseStatus.FOUND //
                        || (status == HttpResponseStatus.NOT_FOUND && !isFavicoReq)) {
                    Log.fine(request.getRequestor() + "\t" + request.getMethod() + "\t" + reqURI + "\t" + status + "\t"
                            + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis()) + " msec");
                } else {
                    Log.info(request.getRequestor() + "\t" + request.getMethod() + "\t" + reqURI + "\t" + status + "\t"
                            + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis()) + " msec");
                }
                Log.info("Caught exception response " + e.getClass().getSimpleName() + " for " + request.getURI());

                boolean closeChannelAfterWrite = closeAfterWrite //
                // TODO: For redirects, don't close channel?
                ;//|| exceptionResponse.getStatus() != HttpResponseStatus.FOUND;

                // Send the exception response
                HttpSendResponse.sendResponse(request.getURI(), request, exceptionResponse, /* isHEAD = */false,
                /* acceptEncodingGzip = */false, ZonedDateTime.now(),
                /* hashTheResponse = */false, /* hashKeyRemainingAgeSeconds = */0,
                /* hashKey = */null, addKeepAliveHeader, /* closeAfterWrite = */closeChannelAfterWrite, ctx);

                return;
            }

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
