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

import java.util.List;

import gribbit.auth.CSRF;
import gribbit.model.TemplateModel;
import gribbit.util.JSON;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.ext.web.RoutingContext;

public class HTMLResponse extends Response {

    private TemplateModel content;
    private String csrfTok;

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
        this.content = TemplateModel.templateSequence(contentList);
    }

    public void setCsrfTok(String csrfTok) {
        this.csrfTok = csrfTok;
    }

    /**
     * Returns the UTF-8 byte encoding of the HTML content of the response, or the UTF-8 byte encoding of the JSON
     * representation of the model behind the HTML content if isGetModelRequest is true.
     */
    @Override
    public void send(RoutingContext routingContext) {
        boolean isGetModelRequest = routingContext.request().getParam("_getModel") != null;
        String contentStr =
                // Return empty string for null content
                content == null ? "" //
                        : isGetModelRequest
                                // Render as JSON if ?_getmodel is appended to the URL
                                ? JSON.toJSON(content)
                                // Render as HTML otherwise
                                : content.renderTemplate(routingContext.request().uri());
        String contentType = isGetModelRequest ? "application/json;charset=utf-8" : "text/html;charset=utf-8";

        if (contentStr.indexOf(CSRF.CSRF_TOKEN_PLACEHOLDER) >= 0 && csrfTok != null) {
            contentStr = contentStr.replace(CSRF.CSRF_TOKEN_PLACEHOLDER, csrfTok);
        }
        sendHeaders(routingContext, contentType);
        routingContext.response().end(contentStr);
    }
}
