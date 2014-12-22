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
import gribbit.server.Route;
import gribbit.server.response.Response;
import gribbit.server.response.TextResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Default 404 handler.
 */
@RouteOverride("/gribbit/err/404")
public class NotFound extends Route.AuthNotRequired {
    public Response get() {
        return new TextResponse(HttpResponseStatus.NOT_FOUND, "404: Not Found");
    }
}
