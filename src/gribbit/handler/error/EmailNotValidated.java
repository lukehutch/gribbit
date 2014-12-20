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
import gribbit.server.RestHandler;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Handler for cases where the user is logged in but their email address has not yet been validated, and
 * where the user's email address must be validated for them to be able to access a given route.
 */
@RouteOverride("/gribbit/err/email-not-validated")
public class EmailNotValidated extends RestHandler.AuthNotRequired {
    public void get() {
        res.setStatus(HttpResponseStatus.UNAUTHORIZED);
        res.setContent("Email address has not yet been validated");
    }
}
