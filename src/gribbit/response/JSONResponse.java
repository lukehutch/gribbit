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

import gribbit.auth.User;
import gribbit.model.DataModel;
import gribbit.server.config.GribbitProperties;
import gribbit.util.thirdparty.UTF8;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;

public class JSONResponse extends Response {

    private Object content;

    public JSONResponse(HttpResponseStatus status, Object content) {
        super(status, "application/json;charset=utf-8");
        this.content = content;
    }

    @Override
    public ByteBuf getContent(User user, boolean isGetModelRequest) {
        return Unpooled
                .wrappedBuffer(UTF8.stringToUTF8(DataModel.toJSON(content, GribbitProperties.PRETTY_PRINT_JSON)));
    }

}
