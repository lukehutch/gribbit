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

import gribbit.model.DataModel;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;

import org.apache.commons.mail.DefaultAuthenticator;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.HtmlEmail;
import org.apache.commons.mail.ImageHtmlEmail;

public class SendEmail {

    /** Send an email. Don't forget to use fully-qualified URLs in the message body. */
    public static void sendEmail(final String toName, final String to, final String subject,
            final DataModel message, final String messagePlainText) {
        // Queue sending of email in a new thread
        GribbitServer.scheduledTaskGroup.execute(new Runnable() {
            @Override
            public void run() {
                if (GribbitProperties.SMTP_SERVER == null || GribbitProperties.SEND_EMAIL_ADDRESS == null
                        || GribbitProperties.SEND_EMAIL_PASSWORD == null
                        || GribbitProperties.SEND_EMAIL_ADDRESS == null
                        || GribbitProperties.SEND_EMAIL_NAME == null) {
                    throw new RuntimeException("SMTP is not fully configured in the properties file");
                }

                String fullEmailAddr = "\"" + toName + "\" <" + to + ">";
                try {
                    HtmlEmail email = new ImageHtmlEmail();
                    email.setDebug(false);

                    email.setHostName(GribbitProperties.SMTP_SERVER);
                    email.setSmtpPort(GribbitProperties.SMTP_PORT);
                    email.setAuthenticator(new DefaultAuthenticator(GribbitProperties.SEND_EMAIL_ADDRESS,
                            GribbitProperties.SEND_EMAIL_PASSWORD));
                    email.setStartTLSRequired(true);

                    email.addTo(to, toName);
                    email.setFrom(GribbitProperties.SEND_EMAIL_ADDRESS, GribbitProperties.SEND_EMAIL_NAME);
                    email.setSubject(subject);
                    email.setHtmlMsg(message.toString());
                    email.setTextMsg(messagePlainText);

                    email.send();

                    Log.info("Sent email to " + fullEmailAddr + " : " + subject);

                } catch (EmailException e) {
                    Log.exception("Failure while trying to send email to " + fullEmailAddr + " : "
                            + subject, e);
                }
            }
        });
    }

}
