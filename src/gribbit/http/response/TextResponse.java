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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;

public class TextResponse extends ByteBufResponse {
    public TextResponse(HttpResponseStatus status, String content) {
        super(status, "text/plain;charset=utf-8", toUTF8Bytes(content));
    }
    
    private static ByteBuf toUTF8Bytes(String str) {
        ByteBuf contentBytes = Unpooled.buffer(str.length() * 3 / 2);
        ByteBufUtil.writeUtf8(contentBytes, str);
        return contentBytes;
    }
}