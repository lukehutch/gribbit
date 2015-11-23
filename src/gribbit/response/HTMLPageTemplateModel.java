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

import gribbit.model.TemplateModel;
import gribbit.response.flashmsg.FlashMessage;

import java.util.ArrayList;
import java.util.List;

/** The template for rendering a complete HTML page. */
public class HTMLPageTemplateModel extends TemplateModel {

    /** Rendered into the title element of the page template. */
    public String title;

    /** The body of the HTML document. */
    public TemplateModel body;

    /** Each FlashMessage is rendered using the corresponding template. */
    public ArrayList<FlashMessage> flashMessages;

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
        this.body = TemplateModel.wrap(body);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Set the flash messages field just before the template is rendered, if there are any flash messages. */
    public void setFlashMessages(ArrayList<FlashMessage> flashMessages) {
        this.flashMessages = flashMessages;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Page template. All files named "head-content.html" on the classpath will be inserted at the end of the head
     * element, and all files named "tail-content.html" on the classpath will be inserted at the end of the body
     * element. The flashMessages parameter is filled in after the response is returned.
     * 
     * FIXME: Add head and tail content to all subclasses of HTMLPageTemplate
     */
    public static final String _template = "<!DOCTYPE html>" //
            // charset should come before title
            + "<html>"//
            + "<head>" //
            + "<meta charset=\"utf-8\">" //
            + "<title>${title}</title>" + "</head>" //
            + "<body>" //
            // Include flash messages at beginning of body
            + "${flashMessages}" //
            // Include templated body content
            + "${body}" //
            + "</body>" //
            + "</html>";
}
