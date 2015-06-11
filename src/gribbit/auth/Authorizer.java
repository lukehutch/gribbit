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
package gribbit.auth;

import gribbit.request.Request;
import gribbit.response.exception.ExceptionResponse;
import gribbit.route.Route;

/**
 * Used with with Auth annotation to determine if a user is allowed to access a given route. This method will always be
 * called with a non-null value for request.getUser(). Throws an ExceptionResponse if the user is not logged in, or is
 * logged in but is not authorized for this route.
 */
public interface Authorizer {
    /**
     * Check if the user is authorized for this route. Throws an ExceptionResponse if the user is not authorized.
     * 
     * @param request
     *            The request. Note that request.getUser() will always return a non-null User object, because an
     *            Authorizer is only called if the route requires the user to be logged in, and the User object is
     *            looked up for the current user (based on the session cookie) before the Authorizer is called.
     * @param route
     *            The route to check.
     * @throws ExceptionResponse
     *             Thrown if the user is not logged in, or is logged in but is not authorized for this route.
     */
    public void checkAuth(Request request, Route route) throws ExceptionResponse;
}
