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
import gribbit.model.DataModel;
import gribbit.server.config.GribbitProperties;
import gribbit.util.thirdparty.UTF8;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;

public class HTMLResponse extends Response {

    /**
     * Some object that can be rendered as an HTML template, including a list or array of DataModel objects that are
     * each bound to their own template.
     */
    public Object content;

    public HTMLResponse(HttpResponseStatus status, DataModel content) {
        super(status, "text/html;charset=utf-8");
        this.content = content;
    }

    public HTMLResponse(HttpResponseStatus status, ArrayList<? extends DataModel> content) {
        super(status, "text/html;charset=utf-8");
        this.content = content;
    }

    public HTMLResponse(HttpResponseStatus status, DataModel[] content) {
        super(status, "text/html;charset=utf-8");
        this.content = content;
    }

    /**
     * Render the content field of this object as a model bound to an HTML template, producing an HTML fragment string
     * as a return value.
     * 
     * Note: This method is overridden in the subclass HTMLPageResponse to render a complete HTML document (with head
     * and body elements), rather than an HTML fragment.
     */
    protected String renderContentTemplates() {
        return DataModel.renderTemplate(content, GribbitProperties.PRETTY_PRINT_HTML);
    }

    /**
     * Returns the UTF-8 byte encoding of the HTML content of the response, or the UTF-8 byte encoding of the JSON
     * representation of the model behind the HTML content if isGetModelRequest is true.
     * 
     * N.B. if isGetModelRequest is true, the caller needs to ignore the content type of text/html and substitute
     * instead application/json.
     */
    public ByteBuf getContent(boolean isGetModelRequest) {
        String contentStr = isGetModelRequest //
                ? DataModel.toJSON(content, GribbitProperties.PRETTY_PRINT_JSON) //
                : renderContentTemplates();
        ByteBuf contentBytes = Unpooled.wrappedBuffer(UTF8.stringToUTF8(contentStr));
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
            throw new RuntimeException("CSRF token is the wrong length");
        }
        for (int i = 0, ni = contentLength, len = csrfBytes.length; i < ni; i++) {
            for (int j = 0, nj = Math.min(ni - i, len); j < nj; j++) {
                if (contentArray[i + j] != csrfBytes[j]) {
                    // Mismatch
                    break;
                } else if (j == nj - 1) {
                    // Found a match -- replace placeholder token with user's own CSRF token
                    for (int k = 0; k < nj; k++) {
                        contentArray[i + k] = (byte) (csrfTokReplace.charAt(k) & 0x7f);
                    }
                }
            }
        }
        return contentBytes;
    }

    /** Returns the UTF-8 byte encoding of the HTML content of the response. */
    @Override
    public ByteBuf getContent() {
        return getContent(false);
    }
}
