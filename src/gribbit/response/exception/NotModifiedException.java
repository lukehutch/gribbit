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
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * This exception is thrown when a user tries to access a resource that hasn't changed. This RequestHandlingException is
 * special in that it does not take an exception message in the constructor, instead it takes the last modified time of
 * the resource that has not been modified since the timestamp of the version stored in the browser's cache.
 */
public class NotModifiedException extends RequestHandlingException {
    /**
     * This exception is thrown when a user tries to access a resource that hasn't changed. This RequestHandlingException is
     * special in that it does not take an exception message in the constructor, instead it takes the last modified time of
     * the resource that has not been modified since the timestamp of the version stored in the browser's cache.
     */
    public NotModifiedException(long lastModifiedEpochSeconds) {
        super(new ErrorResponse(HttpResponseStatus.NOT_MODIFIED, "") //
                .setLastModifiedEpochSeconds(lastModifiedEpochSeconds));
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