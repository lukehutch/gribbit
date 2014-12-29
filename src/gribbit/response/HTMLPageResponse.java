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

import gribbit.model.DataModel;
import gribbit.response.flashmsg.FlashMessage;
import gribbit.server.config.GribbitProperties;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;

/** Rendered by the HTMLPageResponse.html page template. */
public class HTMLPageResponse extends HTMLResponse {

    /** Rendered into the title element of the page template. */
    public String title;

    /** Each FlashMessage is rendered as a Bootstrap template. */
    public ArrayList<FlashMessage> flashMessages;

    // -----------------------------------------------------------------------------------------------------------------

    public HTMLPageResponse(HttpResponseStatus status, String title, DataModel content) {
        super(status, content);
        this.title = title;
    }

    public HTMLPageResponse(String title, DataModel content) {
        this(HttpResponseStatus.OK, title, content);
    }

    public HTMLPageResponse(HttpResponseStatus status, String title, DataModel[] content) {
        super(status, content);
        this.title = title;
    }

    public HTMLPageResponse(String title, DataModel[] content) {
        this(HttpResponseStatus.OK, title, content);
    }

    public HTMLPageResponse(HttpResponseStatus status, String title, ArrayList<DataModel> content) {
        super(status, content);
        this.title = title;
    }

    public HTMLPageResponse(String title, ArrayList<DataModel> content) {
        this(HttpResponseStatus.OK, title, content);
    }

    // -----------------------------------------------------------------------------------------------------------------

    public void setFlashMessages(ArrayList<FlashMessage> flashMessages) {
        this.flashMessages = flashMessages;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Render this Response object as a complete HTML page, complete with head and body elements. This class is bound to
     * the HTMLPageResponse.html template, which binds the field "title" to the "${title}" template parameter within the
     * title element, etc.
     * 
     * The field "content" is simply one of several public fields substituted into the template parameters, as a
     * parameter within the body.
     * 
     * The field "flashMessages" is bound to the "${flashMessages}" parameter, which should be inside the body element
     * of the page template. Flash messages have their own HTML template that is inserted at this point for each flash
     * message.
     * 
     * (Whole-page templates were modified by the template loader on load to insert all head-content.html content found
     * in the classpath at the end of the head element, and all tail-content.html content at the end of the body
     * element.)
     */
    @Override
    protected String renderContentTemplates() {
        return this.renderTemplate(GribbitProperties.PRETTY_PRINT_HTML);
    }
}
