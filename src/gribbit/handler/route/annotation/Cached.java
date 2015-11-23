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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * If this annotation is added to a RouteHandler class, the response data should be cached for the number of seconds
 * specified in the annotation parameter maxAgeSeconds. If the parameter is omitted, the value will default to 5
 * minutes.
 * 
 * In practical terms, if this header is present, the response will be served on a hashURI to all clients, and the
 * MD5-hashcode will be re-computed in the background when some client requests this resource at a minimum interval
 * of maxAgeSeconds.
 * 
 * This annotation will cause the following behavior:
 * 
 * (1) The Last-Modified header will be set to the current server time on the current response, and that will be
 * checked against
 * 
 * The server will subsequently return "Not Modified" to the same client for this resource until the specified
 * duration is complete, if the resource is fetched directly (not by a hash URI)
 * 
 * (2) will schedule the response object for this resource for subsequent hashing, generating a hash URI;
 * 
 * (3) once the hashing is complete, will cause the server to start to substitute the hash URI for the resource in
 * place of the resource's original URI in href/src fields of all served HTML content, with indefinite (i.e. 1 year)
 * cache expiry;
 * 
 * (4) after the maxAgeSeconds time period is up, will cause the server to re-hash the resource.
 * 
 * Important notes:
 * 
 * (i) This header should only be added to route handlers whose content does not depend upon the identity of the
 * user, because URI hashing is not user-dependent.
 * 
 * (ii) If this annotation is added to a route handlers that returns an HTMLResponse, the annotation is ignored,
 * because HTML responses cannot be indefinitely cached, otherwise the hash URIs of their linked resources would not
 * be able to be updated when the linked resources change.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Cached {
    /** The Maximum age to cache this resource for, in seconds */
    int maxAgeSeconds() default 5 * 60;
}
