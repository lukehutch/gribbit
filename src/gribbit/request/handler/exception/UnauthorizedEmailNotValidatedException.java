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

import gribbit.auth.Cookie;
import gribbit.response.ErrorResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * This exception is thrown when a user tries to access a resource they are authorized to access, but which needs their
 * email address to be validated before they are allowed to log in.
 */
public class UnauthorizedEmailNotValidatedException extends ExceptionResponse {
    /**
     * Return Unauthorized: Email Not Validated, and set the redirect cookie so that if the user does successfully log
     * in, they'll end up where they were originally trying to go when they were denied access.
     */
    public UnauthorizedEmailNotValidatedException(String originalRequestURI) {
        super(new ErrorResponse(HttpResponseStatus.UNAUTHORIZED, "Unauthorized: email not validated"));

        // Redirect the user back to the page they were trying to get to once they do log in successfully
        this.exceptionResponse //
                .logOutUser() //
                .setCookie(new Cookie(Cookie.REDIRECT_AFTER_LOGIN_COOKIE_NAME, "/", originalRequestURI, 300));
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