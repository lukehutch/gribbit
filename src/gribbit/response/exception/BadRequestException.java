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

import gribbit.http.logging.Log;
import gribbit.http.request.Request;
import gribbit.http.response.ErrorResponse;
import gribbit.route.Route;
import gribbit.server.GribbitServer;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * This exception is thrown when user-supplied data in the request is invalid.
 */
public class BadRequestException extends RequestHandlingException {
    /**
     * This exception is thrown when user-supplied data in the request is invalid.
     */
    public BadRequestException(Request request, String msg) throws RequestHandlingException {
        super(msg);
        if (msg != null) {
            Log.fine("Bad Request: " + msg);
        }
        Route customHandlerRoute = GribbitServer.siteResources.getBadRequestRoute();
        if (customHandlerRoute != null && request != null) {
            // Call the get() method of the custom error handler route.
            // Throws RequestHandlingException in the place of the object that is currently being constructed if
            // a RequestHandlingException is thrown by the get() method of the custom error handler
            this.errorResponse = customHandlerRoute.callErrorHandler(request);
            // Set status code in case custom handler forgets to set it
            this.errorResponse.setStatus(HttpResponseStatus.BAD_REQUEST);
        } else {
            this.errorResponse = new ErrorResponse(HttpResponseStatus.BAD_REQUEST, "Bad Request");
        }
    }

    /**
     * This exception is thrown when user-supplied data in the request is invalid.
     */
    public BadRequestException() throws RequestHandlingException {
        this(null, null);
    }

    /**
     * This exception is thrown when user-supplied data in the request is invalid.
     */
    public BadRequestException(Request request) throws RequestHandlingException {
        this(request, null);
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