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
package gribbit.template.flashmessage;

import gribbit.model.DataModel;

public class FlashMessage extends DataModel {

    public String cssClass;
    public String strongText;
    public String message;

    public FlashMessage(String cssClass, String strongText, String message) {
        this.cssClass = cssClass;
        this.strongText = strongText;
        this.message = message;
    }

    public FlashMessage(String stringEncoding) {
        // Separate out flash message into FlashType and message text parts
        String flashType = "INFO";
        int tabIdx = stringEncoding.indexOf('\t');
        if (tabIdx > 0) {
            int tabIdx2 = stringEncoding.indexOf('\t', tabIdx + 1);
            if (tabIdx2 > 0) {
                flashType = stringEncoding.substring(0, tabIdx);
                this.strongText = stringEncoding.substring(tabIdx + 1, tabIdx2);
                this.message = stringEncoding.substring(tabIdx2 + 1);
            }
        }
        switch (flashType) {
        case "ERROR":
            this.cssClass = "alert-danger";
            break;
        case "INFO":
            this.cssClass = "alert-info";
            break;
        case "SUCCESS":
            this.cssClass = "alert-success";
            break;
        case "WARNING":
        default:
            this.cssClass = "";
            break;
        }
    }

    public static final String _template = //
            "<div class='alert ${cssClass}'>" //
                    + "<a class='close' data-dismiss='alert'>&times;</a>" //  
                    + "<strong>${strongText}</strong> ${message}" //
                    + "</div>";
}
