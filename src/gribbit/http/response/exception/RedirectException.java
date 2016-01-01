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
package gribbit.http.response.exception;

import gribbit.http.request.Request;
import gribbit.http.response.Response;
import gribbit.http.response.RedirectResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * This exception is thrown to redirect a user to another URL.
 */
public class RedirectException extends LightweightResponseException {
    private String redirectURL;

    /**
     * Redirect to a raw URL. Not recommended for site-local URLs; it's better to use one of the other constructors
     * that takes a Route as a parameter.
     */
    public RedirectException(String redirectURL) {
        super(HttpResponseStatus.FOUND);
        this.redirectURL = redirectURL;
    }

    @Override
    public Response generateErrorResponse(Request request) {
        return new RedirectResponse(request, redirectURL);
    }
}