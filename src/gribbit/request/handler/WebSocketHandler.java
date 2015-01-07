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

import gribbit.auth.CSRF;
import gribbit.auth.User;
import gribbit.route.Route;
import gribbit.server.GribbitServer;
import gribbit.util.WebUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

import java.net.URI;

/**
 * Handle WebSocket upgrade requests and WebSocket request frames.
 *
 * Note that WebSocket upgrade requests can only be made on routes that require authentication, and the user has to
 * first be logged in.
 * 
 * To initiate the WebSocket connection, append the following parameters to a route's URL: "?_ws=1&_csrf=..." (with the
 * CSRF token for the user in place of "...", which can be obtained from any form served by the template engine to an
 * authenticated user).
 */
public class WebSocketHandler {

    /** Websocket handshaker. */
    private WebSocketServerHandshaker handshaker;

    /** The user that was authenticated when the websocket upgrade was requested. */
    private User wsAuthenticatedUser;

    /** The route that the websocket upgrade was requested on. */
    private Route wsRequestedRoute;

    /** Handle a websocket frame. */
    public void handleWebsocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // Check for closing frame
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
            ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
                    .getName()));
        }

        // TODO: placeholder
        // TODO: Read: http://lucumr.pocoo.org/2012/9/24/websockets-101/
        // TODO: See also links here: http://security.stackexchange.com/questions/48378/anti-dos-websockets-best-practices
        String requestText = ((TextWebSocketFrame) frame).text();
        String responseText = wsRequestedRoute.getRoutePath() + " -> " + requestText.toUpperCase() + " -- "
                + (wsAuthenticatedUser == null ? "not logged in" : wsAuthenticatedUser.id);

        ctx.writeAndFlush(new TextWebSocketFrame(responseText));
    }

    public WebSocketHandler(ChannelHandlerContext ctx, HttpRequest httpReq, CharSequence origin, String csrfQueryParam,
            User user, Route authorizedRoute) {

        if (user == null) {
            // Require users to be logged in before initiating WebSocket requests to mitigate DoS attacks
            HttpUtils.sendHttpErrorResponse(ctx, null, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.FORBIDDEN));
            return;
        }

        // Protect against CSWSH: (Cross-Site WebSocket Hijacking)
        // http://www.christian-schneider.net/CrossSiteWebSocketHijacking.html
        // http://tools.ietf.org/html/rfc6455#page-7
        URI originUri = null;
        if (origin != null && origin.length() > 0) {
            try {
                // Try parsing origin URI
                originUri = new URI(origin.toString());
            } catch (Exception e) {
            }
        }
        if (originUri == null || !WebUtils.sameOrigin(originUri, GribbitServer.uri)) {
            // Reject scripted requests to open this websocket from a different domain
            HttpUtils.sendHttpErrorResponse(ctx, null, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.FORBIDDEN));
            return;
        }
        // Log.info("Origin: " + origin.toString());

        // To further mitigate CSWSH attacks (beyond the same-origin check performed above):
        // check for the CSRF token in the URL parameter "_csrf": the passed token must match
        // the user's CSRF token. This means the websocket URL has to be dynamically generated
        // and inserted into the webpage that opened the websocket.
        // TODO: generate this URL and insert into the page somehow (i.e. require a form on the page
        // TODO: so that the CSRF token is available)
        if (!CSRF.csrfTokMatches(csrfQueryParam, user)) {
            // No valid CSRF token in User object
            HttpUtils.sendHttpErrorResponse(ctx, null, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.FORBIDDEN));
            return;
        }

        // Record which user was authenticated when the websocket upgrade request was made.
        // TODO: Also provide a means for revoking user's session while WS is still open,
        // e.g. poll the user table every few seconds to see if user's session token has
        // changed in the database? (Although this would mean that logging in on a new
        // device would log you out of all other sessions...)
        wsAuthenticatedUser = user;

        // The route that the websocket was requested on
        wsRequestedRoute = authorizedRoute;

        // Try upgrading the connection to a websocket
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                GribbitServer.wsUri.toString(), null, true);
        handshaker = wsFactory.newHandshaker(httpReq);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            // Attempt websocket handshake, and if it succeeds, upgrade connection to websocket
            // TODO: filed bug report, handshaker.handshake should take HttpRequest, not FullHttpRequest
            DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(httpReq.protocolVersion(), httpReq.method(),
                    httpReq.uri());
            fullReq.headers().add(httpReq.headers());
            handshaker.handshake(ctx.channel(), (FullHttpRequest) fullReq);
        }
    }
}
