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
package gribbit.template.element;

import gribbit.model.DataModel;

public class div extends DataModel {
    public String id;
    public String cssClass;
    public Object[] content;

    public div(String id, String cssClass, Object... content) {
        this.id = id;
        this.cssClass = cssClass;
        this.content = content;
    }

    // Id and name fields must be preceeded with a prefix, i.e. id='div${id}', not id='${id}',
    // to mitigate the effect of injection attacks by namespacing the parameterized id fields
    // of elements in templates.
    public static final String _template = "<div class='${cssClass}' id='div${id}'>${content}</div>";
}
