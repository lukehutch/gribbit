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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Raw ByteBuf response.
 */
public class ByteBufResponse extends Response {
    protected ByteBuf content;

    public ByteBufResponse(Request request, HttpResponseStatus status, ByteBuf content, String contentType) {
        super(request, status, contentType);
        this.content = content;
    }

    public ByteBufResponse(Request request, ByteBuf content, String contentType) {
        this(request, HttpResponseStatus.OK, content, contentType);
    }

    @Override
    public void writeResponse(ChannelHandlerContext ctx) {
        contentLength = content.readableBytes();
        
        try {
            sendHeaders(ctx);
            if (!request.isHEADRequest()) {
                ctx.write(content);
            }
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

        } catch (Exception e) {
            if (content.refCnt() > 0) {
                content.release();
            }
            throw e;
        }
    }

    @Override
    public void close() {
    }
}
