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
package gribbit.server;

import static io.netty.handler.codec.http.HttpHeaders.Names.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.DATE;
import static io.netty.handler.codec.http.HttpHeaders.Names.EXPECT;
import static io.netty.handler.codec.http.HttpHeaders.Names.EXPIRES;
import static io.netty.handler.codec.http.HttpHeaders.Names.LAST_MODIFIED;
import static io.netty.handler.codec.http.HttpHeaders.Names.PRAGMA;
import static io.netty.handler.codec.http.HttpHeaders.Names.SET_COOKIE;
import static io.netty.handler.codec.http.HttpHeaders.Values.CLOSE;
import static io.netty.handler.codec.http.HttpHeaders.Values.KEEP_ALIVE;
import gribbit.auth.Cookie;
import gribbit.auth.Cookie.EncodingType;
import gribbit.auth.User;
import gribbit.handler.error.MethodNotAllowed;
import gribbit.server.config.GribbitProperties;
import gribbit.server.response.ErrorResponse;
import gribbit.server.response.HTMLPageResponse;
import gribbit.server.response.NotModifiedResponse;
import gribbit.server.response.Response;
import gribbit.server.response.flashmsg.FlashMessage;
import gribbit.thirdparty.UTF8;
import gribbit.util.Log;
import gribbit.util.WebUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
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
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map.Entry;

public class HttpRequestHandler extends SimpleChannelInboundHandler<HttpObject> {

    private Request request;

    private boolean closeAfterWrite = false;
    private boolean addKeepAliveHeader = false;

    // -----------------------------------------------------------------------------------------------------

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

    // -----------------------------------------------------------------------------------------------------

    private HttpPostRequestDecoder decoder;

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

    // -----------------------------------------------------------------------------------------------------

    /** Decode an HTTP message. */
    @Override
    public void messageReceived(ChannelHandlerContext ctx, HttpObject msg) throws Exception {

        // ------------------------------------------------------------------------------
        // Decode HTTP headers
        // ------------------------------------------------------------------------------

        boolean requestComplete = false;
        if (msg instanceof HttpRequest) {
            HttpRequest httpReq = (HttpRequest) msg;

            // System.out.println("REQUEST: " + httpReq.getUri());

            // Start a new request
            request = new Request(httpReq);

            // Handle expect-100-continue
            boolean expect100Continue = false;
            for (String h : httpReq.headers().getAll(EXPECT)) {
                if (h.equals("100-continue")) {
                    expect100Continue = true;
                    break;
                }
            }
            if (expect100Continue) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
                // TODO: is it correct to return here and wait for another message?
                return;
            }

            closeAfterWrite =
                    httpReq.headers().contains(CONNECTION, CLOSE, true) || //
                            (httpReq.getProtocolVersion().equals(HttpVersion.HTTP_1_0) && !httpReq.headers().contains(
                                    CONNECTION, KEEP_ALIVE, true));
            addKeepAliveHeader = !closeAfterWrite && httpReq.getProtocolVersion().equals(HttpVersion.HTTP_1_0);

            if (httpReq.getMethod() == HttpMethod.POST) {
                // Start decoding HttpContent chunks
                destroyDecoder();
                decoder = new HttpPostRequestDecoder(factory, httpReq);

            } else {
                // Non-POST (probably GET), start handling the request
                requestComplete = true;
            }
        }

        // ------------------------------------------------------------------------------
        // Decode HTTP POST body
        // ------------------------------------------------------------------------------

        try {
            if (msg instanceof HttpContent && decoder != null) {
                HttpContent chunk = (HttpContent) msg;
                decoder.offer(chunk);

                try {
                    while (decoder.hasNext()) {
                        InterfaceHttpData data = decoder.next();
                        if (data != null) {
                            HttpDataType httpDataType = data.getHttpDataType();
                            if (httpDataType == HttpDataType.Attribute) {
                                try {
                                    Attribute attribute = (Attribute) data;
                                    request.setPostParam(attribute.getName(), attribute.getString(attribute
                                            .getCharset() == null ? Charset.forName("UTF-8") : attribute.getCharset()));
                                } finally {
                                    // Decrease refcount, freeing data
                                    data.release();
                                }

                            } else if (httpDataType == HttpDataType.FileUpload) {
                                FileUpload fileUpload = (FileUpload) data;
                                // TODO consider imposing size limit and returning 413 (Request Entity Too
                                // Large) once the amount of data that has been sent hits the limit
                                if (fileUpload.isCompleted()) {
                                    // Save the FileUpload object (which wraps a DiskFileUpload in /tmp).
                                    // Need to release this resource later.
                                    request.setPostFileUploadParam(fileUpload.getName(), fileUpload);
                                }
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

            // ------------------------------------------------------------------------------
            // Figure out how to handle HTTP request
            // ------------------------------------------------------------------------------

            if (requestComplete) {
                // All POST chunks received (or no chunks); ready to start handling the request

                String reqURI = request.getURI();

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

                // ------------------------------------------------------------------------------
                // Authenticate user
                // ------------------------------------------------------------------------------

                // The response object generated by a RestHandler
                Response response = null;

                // Call route handlers until one is able to handle the route,
                // or until we run out of handlers
                User user = null;
                Route authorizedRoute = null;
                for (Route route : GribbitServer.siteResources.getAllRoutes()) {

                    // If the request URI matches this route path
                    if (route.matches(reqURI)) {
                        Class<? extends RestHandler> handler = route.getHandler();

                        if (!(request.getMethod() == HttpMethod.GET || request.getMethod() == HttpMethod.POST)) {

                            // We only support GET and POST at this point
                            Log.error("Unsupported HTTP method " + request.getMethod().name() + " for path " + reqURI);
                            response =
                                    getErrorResponse(
                                            GribbitServer.siteResources.routeForHandler(MethodNotAllowed.class),
                                            request, user);

                        } else if ((request.getMethod() == HttpMethod.GET && !route.hasGetMethod())
                                || (request.getMethod() == HttpMethod.POST && !route.hasPostMethod())) {

                            // Tried to call an HTTP method that is not defined for this route
                            Log.error("HTTP method " + request.getMethod().name() + " not implemented in handler "
                                    + handler.getName());
                            response =
                                    getErrorResponse(
                                            GribbitServer.siteResources.routeForHandler(MethodNotAllowed.class),
                                            request, user);

                        } else if (RestHandler.AuthRequired.class.isAssignableFrom(handler)) {

                            // This handler requires authentication -- check if user is logged in
                            user = User.getLoggedInUser(request);
                            if (user == null) {

                                // User is not logged in: handle request with OnUnauthorized handler instead
                                response =
                                        getErrorResponse(GribbitServer.siteResources.getUnauthorizedRoute(), request,
                                                user);
                                
                                // Redirect the user back to the page they were trying to get to once they do manage to
                                // log in successfully
                                request.setCookie(new Cookie(Cookie.REDIRECT_ORIGIN_COOKIE_NAME, reqURI, "/", 300));

                            } else if (RestHandler.AuthAndValidatedEmailRequired.class.isAssignableFrom(handler)
                                    && !user.emailIsValidated()) {

                                // User is logged in, but their email address has not been validated:
                                // handle request with EmailNotValidated handler instead
                                response =
                                        getErrorResponse(GribbitServer.siteResources.getEmailNotValidatedRoute(),
                                                request, user);

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
                // Handle static file requests
                // ------------------------------------------------------------------------------

                // If no error has occurred so far, and no route handler matched the request URI, and this is a
                // GET request, then see if the URI points to a static file resource, and if so, serve the file.
                if (response == null && authorizedRoute == null && request.getMethod() == HttpMethod.GET) {
                    File staticResourceFile = GribbitServer.siteResources.getStaticResource(reqURI);
                    if (staticResourceFile == null) {

                        // Neither a route handler nor a static resource matched the request URI => 404
                        response = getErrorResponse(GribbitServer.siteResources.getNotFoundRoute(), request, user);

                    } else {

                        // A static resource matched the request URI, check last-modified timestamp
                        // against the If-Modified-Since header timestamp in the request. Unfortunately
                        // the resolution of the last modified timestamp is one second.
                        long lastModifiedEpochSecond = staticResourceFile.lastModified() / 1000;
                        String filePath = staticResourceFile.getPath();
                        if (!request.cachedVersionIsOlderThan(lastModifiedEpochSecond)) {

                            // File has not been modified since it was last cached -- return Not Modified
                            response = new NotModifiedResponse(lastModifiedEpochSecond);

                            // Even in Not Modified responses, we have to send the Last-Modified header 
                            response.setLastModifiedEpochSecond(lastModifiedEpochSecond);

                        } else {
                            // If file is newer than what is in browser cache, or is not in cache
                            RandomAccessFile fileToServe = null;
                            try {

                                // Create new RandomAccessFile (which allows us to find file length etc.)
                                fileToServe = new RandomAccessFile(staticResourceFile, "r");

                            } catch (FileNotFoundException e) {

                                // Should not happen, file was just barely found to exist by the getStaticResource()
                                // call above, but if it disappeared between then and now, return 404
                                response =
                                        getErrorResponse(GribbitServer.siteResources.getNotFoundRoute(), request, user);

                            }
                            if (fileToServe != null) {

                                // ------------------------------------------------------------------------------
                                // Handle static file requests (these are not authenticated)
                                // ------------------------------------------------------------------------------

                                DefaultHttpResponse httpRes =
                                        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                                httpRes.headers().add("Server", GribbitServer.SERVER_IDENTIFIER);
                                httpRes.headers().set(DATE,
                                        ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));

                                long fileLength = fileToServe.length();
                                httpRes.headers().set(CONTENT_LENGTH, fileLength);

                                String contentType = "application/octet-stream";
                                int dotIdx = filePath.lastIndexOf('.'), slashIdx =
                                        filePath.lastIndexOf(File.separatorChar);
                                if (dotIdx > 0 && slashIdx < dotIdx) {
                                    String ext = filePath.substring(dotIdx + 1).toLowerCase();
                                    String mimeType = WebUtils.EXTENSION_TO_MIMETYPE.get(ext);
                                    if (mimeType != null) {
                                        contentType = mimeType;
                                    }
                                }
                                httpRes.headers().set(CONTENT_TYPE, contentType);

                                // TODO: Are there other headers that should be set to ensure the file is cached
                                // TODO: forever as long as the last modified date doesn't change?
                                httpRes.headers().set(
                                        LAST_MODIFIED,
                                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochSecond),
                                                ZoneId.of("UTC")).format(DateTimeFormatter.RFC_1123_DATE_TIME));

                                if (addKeepAliveHeader) {
                                    httpRes.headers().add(CONNECTION, KEEP_ALIVE);
                                }

                                // Write headers
                                ctx.write(httpRes);

                                // Write file content
                                @SuppressWarnings("unused")
                                ChannelFuture sendFileFuture;
                                if (ctx.pipeline().get(SslHandler.class) == null) {
                                    sendFileFuture =
                                            ctx.write(new DefaultFileRegion(fileToServe.getChannel(), 0, fileLength),
                                                    ctx.newProgressivePromise());
                                } else {
                                    // Can't use FileRegions / zero-copy with SSL
                                    sendFileFuture =
                                            ctx.write(new HttpChunkedInputTEMP(new ChunkedFile(fileToServe, 0,
                                                    fileLength, 8192)), ctx.newProgressivePromise());
                                }

                                // Flush write, write the end marker, and close channel if needed
                                ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                                if (closeAfterWrite) {
                                    // Can add ChannelProgressiveFutureListener to sendFileFuture if we need to track
                                    // progress (e.g. to update user's UI over a web socket to show download progress?),
                                    // but for now, just close connection after flush 
                                    lastContentFuture.addListener(ChannelFutureListener.CLOSE);
                                }

                                // Invariant: routeToUse == null && response == null

                                Log.fine(request.getRequestor() + "\t" + origReqMethod + "\t" + reqURI + "\tfile://"
                                        + filePath + "\t" + HttpResponseStatus.OK + "\t"
                                        + (System.currentTimeMillis() - request.getReqReceivedTimeMillis()) + " msec");
                            }
                        }
                    }
                }

                // ------------------------------------------------------------------------------
                // Handle GET or POST requests
                // ------------------------------------------------------------------------------

                // If a static file was not served above, and there has been no error response, then handle the request.
                if (response == null && authorizedRoute != null) {
                    // Call the route handler for this request
                    response = getResponse(authorizedRoute, request, user);
                }

                // ----------------------------------------------------------------------------------
                // Serve an HTTP result (except in the case of static files, they were served above)
                // ----------------------------------------------------------------------------------

                // If we have a non-null response (i.e. if we didn't already serve a static file), then turn the
                // Response object into an HttpResponse object and serve it to the user over Netty.
                if (response != null) {

                    // Handle flash messages
                    if (response instanceof HTMLPageResponse) {
                        // Add flash messages to response template, if any
                        ArrayList<FlashMessage> flashMessages = request.getFlashMessages();
                        if (flashMessages != null) {
                            ((HTMLPageResponse) response).setFlashMessages(flashMessages);
                            // Clear pending flash messages
                            request.deleteCookie(Cookie.FLASH_COOKIE_NAME);
                        }
                    } else {
                        // If flash messages were produced while generating response, but the response type
                        // is not an HTMLPageResponse, store them in a cookie so they will show up next time
                        // there is an HTMLPageResponse-typed response generated
                        String pendingFlashMessagesStr = request.getFlashMessageCookieString();
                        if (pendingFlashMessagesStr != null) {
                            request.setCookie(new Cookie(Cookie.FLASH_COOKIE_NAME, pendingFlashMessagesStr, "/", 60,
                                    EncodingType.PLAIN));
                        }
                    }

                    // Get the content of the response as a byte buffer.
                    // If "?_getmodel=1" is appended to the request URL of an request that would normally return HTML,
                    // then the data model backing the response will be returned as JSON instead of rendered HTML.
                    ByteBuf content =
                            response.getContent(user, GribbitProperties.ALLOW_GET_MODEL && request.isGetModelRequest());

                    // Create a FullHttpResponse object that wraps the response status and content
                    DefaultFullHttpResponse httpRes =
                            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, response.getStatus(), content);

                    httpRes.headers().set(CONTENT_LENGTH, content.readableBytes());
                    httpRes.headers().set(CONTENT_TYPE, response.getMimeType());

                    httpRes.headers().add("Server", GribbitServer.SERVER_IDENTIFIER);
                    httpRes.headers().set(DATE, ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));

                    long lastModified = response.getLastModifiedEpochSecond();
                    if (lastModified > 0) {
                        // Cache resources that don't change (e.g. the vulcanized Polymer resources).
                        // Not Modified responses also need to set this header.
                        // TODO: Are there other headers that should be set to ensure the file is cached
                        // TODO: as long as the last modified date doesn't change?
                        httpRes.headers().set(LAST_MODIFIED,
                                ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModified), //
                                        ZoneId.of("UTC")).format(DateTimeFormatter.RFC_1123_DATE_TIME));
                    } else {
                        // Disable caching -- see http://goo.gl/yXGd2x
                        httpRes.headers().add(CACHE_CONTROL, "no-cache, no-store, must-revalidate");
                        httpRes.headers().add(PRAGMA, "no-cache");
                        httpRes.headers().add(EXPIRES, 0);
                    }

                    if (isHEAD) {
                        // Don't return a body for HEAD requests (but still return the content length,
                        // set in the header above)
                        httpRes.content().clear();
                    }

                    // Transfer cookies from the request to the response. Some of these may have been modified
                    // while serving the request, including with expired cookies (causing the cookie to be deleted)
                    Collection<Cookie> cookies = request.getCookies();
                    if (cookies != null) {
                        for (Cookie cookie : cookies) {
                            // System.out.println(routePath + " " + cookie.toString());
                            httpRes.headers().add(SET_COOKIE, ServerCookieEncoder.encode(cookie.getNettyCookie()));
                        }
                    }

                    // Add any custom headers from the Response object
                    if (response.getCustomHeaders() != null) {
                        for (Entry<String, String> header : response.getCustomHeaders().entrySet()) {
                            httpRes.headers().add(header.getKey(), header.getValue());
                        }
                    }

                    // Close the connection after serving the response if the response status is anything other than OK
                    if (addKeepAliveHeader && httpRes.getStatus() == HttpResponseStatus.OK) {
                        httpRes.headers().add(CONNECTION, KEEP_ALIVE);
                    }

                    if (ctx.channel().isOpen()) {
                        // Check Channel.isWritable() to prevent OutOfMemoryError,
                        // see http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#10.0
                        long startTime = System.currentTimeMillis();
                        while (!ctx.channel().isWritable() && (System.currentTimeMillis() - startTime < 5000)) {
                            // TODO: replace this spinlock (usually channel is immediately writeable;
                            // is this even needed?)
                        }
                        if (ctx.channel().isWritable()) {
                            // Write the ByteBuffer returned by httpRes.content() back into the pipeline
                            // See http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#4.0
                            ChannelFuture future = ctx.channel().writeAndFlush(httpRes);

                            // Close the connection after the write operation is done if necessary.
                            // TODO: Apache closes KeepAlive connections after a few seconds,
                            // see http://en.wikipedia.org/wiki/HTTP_persistent_connection
                            // TODO: implement a stale connection tracker
                            if (closeAfterWrite || httpRes.getStatus() != HttpResponseStatus.OK) {
                                future.addListener(ChannelFutureListener.CLOSE);
                            }

                        } else {
                            // Tried for a period of time but could not send response; close channel
                            ctx.channel().close();
                            throw new RuntimeException("Could not send response after repeated attempts");
                        }
                    } else {
                        // Client already closed the connection, nothing can be sent
                        // Log.info("Channel closed by client before response sent");
                    }

                    // Log request
                    HttpResponseStatus status = response.getStatus();
                    String logMsg =
                            request.getRequestor() + "\t" + origReqMethod + "\t" + reqURI
                                    + (request.getMethod() == origReqMethod ? "" : "\t" + request.getMethod()) + "\t"
                                    + status + "\t" + (System.currentTimeMillis() - request.getReqReceivedTimeMillis())
                                    + " msec";
                    if (status == HttpResponseStatus.OK || status == HttpResponseStatus.NOT_MODIFIED
                            || status == HttpResponseStatus.FOUND || (status == HttpResponseStatus.NOT_FOUND //
                            && (reqURI.equals("favicon.ico") || reqURI.endsWith("/favicon.ico")))) {
                        // Log at level "fine" for non-errors, or 404 for favicon
                        Log.fine(logMsg);
                    } else {
                        // Log at level "warning" for errors, or 404 for non-favicon
                        Log.warningWithoutCallerRef(logMsg);
                    }
                }

                // Finished request -- destroy the multipart decoder and remove temporary files
                destroyDecoder();
            }

        } catch (Exception e) {
            // Release HTTP decoder resources, including any file uploads that were received in a POST
            // request and stored in /tmp
            destroyDecoder();
            throw new RuntimeException(e);
        }
    }

    /** Call the route handler for the given route. */
    private static Response getResponse(Route route, Request req, User user) throws Exception {
        Response response;
        try {
            // Call the RestHandler for the route
            response = route.callHandler(req, user);

        } catch (Exception e) {
            Log.exception("Exception while handling URI " + req.getURI(), e);
            try {
                // Call Internal Server Error handler on exception
                response = GribbitServer.siteResources.getInternalServerErrorRoute().callHandler(req, user);
            } catch (Exception e1) {
                // Fallback in case there's an exception in the Internal Server Error handler
                Log.exception("Error in internal server error handler while handling URI " + req.getURI(), e1);
                response = new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
            }
        }
        return response;
    }

    /** Call the given error handler route. */
    private static Response getErrorResponse(Route route, Request req, User user) throws Exception {
        // Temporarily replace the method on the original route with a GET request on the error handler's route
        String origURI = req.getURI();
        HttpMethod origMethod = req.getMethod();
        req.setURI(route.getRoutePath());
        req.setMethod(HttpMethod.GET);
        // Call the error handler
        Response response = getResponse(route, req, user);
        req.setURI(origURI);
        req.setMethod(origMethod);
        return response;
    }

    // -----------------------------------------------------------------------------------------------------

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if ("Connection reset by peer".equals(cause.getMessage())) {
            // (No need to log the backtrace in this case)
            // Log.info(cause.getMessage());            
        } else {
            // Log exception with backtrace
            Log.exception("Uncaught exception", cause);
        }
        if (ctx.channel().isActive()) {
            String message = "Internal server error";
            ByteBuf contentByteBuf = Unpooled.wrappedBuffer(UTF8.stringToUTF8(message));
            DefaultFullHttpResponse httpRes =
                    new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                            contentByteBuf);
            httpRes.headers().set(CONTENT_LENGTH, contentByteBuf.readableBytes());
            httpRes.headers().set(CONTENT_TYPE, "text/plain;charset=utf-8");
            ctx.writeAndFlush(httpRes);
            ctx.channel().close();
        }
    }
}
