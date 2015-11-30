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
package gribbit.websocket;

import gribbit.auth.CSRF;
import gribbit.auth.User;
import gribbit.http.response.exception.BadRequestException;
import gribbit.http.response.exception.ForbiddenException;
import gribbit.http.response.exception.MethodNotAllowedException;
import gribbit.http.response.exception.ResponseException;
import gribbit.http.response.exception.UnauthorizedException;
import gribbit.route.Route;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.util.WebUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

import java.net.URI;

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



//// ------------------------------------------------------------------------------
//// ------------------------------------------------------------------------------
//
//boolean isWebSocketUpgradeRequest = "1".equals(this.getQueryParam("_ws"));
//if (isWebSocketUpgradeRequest) {
//    if (!GribbitProperties.ALLOW_WEBSOCKETS) {
//        throw new BadRequestException();
//    }
//    if (this.authorizedRoute == null) {
//        throw new UnauthorizedException(this);
//    }
//    if (this.method != HttpMethod.GET) {
//        throw new MethodNotAllowedException();
//    }
//    // Create a new WebSocketHandler, and upgrade the connection
//    this.webSocketHandler = new AuthenticatedWebSocketHandler(ctx, httpReq, this.origin, getQueryParam("_csrf"),
//            lookupUser(), this.authorizedRoute);
//    // Remove the _ws query parameter
//    this.queryParamToVals.remove("_ws");


/** The websocket handler. Appending "?_ws=1" to the URL to upgrade the connection to a websocket. */
// private AuthenticatedWebSocketHandler webSocketHandler;

//// TODO: placeholder
//// TODO: Read: http://lucumr.pocoo.org/2012/9/24/websockets-101/
//// TODO: See also links here: http://security.stackexchange.com/questions/48378/anti-dos-websockets-best-practices
//String requestText = ((TextWebSocketFrame) frame).text();
//String responseText = wsRequestedRoute.getRoutePath() + " -> " + requestText.toUpperCase() + " -- "
//        + (wsAuthenticatedUser == null ? "not logged in" : wsAuthenticatedUser.id);
//


/**
 * Handle WebSocket upgrade requests and WebSocket request frames.
 *
 * Note that WebSocket upgrade requests can only be made on routes that require authentication, and the user has to
 * first be logged in.
 * 
 * To initiate the WebSocket connection, append the following parameters to a route's URL: "?_ws=1&_csrf=..." (with
 * the CSRF token for the user in place of "...", which can be obtained from any form served by the template engine
 * to an authenticated user).
 */
public class AuthenticatedWebSocketHandler {

    // Put the following into tail-content.html to add WebSocket support to a page:

    //    <script type="text/javascript">
    //
    //    function wsOpened(event) {
    //      // ...
    //    }
    //
    //    function wsMessageReceived(event) {
    //      // ...
    //    }
    //
    //    function wsClosed(event) {
    //      // ...
    //    }
    //
    //    var socket = null;
    //    if (!window.WebSocket) {
    //      window.WebSocket = window.MozWebSocket;
    //    }
    //    if (window.WebSocket && typeof wsOpen !== "undefined" && typeof wsMsg !== "undefined"
    //        && typeof wsClose !== "undefined") {
    //      var wsURL = null;
    //      if (document.forms !== null) {
    //        for (var i = 0; i < document.forms.length; i++) {
    //          var form = document.forms[i];
    //          console.log(form);
    //          if (typeof form.csrf !== "undefined") {
    //            wsURL = document.URL.replace(/^http/, "ws").replace(/\?.*/, "") + "?_ws=1&_csrf=" + form.csrf.value;
    //            break;
    //          }
    //        }
    //      }
    //      if (wsURL == null) {
    //        console.log("Could not generate web socket URL (could not locate CSRF token in a form on the page)");
    //      } else {
    //        var trySocket = new WebSocket(wsURL);
    //        trySocket.onopen = function(event) {
    //          socket = trySocket;
    //          wsOpened(event);
    //        };
    //        trySocket.onmessage = function(event) {
    //          wsMessageReceived(event);
    //        };
    //        trySocket.onclose = function(event) {
    //          socket = null;
    //          wsClosed(event);
    //        };
    //      }
    //    } else {
    //      console.log("Your browser does not support web sockets");
    //    }
    //
    //    function send(message) {
    //      if (socket === null || typeof socket === "undefined") {
    //        console.log("Socket not open");
    //        return;
    //      }
    //      if (socket.readyState == WebSocket.OPEN) {
    //        socket.send(message);
    //      } else {
    //        console.log("The web socket is not open");
    //      }
    //    }
    //
    //  </script>

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

    public AuthenticatedWebSocketHandler(ChannelHandlerContext ctx, HttpRequest httpReq, CharSequence origin,
            String csrfQueryParam, User user, Route authorizedRoute) throws ResponseException {

        if (user == null) {
            // Require users to be logged in before initiating WebSocket requests to mitigate DoS attacks
            throw new ForbiddenException();
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
            throw new ForbiddenException();
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
            throw new ForbiddenException();
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
            handshaker.handshake(ctx.channel(), httpReq);
        }
    }
}