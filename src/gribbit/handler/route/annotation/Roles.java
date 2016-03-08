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
package gribbit.handler.route.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This annotation may be added to a RouteHandler class, or to its get() or post() methods, for Role-Based Access
 * Control.
 * 
 * If the Roles annotation is added to a RouteHandler class, the annotation is inherited by any methods that do not
 * have their own Roles annotation. If a method has a Roles annotation, any Roles annotation on the class is ignored
 * for that method.
 * 
 * Listing the role RoleNames.PUBLIC means a user doesn't even have to be logged in to access the route.
 * 
 * If a Roles annotation is not present on a route, a user must be logged in to access the route, but the user does
 * not have to have a specific role.
 * 
 * If one or more roles are given in a Roles annotation, the user must have one or more of the listed roles to
 * access the route.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Roles {
    /** The Authorized roles for the route, or "public" for anyone. */
    String[] value();
}
