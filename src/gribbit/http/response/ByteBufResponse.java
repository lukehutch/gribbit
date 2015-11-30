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

import gribbit.http.logging.Log;
import gribbit.http.request.Request;
import gribbit.util.WebUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/**
 * Raw ByteBuf response.
 */
public class ByteBufResponse extends GeneralResponse {

    protected ByteBuf content;

    private static final boolean COMPRESS_CONTENT = true;

    /** Create an empty response */
    public ByteBufResponse(Request request, HttpResponseStatus status) {
        super(request, status);
    }

    public ByteBufResponse(Request request, HttpResponseStatus status, ByteBuf content, String contentType) {
        super(request, status, contentType);
        this.gzipContent = COMPRESS_CONTENT && request.acceptEncodingGzip() && content.readableBytes() > 1024
                && WebUtils.isCompressibleContentType(contentType);
        this.content = content;
    }

    public ByteBufResponse(Request request, ByteBuf content, String contentType) {
        this(request, HttpResponseStatus.OK, content, contentType);
    }

    @Override
    public void writeResponse(ChannelHandlerContext ctx) {
        if (this.gzipContent) {
            ByteBuf gzippedContent = ctx.alloc().buffer(content.readableBytes());
            try {
                // TODO: compare speed to using JZlib.GZIPOutputStream
                try (GZIPOutputStream gzipStream = new GZIPOutputStream(new ByteBufOutputStream(gzippedContent))) {
                    gzipStream.write(content.array(), 0, content.readableBytes());
                }
                // Release the content ByteBuf after last usage, and then use gzipped content instead
                content.release();
                content = gzippedContent;
                contentEncodingGzip = true;
            } catch (IOException e) {
                // Should not happen
                Log.exception("Could not gzip content", e);
                gzippedContent.release();
            }
        }
        contentLength = content.readableBytes();

        sendHeaders(ctx);

        if (!request.isHEADRequest()) {
            content.retain(); // TODO: is this right? See close() below
            ctx.write(content);
        }
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    @Override
    public void close() {
        if (content != null) {
            if (content.refCnt() > 0) {
                content.release();
            }
            content = null;
        }
    }
}
