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
package gribbit.response.flashmsg;

import gribbit.model.TemplateModel;
import gribbit.util.StringUtils;

import java.util.ArrayList;

public class FlashMessage extends TemplateModel {
    public FlashType flashType;
    public String strongText;
    public String message;

    /** Type of flash message. */
    public enum FlashType {
        INFO, SUCCESS, WARNING, ERROR;

        /** Render FlashType enum values into the HTML template as CSS classes. */
        @Override
        public String toString() {
            switch (this) {
            case ERROR:
                return "alert-danger";
            case SUCCESS:
                return "alert-success";
            case INFO:
                return "alert-info";
            case WARNING:
            default:
                return "";
            }
        }
    };

    public FlashMessage(FlashType flashType, String strongText, String message) {
        this.flashType = flashType;
        this.strongText = strongText;
        this.message = message;
    }

    public static final String _template = //
    // FlashType objects are rendered directly into CSS classes here by overriding the toString() method
    // of the FlashType enum
    "<div class='alert ${flashType}'>" //
            + "<a class='close' data-dismiss='alert'>&times;</a>" //  
            + "<strong>${strongText}</strong> ${message}" //
            + "</div>";

    /**
     * Create a list of flash messages from a cookie string, or returns null if the cookie string is null or contains no
     * valid flash messages.
     */
    public static ArrayList<FlashMessage> fromCookieString(String flashMessagesCookieStr) {
        if (flashMessagesCookieStr == null || flashMessagesCookieStr.isEmpty()) {
            return null;
        }
        ArrayList<FlashMessage> flashMessages = null;
        String[] cookieParts = StringUtils.split(flashMessagesCookieStr, "\n");
        for (String cookiePart : cookieParts) {
            FlashMessage flashMessage = null;

            // Separate out flash message into FlashType and message text parts
            // Check formatting in case someone has been messing with cookie values.
            int tabIdx = cookiePart.indexOf('\t');
            if (tabIdx > 0) {
                int tabIdx2 = cookiePart.indexOf('\t', tabIdx + 1);
                if (tabIdx2 > 0) {
                    try {
                        FlashType flashType = FlashType.valueOf(cookiePart.substring(0, tabIdx));
                        String strongText = cookiePart.substring(tabIdx + 1, tabIdx2);
                        String message = cookiePart.substring(tabIdx2 + 1);
                        flashMessage = new FlashMessage(flashType, strongText, message);
                    } catch (Exception e) {
                    }
                }
            }

            if (flashMessage != null) {
                if (flashMessages == null) {
                    flashMessages = new ArrayList<>();
                }
                flashMessages.add(flashMessage);
            }
        }
        return flashMessages;
    }

    /** Create the cookie string encoding for a list of flash messages, or returns null if the list is null or empty. */
    public static String toCookieString(ArrayList<FlashMessage> flashMessages) {
        if (flashMessages == null || flashMessages.isEmpty()) {
            return null;
        }
        StringBuilder buf = new StringBuilder(1024);
        for (FlashMessage flashMessage : flashMessages) {
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(flashMessage.flashType.toString() //
                    + "\t" //
                    + (flashMessage.strongText == null ? "" : StringUtils.normalizeSpacing(flashMessage.strongText) // 
                            + "\t" //
                            + (flashMessage.message == null ? "" : StringUtils.normalizeSpacing(flashMessage.message))));
        }
        return buf.toString();
    }
}
