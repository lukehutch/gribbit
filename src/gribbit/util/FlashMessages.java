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
package gribbit.util;

import java.util.ArrayList;

import gribbit.model.TemplateModel;
import io.vertx.ext.web.Session;

public class FlashMessages extends TemplateModel {
    public FlashType flashType;
    public String strongText;
    public String message;

    /** Type of flash message. */
    public enum FlashType {
        WARNING(""), INFO("alert-info"), SUCCESS("alert-success"), ERROR("alert-danger");

        private String cssClass;

        FlashType(String cssClass) {
            this.cssClass = cssClass;
        }

        /** Override toString() in order to render the FlashType into a CSS class in the HTML template. */
        @Override
        public String toString() {
            return cssClass;
        }
    };

    private FlashMessages(FlashType flashType, String strongText, String message) {
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

    // -------------------------------------------------------------------------------------------------------------

    private static final String FLASH_MESSAGE_SESSION_KEY = "_flashmsgs";

    public static void add(FlashType flashType, String strongText, String message, Session session) {
        String flashMessagesStr = session.get(FLASH_MESSAGE_SESSION_KEY);
        session.put(FLASH_MESSAGE_SESSION_KEY, (flashMessagesStr != null ? flashMessagesStr + "\n" : "")
                + flashType.name() + "\t" + strongText.replace('\t', ' ') + "\t" + message.replace('\t', ' '));
    }

    public static void clear(Session session) {
        session.remove(FLASH_MESSAGE_SESSION_KEY);
    }

    /** Gets the list of flash messages from the session. */
    public static ArrayList<FlashMessages> get(Session session) {
        String flashMessagesCookieStr = session.get(FLASH_MESSAGE_SESSION_KEY);
        ArrayList<FlashMessages> flashMessages = null;
        if (flashMessagesCookieStr != null && !flashMessagesCookieStr.isEmpty()) {
            String[] messageStrs = StringUtils.split(flashMessagesCookieStr, "\n");
            for (String messageStr : messageStrs) {
                if (!messageStr.isEmpty()) {
                    String[] messageStrParts = messageStr.split("\t");
                    if (messageStrParts.length == 3) {
                        try {
                            FlashMessages flashMessage = new FlashMessages(FlashType.valueOf(messageStrParts[0]),
                                    messageStrParts[1], messageStrParts[2]);
                            if (flashMessages == null) {
                                flashMessages = new ArrayList<>();
                            }
                            flashMessages.add(flashMessage);
                        } catch (Exception e) {
                        }
                    }
                }
            }
        }
        return flashMessages;
    }
}
