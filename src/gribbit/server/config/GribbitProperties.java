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
import gribbit.util.KeyGenerator;

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

    public static int PORT;
    public static boolean SSL;
    static {
        String portStr = properties.getProperty("port");
        String sslStr = properties.getProperty("ssl");
        if (sslStr == null) {
            SSL = false;
        } else {
            SSL = true;
        }
        if (portStr == null) {
            PORT = SSL ? 8443 : 8080;
        } else {
            PORT = SSL ? 443 : Integer.parseInt(portStr);
        }
    }

    public static String SMTP_SERVER = properties.getProperty("smtpServer");
    public static int SMTP_PORT;
    public static String SEND_EMAIL_ADDRESS = properties.getProperty("emailAddress");
    public static String SEND_EMAIL_NAME = properties.getProperty("emailName");
    public static String SEND_EMAIL_PASSWORD = properties.getProperty("emailPassword");
    static {
        if (SMTP_SERVER == null) {
            throw new RuntimeException("Need to specify smtpServer in " + propFilename);
        }
        String smtpPortStr = properties.getProperty("smtpPort");
        if (smtpPortStr != null) {
            try {
                SMTP_PORT = Integer.parseInt(smtpPortStr);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Invalid smtpPort in " + propFilename);
            }
        } else {
            SMTP_PORT = 587;
        }
        if (SEND_EMAIL_ADDRESS == null) {
            throw new RuntimeException("Need to specify emailAddress in " + propFilename);
        }
        if (SEND_EMAIL_NAME == null) {
            throw new RuntimeException("Need to specify emailName in " + propFilename);
        }
        if (SEND_EMAIL_PASSWORD == null) {
            throw new RuntimeException("Need to specify emailPassword in " + propFilename);
        }
    }

    public static byte[] COOKIE_ENCRYPTION_KEY;
    static {
        String cookieEncryptionKeyHex = properties.getProperty("cookieEncryptionKey");
        if (cookieEncryptionKeyHex == null) {
            throw new RuntimeException("Need to specify cookieEncryptionKey in " + propFilename);
        } else {
            try {
                // To generate an encryption key, run gribbit.util.KeyGenerator.main()
                // FIXME: generate key automatically on first run, and save in the database
                COOKIE_ENCRYPTION_KEY = KeyGenerator.hexToBytes(cookieEncryptionKeyHex);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Invalid cookieEncryptionKey in " + propFilename);
            }
        }
    }

    public static String oauthGoogleClientID = properties.getProperty("oauth.google.client_id");
    public static String oauthGoogleClientSecret = properties.getProperty("oauth.google.client_secret");

    public static String DB_NAME;
    static {
        DB_NAME = properties.getProperty("dbname");
        if (DB_NAME == null) {
            DB_NAME = "gribbit";
        }
    }

    public static Level LOG_LEVEL = Level.INFO;
    static {
        String logLevelStr = properties.getProperty("loglevel");
        if (logLevelStr != null) {
            try {
                LOG_LEVEL = Level.parse(logLevelStr);
            } catch (Exception e) {
                throw new RuntimeException("Could not parse loglevel in properties file");
            }
        }
    }

    private static final Boolean parseBooleanOpt(String optName) {
        String opt = properties.getProperty(optName);
        if (opt == null) {
            return null;
        } else {
            opt = opt.toLowerCase();
            if (opt.equals("1") || opt.equals("true") || opt.equals("t") || opt.equals("yes") || opt.equals("y")) {
                return true;
            } else if (opt.equals("0") || opt.equals("false") || opt.equals("f") || opt.equals("no") || opt.equals("n")) {
                return false;
            } else {
                System.err.println("Unrecognized property value: " + optName + "=" + properties.getProperty(optName));
                return null;
            }
        }
    }

    public static boolean PRETTY_PRINT_HTML = true;
    static {
        Boolean prettyPrintHTML = parseBooleanOpt("pretty_print_html");
        if (prettyPrintHTML != null) {
            PRETTY_PRINT_HTML = prettyPrintHTML;
        }
    }

    public static boolean PRETTY_PRINT_JSON = true;
    static {
        Boolean prettyPrintJSON = parseBooleanOpt("pretty_print_json");
        if (prettyPrintJSON != null) {
            PRETTY_PRINT_JSON = prettyPrintJSON;
        }
    }

    public static boolean ALLOW_GET_MODEL = true;
    static {
        Boolean allowGetModel = parseBooleanOpt("allow_get_model");
        if (allowGetModel != null) {
            ALLOW_GET_MODEL = allowGetModel;
        }
    }

}
