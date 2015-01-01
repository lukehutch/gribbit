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
package gribbit.request.handler;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import gribbit.request.handler.exception.ExceptionResponse;
import gribbit.request.handler.exception.InternalServerErrorException;
import gribbit.server.GribbitServer;
import gribbit.server.siteresources.CacheExtension;
import gribbit.util.WebUtils;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.ZonedDateTime;

/** Serve a static file. */
public class HttpSendStaticFile {

    /** Serve a static file. */
    public static void sendStaticFile(String reqURI, boolean isHEAD, String hashKey, File staticResourceFile,
            long lastModifiedEpochSeconds, boolean addKeepAliveHeader, boolean closeAfterWrite,
            ChannelHandlerContext ctx) throws ExceptionResponse {

        RandomAccessFile fileToServe = null;
        try {
            // Create new RandomAccessFile (which allows us to find file length etc.)
            fileToServe = new RandomAccessFile(staticResourceFile, "r");

            // -----------------------------------------
            // Serve a static file (not authenticated)
            // -----------------------------------------

            DefaultHttpResponse httpRes = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            httpRes.headers().add("Server", GribbitServer.SERVER_IDENTIFIER);

            long fileLength = fileToServe.length();
            httpRes.headers().set(CONTENT_LENGTH, Long.toString(fileLength));
            WebUtils.setContentTypeHeaders(httpRes.headers(), staticResourceFile.getPath());

            // If the file contents have changed since the last time the file was hashed,
            // schedule the file to be hashed in the background so that future references to the
            // file's URI in a src/href attribute of served HTML templates will include a hash
            // URI rather than the original URI for the file, allowing the browser to cache the
            // file indefinitely until it changes.
            CacheExtension.updateHashURI(reqURI, staticResourceFile);

            // If file was already cached, and the request URI included the hash key, then this is
            // the first time this client has fetched this file since the browser cache was last
            // cleared. Mark this resource as indefinitely cached. If the file is not being served
            // on a hash URI, then at least set the Last-Modified header, so that if the client
            // requests the same unmodified resource again on the same non-hash URI, the server can
            // return Not Modified instead of serving the contents of the file.
            HttpUtils.setDateAndCacheHeaders(httpRes.headers(), ZonedDateTime.now(), lastModifiedEpochSeconds, //
                    hashKey != null ? /* cache indefinitely: */-1 : /* ignored if hashKey == null: */0, hashKey);

            if (addKeepAliveHeader) {
                httpRes.headers().add(CONNECTION, KEEP_ALIVE);
            }

            // Write HTTP headers to channel
            ctx.write(httpRes);

            // For HEAD requests, don't send the body
            if (isHEAD) {
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                fileToServe.close();
                return;
            }
            
            // Write file content to channel.
            // Can add ChannelProgressiveFutureListener to sendFileFuture if we need to track
            // progress (e.g. to update user's UI over a web socket to show download progress.)
            ChannelFuture sendFileFuture;
            ChannelFuture lastContentFuture;
            if (ctx.pipeline().get(SslHandler.class) == null) {
                // Use FileRegions if possible, which supports zero-copy / mmio
                sendFileFuture =
                        ctx.write(new DefaultFileRegion(fileToServe.getChannel(), 0, fileLength),
                                ctx.newProgressivePromise());
                // Write the end marker
                lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                // Can't use FileRegions / zero-copy with SSL
                sendFileFuture =
                        ctx.write(new HttpChunkedInput(new ChunkedFile(fileToServe, 0, fileLength, 1)),
                                ctx.newProgressivePromise());
                // HttpChunkedInput will write the end marker (LastHttpContent) for us.
                // See https://github.com/netty/netty/commit/4ba2ce3cbbc55391520cfc98a7d4227630fbf978
                lastContentFuture = sendFileFuture;
            }

            //    sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
            //        @Override
            //        public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
            //            if (total < 0) { // total unknown
            //                System.err.println(future.channel() + " Transfer progress: " + progress);
            //            } else {
            //                System.err.println(future.channel() + " Transfer progress: " + progress + " / " + total);
            //            }
            //        }
            //    
            //        @Override
            //        public void operationComplete(ChannelProgressiveFuture future) {
            //            System.err.println(future.channel() + " Transfer complete.");
            //        }
            //    });

            // Close connection after flush if needed, and close file after flush 
            final RandomAccessFile fileToClose = fileToServe;
            lastContentFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    if (closeAfterWrite) {
                        future.channel().close();
                    }
                    try {
                        fileToClose.close();
                    } catch (IOException e) {
                    }
                }
            });

        } catch (Exception e) {
            if (fileToServe != null) {
                try {
                    fileToServe.close();
                } catch (IOException e1) {
                }
            }
            throw new InternalServerErrorException("Exception serving static file", e);
        }
    }
}
