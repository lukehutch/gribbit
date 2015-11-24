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
package gribbit.http.response;

import gribbit.http.request.Request;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Raw ByteBuf response.
 */
public class ByteBufResponse extends Response {
    private String contentType;
    ByteBuf content;
    
    public ByteBufResponse(HttpResponseStatus status) {
        super(status);
    }

    public ByteBufResponse(HttpResponseStatus status, String contentType, ByteBuf content) {
        super(status);
        this.contentType = contentType;
        this.content = content;
    }

    @Override
    public String getContentType(Request request) {
        return contentType;
    }

    @Override
    public ByteBuf getContent(Request request) {
        return content;
    }
}
