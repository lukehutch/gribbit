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

import gribbit.handler.vulcanized.VulcanizedHTMLHandler;
import gribbit.model.DataModel;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.server.response.flashmsg.FlashMessage;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;

/** Rendered by the HTMLPageResponse.html page template. */
public class HTMLPageResponse extends HTMLResponse {

    /** Rendered into the title element of the page template. */
    public String title;

    /** Each FlashMessage is rendered as a Bootstrap template. */
    public ArrayList<FlashMessage> flashMessages;

    /** The route to the vulcanized HTML handler will be inserted into the HTML template from this field. */
    public String vulcanizedHtmlURI = GribbitServer.siteResources.routeURIForHandler(VulcanizedHTMLHandler.class);

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
     * Since Response extends DataModel, we can simply render the public fields of HTMLPageResponse as a template to
     * produce a complete HTML page. This overrides the method of HTMLResponse, which only renders any templates
     * associated with the field "content". Here we render "this", because HTMLPageResponse has its own associated
     * template, HTMLPageResponse.html. The field "content" is simply one of several public fields substituted into
     * template parameters.
     */
    @Override
    protected String renderContentTemplates() {
        return this.renderTemplate(GribbitProperties.PRETTY_PRINT_HTML);
    }
}
