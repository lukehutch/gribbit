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

import static io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN;
import gribbit.http.request.Request;
import gribbit.http.response.exception.NotFoundException;
import gribbit.http.response.exception.NotModifiedException;
import gribbit.http.response.exception.ResponseException;
import gribbit.http.utils.ContentTypeUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

public class FileResponse extends Response implements AutoCloseable {
    private RandomAccessFile raf;

    public FileResponse(Request request, String path) throws ResponseException {
        super(request, HttpResponseStatus.OK);

        File f = new File(path);
        try {
            raf = new RandomAccessFile(path, "r");
        } catch (FileNotFoundException e) {
            throw new NotFoundException();
        }
        if (!f.isFile() || f.isHidden()) {
            throw new NotFoundException();
        }

        contentLength = f.length();

        // Check last-modified timestamp against the If-Modified-Since header timestamp in the request
        // (resolution is 1 sec)
        lastModifiedEpochSeconds = f.lastModified() / 1000;
        if (!request.contentModified(lastModifiedEpochSeconds)) {
            // File has not been modified since it was last cached -- return Not Modified
            throw new NotModifiedException();
        }

        int dotIdx = path.lastIndexOf('.'), slashIdx = path.lastIndexOf(File.separatorChar);
        if (dotIdx > 0 && slashIdx < dotIdx) {
            String leaf = path.substring(slashIdx + 1).toLowerCase();
            String ext = path.substring(dotIdx + 1).toLowerCase();
            String mimeType = ContentTypeUtils.EXTENSION_TO_MIMETYPE.get(ext);
            if (mimeType != null) {
                contentType = mimeType;
            }
            // .svgz files need a "Content-Encoding: gzip" header -- see http://kaioa.com/node/45
            if (ext.equals("svgz")) {
                contentEncodingGzip = true;
            }
            // Fonts need a CORS header if served across domains, to work in Firefox and IE (and according to spec)
            // -- see http://davidwalsh.name/cdn-fonts
            if (ContentTypeUtils.FONT_EXTENSION.contains(ext) || leaf.equals("font.css")
                    || leaf.equals("fonts.css")) {
                addHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            }
        }
    }

    @Override
    public void writeResponse(ChannelHandlerContext ctx) throws Exception {
        // FileRegions cannot be used with SSL, have to use chunked content.
        // TODO: Does this work with HTTP2?
        isChunked |= ctx.pipeline().get(SslHandler.class) != null;

        sendHeaders(ctx);

        // TODO: Add content compression.
        if (!request.isHEADRequest()) {
            // Write file content to channel. Both methods will close file after sending, see:
            // https://github.com/netty/netty/issues/2474#issuecomment-117905496
            if (!isChunked) {
                // Use FileRegions if possible, which supports zero-copy / mmio.
                ctx.write(new DefaultFileRegion(raf.getChannel(), 0, contentLength));
                // Write the end marker
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                // Can't use FileRegions / zero-copy with SSL
                // HttpChunkedInput will write the end marker (LastHttpContent) for us, see:
                // https://github.com/netty/netty/commit/4ba2ce3cbbc55391520cfc98a7d4227630fbf978
                ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf, 0, contentLength, 8192)));
            }
        }
    }

    @Override
    public void close() {
        if (raf != null) {
            try {
                raf.close();
                raf = null;
            } catch (IOException e) {
            }
        }
    }
}
