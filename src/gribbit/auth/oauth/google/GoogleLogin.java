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

import gribbit.auth.User;
import gribbit.exception.BadRequestException;
import gribbit.exception.UnauthorizedException;
import gribbit.handler.route.annotation.RouteOverride;
import gribbit.server.GribbitServer;
import gribbit.server.Response.FlashType;
import gribbit.server.RestHandler;
import gribbit.server.Route;
import gribbit.server.config.GribbitProperties;
import gribbit.util.Log;
import gribbit.util.RequestBuilder;

/**
 * Google OAuth2 provider. To use this, your Login button should send the user to the URL /oauth/google/login (i.e. the route for this handler is "/oauth/google", but the
 * additional URI param "login" should be provided after the route, giving "/oauth/google/login" -- this route is also used for handling the OAuth2 callback, at
 * /oauth/google/callback).
 */
@RouteOverride("/oauth/google")
public class GoogleLogin extends RestHandler.AuthNotRequired {

    public static class AuthResponse {
        public String access_token, token_type, expires_in, id_token, refresh_token;
    }

    public static class UserInfo {
        public String id, email, name, given_name, family_name, link, picture, gender;
        public Boolean verified_email;
    }

    static {
        if (GribbitProperties.OAUTH_GOOGLE_CLIENT_ID == null || GribbitProperties.OAUTH_GOOGLE_CLIENT_ID.isEmpty() || GribbitProperties.OAUTH_GOOGLE_CLIENT_SECRET == null
                || GribbitProperties.OAUTH_GOOGLE_CLIENT_SECRET.isEmpty()) {
            throw new RuntimeException("Google OAuth parameters not correctly specified in properties file");
        }
    }

    /**
     * Check if a user's access token has expired or is about to expire, and if so, generate a new access token using the user's refresh token.
     * 
     * @throws BadRequestException
     *             if the user is null, or doesn't have a refresh token, or if the token refresh fails.
     */
    public static void refreshAccessTokenIfNeeded(User user) throws BadRequestException {
        if (user == null) {
            throw new BadRequestException("User is null");
        }
        String refreshToken = user.getData("googleRefreshToken");
        String accessToken = user.getData("googleAccessToken");
        String accessTokenExpiresMillisStr = user.getData("googleAccessTokenExpiresMillis");
        if (accessToken == null || accessTokenExpiresMillisStr == null || Long.parseLong(accessTokenExpiresMillisStr) - System.currentTimeMillis() <= 60000L) {
            // Access token missing or expired, or will expire in <= 60 seconds -- generate a new access token from the refresh token

            if (refreshToken == null) {
                throw new BadRequestException("No refresh token");
            }
            Log.info("Updating access token for user " + user.id); // TODO: for debugging, remove later

            // Get a new access token using the refresh token.
            // Throws BadRequestException if the refresh token is bad. 
            AuthResponse auth = RequestBuilder.postToURLWithJSONResponse(AuthResponse.class, //
                    "https://accounts.google.com/o/oauth2/token", //
                    "refresh_token", refreshToken, //
                    "client_id", GribbitProperties.OAUTH_GOOGLE_CLIENT_ID, //
                    "client_secret", GribbitProperties.OAUTH_GOOGLE_CLIENT_SECRET, //
                    "grant_type", "refresh_token");

            // The access token obtained from the refresh token.
            accessToken = auth.access_token;
            long accessTokenExpiresMillis = System.currentTimeMillis() + (auth.expires_in == null ? 0L : Long.parseLong(auth.expires_in) * 1000L);

            // Replace refresh token with the new one, if provided (although in general, we won't be given a new refresh token if we already have one)
            refreshToken = auth.refresh_token != null ? auth.refresh_token : refreshToken;

            // Update token values in user object
            user.putData("googleRefreshToken", refreshToken);
            user.putData("googleAccessToken", accessToken);
            user.putData("googleAccessTokenExpiresMillis", Long.toString(accessTokenExpiresMillis));
            user.save();

        } else {
            // Access token has not expired yet -- nothing to do
        }
    }

    // See http://highaltitudedev.blogspot.com/2013/10/google-oauth2-with-jettyservlets.html

    // TODO: Need to set up the image to show on the approval page in the API console, and the email address to contact if something goes wrong

    private static String callbackURI() {
        return GribbitServer.uri + Route.forGet(GoogleLogin.class, "callback");
    }

    private static String getAuthorizationCodeURL(boolean forceApprovalPrompt) {
        return "https://accounts.google.com/o/oauth2/auth" //
                // The client id from the API console
                + "?client_id=" + GribbitProperties.OAUTH_GOOGLE_CLIENT_ID
                // TODO: what is this for?
                + "&response_type=code"
                // The api permissions we are requesting
                + "&scope=openid%20email"
                // Where to redirect to after authorization
                + "&redirect_uri=" + callbackURI()
                // "&state=this_can_be_anything_to_help_correlate_the_response%3Dlike_session_id" // TODO -- do we need this for anything? (Maybe to allow already logged-in user to sign into another service?)
                + "&state="
                // Request offline access -- generates a refresh token
                + "&access_type=offline"
                // This requires the user to verify which account to use, if they are already signed in, but even if they are only signed into one account brings up the approval page
                + (forceApprovalPrompt ? "&approval_prompt=force" : "");
    }

    // This handler is initially called with "/login" appended to the URI, initiating the OAuth process.
    // The route of this same handler is given to Google with "/callback" appended in place of "/login" to handle the OAuth2 callback after successful authentication.
    public void get(String action) throws Exception {
        User user = null;
        // If the user was denied access, we get back an error, e.g. "error=access_denied"
        String error = req.getQueryParam("error");
        if (error == null) {
            if ("login".equals(action)) {
                // Initial click by the user on the "Sign in with Google" button: redirect to Google to get an authorization code
                res.redirectToTrustedURL(getAuthorizationCodeURL(/* forceApprovalPrompt = */false));

            } else if ("callback".equals(action)) {
                // Handling a callback request from Google's OAuth2 server

                try {
                    // Exchange authorization code provided by Google for an access token.
                    // Throws BadRequestException if the authorization code is bad. 
                    AuthResponse auth = RequestBuilder.postToURLWithJSONResponse(AuthResponse.class, //
                            "https://accounts.google.com/o/oauth2/token", //
                            // Authorization code provided by Google
                            "code", req.getQueryParam("code"), //
                            "client_id", GribbitProperties.OAUTH_GOOGLE_CLIENT_ID, //
                            "client_secret", GribbitProperties.OAUTH_GOOGLE_CLIENT_SECRET, //
                            "redirect_uri", callbackURI(),//
                            "grant_type", "authorization_code");

                    // The access token obtained from the authorization code
                    String accessToken = auth.access_token;
                    long accessTokenExpiresIn = auth.expires_in == null ? 0L : Long.parseLong(auth.expires_in);
                    if (accessToken == null || accessTokenExpiresIn <= 0) {
                        // Should not happen, should always get an access token.
                        // On any result code other than 200 OK (e.g. 400 Bad Request / 401 Not Authorized), the
                        // POST request above will have already thrown an exception.
                        throw new BadRequestException("Could not fetch access token");
                    }
                    long accessTokenExpiresMillis = System.currentTimeMillis() + accessTokenExpiresIn * 1000;

                    // Get user's email address, name, gender, profile pic URL etc.
                    // Throws BadRequestException if the access token is bad. 
                    UserInfo userInfo = RequestBuilder.getFromURLWithJSONResponse(UserInfo.class, //
                            "https://www.googleapis.com/oauth2/v1/userinfo", //
                            "access_token", accessToken);

                    String email = userInfo.email;
                    if (email == null || email.isEmpty()) {
                        // Need an email address to look up user
                        error = "No email address";

                    } else {

                        // Look up user based on email address
                        user = User.findByEmail(email);

                        // Refresh token is only non-null the first time the user authorizes offline access.
                        // We have to store the token and retrieve it from the user record on subsequent operations.
                        String refreshToken = auth.refresh_token;
                        if (refreshToken == null && user != null) {
                            // See: https://developers.google.com/drive/web/credentials
                            refreshToken = user.getData("googleRefreshToken");
                        }

                        if (refreshToken == null) {
                            // If we still don't have a refresh token, we have to ask the user for the "Have offline access" permission.
                            // This can happen when this is not the first time the user has logged in, according to the browser's cookies,
                            // but the user's database record has been deleted, deleting the refresh token.
                            // See: http://stackoverflow.com/questions/10827920/google-oauth-refresh-token-is-not-being-received
                            if (user != null) {
                                user.logOut(res);
                            }
                            res.redirectToTrustedURL(getAuthorizationCodeURL(/* forceApprovalPrompt = */true));

                        } else {

                            if (user == null) {
                                // There was no user with this email address -- create a new user
                                user = User.createFederatedLoginUser(email, res);

                                user.emailValidated = userInfo.verified_email != null && userInfo.verified_email;
                                if (!user.emailValidated) {
                                    // Add a flash message if the verified_email field is not set to true, so that the user
                                    // doesn't get confused about why we're asking them to validate their email address.
                                    // (TODO: under what circumstances will Google report verified_email == false?)
                                    res.addFlashMessage(FlashType.WARNING, "Please verify email address",
                                            "Google has informed us that you have not verified your email address with them. Please do so, then log out and log back into this site.");
                                }
                                user.putData("name", userInfo.name);
                                user.putData("givenName", userInfo.given_name);
                                user.putData("familyName", userInfo.family_name);
                                user.putData("gender", userInfo.gender);
                                user.putData("gPlusID", userInfo.id);
                                user.putData("gPlusProfile", userInfo.link);
                                user.putData("gPlusPhotoURL", userInfo.picture); // TODO: fetch and cache?

                                // res.clearFlashMessages();
                                // res.addFlashMessage(FlashType.INFO, "Welcome", "We created a new account for you with the email address " + email);
                                Log.info(req.getRequestor() + "\tCreated new Google user " + email);

                            } else {
                                if (!user.emailValidated) {
                                    // If email was not previously validated, see if it is now.
                                    user.emailValidated = userInfo.verified_email != null && userInfo.verified_email;
                                }

                                // Log in existing user with this email address
                                user.logIn(res);

                                // res.clearFlashMessages();
                                // res.addFlashMessage(FlashType.SUCCESS, "Welcome back", "You are now signed in with the email address " + email);
                                Log.info(req.getRequestor() + "\tGoogle login okay for user " + email);
                            }

                            // Store tokens in user record
                            user.putData("googleRefreshToken", refreshToken);
                            user.putData("googleAccessToken", accessToken);
                            user.putData("googleAccessTokenExpiresMillis", Long.toString(accessTokenExpiresMillis));
                            user.save();

                            // Redirect back home
                            res.redirectToTrustedURL("/");
                        }
                    }
                } catch (BadRequestException e) {
                    error = "Bad request: " + e.getMessage();
                } catch (UnauthorizedException e) {
                    error = "Unauthorized: " + e;
                } catch (Exception e) {
                    Log.exception("Exception during Google OAuth2 login", e);
                    if (error == null) {
                        error = e.toString();
                    }
                }
            } else {
                error = "Bad URI parameter";
            }
        }
        if (error != null) {
            Log.error("Error during Google OAuth2 login: " + error);
            if (user != null) {
                user.logOut(res);
            }
            res.clearFlashMessages();
            if (error.contains("Unauthorized")) {
                res.addFlashMessage(FlashType.ERROR, "Error",
                        "Could not log in, you are not authorized to access this site. Please contact the site administrator for authorization.");
            } else {
                res.addFlashMessage(FlashType.ERROR, "Error", "Could not log in, please check your password and try again, or contact the site administrator.");
            }
            if ("callback".equals(action)) {
                // We don't want the long callback URI in the browser address field, so redirect to the unauthorized handler's route
                res.redirect(GribbitServer.siteResources.getUnauthorizedRoute().getHandler());
            } else {
                // Otherwise just call the unauthorized handler without actually redirecting to the unauthorized route
                GribbitServer.siteResources.getUnauthorizedRoute().callHandler(req, res);
            }
        }
    }
}
