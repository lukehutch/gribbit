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
package gribbit.server;

import gribbit.auth.User;

/**
 * RestHandler: a REST request Handler. Implement the get() method with optional params to accept URL params, and/or the
 * post() method with one optional param of type DataModel to populate the DataModel values from POST param values.
 */
public abstract class RestHandler {

    protected Request req;
    protected Response res;

    /**
     * Auth required, but validated email NOT required.
     */
    public static class AuthRequired extends RestHandler {
        protected User user;
    }

    /**
     * Auth and validated email required.
     */
    public static class AuthAndValidatedEmailRequired extends AuthRequired {
    }

    /**
     * Neither auth nor validated email required.
     */
    public static class AuthNotRequired extends RestHandler {
    }
}
