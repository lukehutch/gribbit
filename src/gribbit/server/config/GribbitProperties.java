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
package gribbit.server.config;

import java.util.Properties;
import java.util.logging.Level;

public class GribbitProperties {

    private static String propFilename = "gribbit.properties";
    public static Properties properties = PropertyUtils.load(propFilename);

    // -----------------------------------------------------------------------------------------------------

    public static Level LOG_LEVEL = Level.INFO;
    static {
        String logLevelStr = properties.getProperty("loglevel", "INFO");
        try {
            LOG_LEVEL = Level.parse(logLevelStr);
        } catch (Exception e) {
            throw new RuntimeException("Could not parse loglevel in properties file");
        }
    }

    public static boolean SSL = PropertyUtils.getPropertyBoolean(properties, "ssl", true);
    public static int PORT = PropertyUtils.getPropertyInt(properties, "port", SSL ? 8443 : 8080);

    public static String SMTP_SERVER = properties.getProperty("smtp.server");
    public static int SMTP_PORT = PropertyUtils.getPropertyInt(properties, "smtp.port", 587);
    public static String SEND_EMAIL_ADDRESS = properties.getProperty("email.address");
    public static String SEND_EMAIL_NAME = properties.getProperty("email.fullname");
    public static String SEND_EMAIL_PASSWORD = properties.getProperty("email.password");

    public static String OAUTH_GOOGLE_CLIENT_ID = properties.getProperty("oauth.google.client.id");
    public static String OAUTH_GOOGLE_CLIENT_SECRET = properties.getProperty("oauth.google.client.secret");
    
    public static String OAUTH_FACEBOOK_CLIENT_ID = properties.getProperty("oauth.facebook.client.id");
    public static String OAUTH_FACEBOOK_CLIENT_SECRET = properties.getProperty("oauth.facebook.client.secret");
    // See https://developers.facebook.com/docs/facebook-login/permissions/v2.3
    public static String OAUTH_FACEBOOK_SCOPE = properties.getProperty("oauth.facebook.scope");

    public static String DB_NAME = properties.getProperty("dbname", "gribbit");

    public static String STATIC_RESOURCE_ROOT = properties.getProperty("staticroot", null);

    public static boolean ALLOW_WEBSOCKETS = PropertyUtils.getPropertyBoolean(properties, "websockets.allow", true);

    public static boolean CONTENT_GZIP = PropertyUtils.getPropertyBoolean(properties, "content.gzip", true);
    public static boolean PRETTY_PRINT_HTML = PropertyUtils.getPropertyBoolean(properties, "prettyprint.html", true);
    public static boolean PRETTY_PRINT_JSON = PropertyUtils.getPropertyBoolean(properties, "prettyprint.json", true);

    public static boolean ALLOW_GET_MODEL = PropertyUtils.getPropertyBoolean(properties, "_getmodel.allow", true);

    public static int CLASSPATH_CHANGE_DETECTION_POLL_INTERVAL_MS = PropertyUtils.getPropertyInt(properties,
            "classpath.poll.ms", 5000);

}
