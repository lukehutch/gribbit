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

import gribbit.http.request.Request;
import gribbit.http.response.Response;
import gribbit.server.config.GribbitProperties;
import gribbit.util.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;

public class JSONResponse extends Response {

    private Object content;

    public JSONResponse(HttpResponseStatus status, Object content) {
        super(status);
        this.content = content;
    }

    @Override
    public ByteBuf getContent(Request request) {
        String jsonStr = JSON.toJSON(content, GribbitProperties.PRETTY_PRINT_JSON);
        ByteBuf contentBytes = Unpooled.buffer(jsonStr.length() * 3 / 2);
        ByteBufUtil.writeUtf8(contentBytes, jsonStr);
        return contentBytes;
    }

    @Override
    public String getContentType(Request request) {
        return "application/json;charset=utf-8";
    }

}
