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

import gribbit.http.request.Request;
import gribbit.response.flashmsg.FlashMessage;

/**
 * A route handler. Override the public default get() method with optional params to accept URL params, and/or the
 * public default post() method with one optional param of type DataModel to populate the DataModel values from POST
 * param values. Note: you should normally subclass RouteHandlerAuthNotRequired, RouteHandlerAuthRequired or
 * RouteHandlerAuthAndValidatedEmailRequired, and not RouteHandler itself.
 */
public abstract class RouteHandler {

    public Request request;

    /**
     * Add a flash message (a message that will be popped up at the top of a webpage the next time a page is served.
     */
    public void addFlashMessage(FlashMessage flashMessage) {
        request.addFlashMessage(flashMessage);
    }

    /** Clear flash messages. */
    public void clearFlashMessages() {
        request.clearFlashMessage();
    }

    /**
     * Convenience method to get the value of a named cookie from the request. If there are multiple cookies with
     * this name, returns the value of the cookie with the longest path, as per the HTTP spec.
     */
    public String getCookieValue(String cookieName) {
        return request.getCookieValue(cookieName);
    }
}
