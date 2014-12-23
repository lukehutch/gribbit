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
package gribbit.handler.error;

import gribbit.handler.route.annotation.RouteOverride;
import gribbit.response.Response;
import gribbit.response.TextResponse;
import gribbit.server.siteresources.route.Route;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Default handler for "bad request" HTTP errors.
 * 
 * N.B. Netty changes the URI of the request to "/bad-request" if the HTTP request was malformed, so we need to handle
 * this route anyway. We catch that case automatically here by setting the route of this handler to that URI, so that
 * this handler is automatically called for requests that were detected as mal-formed by Netty before we even see them.
 * We can also call this handler ourselves if user-supplied data is invalid.
 */
@RouteOverride("/gribbit/err/bad-request")
public interface BadRequest extends Route.AuthNotRequired {
    public default Response get() {
        return new TextResponse(HttpResponseStatus.BAD_REQUEST, "Bad request");
    }
}
