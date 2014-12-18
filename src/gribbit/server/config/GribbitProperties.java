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

import gribbit.server.GribbitServer;
import gribbit.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;

public class GribbitProperties {

    private static String propFilename = "gribbit.properties";
    public static Properties properties = new Properties();
    static {
        // Read properties file
        try (InputStream inputStream = GribbitServer.class.getClassLoader().getResourceAsStream(propFilename)) {
            if (inputStream == null) {
                System.err.println(propFilename + " not found on the classpath");
                System.exit(1);
            }
            properties.load(inputStream);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------

    private static final boolean getPropertyBoolean(String propName, boolean defaultValue) {
        String opt = properties.getProperty(propName);
        if (opt == null) {
            return defaultValue;
        } else {
            opt = opt.toLowerCase().trim();
            if (opt.equals("1") || opt.equals("true") || opt.equals("t") || opt.equals("yes") || opt.equals("y")) {
                return true;
            } else if (opt.equals("0") || opt.equals("false") || opt.equals("f") || opt.equals("no") || opt.equals("n")) {
                return false;
            } else {
                Log.warning("Unrecognized property value: " + propName + "=" + properties.getProperty(propName));
                return defaultValue;
            }
        }
    }

    private static final int getPropertyInt(String propName, int defaultValue) {
        String opt = properties.getProperty(propName);
        if (opt == null || opt.isEmpty()) {
            return defaultValue;
        } else {
            try {
                return Integer.parseInt(opt.trim());
            } catch (NumberFormatException e) {
                Log.warning("Illegal property value: " + propName + "=" + properties.getProperty(propName));
                return defaultValue;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------

    public static Level LOG_LEVEL = Level.INFO;
    static {
        String logLevelStr = properties.getProperty("loglevel", "INFO");
        try {
            LOG_LEVEL = Level.parse(logLevelStr);
        } catch (Exception e) {
            throw new RuntimeException("Could not parse loglevel in properties file");
        }
    }

    public static boolean SSL = getPropertyBoolean("ssl", true);
    public static int PORT = getPropertyInt("port", SSL ? 8443 : 8080);

    public static String SMTP_SERVER = properties.getProperty("smtp.server");
    public static int SMTP_PORT = getPropertyInt("smtp.port", 587);
    public static String SEND_EMAIL_ADDRESS = properties.getProperty("email.address");
    public static String SEND_EMAIL_NAME = properties.getProperty("email.fullname");
    public static String SEND_EMAIL_PASSWORD = properties.getProperty("email.password");

    public static String OAUTH_GOOGLE_CLIENT_ID = properties.getProperty("oauth.google.client.id");
    public static String OAUTH_GOOGLE_CLIENT_SECRET = properties.getProperty("oauth.google.client.secret");

    public static String DB_NAME = properties.getProperty("dbname", "gribbit");

    public static boolean PRETTY_PRINT_HTML = getPropertyBoolean("prettyprint.html", true);
    public static boolean PRETTY_PRINT_JSON = getPropertyBoolean("prettyprint.json", true);

    public static boolean ALLOW_GET_MODEL = getPropertyBoolean("_getmodel.allow", true);

    public static int CLASSPATH_CHANGE_DETECTION_POLL_INTERVAL_MS = getPropertyInt("classpath.poll.ms", 5000);

}
