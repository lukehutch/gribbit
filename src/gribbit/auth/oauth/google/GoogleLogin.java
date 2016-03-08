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
package gribbit.auth.oauth.google;

import gribbit.handler.route.annotation.RoutePath;
import gribbit.route.RouteHandler;

/**
 * Google OAuth2 provider. To use this, your Login button should send the user to the URL /oauth/google/login. (i.e.
 * The route for this handler is "/oauth/google", but the additional URI param "login" should be provided after the
 * route, giving "/oauth/google/login".)
 * 
 * This route is also used for handling the OAuth2 callback, at /oauth/google/callback .
 */
@RoutePath("/oauth/google")
public class GoogleLogin extends RouteHandler {
//    public static final String GOOGLE_ACCESS_TOKEN_EXPIRES_KEY = "auth_gX";
//    public static final String GOOGLE_ACCESS_TOKEN_KEY = "auth_gT";
//    public static final String GOOGLE_REFRESH_TOKEN_KEY = "auth_gRT";
//
//    public static class AuthResponse {
//        public String access_token, token_type, expires_in, id_token, refresh_token;
//    }
//
//    public static class UserInfo {
//        public String id, email, name, given_name, family_name, link, picture, gender;
//        public Boolean verified_email;
//    }
//
//    /**
//     * Check if a user's access token has expired or is about to expire, and if so, generate a new access token
//     * using the user's refresh token. Can be used by a web socket connection to keep a user's login alive.
//     */
//    public static void refreshAccessTokenIfNeeded(Request request, User user) throws ResponseException {
//        if (user == null) {
//            throw new BadRequestException(request, "User is null");
//        }
//        String refreshToken = user.getData(GOOGLE_REFRESH_TOKEN_KEY);
//        String accessToken = user.getData(GOOGLE_ACCESS_TOKEN_KEY);
//        String accessTokenExpiresStr = user.getData(GOOGLE_ACCESS_TOKEN_EXPIRES_KEY);
//        long currentTimeEpochSeconds = ZonedDateTime.now().toEpochSecond();
//        if (accessToken == null || accessTokenExpiresStr == null
//                || Long.parseLong(accessTokenExpiresStr) - currentTimeEpochSeconds <= 60L) {
//            // Access token missing or expired, or will expire in <= 60 seconds --
//            // generate a new access token from the refresh token
//
//            if (refreshToken == null) {
//                throw new BadRequestException(request, "No refresh token");
//            }
//            Log.info("Updating access token for user " + user.id); // TODO: for debugging, remove later
//
//            AuthResponse auth = null;
//            try {
//                // Get a new access token using the refresh token.
//                // Throws BadRequestException if the refresh token is bad. 
//                auth = RequestBuilder.postToURLWithJSONResponse(AuthResponse.class, //
//                        "https://accounts.google.com/o/oauth2/token", //
//                        "refresh_token", refreshToken, //
//                        "client_id", GribbitProperties.OAUTH_GOOGLE_CLIENT_ID, //
//                        "client_secret", GribbitProperties.OAUTH_GOOGLE_CLIENT_SECRET, //
//                        "grant_type", "refresh_token");
//            } catch (Exception e) {
//                throw new BadRequestException(request, "Could not get access token using refresh token");
//            }
//            // The access token obtained from the refresh token.
//            accessToken = auth.access_token;
//            long accessTokenExpiresSeconds = currentTimeEpochSeconds
//                    + (auth.expires_in == null ? 0L : Long.parseLong(auth.expires_in));
//
//            // Replace refresh token with the new one, if provided (although in general, we won't be given
//            // a new refresh token if we already have one)
//            refreshToken = auth.refresh_token != null ? auth.refresh_token : refreshToken;
//
//            // Update token values in user object
//            user.putData(GOOGLE_REFRESH_TOKEN_KEY, refreshToken);
//            user.putData(GOOGLE_ACCESS_TOKEN_KEY, accessToken);
//            user.putData(GOOGLE_ACCESS_TOKEN_EXPIRES_KEY, Long.toString(accessTokenExpiresSeconds));
//            user.save();
//
//        } else {
//            // Access token has not expired yet -- nothing to do
//        }
//    }
//
//    // See http://highaltitudedev.blogspot.com/2013/10/google-oauth2-with-jettyservlets.html
//
//    // TODO: Need to set up the image to show on the approval page in the API console, and the email address
//    // to contact if something goes wrong
//
//    private String callbackURI() {
//        return GribbitServer.uri + Route.forGet(GoogleLogin.class, "callback");
//    }
//
//    private String getAuthorizationCodeURL(boolean forceApprovalPrompt) {
//        return "https://accounts.google.com/o/oauth2/auth" //
//                // The client id from the API console
//                + "?client_id=" + GribbitProperties.OAUTH_GOOGLE_CLIENT_ID
//                // TODO: what is this for?
//                + "&response_type=code"
//                // The api permissions we are requesting
//                + "&scope=openid%20email"
//                // Where to redirect to after authorization
//                + "&redirect_uri=" + callbackURI()
//                // TODO -- add CSRF token to "state"
//                // "&state=this_can_be_anything_to_help_correlate_the_response%3Dlike_session_id"
//                + "&state="
//                // Request offline access -- generates a refresh token
//                + "&access_type=offline"
//                // This requires the user to verify which account to use, if they are already signed in,
//                // but even if they are only signed into one account brings up the approval page
//                + (forceApprovalPrompt ? "&approval_prompt=force" : "");
//    }
//
//    // This handler is initially called with "/login" appended to the URI, initiating the OAuth process.
//    // The route of this same handler is given to Google with "/callback" appended in place of "/login" to
//    // handle the OAuth2 callback after successful authentication.
//    public GeneralResponse get(String action) throws ResponseException {
//        // Throw 404 if OAuth2 params are not configured 
//        if (GribbitProperties.OAUTH_GOOGLE_CLIENT_ID == null || GribbitProperties.OAUTH_GOOGLE_CLIENT_ID.isEmpty()
//                || GribbitProperties.OAUTH_GOOGLE_CLIENT_SECRET == null
//                || GribbitProperties.OAUTH_GOOGLE_CLIENT_SECRET.isEmpty()) {
//            throw new NotFoundException(request);
//        }
//
//        User user = null;
//        String error = request.getQueryParam("error");
//        if (error != null) {
//            // If the user was denied access, we get back an error, e.g. "error=access_denied"
//            error = "Unauthorized: " + error;
//        } else {
//            if ("login".equals(action)) {
//                // Initial click by the user on the "Sign in with Google" button: redirect to Google
//                // to get an authorization code
//                throw new RedirectException(getAuthorizationCodeURL(/* forceApprovalPrompt = */false));
//
//            } else if ("callback".equals(action)) {
//                // Handling a callback request from Google's OAuth2 server
//
//                try {
//                    AuthResponse auth = null;
//                    try {
//                        // Exchange authorization code provided by Google for an access token.
//                        // Throws BadRequestException if the authorization code is bad. 
//                        auth = RequestBuilder.postToURLWithJSONResponse(AuthResponse.class, //
//                                "https://accounts.google.com/o/oauth2/token", //
//                                // Authorization code provided by Google
//                                "code", request.getQueryParam("code"), //
//                                "client_id", GribbitProperties.OAUTH_GOOGLE_CLIENT_ID, //
//                                "client_secret", GribbitProperties.OAUTH_GOOGLE_CLIENT_SECRET, //
//                                "redirect_uri", callbackURI(),//
//                                "grant_type", "authorization_code");
//                    } catch (Exception e) {
//                        throw new BadRequestException(request, "Could not get authorization code");
//                    }
//
//                    // The access token obtained from the authorization code
//                    String accessToken = auth.access_token;
//                    long accessTokenExpiresInSeconds = auth.expires_in == null ? 0L : Long
//                            .parseLong(auth.expires_in);
//                    if (accessToken == null || accessTokenExpiresInSeconds <= 0) {
//                        // Should not happen, should always get an access token.
//                        // On any result code other than 200 OK (e.g. 400 Bad Request / 401 Not Authorized),
//                        // the POST request above will have already thrown an exception.
//                        throw new BadRequestException(request, "Could not fetch access token");
//                    }
//                    long timeNowEpochSeconds = ZonedDateTime.now().toEpochSecond();
//                    long accessTokenExpiresSeconds = timeNowEpochSeconds + accessTokenExpiresInSeconds;
//
//                    UserInfo userInfo = null;
//                    try {
//                        // Get user's email address, name, gender, profile pic URL etc.
//                        // Throws BadRequestException if the access token is bad. 
//                        userInfo = RequestBuilder.getFromURLWithJSONResponse(UserInfo.class, //
//                                "https://www.googleapis.com/oauth2/v1/userinfo", //
//                                "access_token", accessToken);
//                    } catch (Exception e) {
//                        throw new BadRequestException(request, "Could not get userInfo");
//                    }
//
//                    String email = userInfo.email;
//                    if (email == null || email.isEmpty()) {
//                        // Need an email address to look up user
//                        error = "Bad Request: no email address";
//
//                    } else {
//
//                        // Look up user based on email address
//                        user = User.findByEmail(email);
//
//                        // Refresh token is only non-null the first time the user authorizes offline access.
//                        // We have to store the token and retrieve it from the user record on subsequent
//                        // operations.
//                        String refreshToken = auth.refresh_token;
//                        if (refreshToken == null && user != null) {
//                            // See: https://developers.google.com/drive/web/credentials
//                            refreshToken = user.getData(GOOGLE_REFRESH_TOKEN_KEY);
//                        }
//
//                        if (refreshToken == null) {
//                            // If we still don't have a refresh token, we have to ask the user for the
//                            // "Have offline access" permission. This can happen when this is not the first
//                            // time the user has logged in, according to the browser's cookies, but the
//                            // user's database record has been deleted, deleting the refresh token.
//                            // See: http://goo.gl/aUoDLl
//                            RedirectException ex = new RedirectException( //
//                                    getAuthorizationCodeURL(/* forceApprovalPrompt = */true));
//                            ex.generateErrorResponse().logOutUser(user);
//                            throw ex;
//
//                        } else {
//
//                            // User has successfully logged in. See if they were originally trying to reach a specific
//                            // URL when they were originally told they were unauthorized. If so, try again, otherwise
//                            // redirect back home.
//                            String redirectOrigin = request.getCookieValue(Cookie.REDIRECT_AFTER_LOGIN_COOKIE_NAME);
//                            ResponseException redirException;
//                            if (redirectOrigin == null) {
//                                // Redirect home if the user didn't previously try to go directly to another URL
//                                redirException = new RedirectException("/");
//                            } else {
//                                // Redirect to wherever the user was trying to get before, and clear the redirect cookie
//                                redirException = new RedirectException(redirectOrigin);
//                                redirException.generateErrorResponse().deleteCookie(
//                                        Cookie.REDIRECT_AFTER_LOGIN_COOKIE_NAME);
//                            }
//
//                            if (user == null) {
//                                // There was no user with this email address -- create a new user
//                                user = User.createFederatedLoginUser(request, email,
//                                        redirException.generateErrorResponse());
//
//                                user.putData("name", userInfo.name);
//                                user.putData("givenName", userInfo.given_name);
//                                user.putData("surname", userInfo.family_name);
//                                user.putData("gender", userInfo.gender);
//                                user.putData("g+id", userInfo.id);
//                                user.putData("g+profile", userInfo.link);
//                                user.putData("g+photo", userInfo.picture); // TODO: fetch and cache?
//
//                                // res.clearFlashMessages();
//                                // res.addFlashMessage(FlashType.INFO, "Welcome",
//                                // "We created a new account for you with the email address " + email);
//                                Log.info(request.getRequestor() + "\tCreated new Google user " + email);
//
//                            } else {
//                                // res.clearFlashMessages();
//                                // res.addFlashMessage(FlashType.SUCCESS, "Welcome back",
//                                // "You are now signed in with the email address " + email);
//                                Log.info(request.getRequestor() + "\tGoogle login okay for user " + email);
//                            }
//
//                            if (userInfo.verified_email != null && userInfo.verified_email) {
//                                user.registrationComplete = true;
//                            }
//                            if (!user.registrationComplete) {
//                                // Add a flash message if the verified_email field is not set to true,
//                                // so that the user doesn't get confused about why we're asking them to
//                                // validate their email address. (TODO: under what circumstances will
//                                // Google report verified_email == false?)
//                                addFlashMessage(new FlashMessages(FlashType.WARNING, "Please verify email address",
//                                        "Google has informed us that you have not verified your email "
//                                                + "address with them. Please do so, then log out and "
//                                                + "log back into this site."));
//                            }
//
//                            // Store tokens in user record
//                            user.putData(GOOGLE_REFRESH_TOKEN_KEY, refreshToken);
//                            user.putData(GOOGLE_ACCESS_TOKEN_KEY, accessToken);
//                            user.putData(GOOGLE_ACCESS_TOKEN_EXPIRES_KEY, Long.toString(accessTokenExpiresSeconds));
//                            user.save();
//
//                            // Log the user in by setting session cookies in the response
//                            user.logIn(request, redirException.generateErrorResponse());
//
//                            // Perform the redirect
//                            throw redirException;
//                        }
//                    }
//
//                } catch (ResponseException e) {
//                    // Pass back to caller
//                    throw e;
//
//                } catch (Exception e) {
//                    error = "Exception during Google OAuth2 login"
//                            + (e.getMessage() == null ? "" : ": " + e.getMessage());
//                    Log.error(error);
//                }
//            } else {
//                error = "Bad Request: bad URI parameter";
//            }
//        }
//
//        // If got to here, OAuth login failed somehow
//        clearFlashMessages();
//        addFlashMessage(new FlashMessages(FlashType.ERROR, "Error",
//                "Could not log you in, please check your password or contact the site administrator."));
//
//        // Generate Unauthorized response
//        ResponseException unauthorizedException;
//        if ("callback".equals(action)) {
//            // We don't want the long callback URI in the browser address field,
//            // so redirect to the unauthorized handler's route
//            unauthorizedException = new RedirectException(GribbitServer.siteResources.getUnauthorizedRoute());
//        } else {
//            // Otherwise the URL is fine (not full of OAuth tokens), just return an Unauthorized response type
//            unauthorizedException = new UnauthorizedException(request);
//        }
//        // Clear session cookies in response
//        if (user != null) {
//            // This logout method is a little faster, because user doesn't have to be looked up in request
//            unauthorizedException.generateErrorResponse().logOutUser(user);
//        } else {
//            unauthorizedException.generateErrorResponse().logOutUser(request);
//        }
//        throw unauthorizedException;
//    }
}
