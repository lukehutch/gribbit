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
package gribbit.server;

/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedInput;

/**
 * A {@link ChunkedInput} that fetches data chunk by chunk for use with HTTP chunked transfers.
 * <p>
 * Each chunk from the input data will be wrapped within a {@link HttpContent}. At the end of the input data,
 * {@link LastHttpContent} will be written.
 * <p>
 * Ensure that your HTTP response header contains {@code Transfer-Encoding: chunked}.
 * <p>
 * 
 * <pre>
 * public void messageReceived(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
 *     HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
 *     response.headers().set(TRANSFER_ENCODING, CHUNKED);
 *     ctx.write(response);
 * 
 *     HttpContentChunkedInput httpChunkWriter = new HttpChunkedInput(new ChunkedFile(&quot;/tmp/myfile.txt&quot;));
 *     ChannelFuture sendFileFuture = ctx.write(httpChunkWriter);
 * }
 * </pre>
 */
public class HttpChunkedInputTEMP implements ChunkedInput<HttpContent> {

    private final ChunkedInput<ByteBuf> input;
    private final LastHttpContent lastHttpContent;
    private boolean sentLastChunk;

    /**
     * Creates a new instance using the specified input.
     * 
     * @param input
     *            {@link ChunkedInput} containing data to write
     */
    public HttpChunkedInputTEMP(ChunkedInput<ByteBuf> input) {
        this.input = input;
        lastHttpContent = LastHttpContent.EMPTY_LAST_CONTENT;
    }

    /**
     * Creates a new instance using the specified input. {@code lastHttpContent} will be written as the terminating
     * chunk.
     * 
     * @param input
     *            {@link ChunkedInput} containing data to write
     * @param lastHttpContent
     *            {@link LastHttpContent} that will be written as the terminating chunk. Use this for training headers.
     */
    public HttpChunkedInputTEMP(ChunkedInput<ByteBuf> input, LastHttpContent lastHttpContent) {
        this.input = input;
        this.lastHttpContent = lastHttpContent;
    }

    @Override
    public boolean isEndOfInput() throws Exception {
        if (input.isEndOfInput()) {
            // Only end of input after last HTTP chunk has been sent
            return sentLastChunk;
        } else {
            return false;
        }
    }

    @Override
    public void close() throws Exception {
        input.close();
    }

    @Override
    public HttpContent readChunk(ChannelHandlerContext ctx) throws Exception {
        if (input.isEndOfInput()) {
            if (sentLastChunk) {
                return null;
            } else {
                // Send last chunk for this input
                sentLastChunk = true;
                return lastHttpContent;
            }
        } else {
            ByteBuf buf = input.readChunk(ctx);
            return new DefaultHttpContent(buf);
        }
    }
}