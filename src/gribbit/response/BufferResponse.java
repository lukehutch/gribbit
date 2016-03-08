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

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/** Buffer response. */
public class BufferResponse extends Response {
    protected Buffer content;
    private String contentType;

    public BufferResponse(HttpResponseStatus status, Buffer content, String contentType) {
        super(status);
        this.content = content;
        this.contentType = contentType;
    }

    public BufferResponse(Buffer content, String contentType) {
        this(HttpResponseStatus.OK, content, contentType);
    }

    @Override
    public void send(RoutingContext routingContext) {
        sendHeaders(routingContext, contentType);
        routingContext.response().end(content);
    }
}
