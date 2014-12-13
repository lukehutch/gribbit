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
import gribbit.auth.CSRF;
import gribbit.auth.Cookie;
import gribbit.auth.Cookie.EncodingType;
import gribbit.auth.User;
import gribbit.handler.error.MethodNotAllowed;
import gribbit.handler.error.NotModified;
import gribbit.server.config.GribbitProperties;
import gribbit.util.Log;
import gribbit.util.StringUtils;
import gribbit.util.UTF8;
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
import java.util.logging.Level;

public class HttpRequestHandler extends SimpleChannelInboundHandler<HttpObject> {

    private Request req;

    private boolean closeAfterWrite = false;
    private boolean addKeepAliveHeader = false;

    // ----------------------------------------------------------------------------------------------------------------------------------

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

    // ----------------------------------------------------------------------------------------------------------------------------------

    private HttpPostRequestDecoder decoder;

    private void destroyDecoder() {
        if (req != null) {
            try {
                // Release any temporary files
                req.releasePostFileUploadParams();
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

    // ----------------------------------------------------------------------------------------------------------------------------------

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
            req = new Request(httpReq);

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

            closeAfterWrite = httpReq.headers().contains(CONNECTION, CLOSE, true) || //
                    (httpReq.getProtocolVersion().equals(HttpVersion.HTTP_1_0) && !httpReq.headers().contains(CONNECTION, KEEP_ALIVE, true));
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
                                    req.setPostParam(attribute.getName(), attribute.getString(attribute.getCharset() == null ? Charset.forName("UTF-8") : attribute.getCharset()));
                                } finally {
                                    // Decrease refcount, freeing data
                                    data.release();
                                }

                            } else if (httpDataType == HttpDataType.FileUpload) {
                                FileUpload fileUpload = (FileUpload) data;
                                // TODO consider imposing size limit and returning 413 (Request Entity Too Large) once the amount of data that has been sent hits the limit
                                if (fileUpload.isCompleted()) {
                                    // Save the FileUpload object (which wraps a DiskFileUpload in /tmp).
                                    // Need to release this resource later.
                                    req.setPostFileUploadParam(fileUpload.getName(), fileUpload);
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

                String reqURI = req.getURI();
                HttpMethod reqMethod = req.getMethod();
                HttpMethod origReqMethod = reqMethod;

                InetSocketAddress requestor = (InetSocketAddress) ctx.channel().remoteAddress();
                if (requestor != null) {
                    InetAddress address = requestor.getAddress();
                    if (address != null) {
                        req.setRequestor(address.getHostAddress());
                    }
                }

                boolean isHEAD = reqMethod == HttpMethod.HEAD;

                // Run the GET method if HEAD is requested, just don't return a body.
                if (isHEAD) {
                    reqMethod = HttpMethod.GET;
                }

                // Default to 404 handler if we can't find a route to handle this URI
                Route routeToUse = null;

                // Call route handlers until one is able to handle the route, or until we run out of handlers
                User user = null;
                for (Route route : GribbitServer.siteResources.getAllRoutes()) {

                    // If the request URI matches this route path
                    if (route.matches(reqURI)) {
                        Class<? extends RestHandler> handler = route.getHandler();

                        if (!(reqMethod == HttpMethod.GET || reqMethod == HttpMethod.POST)) {

                            // We only support GET and POST at this point
                            Log.error("Unsupported HTTP method " + reqMethod.name() + " for path " + reqURI);
                            routeToUse = GribbitServer.siteResources.routeForHandler(MethodNotAllowed.class);
                            reqMethod = HttpMethod.GET;

                        } else if ((reqMethod == HttpMethod.GET && !route.hasGetMethod()) || (reqMethod == HttpMethod.POST && !route.hasPostMethod())) {

                            // Tried to call an HTTP method that is not defined for this route
                            Log.error("HTTP method " + reqMethod.name() + " not implemented in handler " + handler.getName());
                            routeToUse = GribbitServer.siteResources.routeForHandler(MethodNotAllowed.class);
                            reqMethod = HttpMethod.GET;

                        } else if (RestHandler.AuthRequired.class.isAssignableFrom(handler)) {

                            // This handler requires authentication -- check if user is logged in
                            user = User.getLoggedInUser(req);
                            if (user == null) {

                                // User is not logged in -- handle request with OnUnauthorized handler instead
                                routeToUse = GribbitServer.siteResources.getUnauthorizedRoute();
                                reqMethod = HttpMethod.GET;

                            } else if (RestHandler.AuthAndValidatedEmailRequired.class.isAssignableFrom(handler) && !user.emailIsValidated()) {

                                // User is logged in, but their email address has not been validated -- handle request with EmailNotValidated handler instead
                                routeToUse = GribbitServer.siteResources.getEmailNotValidatedRoute();
                                reqMethod = HttpMethod.GET;

                            } else {

                                // Authorization required and user logged in -- OK to handle request with this route
                                routeToUse = route;
                            }
                        } else {

                            // Authorization not required -- OK to handle request with this route
                            routeToUse = route;
                        }

                        // URI matches, so don't need to search further URIs
                        break;
                    }
                }

                // If no route handler matched the request URI, and this is a GET request, see if the URI points to a static resource
                RandomAccessFile fileToServe = null;
                long lastModifiedEpochSecond = 0;
                String filePath = "";
                if (routeToUse == null && reqMethod == HttpMethod.GET) {
                    File staticResourceFile = GribbitServer.siteResources.getStaticResource(reqURI);
                    if (staticResourceFile == null) {

                        // Neither a route handler nor a static resource matched the request URI --
                        // return 404 via the regular HTTP request handler below, rather than serving a file. 
                        routeToUse = GribbitServer.siteResources.getNotFoundRoute();
                        reqMethod = HttpMethod.GET;

                    } else {

                        // A static resource matched the request URI, check last-modified timestamp against If-Modified-Since header timestamp in request
                        lastModifiedEpochSecond = staticResourceFile.lastModified() / 1000;
                        filePath = staticResourceFile.getPath();
                        if (!req.cachedVersionIsOlderThan(lastModifiedEpochSecond)) {
                            // File has not been modified since it was last cached -- return Not Modified
                            staticResourceFile = null;
                            routeToUse = GribbitServer.siteResources.routeForHandler(NotModified.class);
                            reqMethod = HttpMethod.GET;
                        }

                        // If file is newer than what is in browser cache, or is not in cache
                        if (staticResourceFile != null) {
                            try {
                                // Create new RandomAccessFile (which allows us to find file length etc.)
                                fileToServe = new RandomAccessFile(staticResourceFile, "r");

                            } catch (FileNotFoundException e) {
                                // Should not happen, file was just barely found to exist by the getStaticResource()
                                // call above, but if it disappeared between then and now, return 404
                                routeToUse = GribbitServer.siteResources.getNotFoundRoute();
                                reqMethod = HttpMethod.GET;
                            }
                        }
                    }
                }

                if (fileToServe != null) {

                    // ------------------------------------------------------------------------------
                    // Handle static file requests (these are not authenticated)
                    // ------------------------------------------------------------------------------

                    Log.fine(req.getRequestor() + "\t" + origReqMethod + "\t" + reqURI + "\tfile://" + filePath + "\t" + HttpResponseStatus.OK);

                    DefaultHttpResponse httpRes = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                    httpRes.headers().add("Server", GribbitServer.SERVER_IDENTIFIER);
                    httpRes.headers().set(DATE, ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));

                    long fileLength = fileToServe.length();
                    httpRes.headers().set(CONTENT_LENGTH, fileLength);

                    String contentType = "application/octet-stream";
                    int dotIdx = filePath.lastIndexOf('.'), slashIdx = filePath.lastIndexOf(File.separatorChar);
                    if (dotIdx > 0 && slashIdx < dotIdx) {
                        String ext = filePath.substring(dotIdx + 1).toLowerCase();
                        String mimeType = WebUtils.EXTENSION_TO_MIMETYPE.get(ext);
                        if (mimeType != null) {
                            contentType = mimeType;
                        }
                    }
                    httpRes.headers().set(CONTENT_TYPE, contentType);

                    // TODO: Are there other headers that should be set to ensure the file is cached forever as long as the last modified date doesn't change?
                    // TODO: Should I serve static files on a path that includes an MD5 hash of file contents?
                    // FIXME: currently static CSS files are cached indefinitely
                    httpRes.headers().set(LAST_MODIFIED,
                            ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochSecond), ZoneId.of("UTC")).format(DateTimeFormatter.RFC_1123_DATE_TIME));

                    if (addKeepAliveHeader) {
                        httpRes.headers().add(CONNECTION, KEEP_ALIVE);
                    }

                    // Write headers
                    ctx.write(httpRes);

                    // Write file content
                    @SuppressWarnings("unused")
                    ChannelFuture sendFileFuture;
                    if (ctx.pipeline().get(SslHandler.class) == null) {
                        sendFileFuture = ctx.write(new DefaultFileRegion(fileToServe.getChannel(), 0, fileLength), ctx.newProgressivePromise());
                    } else {
                        // Can't use FileRegions / zero-copy with SSL
                        sendFileFuture = ctx.write(new HttpChunkedInputTEMP(new ChunkedFile(fileToServe, 0, fileLength, 8192)), ctx.newProgressivePromise());
                    }

                    // Flush write, write the end marker, and close channel if needed
                    ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                    if (closeAfterWrite) {
                        // Can add ChannelProgressiveFutureListener to sendFileFuture if we need to track progress (e.g. to update user's UI
                        // over a web socket to show download progress?) but for now, just close connection after flush 
                        lastContentFuture.addListener(ChannelFutureListener.CLOSE);
                    }

                } else {

                    // ------------------------------------------------------------------------------
                    // Handle a GET or POST request
                    // ------------------------------------------------------------------------------

                    // Set the request method, in case it was overridden above (i.e. in case it was set to GET by an error handler)
                    req.setMethod(reqMethod);

                    Response res = new Response();

                    // Set the last modified time, in case we are serving a Not Modified response for a static file
                    // (have to set this header even on Not Modified, or the resource will no longer be cached)
                    if (lastModifiedEpochSecond > 0) {
                        res.setLastModifiedEpochSecond(lastModifiedEpochSecond);
                    }

                    // Copy flash messages across from request to response, so that they persist until they are read and displayed
                    res.setFlashMessages(req.getCookieValue(Cookie.FLASH_COOKIE_NAME));

                    try {

                        // -------------------------------------
                        // Call the route handler method
                        // -------------------------------------

                        routeToUse.callHandler(req, res, user);

                    } catch (Exception e) {
                        Log.exception("Exception while handling URI " + reqURI, e);
                        try {
                            req.setMethod(HttpMethod.GET);
                            GribbitServer.siteResources.getInternalServerErrorRoute().callHandler(req, res, user);
                        } catch (Exception e1) {
                            String errMsg = "Error in internal server error handler while handling URI " + reqURI;
                            Log.exception(errMsg, e1);
                            res.setContent(errMsg);
                            res.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                        }
                    }

                    // Log OK result status at log level FINE, error status at level WARNING. 
                    // Don't construct log message if we're not logging anything (to save on object creation).
                    boolean statusOK = res.getStatus() == HttpResponseStatus.OK || res.getStatus() == HttpResponseStatus.NOT_MODIFIED
                            || res.getStatus() == HttpResponseStatus.FOUND
                            // Don't log 404 for favicon
                            || (res.getStatus() == HttpResponseStatus.NOT_FOUND && StringUtils.leafName(reqURI).equals("favicon.ico"));
                    if (Log.getLevelNum() <= Log.getLevelNum(statusOK ? Level.FINE : Level.WARNING)) {
                        String logMsg = req.getRequestor() + "\t" + origReqMethod + "\t" + reqURI + (req.getMethod() == origReqMethod ? "" : "\t" + req.getMethod()) + "\t"
                                + res.getStatus() + "\t";
                        if (statusOK) {
                            Log.fine(logMsg);
                        } else {
                            Log.warningWithoutCallerRef(logMsg);
                        }
                    }

                    if (statusOK) {
                        if (res.getRedirectURI() != null) {
                            // Set redirect status code only if there was no error while generating content
                            res.setStatus(HttpResponseStatus.FOUND);
                        }
                    }

                    // Get the content of the response as a byte buffer. If the content is HTML, JSON or text, this will be in the form of UTF8 bytes.
                    // If "?_getmodel=1" is appended to the request URL of a non-JSON request, the data model backing the response will be returned as
                    // a JSON response, rather than the rendered HTML or raw text.  
                    ByteBuf content = res.getContent(GribbitProperties.ALLOW_GET_MODEL && req.isGetModelRequest());

                    // Replace placeholder instances of the CSRF input value in forms with the user's CSRF value if the user
                    // is logged in. This is a bit of a hack, but the CSRF token placeholder should be highly unique, and
                    // therefore should not collide with unintended content.
                    if (res.getContentType().contains("html")) {
                        byte[] contentArray = content.array();
                        byte[] csrfBytes = CSRF.CSRF_TOKEN_PLACEHOLDER_BYTES;
                        String csrfTokReplace = user != null ? user.csrfTok : CSRF.CSRF_TOKEN_UNKNOWN;
                        for (int i = 0, ni = contentArray.length, len = csrfBytes.length; i < ni; i++) {
                            for (int j = 0, nj = Math.min(ni - i, len); j < nj; j++) {
                                if (contentArray[i + j] != csrfBytes[j]) {
                                    // Mismatch
                                    break;
                                } else if (j == nj - 1) {
                                    // Found a match -- replace placeholder token with user's own CSRF token
                                    for (int k = 0; k < nj; k++) {
                                        contentArray[i + k] = (byte) (csrfTokReplace.charAt(k) & 0x7f);
                                    }
                                }
                            }
                        }
                    }

                    // Create a FullHttpResponse object that wraps the response status and content
                    DefaultFullHttpResponse httpRes = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, res.getStatus(), content);
                    httpRes.headers().add("Server", GribbitServer.SERVER_IDENTIFIER);
                    httpRes.headers().set(DATE, ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));

                    long lastModified = res.getLastModifiedEpochSecond();
                    if (lastModified > 0) {
                        // Cache resources that don't change (e.g. the vulcanized Polymer resources)
                        // TODO: Are there other headers that should be set to ensure the file is cached as long as the last modified date doesn't change?
                        httpRes.headers().set(LAST_MODIFIED,
                                ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModified), ZoneId.of("UTC")).format(DateTimeFormatter.RFC_1123_DATE_TIME));
                    } else {
                        // Disable caching -- see http://stackoverflow.com/questions/49547/making-sure-a-web-page-is-not-cached-across-all-browsers
                        httpRes.headers().add(CACHE_CONTROL, "no-cache, no-store, must-revalidate");
                        httpRes.headers().add(PRAGMA, "no-cache");
                        httpRes.headers().add(EXPIRES, 0);
                    }

                    httpRes.headers().set(CONTENT_LENGTH, res.getContentLength());
                    httpRes.headers().set(CONTENT_TYPE, res.getContentType());
                    if (isHEAD) {
                        // Don't return a body for HEAD requests (but still return the content length, set in the header above)
                        httpRes.content().clear();
                    }

                    if (res.getRedirectURI() != null && res.getStatus() == HttpResponseStatus.FOUND) {
                        // Set redirect target only if there was no error while generating content
                        httpRes.headers().add("Location", res.getRedirectURI());
                    }

                    // Clear flash messages if they were read by a handler that generated HTML content
                    String flashMessages = res.getFlashMessages();
                    if (flashMessages == null || flashMessages.isEmpty()) {
                        // If there are no flash messages, delete the flash message cookie
                        res.deleteCookie(Cookie.FLASH_COOKIE_NAME);
                    } else {
                        // There were flash messages that were not displayed by this response, i.e. it is a POST handler or something that
                        // did not generate content. Expire flash messages after 1 minute. Flash messages will be displayed on next page load.
                        res.setCookie(Cookie.FLASH_COOKIE_NAME, flashMessages, "/", 60, EncodingType.PLAIN);
                    }

                    // Delete any cookies set for deletion in the request (e.g. auth cookies that were
                    // deleted while the request was being checked, before the response was created)
                    for (String cookieToDelete : req.getCookiesToDelete()) {
                        if (!res.getCookies().containsKey(cookieToDelete)) {
                            res.deleteCookie(cookieToDelete);
                        }
                    }

                    // Add cookies to response
                    if (res.getCookies() != null) {
                        for (Cookie cookie : res.getCookies().values()) {
                            // System.out.println(routePath + " " + cookie.toString());
                            httpRes.headers().add(SET_COOKIE, ServerCookieEncoder.encode(cookie.getNettyCookie()));
                        }
                    }

                    // Close the connection if we get anything other than OK back as the response status
                    if (addKeepAliveHeader && httpRes.getStatus() == HttpResponseStatus.OK) {
                        httpRes.headers().add(CONNECTION, KEEP_ALIVE);
                    }

                    if (ctx.channel().isOpen()) {
                        // Check Channel.isWritable() to prevent OutOfMemoryError,
                        // see http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#10.0
                        long startTime = System.currentTimeMillis();
                        while (!ctx.channel().isWritable() && (System.currentTimeMillis() - startTime < 5000)) {
                            // TODO: replace this spinlock (usually channel is immediately writeable; is this even needed?)
                        }
                        if (ctx.channel().isWritable()) {
                            // Write the ByteBuffer returned by httpRes.content() back into the pipeline
                            // See http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#4.0
                            ChannelFuture future = ctx.channel().writeAndFlush(httpRes);

                            // Close the connection after the write operation is done if necessary.
                            // TODO: Apache closes KeepAlive connections after a few seconds, see http://en.wikipedia.org/wiki/HTTP_persistent_connection -- implement a stale connection tracker
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
                }
                // Finished request
                destroyDecoder();
            }

        } catch (Exception e) {
            // Release HTTP decoder resources, including any file uploads that were received in a POST request and stored in /tmp
            destroyDecoder();
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------

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
            DefaultFullHttpResponse httpRes = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR, contentByteBuf);
            httpRes.headers().set(CONTENT_LENGTH, contentByteBuf.readableBytes());
            httpRes.headers().set(CONTENT_TYPE, "text/plain;charset=utf-8");
            ctx.writeAndFlush(httpRes);
            ctx.channel().close();
        }
    }
}
