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

import gribbit.response.RedirectResponse;
import gribbit.response.Response;
import gribbit.server.siteresources.SiteResources;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.ext.web.RoutingContext;

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
    public Response generateErrorResponse(RoutingContext routingContext, SiteResources siteResources) {
        return new RedirectResponse(redirectURL);
    }
}