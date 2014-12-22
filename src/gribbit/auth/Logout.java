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
package gribbit.auth;

import gribbit.handler.route.annotation.RouteOverride;
import gribbit.server.RestHandler;
import gribbit.server.response.RedirectResponse;
import gribbit.server.response.Response;

/**
 * Default GET/POST handler for /logout URL. Logs the user out then redirects to the home page.
 */
@RouteOverride("/logout")
public class Logout extends RestHandler.AuthNotRequired {
    public Response get() {
        User.logOutUser(request);
        return new RedirectResponse("/");
    }

    public Response post() {
        return get();
    }
}
