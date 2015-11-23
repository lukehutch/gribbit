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

import gribbit.request.Request;
import gribbit.response.ErrorResponse;
import gribbit.route.Route;
import gribbit.server.GribbitServer;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * This exception is thrown when an exception occurs due to internal state that is not the fault of the user.
 */
public class InternalServerErrorException extends RequestHandlingException {
    /**
     * This exception is thrown when an exception occurs due to internal state that is not the fault of the user.
     */
    public InternalServerErrorException() {
        super(new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"));
    }

    /**
     * This exception is thrown when an exception occurs due to internal state that is not the fault of the user.
     */
    public InternalServerErrorException(String msg) {
        super(new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"), msg);
    }

    /**
     * This exception is thrown when an exception occurs due to internal state that is not the fault of the user.
     */
    public InternalServerErrorException(Exception e) {
        super(new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"), e);
    }

    /**
     * This exception is thrown when an exception occurs due to internal state that is not the fault of the user.
     */
    public InternalServerErrorException(Request request, String msg, Exception e) throws RequestHandlingException {
        super(msg, e);
        generateResponse(request);
    }

    /**
     * This exception is thrown when an exception occurs due to internal state that is not the fault of the user.
     */
    public InternalServerErrorException(Request request, String msg) throws RequestHandlingException {
        super(msg);
        generateResponse(request);
    }

    private void generateResponse(Request request) throws RequestHandlingException {
        Route customHandlerRoute = GribbitServer.siteResources.getInternalServerErrorRoute();
        if (customHandlerRoute != null) {
            // Call the get() method of the custom error handler route.
            // Throws RequestHandlingException in the place of the object that is currently being constructed if
            // a RequestHandlingException is thrown by the get() method of the custom error handler
            this.errorResponse = customHandlerRoute.callErrorHandler(request);
            // Set status code in case custom handler forgets to set it
            this.errorResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        } else {
            this.errorResponse = new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal Server Error");
        }
    }
}