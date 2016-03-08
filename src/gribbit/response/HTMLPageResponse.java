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

import gribbit.model.TemplateModel;
import io.netty.handler.codec.http.HttpResponseStatus;

/** A response that yields a complete HTML document. */
public class HTMLPageResponse extends HTMLResponse {
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
}
