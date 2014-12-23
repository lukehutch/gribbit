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
package gribbit.route;

import gribbit.auth.Cookie;
import gribbit.auth.User;
import gribbit.request.Request;
import gribbit.response.flashmsg.FlashMessage.FlashType;

import java.util.Collection;

/**
 * A route handler. Implement the get() method with optional params to accept URL params, and/or the post() method with
 * one optional param of type DataModel to populate the DataModel values from POST param values.
 */
public interface Route {

    // This is overridden in the InvokeHandler to return the actual request
    public default Request getRequest() {
        return null;
    }

    /** Add a flash message (a message that will be popped up at the top of a webpage the next time a page is served. */
    public default void addFlashMessage(FlashType flashType, String strongText, String flashMessage) {
        getRequest().addFlashMessage(flashType, strongText, flashMessage);
    }

    /** Clear flash messages. */
    public default void clearFlashMessages() {
        getRequest().clearFlashMessages();
    }

    /** Add a cookie to the response. */
    public default void addCookie(Cookie cookie) {
        // (Cookies are stored in the request temporarily, but are moved to the response when the response is served)
        getRequest().setCookie(cookie);
    }

    /** Delete a named cookie in the browser when sending the response. */
    public default void deleteCookie(String cookieName) {
        // (Cookies are stored in the request temporarily, but are moved to the response when the response is served)
        getRequest().deleteCookie(cookieName);
    }

    /** Get all cookies. */
    public default Collection<Cookie> getCookies() {
        return getRequest().getCookies();
    }

    /** Get the String value of a specific cookie. */
    public default String getCookieValue(String cookieName) {
        return getRequest().getCookieValue(cookieName);
    }

    /**
     * Auth required, but validated email NOT required.
     */
    public interface AuthRequired extends Route {
        // This is overridden in the InvokeHandler to return the actual User object
        public default User getUser() {
            return null;
        }

        /**
         * Delete the user's session cookies, and invalidate their login session in the database if they are currently
         * logged in.
         */
        public default void logOutUser() {
            User user = getUser();
            if (user != null) {
                // Invalidate user's session and CSRF token in the database if they are currently logged in 
                user.logOut(getRequest());
            } else {
                // Otherwise just remove the session cookies
                User.removeLoginCookies(getRequest());
            }
        }
    }

    /**
     * Auth and validated email required.
     */
    public interface AuthAndValidatedEmailRequired extends AuthRequired {
    }

    /**
     * Neither auth nor validated email required.
     */
    public interface AuthNotRequired extends Route {
        /**
         * Delete the user's session cookies, and invalidate their login session in the database if they are currently
         * logged in.
         */
        public default void logOutUser() {
            User.logOutUser(getRequest());
        }
    }
}