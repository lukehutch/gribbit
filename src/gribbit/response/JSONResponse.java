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
package gribbit.response;

import gribbit.util.JSON;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class JSONResponse extends Response {

    private String contentStr;

    public JSONResponse(HttpResponseStatus status, JsonObject jsonObject) {
        super(status);
        this.contentStr = jsonObject.encode();
    }

    public JSONResponse(HttpResponseStatus status, JsonArray jsonArray) {
        super(status);
        this.contentStr = jsonArray.encode();
    }

    public JSONResponse(HttpResponseStatus status, Object obj) {
        super(status);
        this.contentStr = JSON.toJSON(obj);
    }

    public JSONResponse(JsonObject jsonObject) {
        this(HttpResponseStatus.OK, jsonObject);
    }

    public JSONResponse(JsonArray jsonArray) {
        this(HttpResponseStatus.OK, jsonArray);
    }

    public JSONResponse(Object obj) {
        this(HttpResponseStatus.OK, obj);
    }

    @Override
    public void send(RoutingContext routingContext) {
        sendHeaders(routingContext, "application/json;charset=utf-8");
        routingContext.response().end(contentStr);
    }
}
