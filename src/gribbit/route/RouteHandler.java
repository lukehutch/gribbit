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
package gribbit.route;

import gribbit.auth.User;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;

/**
 * A route handler. Override the public default get() method with optional params to accept URL params, and/or the
 * public default post() method with one optional param of type DataModel to populate the DataModel values from POST
 * param values. Note: you should normally subclass RouteHandlerAuthNotRequired, RouteHandlerAuthRequired or
 * RouteHandlerAuthAndValidatedEmailRequired, and not RouteHandler itself.
 */
public abstract class RouteHandler {
    public RoutingContext routingContext;
    public Session session;
    public User user;
}
