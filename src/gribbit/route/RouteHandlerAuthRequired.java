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

import gribbit.auth.User;
import gribbit.response.Response;

/**
 * Auth required, but validated email NOT required.
 */
public interface RouteHandlerAuthRequired extends RouteHandler {
    /** Return the user that is currently logged in. */
    public default User getUser() {
        // Should not happen. This is overridden in the InvokeHandler to return the actual User object.
        throw new RuntimeException("getUser() not intercepted by InvokeHandler");
    }

    /**
     * Delete the user's session cookies, and invalidate their login session in the database if they are currently
     * logged in.
     */
    public default void logOutUser(Response response) {
        User user = getUser();
        if (user != null) {
            // Invalidate user's session and CSRF token in the database if they are currently logged in 
            user.logOut(response);
        } else {
            // Otherwise just remove the session cookies
            User.removeLoginCookies(response);
        }
    }
}