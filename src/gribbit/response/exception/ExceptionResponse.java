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

import gribbit.response.ErrorResponse;
import gribbit.response.Response;
import gribbit.util.Log;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * This abstract class should be extended by all exceptions that can be thrown in the course of handling an HTTP
 * request, where the exception should generate an HTTP response.
 */
public abstract class ExceptionResponse extends Exception {

    Response exceptionResponse;

    /**
     * This abstract class should be extended by all exceptions that can be thrown in the course of handling an HTTP
     * request, where the exception should generate an HTTP response.
     */
    public ExceptionResponse() {
    }

    /**
     * This abstract class should be extended by all exceptions that can be thrown in the course of handling an HTTP
     * request, where the exception should generate an HTTP response.
     */
    public ExceptionResponse(String msg) {
        super(msg);
    }

    /**
     * This abstract class should be extended by all exceptions that can be thrown in the course of handling an HTTP
     * request, where the exception should generate an HTTP response.
     */
    public ExceptionResponse(Exception e) {
        super(e);
        Log.exceptionWithoutCallerRef("Exception while generating response", e);
    }

    /**
     * This abstract class should be extended by all exceptions that can be thrown in the course of handling an HTTP
     * request, where the exception should generate an HTTP response.
     */
    public ExceptionResponse(String msg, Exception e) {
        super(msg, e);
        Log.exceptionWithoutCallerRef("Exception while generating response: " + msg, e);
    }

    /**
     * This abstract class should be extended by all exceptions that can be thrown in the course of handling an HTTP
     * request, where the exception should generate an HTTP response.
     */
    public ExceptionResponse(Response exceptionResponse) {
        this();
        this.exceptionResponse = exceptionResponse;
    }

    /**
     * This abstract class should be extended by all exceptions that can be thrown in the course of handling an HTTP
     * request, where the exception should generate an HTTP response.
     */
    public ExceptionResponse(Response exceptionResponse, String msg) {
        this(msg);
        this.exceptionResponse = exceptionResponse;
    }

    /**
     * This abstract class should be extended by all exceptions that can be thrown in the course of handling an HTTP
     * request, where the exception should generate an HTTP response.
     */
    public ExceptionResponse(Response exceptionResponse, Exception e) {
        this(e);
        this.exceptionResponse = exceptionResponse;
    }

    /**
     * This abstract class should be extended by all exceptions that can be thrown in the course of handling an HTTP
     * request, where the exception should generate an HTTP response.
     */
    public ExceptionResponse(Response exceptionResponse, String msg, Exception e) {
        this(msg, e);
        this.exceptionResponse = exceptionResponse;
    }

    /**
     * This abstract class should be extended by all exceptions that can be thrown in the course of handling an HTTP
     * request, where the exception should generate an HTTP response.
     */
    public void setResponse(Response exceptionResponse) {
        this.exceptionResponse = exceptionResponse;
    }

    /**
     * Returns a Response object for this exception. N.B. the exceptionResponse object needs to be initialized by all
     * subclasses.
     */
    public Response getResponse() {
        if (exceptionResponse == null) {
            // Should not happen, subclasses should initialize exceptionResponse
            Log.exception("exceptionResponse not initialized", this);
            return new ErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
        } else {
            return exceptionResponse;
        }
    }

    /**
     * Return the exception name without the "Exception" suffix, if present, and insert spaces at lowercase-uppercase
     * transitions, i.e. "InternalServerErrorException" -> "Internal Server Error".
     */
    public String getResponseType() {
        String name = getClass().getSimpleName();
        int end = name.endsWith("Exception") ? name.length() - 9 : name.length();
        StringBuilder buf = new StringBuilder(64);
        for (int i = 0; i < end; i++) {
            char c = name.charAt(i);
            if (buf.length() > 0 && Character.isLowerCase(buf.charAt(buf.length() - 1)) && Character.isUpperCase(c)) {
                buf.append(' ');
            }
            buf.append(c);
        }
        return buf.toString();
    }
}
