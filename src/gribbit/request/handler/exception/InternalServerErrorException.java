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
package gribbit.request.handler.exception;

import gribbit.response.ErrorResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * This exception is thrown when an exception occurs due to internal state that is not the fault of the user.
 */
public class InternalServerErrorException extends ExceptionResponse {
    public InternalServerErrorException() {
        super(new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"));
    }

    public InternalServerErrorException(String msg) {
        super(new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"), msg);
    }

    public InternalServerErrorException(Exception e) {
        super(new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"), e);
    }

    public InternalServerErrorException(String msg, Exception e) {
        super(new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"), msg, e);
    }
}