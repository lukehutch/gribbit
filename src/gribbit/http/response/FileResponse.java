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
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import gribbit.http.request.Request;
import gribbit.http.response.exception.NotFoundException;
import gribbit.http.response.exception.NotModifiedException;
import gribbit.http.response.exception.ResponseException;
import io.netty.channel.ChannelFuture;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class FileResponse extends GeneralResponse implements AutoCloseable {
    private RandomAccessFile raf;

    // -------------------------------------------------------------------------------------------------------------

    private static <T> HashMap<T, T> toMap(T[][] pairs) {
        HashMap<T, T> map = new HashMap<>();
        for (T[] pair : pairs) {
            map.put(pair[0], pair[1]);
        }
        return map;
    }

    private static final Map<String, String> EXTENSION_TO_MIMETYPE = toMap(new String[][] {
            // See https://github.com/h5bp/server-configs-nginx/blob/master/mime.types for more
            { "txt", "text/plain" }, //
            { "htm", "text/html" }, //
            { "html", "text/html" }, //
            { "js", "application/javascript" }, //
            { "json", "application/json" }, //
            { "css", "text/css" }, //
            { "xml", "application/xml" }, //
            { "ico", "image/x-icon" }, //
            { "png", "image/png" }, //
            { "webp", "image/webp" }, //
            { "gif", "image/gif" }, //
            { "mng", "image/mng" }, //
            { "jpg", "image/jpeg" }, //
            { "jpeg", "image/jpeg" }, //
            { "svg", "image/svg+xml" }, //
            { "svgz", "image/svg+xml" }, // Served as image/svg+xml with a "Content-Encoding: gzip" header
            { "gz", "application/x-gzip" }, //
            { "bz2", "application/x-bzip2" }, //
            { "zip", "application/zip" }, //
            { "pdf", "application/pdf" }, //
            { "ogg", "audio/ogg" }, //
            { "mp3", "audio/mpeg" }, //
            { "wav", "audio/x-wav" }, //
            { "csv", "text/comma-separated-values" }, //

            // Font types:
            { "ttf", "application/x-font-ttf" }, //
            { "ttc", "application/x-font-ttf" }, //
            { "otf", "application/x-font-opentype" }, //
            { "woff", "application/font-woff" }, //
            { "woff2", "application/font-woff2" }, //
            { "eot", "application/vnd.ms-fontobject" }, //
            { "sfnt", "application/font-sfnt" }, //
    });

    private static <T> HashSet<T> toSet(T[] elts) {
        HashSet<T> set = new HashSet<>();
        for (T elt : elts) {
            set.add(elt);
        }
        return set;
    }

    private static final HashSet<String> FONT_EXTENSION = toSet(new String[] { "ttf", "ttc", "otf", "woff",
            "woff2", "eot", "sfnt" });

    // -------------------------------------------------------------------------------------------------------------

    public FileResponse(Request request, String path) throws ResponseException {
        super(request, HttpResponseStatus.OK);

        File f = new File(path);
        try {
            raf = new RandomAccessFile(path, "r");
            if (!f.isFile() || f.isHidden()) {
                throw new NotFoundException();
            }

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
                String mimeType = EXTENSION_TO_MIMETYPE.get(ext);
                if (mimeType != null) {
                    contentType = mimeType;
                }
                // .svgz files need a "Content-Encoding: gzip" header -- see http://kaioa.com/node/45
                if (ext.equals("svgz")) {
                    contentEncodingGzip = true;
                }
                // Fonts need a CORS header if served across domains, to work in Firefox and IE (and according to spec)
                // -- see http://davidwalsh.name/cdn-fonts
                if (FONT_EXTENSION.contains(ext) || leaf.equals("font.css") || leaf.equals("fonts.css")) {
                    addHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                }
            }

            contentLength = f.length();

        } catch (FileNotFoundException e) {
            throw new NotFoundException();
        }
    }

    @Override
    public void writeResponse(ChannelHandlerContext ctx) throws Exception {

        //        // If the file contents have changed since the last time the file was hashed,
        //        // schedule the file to be hashed in the background so that future references to the
        //        // file's URI in a src/href attribute of served HTML templates will include a hash
        //        // URI rather than the original URI for the file, allowing the browser to cache the
        //        // file indefinitely until it changes.
        //        CacheExtension.updateHashURI(request.getURLPathUnhashed(), file);
        //
        //        // If file was already cached, and the request URI included the hash key, then this is
        //        // the first time this client has fetched this file since the browser cache was last
        //        // cleared. Mark this resource as indefinitely cached. If the file is not being served
        //        // on a hash URI, then at least set the Last-Modified header, so that if the client
        //        // requests the same unmodified resource again on the same non-hash URI, the server can
        //        // return Not Modified instead of serving the contents of the file.
        //
        //        String hashKey = request.getURLHashKey();
        //        if (hashKey != null) {
        //            // TODO: Need to do this before cache headers are set
        //            // Cache forever
        //            maxAgeSeconds = -1;
        //        }

        sendHeaders(ctx);

        // TODO: when a file is requested, if it's a compressible type, schedule it to be gzipped on disk, and
        // return the gzipped version instead of the original version, as long as the gzipped version has a
        // newer timestamp.

        if (!request.isHEADRequest()) {
            // FileRegions cannot be used with SSL, have to use chunked content.
            // TODO: Does this work with HTTP2?
            boolean isChunked = ctx.pipeline().get(SslHandler.class) != null;

            // Write file content to channel. Both methods will close file after sending, see:
            // https://github.com/netty/netty/issues/2474#issuecomment-117905496
            @SuppressWarnings("unused")
            ChannelFuture sendFileFuture;
            if (!isChunked) {
                // Use FileRegions if possible, which supports zero-copy / mmio.
                sendFileFuture = ctx.write(new DefaultFileRegion(raf.getChannel(), 0, contentLength),
                        ctx.newProgressivePromise());
                // Write the end marker
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                // Can't use FileRegions / zero-copy with SSL
                // HttpChunkedInput will write the end marker (LastHttpContent) for us, see:
                // https://github.com/netty/netty/commit/4ba2ce3cbbc55391520cfc98a7d4227630fbf978
                addHeader(TRANSFER_ENCODING, CHUNKED);
                sendFileFuture = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf,
                        0, contentLength, 1)), ctx.newProgressivePromise());
            }

            //    // Can add ChannelProgressiveFutureListener to sendFileFuture if we need to track
            //    // progress (e.g. to update user's UI over a web socket to show download progress.)
            //    sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
            //        @Override
            //        public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
            //            if (total < 0) { // Total unknown
            //                System.err.println(future.channel() + " Progress: " + progress);
            //            } else {
            //                System.err.println(future.channel() + " Progress: " + progress + " / " + total);
            //            }
            //        }
            //    
            //        @Override
            //        public void operationComplete(ChannelProgressiveFuture future) {
            //            System.err.println(future.channel() + " Transfer complete.");
            //        }
            //    });
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
