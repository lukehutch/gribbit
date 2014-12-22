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
package gribbit.server.response.flashmsg;

import gribbit.model.DataModel;
import gribbit.util.StringUtils;

public class FlashMessage extends DataModel {

    public String cssClass;
    public String strongText;
    public String message;

    public enum FlashType {
        INFO, SUCCESS, WARNING, ERROR
    };

    private void init(FlashType flashType, String strongText, String message) {
        switch (flashType) {
        case ERROR:
            this.cssClass = "alert-danger";
            break;
        case SUCCESS:
            this.cssClass = "alert-success";
            break;
        case INFO:
            this.cssClass = "alert-info";
            break;
        case WARNING:
        default:
            this.cssClass = "";
            break;
        }
        this.strongText = strongText;
        this.message = message;
    }

    public FlashMessage(FlashType flashType, String strongText, String message) {
        init(flashType, strongText, message);
    }

    public static FlashMessage fromCookieString(String cookieString) {
        // Separate out flash message into FlashType and message text parts
        // Check formatting in case someone has been messing with cookie values.
        int tabIdx = cookieString.indexOf('\t');
        if (tabIdx > 0) {
            int tabIdx2 = cookieString.indexOf('\t', tabIdx + 1);
            if (tabIdx2 > 0) {
                try {
                    FlashType flashType = FlashType.valueOf(cookieString.substring(0, tabIdx));
                    String strongText = cookieString.substring(tabIdx + 1, tabIdx2);
                    String message = cookieString.substring(tabIdx2 + 1);
                    return new FlashMessage(flashType, strongText, message);
                } catch (Exception e) {
                }
            }
        }
        return null;
    }

    /**
     * Create the cookie string encoding for a flash message.
     * 
     * @param flashType
     *            the type of popup
     * @param strongText
     *            optional bolded text to put before the main message
     * @param flashMessage
     *            the flash message to pop up
     */
    public static String toCookieString(FlashType flashType, String strongText, String flashMessage) {
        return flashType.toString() //
                + "\t" //
                + (strongText == null ? "" : StringUtils.normalizeSpacing(strongText) // 
                        + "\t" //
                        + (flashMessage == null ? "" : StringUtils.normalizeSpacing(flashMessage)));
    }

    public static final String _template = //
            "<div class='alert ${cssClass}'>" //
                    + "<a class='close' data-dismiss='alert'>&times;</a>" //  
                    + "<strong>${strongText}</strong> ${message}" //
                    + "</div>";
}
