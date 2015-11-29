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

import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.DATE;
import static io.netty.handler.codec.http.HttpHeaderNames.ETAG;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPIRES;
import static io.netty.handler.codec.http.HttpHeaderNames.LAST_MODIFIED;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import gribbit.http.logging.Log;
import gribbit.http.request.Request;
import gribbit.http.response.exception.NotFoundException;
import gribbit.http.response.exception.NotModifiedException;
import gribbit.http.response.exception.ResponseException;
import gribbit.server.GribbitServer;
import gribbit.server.siteresources.CacheExtension;
import gribbit.util.WebUtils;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class FileResponse extends Response {
    private File file;

    public FileResponse(File file) {
        this.file = file;
    }

    /** Serve a File. */
    @Override
    public void send(Request request, ChannelHandlerContext ctx) throws ResponseException {
        // Create new RandomAccessFile (which allows us to find file length etc.)
        try (RandomAccessFile fileToServe = new RandomAccessFile(file, "r")) {
            // Check last-modified timestamp against the If-Modified-Since header timestamp in the request
            // (resolution is 1 sec)
            long lastModifiedEpochSeconds = file.lastModified() / 1000;
            if (!request.contentModified(lastModifiedEpochSeconds)) {
                // File has not been modified since it was last cached -- return Not Modified
                throw new NotModifiedException(lastModifiedEpochSeconds);
            }

            // If file is newer than the browser cache version, or is not in cache, serve the file
            DefaultHttpResponse httpRes = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            HttpHeaders headers = httpRes.headers();
            headers.add("Server", GribbitServer.SERVER_IDENTIFIER);

            long fileLength = fileToServe.length();
            headers.set(CONTENT_LENGTH, Long.toString(fileLength));
            WebUtils.setContentTypeHeaders(headers, file.getPath());

            // If the file contents have changed since the last time the file was hashed,
            // schedule the file to be hashed in the background so that future references to the
            // file's URI in a src/href attribute of served HTML templates will include a hash
            // URI rather than the original URI for the file, allowing the browser to cache the
            // file indefinitely until it changes.
            CacheExtension.updateHashURI(request.getURLPathUnhashed(), file);

            // If file was already cached, and the request URI included the hash key, then this is
            // the first time this client has fetched this file since the browser cache was last
            // cleared. Mark this resource as indefinitely cached. If the file is not being served
            // on a hash URI, then at least set the Last-Modified header, so that if the client
            // requests the same unmodified resource again on the same non-hash URI, the server can
            // return Not Modified instead of serving the contents of the file.

            // Date header uses server time, and should use the same clock as Expires and Last-Modified
            ZonedDateTime timeNow = ZonedDateTime.now();
            headers.set(DATE, timeNow.format(DateTimeFormatter.RFC_1123_DATE_TIME));

            // Last-Modified is used to determine whether a Not Modified response should be returned on next request
            // Add last modified header to cacheable resources. This is needed because Chrome sends
            // "Cache-Control: max-age=0" when the user types in a URL and hits enter, or hits refresh.
            // In these circumstances, sending back "Cache-Control: public, max-age=31536000" does
            // no good, because the browser has already requested the resource rather than relying on
            // its cache. By setting the last modified header for all cacheable resources, we can
            // at least send "Not Modified" as a response if the resource has not been modified,
            // which doesn't save on roundtrips, but at least saves on re-transferring the resources
            // to the browser when they're already in the browser's cache.
            headers.set(LAST_MODIFIED,
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpochSeconds), ZoneId.of("UTC"))
                            .format(DateTimeFormatter.RFC_1123_DATE_TIME));

            String hashKey = request.getURLHashKey();
            if (hashKey != null) {
                // File was requested on a hash URL => cache the most recent version of the file indefinitely
                // at the hash URL. If the file contents no longer match the hash (i.e. the contents have
                // changed since the hash for the request URI was looked up by the RouteHandler), it's no big
                // deal, we can just serve the newer file contents at the old hash, and the client will still
                // get the newest content, just cached against the old hash URL.
                int indefinitely = 31536000; // 1 year (max according to spec)
                headers.set(CACHE_CONTROL, "public, max-age=" + indefinitely);
                headers.set(EXPIRES, timeNow.plusSeconds(indefinitely).format(DateTimeFormatter.RFC_1123_DATE_TIME));
                headers.set(ETAG, hashKey);
            }

            if (request.isKeepAlive()) {
                headers.add(CONNECTION, KEEP_ALIVE);
            }

            // FileRegions cannot be used with SSL, have to use chunked content
            boolean isChunked = ctx.pipeline().get(SslHandler.class) != null;
            if (isChunked) {
                headers.add(TRANSFER_ENCODING, CHUNKED);
            }

            // Write HTTP headers to channel
            ctx.write(httpRes);

            // For HEAD requests, don't send the body
            if (request.isHEADRequest()) {
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                return;
            }

            // TODO: when a file is requested, if it's a compressible type, schedule it to be gzipped on disk, and
            // return the gzipped version instead of the original version, as long as the gzipped version has a
            // newer timestamp.

            // Write file content to channel.
            // Both methods will close fileToServe after sending the file, see:
            // https://github.com/netty/netty/issues/2474#issuecomment-117905496
            @SuppressWarnings("unused")
            ChannelFuture sendFileFuture;
            ChannelFuture lastContentFuture;
            if (!isChunked) {
                // Use FileRegions if possible, which supports zero-copy / mmio.
                sendFileFuture = ctx.write(new DefaultFileRegion(fileToServe.getChannel(), 0, fileLength),
                        ctx.newProgressivePromise());
                // Write the end marker
                lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                // Can't use FileRegions / zero-copy with SSL
                // HttpChunkedInput will write the end marker (LastHttpContent) for us, see:
                // https://github.com/netty/netty/commit/4ba2ce3cbbc55391520cfc98a7d4227630fbf978
                lastContentFuture = sendFileFuture = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(
                        fileToServe, 0, fileLength, 1)), ctx.newProgressivePromise());
            }

            // Possibly close the connection after the last chunk has been sent.
            if (!request.isKeepAlive()) {
                lastContentFuture.addListener(ChannelFutureListener.CLOSE);
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

            Log.fine(request.getRequestor() + "\t" + request.getURLPathUnhashed() + "\tfile://" + file.getPath()
                    + "\t" + HttpResponseStatus.OK + "\t"
                    + (System.currentTimeMillis() - request.getReqReceivedTimeEpochMillis()) + " msec");
        } catch (FileNotFoundException e) {
            // 404 Not Found
            throw new NotFoundException(request);
        } catch (IOException e1) {
            // Only thrown by Autocloseable if close() fails -- ignore
        }
    }
}
