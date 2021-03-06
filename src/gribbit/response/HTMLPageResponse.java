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

import java.util.ArrayList;
import java.util.List;

import gribbit.model.TemplateModel;
import gribbit.util.FlashMessages;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.ext.web.Session;

/** A response that yields a complete HTML document. */
public class HTMLPageResponse extends HTMLResponse {

    /** The template for rendering a complete HTML page. */
    public static class HTMLPageTemplateModel extends TemplateModel {

        /** Rendered into the title element of the page template. */
        public String title;

        /** The body of the HTML document. */
        public TemplateModel body;

        /** Each FlashMessage is rendered using the corresponding template. */
        public ArrayList<FlashMessages> flashMessages;

        // -----------------------------------------------------------------------------------------------------------------

        public HTMLPageTemplateModel(String title, TemplateModel body) {
            this.title = title;
            this.body = body;
        }

        public HTMLPageTemplateModel(String title, List<? extends TemplateModel> body) {
            this.title = title;
            this.body = TemplateModel.templateSequence(body);
        }

        public HTMLPageTemplateModel(String title, TemplateModel... content) {
            this.title = title;
            this.body = TemplateModel.templateSequence(body);
        }

        // -----------------------------------------------------------------------------------------------------------------

        /** Set the flash messages field just before the template is rendered, if there are any flash messages. */
        public void setFlashMessages(ArrayList<FlashMessages> flashMessages) {
            this.flashMessages = flashMessages;
        }

        // -----------------------------------------------------------------------------------------------------------------

        /**
         * Page template. All files named "head-content.html" on the classpath will be inserted at the end of the
         * head element, and all files named "tail-content.html" on the classpath will be inserted at the end of the
         * body element. The flashMessages parameter is filled in after the response is returned.
         * 
         * FIXME: Add head and tail content to all subclasses of HTMLPageTemplate
         */
        public static final String _template = "<!DOCTYPE html>" //
                + "<html>"//
                + "<head>" //
                // charset should come before title
                + "<meta charset=\"utf-8\">" //
                + "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=Edge\">" + "<title>${title}</title>"
                + "</head>" //
                + "<body>" //
                // Include flash messages at beginning of body
                + "${flashMessages}" //
                // Include templated body content
                + "${body}" //
                + "</body>" //
                + "</html>";
    }

    public HTMLPageResponse(HttpResponseStatus status, String title, TemplateModel body) {
        super(status, new HTMLPageTemplateModel(title, body));
    }

    public HTMLPageResponse(String title, TemplateModel body) {
        this(HttpResponseStatus.OK, title, body);
    }

    public HTMLPageResponse(HttpResponseStatus status, String title, TemplateModel... bodyItems) {
        super(status, new HTMLPageTemplateModel(title, bodyItems));
    }

    public HTMLPageResponse(String title, TemplateModel... bodyItems) {
        this(HttpResponseStatus.OK, title, bodyItems);
    }

    public HTMLPageResponse(HttpResponseStatus status, String title, ArrayList<? extends TemplateModel> bodyItems) {
        super(status, new HTMLPageTemplateModel(title, bodyItems));
    }

    public HTMLPageResponse(String title, ArrayList<? extends TemplateModel> bodyItems) {
        this(HttpResponseStatus.OK, title, bodyItems);
    }

    public void setFlashMessages(Session session) {
        ArrayList<FlashMessages> flashMessages = FlashMessages.get(session);
        if (flashMessages != null) {
            ((HTMLPageTemplateModel) content).setFlashMessages(flashMessages);
        }
    }
}
