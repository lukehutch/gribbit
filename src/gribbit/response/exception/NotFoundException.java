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
package gribbit.response.exception;

import gribbit.auth.User;
import gribbit.request.Request;
import gribbit.response.ErrorResponse;
import gribbit.route.RouteInfo;
import gribbit.server.GribbitServer;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.regex.Pattern;

/**
 * This exception is thrown when a user tries to access a resource that doesn't exist (404).
 */
public class NotFoundException extends ExceptionResponse {

    private static final Pattern favicon = Pattern.compile("^(.*/)?favicon\\.(ico|png|gif|jpeg|jpg|apng)$");

    /**
     * This exception is thrown when a user tries to access a resource that doesn't exist (404).
     */
    public NotFoundException(Request request, User user) throws ExceptionResponse {
        String uri = request.getURI();
        RouteInfo customHandlerRoute = GribbitServer.siteResources.getNotFoundRoute();
        if (customHandlerRoute != null) {
            if (favicon.matcher(uri).matches()) {
                // Don't give favicon requests a custom 404 page when there is a custom handler registered
                this.exceptionResponse = new ErrorResponse(HttpResponseStatus.NOT_FOUND, "404 Not Found");
            } else {
                // Call the get() method of the custom error handler route. 
                // Throws ExceptionResponse in the place of the object that is currently being constructed if
                // an ExceptionResponse is thrown by the get() method of the custom error handler
                this.exceptionResponse = customHandlerRoute.callHandler(request, user, /* isErrorHandler = */true);
                // Set status code in case custom handler forgets to set it
                this.exceptionResponse.setStatus(HttpResponseStatus.NOT_FOUND);
            }
        } else {
            this.exceptionResponse = new ErrorResponse(HttpResponseStatus.NOT_FOUND, "404 Not Found");
        }
    }

    /**
     * Don't pay the cost of filling in the stack trace -- see
     * http://java-performance.info/throwing-an-exception-in-java-is-very-slow/
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}