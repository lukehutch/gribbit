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
package gribbit.request;

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
import gribbit.response.ErrorResponse;
import gribbit.response.HTMLPageResponse;
import gribbit.response.NotModifiedResponse;
import gribbit.response.RedirectResponse;
import gribbit.response.Response;
import gribbit.response.flashmsg.FlashMessage;
import gribbit.route.AuthAndValidatedEmailRequiredRoute;
import gribbit.route.AuthRequiredRoute;
import gribbit.route.Route;
import gribbit.route.RouteInfo;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.CacheExtension;
import gribbit.util.Log;
import gribbit.util.WebUtils;
import gribbit.util.thirdparty.UTF8;
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
import io.netty.handler.codec.http.HttpHeaders;
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
import java.io.IOException;
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

    // -----------------------------------------------------------------------------------------------------------------

    private void setCacheHeaders(HttpHeaders httpHeaders, long lastModifiedEpochMillis, boolean isHashURI) {
        if (lastModifiedEpochMillis > 0) {
            // Add last modified header to cacheable resources. This is needed because Chrome sends
            // "Cache-Control: max-age=0" when the user types in a URL and hits enter, or hits refresh.
            // In these circumstances, sending back "Cache-Control: public, max-age=31536000" does
            // no good, because the browser has already requested the resource rather than relying on
            // its cache. By setting the last modified header for all cacheable resources, we can
            // at least send "Not Modified" as a response if the resource has not been modified,
            // which doesn't save on roundtrips, but at least saves on re-transferring the resources
            // to the browser when they're already in the browser's cache.
            httpHeaders.set(LAST_MODIFIED,
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochMillis / 1000), ZoneId.of("UTC"))
                            .format(DateTimeFormatter.RFC_1123_DATE_TIME));
        }
        // TODO: add etag header too? see
        // See http://www.mobify.com/blog/beginners-guide-to-http-cache-headers/
        if (isHashURI) {
            // Cache hash URIs for one year
            httpHeaders.set(CACHE_CONTROL, "public, max-age=31536000");
            httpHeaders.set(EXPIRES, ZonedDateTime.now().plusDays(365).format(DateTimeFormatter.RFC_1123_DATE_TIME));
        }

        if (!isHashURI && lastModifiedEpochMillis == 0) {
            // Disable caching for all other resources -- see http://goo.gl/yXGd2x
            httpHeaders.add(CACHE_CONTROL, "no-cache, no-store, must-revalidate");
            httpHeaders.add(PRAGMA, "no-cache");
            httpHeaders.add(EXPIRES, 0);
        }

    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Call the route handler for the given route. */
    private static Response getResponseForRoute(RouteInfo route, Request req, User user) throws Exception {
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

    // -----------------------------------------------------------------------------------------------------------------

    /** Call the given error handler route. */
    private static Response getResponseForErrorHandlerRoute(RouteInfo route, Request req, User user) throws Exception {
        // Temporarily replace the method on the original route with a GET request on the error handler's route
        String origURI = req.getURI();
        HttpMethod origMethod = req.getMethod();
        req.setURI(route.getRoutePath());
        req.setMethod(HttpMethod.GET);
        // Call the error handler
        Response response = getResponseForRoute(route, req, user);
        req.setURI(origURI);
        req.setMethod(origMethod);
        return response;
    }

    // -----------------------------------------------------------------------------------------------------------------

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

                // All POST chunks have been received (or there are no chunks); ready to start handling the request

                String reqURI = request.getURI();

                // If this is a hash URI, look up original URI whose served resource was hashed to give this hash URI.
                // This causes a database hit, but we only need to do this once per resource per client, since resources
                // served from hash URIs are indefinitely cached in the browser.
                boolean isHashURI = CacheExtension.isHashURI(reqURI);
                if (isHashURI) {
                    reqURI = CacheExtension.getOrigURI(reqURI);
                }

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
                RouteInfo authorizedRoute = null;
                boolean redirectAfterLogin = false;
                for (RouteInfo route : GribbitServer.siteResources.getAllRoutes()) {

                    // If the request URI matches this route path
                    if (route.matches(reqURI)) {
                        Class<? extends Route> handler = route.getHandler();

                        if (!(request.getMethod() == HttpMethod.GET || request.getMethod() == HttpMethod.POST)) {

                            // We only support GET and POST at this point
                            Log.error("Unsupported HTTP method " + request.getMethod().name() + " for path " + reqURI);
                            response =
                                    new ErrorResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed");

                        } else if ((request.getMethod() == HttpMethod.GET && !route.hasGetMethod())
                                || (request.getMethod() == HttpMethod.POST && !route.hasPostMethod())) {

                            // Tried to call an HTTP method that is not defined for this route
                            Log.error("HTTP method " + request.getMethod().name() + " not implemented in handler "
                                    + handler.getName());
                            response =
                                    new ErrorResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed");

                        } else if (AuthRequiredRoute.class.isAssignableFrom(handler)) {

                            // This handler requires authentication -- check if user is logged in
                            user = User.getLoggedInUser(request);
                            if (user == null) {

                                // User is not logged in: handle request with OnUnauthorized handler instead
                                response =
                                        getResponseForErrorHandlerRoute(
                                                GribbitServer.siteResources.getUnauthorizedRoute(), request, user);

                                // Redirect the user back to the page they were trying to get to once they do
                                // manage to log in successfully
                                redirectAfterLogin = true;

                            } else if (AuthAndValidatedEmailRequiredRoute.class.isAssignableFrom(handler)
                                    && !user.emailIsValidated()) {

                                // User is logged in, but their email address has not been validated:
                                // handle request with EmailNotValidated handler instead
                                response =
                                        getResponseForErrorHandlerRoute(
                                                GribbitServer.siteResources.getEmailNotValidatedRoute(), request, user);

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
                        response =
                                getResponseForErrorHandlerRoute(GribbitServer.siteResources.getNotFoundRoute(),
                                        request, user);

                    } else {

                        // A static resource matched the request URI, check last-modified timestamp
                        // against the If-Modified-Since header timestamp in the request.
                        long lastModifiedEpochMillis = staticResourceFile.lastModified();
                        String filePath = staticResourceFile.getPath();
                        if (!request.cachedVersionIsOlderThan(lastModifiedEpochMillis)) {
                            // File has not been modified since it was last cached -- return Not Modified
                            response = new NotModifiedResponse(lastModifiedEpochMillis);

                        } else {
                            // If file is newer than what is in the browser cache, or is not in cache, serve the file
                            RandomAccessFile fileToServe = null;
                            try {
                                // Create new RandomAccessFile (which allows us to find file length etc.)
                                fileToServe = new RandomAccessFile(staticResourceFile, "r");

                                // -----------------------------------------
                                // Serve a static file (not authenticated)
                                // -----------------------------------------

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

                                setCacheHeaders(httpRes.headers(), lastModifiedEpochMillis, isHashURI);

                                if (addKeepAliveHeader) {
                                    httpRes.headers().add(CONNECTION, KEEP_ALIVE);
                                }

                                // Write HTTP headers to channel
                                ctx.write(httpRes);

                                // Write file content to channel.
                                // Can add ChannelProgressiveFutureListener to sendFileFuture if we need to track
                                // progress (e.g. to update user's UI over a web socket to show download progress.)
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

                                // Write the end marker and flush the channel
                                ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

                                // Close connection after flush if needed, and close file 
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

                                // Hash the file contents in the background so that the file's URI can include a
                                // hashcode, allowing the browser to cache the file indefinitely until it changes
                                CacheExtension.updateHashURI(reqURI, staticResourceFile);

                                Log.fine(request.getRequestor() + "\t" + origReqMethod + "\t" + reqURI + "\tfile://"
                                        + filePath + "\t" + HttpResponseStatus.OK + "\t"
                                        + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis())
                                        + " msec");

                            } catch (FileNotFoundException e) {

                                // Should not happen, file was just barely found to exist by the getStaticResource()
                                // call above, but if it disappeared between then and now, return 404
                                response =
                                        getResponseForErrorHandlerRoute(GribbitServer.siteResources.getNotFoundRoute(),
                                                request, user);

                            } catch (Exception e) {
                                if (fileToServe != null) {
                                    try {
                                        fileToServe.close();
                                    } catch (IOException e1) {
                                    }
                                }
                                throw new RuntimeException("Exception serving static file", e);
                            }
                        }
                    }
                }

                // ------------------------------------------------------------------------------
                // Handle GET or POST requests
                // ------------------------------------------------------------------------------

                if (isHashURI) {
                    // For hashed *non-file* URIs, the actual last modified timestamp can't be read directly,
                    // so read the last modified timestamp stored in the CacheExtension class, and see if this
                    // is the same age as the version cached in the browser. The important ramification of this
                    // is that when the resource identified by the non-file URI changes, the CacheExtension
                    // class must be notified of that change (including in cases where the database is modified
                    // by another database client), otherwise the web client connected to this web server will
                    // continue to serve old resources.
                    // TODO: fix this by adding a lastModified method to Routes that takes the same params as GET,
                    // but returns in less time?
                    // FIXME: or just call the get method as normal, but check last modified in the response object on return -- and if it has changed, update the cache automatically. Then we can auto-cache these objects too by adding an annotation.
                    // FIXME: this won't reduce the time to read the object from the database, but it will eliminate the need to send the object over the network, because a Not Modified will be returned instead. Not a perfect solution.
                    Long lastModifiedEpochMillis = CacheExtension.getLastModifiedEpochMillis(reqURI);
                    if (lastModifiedEpochMillis != null) {
                        if (!request.cachedVersionIsOlderThan(lastModifiedEpochMillis)) {
                            // File has not been modified since it was last cached -- return Not Modified
                            response = new NotModifiedResponse(lastModifiedEpochMillis);
                        }
                    }
                }

                // If a static file was not served above, and there has been no error response, then handle the request.
                if (response == null && authorizedRoute != null) {
                    // Call the route handler for this request
                    response = getResponseForRoute(authorizedRoute, request, user);
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

                    // Set headers for caching
                    setCacheHeaders(httpRes.headers(), response.getLastModifiedEpochMillis(), isHashURI);

                    if (isHEAD) {
                        // Don't return a body for HEAD requests (but still return the content length,
                        // set in the header above)
                        httpRes.content().clear();
                    }

                    if (redirectAfterLogin) {
                        // Redirect to the requested URI after successful login.
                        // Auth providers should redirect to this cookie's URI, if present, after on successful login. 
                        request.setCookie(new Cookie(Cookie.REDIRECT_AFTER_LOGIN_COOKIE_NAME, reqURI, "/", 300));
                    } else if (response instanceof RedirectResponse) {
                        // Clear the redirect cookie after a redirect has been performed
                        request.deleteCookie(Cookie.REDIRECT_AFTER_LOGIN_COOKIE_NAME);
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
                                    + status + "\t"
                                    + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis()) + " msec";
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

    // -----------------------------------------------------------------------------------------------------------------

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
