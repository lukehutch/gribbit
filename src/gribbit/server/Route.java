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

import gribbit.auth.Cookie;
import gribbit.auth.User;
import gribbit.server.response.flashmsg.FlashMessage.FlashType;

import java.util.Collection;

/**
 * A route handler. Implement the get() method with optional params to accept URL params, and/or the post() method with
 * one optional param of type DataModel to populate the DataModel values from POST param values.
 */
public abstract class Route {

    protected Request request;

    /** Add a flash message (a message that will be popped up at the top of a webpage the next time a page is served. */
    protected void addFlashMessage(FlashType flashType, String strongText, String flashMessage) {
        request.addFlashMessage(flashType, strongText, flashMessage);
    }

    /** Clear flash messages. */
    protected void clearFlashMessages() {
        request.clearFlashMessages();
    }

    /** Add a cookie to the response. */
    protected void addCookie(Cookie cookie) {
        // (Stored in the request temporarily)
        request.setCookie(cookie);
    }

    /** Delete a named cookie in the browser when sending the response. */
    protected void deleteCookie(String cookieName) {
        // (Stored in the request temporarily)
        request.deleteCookie(cookieName);
    }

    public Collection<Cookie> getCookies() {
        return request.getCookies();
    }

    /**
     * Auth required, but validated email NOT required.
     */
    public static class AuthRequired extends Route {
        protected User user;
    }

    /**
     * Auth and validated email required.
     */
    public static class AuthAndValidatedEmailRequired extends AuthRequired {
    }

    /**
     * Neither auth nor validated email required.
     */
    public static class AuthNotRequired extends Route {
    }
}
