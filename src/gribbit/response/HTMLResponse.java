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

import gribbit.auth.CSRF;
import gribbit.model.TemplateModel;
import gribbit.request.Request;
import gribbit.util.JSON;
import gribbit.util.Log;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.List;

public class HTMLResponse extends Response {

    /** The content of the response. */
    public TemplateModel content;

    public HTMLResponse(HttpResponseStatus status, TemplateModel content) {
        super(status);
        this.content = content;
    }

    public HTMLResponse(HttpResponseStatus status, List<? extends TemplateModel> contentList) {
        super(status);
        this.content = TemplateModel.templateSequence(contentList);
    }

    public HTMLResponse(HttpResponseStatus status, TemplateModel... contentList) {
        super(status);
        this.content = TemplateModel.wrap(contentList);
    }

    /**
     * Returns the UTF-8 byte encoding of the HTML content of the response, or the UTF-8 byte encoding of the JSON
     * representation of the model behind the HTML content if isGetModelRequest is true.
     */
    @Override
    public ByteBuf getContent(Request request) {
        String contentStr =
        // Return empty string for null content
        content == null ? "" //
                : request.isGetModelRequest()
                // Render as JSON if ?_getmodel=1 is appended to the URL
                ? JSON.toJSON(content)
                        // Render as HTML otherwise
                        : content.renderTemplate(request.getURLPathUnhashed());

        ByteBuf contentBytes = Unpooled.buffer(contentStr.length() * 3 / 2);
        ByteBufUtil.writeUtf8(contentBytes, contentStr);
        int contentLength = contentBytes.readableBytes();
        byte[] contentArray = contentBytes.array();

        // Replace placeholder instances of the CSRF input value in forms with the user's
        // CSRF token if the user is logged in. This is a bit of a hack, but the CSRF token
        // placeholder should be highly unique, and therefore should not collide with
        // unintended content.
        byte[] csrfBytes = CSRF.CSRF_TOKEN_PLACEHOLDER_BYTES;
        String csrfTokReplace = getCsrfTok();
        if (csrfTokReplace == null) {
            csrfTokReplace = CSRF.CSRF_TOKEN_UNKNOWN;
        }
        if (csrfTokReplace.length() != csrfBytes.length) {
            // Should not happen
            Log.error("CSRF token is the wrong length");
        } else {
            // We converted the content to UTF8 bytes, so we have to implement our own firstIndexOf() to find any match
            for (int i = 0, ni = contentLength, len = csrfBytes.length; i < ni; i++) {
                for (int j = 0, nj = Math.min(ni - i, len); j < nj; j++) {
                    if (contentArray[i + j] != csrfBytes[j]) {
                        // Mismatch
                        break;
                    } else if (j == nj - 1) {
                        // Found a match starting at position i -- replace placeholder token with user's own CSRF token
                        for (int k = 0; k < nj; k++) {
                            contentArray[i + k] = (byte) (csrfTokReplace.charAt(k) & 0x7f);
                        }
                    }
                }
            }
        }
        return contentBytes;
    }

    @Override
    public String getContentType(Request request) {
        return request.isGetModelRequest() ? "application/json;charset=utf-8" : "text/html;charset=utf-8";
    }
}
