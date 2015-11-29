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
package gribbit.http.request.decoder;

import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPIRES;
import static io.netty.handler.codec.http.HttpHeaderNames.PRAGMA;
import gribbit.http.logging.Log;
import gribbit.http.request.Request;
import gribbit.http.request.handler.HttpErrorHandler;
import gribbit.http.request.handler.HttpRequestHandler;
import gribbit.http.request.handler.WebSocketHandler;
import gribbit.http.response.Response;
import gribbit.http.response.exception.BadRequestException;
import gribbit.http.response.exception.InternalServerErrorException;
import gribbit.http.response.exception.ResponseException;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
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
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.ssl.NotSslRecordException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

public class HttpRequestDecoder extends SimpleChannelInboundHandler<Object> {

    private Request request;
    private HttpPostRequestDecoder postRequestDecoder;
    private WebSocketHandler webSocketHandler;
    private WebSocketServerHandshaker webSocketHandshaker;

    private ArrayList<HttpRequestHandler> httpRequestHandlers;
    private ArrayList<WebSocketHandler> webSocketHandlers;
    private HashMap<Class<? extends ResponseException>, //
    HttpErrorHandler<? extends ResponseException>> errorHandlers;

    // -------------------------------------------------------------------------------------------------------------

    // Use disk for HTTP data if size >16kB 
    private static final HttpDataFactory httpDataFactory = new DefaultHttpDataFactory(
            DefaultHttpDataFactory.MINSIZE);

    static {
        // Delete temporary files on exit
        DiskFileUpload.deleteOnExitTemporaryFile = true;
        DiskAttribute.deleteOnExitTemporaryFile = true;
        // Use system temp directory
        DiskFileUpload.baseDirectory = null;
        DiskAttribute.baseDirectory = null;
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // -------------------------------------------------------------------------------------------------------------

    public HttpRequestDecoder() {
    }

    public HttpRequestDecoder(ArrayList<HttpRequestHandler> httpRequestHandlers,
            ArrayList<WebSocketHandler> webSocketHandlers, HashMap<Class<? extends ResponseException>, //
            HttpErrorHandler<? extends ResponseException>> errorHandlers) {
        this.httpRequestHandlers = httpRequestHandlers;
        this.webSocketHandlers = webSocketHandlers;
        this.errorHandlers = errorHandlers;
    }

    /**
     * Free resources once a request is complete. (POST requests may generate temporary files on the filesystem,
     * and/or objects in memory.)
     */
    // FIXME: call automatically when channel is closed to release resources
    private void freeResources() {
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
        request = null;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Add an HTTP request handler. Handlers are called in order until one of them returns a non-null response. */
    public HttpRequestDecoder addHttpRequestHandler(HttpRequestHandler handler) {
        if (httpRequestHandlers == null) {
            httpRequestHandlers = new ArrayList<>();
        }
        httpRequestHandlers.add(handler);
        return this;
    }

    /**
     * Add an WebSocket handler. Handlers are called in order until one of them handles the WebSocket upgrade
     * request.
     */
    public HttpRequestDecoder addWebSocketHandler(WebSocketHandler handler) {
        if (webSocketHandlers == null) {
            webSocketHandlers = new ArrayList<>();
        }
        webSocketHandlers.add(handler);
        return this;
    }

    /** Add an error handler that overrides a default plain text error response. */
    public <E extends ResponseException> HttpRequestDecoder addHttpErrorHandler(Class<E> exceptionType,
            HttpErrorHandler<E> errorHandler) {
        if (errorHandlers == null) {
            errorHandlers = new HashMap<>();
        }
        errorHandlers.put(exceptionType, errorHandler);
        return this;
    }

    /**
     * See if there is an error handler for the specified exception type, and if so, use it to generate the
     * response.
     */
    private <E extends ResponseException> Response tryCustomHttpErrorHandler(E exception) {
        @SuppressWarnings("unchecked")
        HttpErrorHandler<E> errorHandler = (HttpErrorHandler<E>) errorHandlers.get(exception.getClass());
        return errorHandler == null ? null : errorHandler.generateResponse(request, exception);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Decode an HTTP message. */
    @Override
    public void messageReceived(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof HttpRequest) {
                // Got a new HTTP request -- decode HTTP headers
                HttpRequest httpReq = (HttpRequest) msg;
                if (!httpReq.decoderResult().isSuccess()) {
                    // Headers were malformed
                    throw new BadRequestException(null);
                }

                // Parse the HttpRequest fields. 
                request = new Request(ctx, httpReq);

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
                    freeResources();
                    postRequestDecoder = new HttpPostRequestDecoder(httpDataFactory, httpReq);
                }

            } else if (request != null) {
                // (If request is null here, got an exception partway through the request -- ignore the rest of the
                // messages in the request.)

                if (msg instanceof WebSocketFrame) {
                    // Handle WebSocket frame
                    if (webSocketHandler == null) {
                        // Connection was never upgraded to websocket
                        throw new BadRequestException();
                    }
                    handleWebSocketFrame(ctx, (WebSocketFrame) msg);

                } else if (msg instanceof HttpContent) {
                    // Decode HTTP POST body
                    HttpContent chunk = (HttpContent) msg;
                    handlePOSTChunk(chunk);

                } else if (msg instanceof LastHttpContent) {
                    // Reached end of HTTP request

                    // Check for WebSocket upgrade request
                    if (!tryWebSocketHandlers(ctx, request.getHttpRequest())) {
                        // This is a regular HTTP request -- find a handler for the request
                        tryHttpRequestHandlers(ctx);
                    }
                    // After the last content message has been processed, free resources
                    freeResources();
                }
            }
        } catch (Exception e) {
            exceptionCaught(ctx, e);
        }
    }

    /** Try upgrading the request to a WebSocket connection using one of the provided WebSocketHandlers. */
    private boolean tryWebSocketHandlers(ChannelHandlerContext ctx, HttpRequest httpReq)
            throws ResponseException {
        String url = httpReq.uri();
        if (webSocketHandler == null) {
            if (webSocketHandlers != null) {
                // Look for a WebSocket handler that can handle this URL
                for (WebSocketHandler handler : webSocketHandlers) {
                    if (handler.isWebSocketUpgradeURL(url)) {
                        // Upgrade connection to WebSocket
                        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                                httpReq.uri(), null, true);
                        webSocketHandshaker = wsFactory.newHandshaker(httpReq);
                        if (webSocketHandshaker == null) {
                            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                            return false;
                        } else {
                            // Attempt websocket handshake, and if it succeeds, upgrade connection to websocket
                            webSocketHandshaker.handshake(ctx.channel(), httpReq);
                        }
                        webSocketHandler = handler;
                        // TODO: do we need to send an empty OK response? ***********
                        return true;
                    }
                }
            } else {
                // No WebSocket handlers
                return false;
            }
        } else {
            // Duplicate upgrade request, should not happen
            throw new BadRequestException();
        }
        return false;
    }

    /** Handle a WebSocket frame using the same handler that was used to upgrade the connection. */
    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) throws BadRequestException,
            ResponseException {
        if (frame instanceof CloseWebSocketFrame) {
            webSocketHandler.close();
            webSocketHandler = null;
            webSocketHandshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            webSocketHandshaker = null;
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof TextWebSocketFrame) {
            webSocketHandler.handleTextFrame(ctx, (TextWebSocketFrame) frame);
        } else if (frame instanceof BinaryWebSocketFrame) {
            webSocketHandler.handleBinaryFrame(ctx, (BinaryWebSocketFrame) frame);
        } else {
            throw new BadRequestException();
        }
    }

    /** Try handling the HTTP request using one of the provided HttpRequestHandlers. */
    private void tryHttpRequestHandlers(ChannelHandlerContext ctx) throws ResponseException {
        boolean handled = false;
        if (httpRequestHandlers != null) {
            for (HttpRequestHandler handler : httpRequestHandlers) {
                // Try generating a response with this HttpRequestHandler
                Response response = handler.handle(request);
                if (response != null) {
                    // If a response was generated, send it, and don't check any remaining handlers
                    response.send(request, ctx);
                    handled = true;
                    break;
                }
            }
        }
        if (!handled) {
            // There is no handler registered for this HTTP request -- respond with Bad Request
            Log.info("No registered HttpRequestHandler for request: " + request.getURLPathUnhashed());
            throw new BadRequestException();
        }
    }

    private void handlePOSTChunk(HttpContent chunk) throws BadRequestException, ResponseException {
        if (request == null || postRequestDecoder == null) {
            // Should not happen for well-formed requests
            return;
        }

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
                            Charset encoding = attribute.getCharset() == null ? UTF8 : attribute.getCharset();
                            try {
                                if (attribute.isInMemory()) {
                                    request.setPostParam(attribute.getName(), attribute.getString(encoding));
                                } else {
                                    // // TODO: Handle File-typed POST values without loading them straight into
                                    // // memory (to avoid OOM attacks):
                                    // request.setPostParam(attribute.getName(), attribute.getFile(encoding));
                                    // // For now though, just read the file contents into a string.
                                    request.setPostParam(attribute.getName(), attribute.getString(encoding));
                                }
                            } catch (IOException e) {
                                // Thrown if attribute value is too large for max size, but temp file can't be
                                // created on disk
                                throw new InternalServerErrorException(e);
                            }
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
            // decoder.hasNext() doesn't actually work, this exception always gets thrown
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    private static final Pattern FAVICON_PATTERN = Pattern.compile("^(.*/)?favicon\\.(ico|png|gif|jpeg|jpg|apng)$");

    /**
     * Send non-OK response. Used by Netty pipeline for uncaught exceptions (e.g. connecion reset by peer, malformed
     * HTTP message, etc.), and also called manually for caught exceptions.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        try {
            if (!ctx.channel().isActive()) {
                Log.exception("Exception thrown while calling toplevel exception handler, and channel "
                        + "is already closed", e);
                return;
            }
            if (e instanceof NotSslRecordException) {
                ctx.channel().flush();
                ctx.channel().close();
            } else if ("Connection reset by peer".equals(e.getMessage())) {
                // (No need to log the backtrace in this case)
                // Log.info(cause.getMessage());
                // TODO: should connection be closed in this case? Does a response need to be sent?
            } else {
                // If there is no valid request object, can't generate a normal response page,
                // because ErrorResponse requires a non-null request object to be able to call getContent() 

                ResponseException exception = e instanceof ResponseException //
                ? (ResponseException) e
                        : new InternalServerErrorException(e);

                if (exception instanceof InternalServerErrorException) {
                    Log.exception("Unexpected exception while handling request", e);
                }

                if (request != null) {
                    // Override default error response page if there is a custom handler for this error type
                    Response response = null;
                    if (errorHandlers != null && !FAVICON_PATTERN.matcher(request.getURLPathUnhashed()).matches()) {
                        // Override response with custom exception handler
                        response = tryCustomHttpErrorHandler(exception);
                    }
                    if (response == null) {
                        // Otherwise, use default plain text response
                        response = exception.generateErrorResponse();
                    }

                    // Try sending error response
                    if (request != null && response != null) {
                        try {
                            response.send(request, ctx);
                            return;

                        } catch (Exception e2) {
                        }
                    }
                }

                // If couldn't send response in normal way (either there is no request object generated yet, or
                // there was an exception calling response.send()), then send a plain text response as a fallback
                if (!(exception instanceof InternalServerErrorException)) {
                    Log.exception("Unable to send exception response", e);
                }
                FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR);
                res.content().writeBytes("Internal Server Error".getBytes("UTF-8"));
                HttpHeaders headers = res.headers();
                headers.set(CONTENT_TYPE, "text/plain;charset=utf-8");
                HttpUtil.setContentLength(res, res.content().readableBytes());

                // Disable caching
                headers.add(CACHE_CONTROL, "no-cache, no-store, must-revalidate"); // HTTP 1.1
                headers.add(PRAGMA, "no-cache"); // HTTP 1.0
                headers.add(EXPIRES, "0"); // Proxies

                ChannelFuture f = ctx.writeAndFlush(res);
                f.addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Exception e2) {
            Log.exception("Exception thrown while calling toplevel exception handler", e2);
            try {
                ctx.channel().flush();
                ctx.channel().close();
            } catch (Exception e3) {
            }
        } finally {
            freeResources();
        }
    }
}
