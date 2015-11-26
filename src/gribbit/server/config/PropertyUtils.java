package gribbit.server.config;

import gribbit.http.logging.Log;
import gribbit.server.GribbitServer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class PropertyUtils {

    /** Load properties file. */
    public static Properties load(String propFilename) {
        try (InputStream inputStream = GribbitServer.class.getClassLoader().getResourceAsStream(propFilename)) {
            if (inputStream == null) {
                System.err.println(propFilename + " not found on the classpath");
                System.exit(1);
            }
            Properties properties = new Properties();
            properties.load(inputStream);
            return properties;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static final boolean getPropertyBoolean(Properties properties, String propName, boolean defaultValue) {
        String opt = properties.getProperty(propName);
        if (opt == null) {
            return defaultValue;
        } else {
            opt = opt.toLowerCase().trim();
            if (opt.equals("1") || opt.equals("true") || opt.equals("t") || opt.equals("yes") || opt.equals("y")) {
                return true;
            } else if (opt.equals("0") || opt.equals("false") || opt.equals("f") || opt.equals("no")
                    || opt.equals("n")) {
                return false;
            } else {
                Log.warning("Unrecognized property value: " + propName + "="
                        + GribbitProperties.properties.getProperty(propName));
                return defaultValue;
            }
        }
    }

    public static final int getPropertyInt(Properties properties, String propName, int defaultValue) {
        String opt = properties.getProperty(propName);
        if (opt == null || opt.isEmpty()) {
            return defaultValue;
        } else {
            try {
                return Integer.parseInt(opt.trim());
            } catch (NumberFormatException e) {
                Log.warning("Illegal property value: " + propName + "="
                        + GribbitProperties.properties.getProperty(propName));
                return defaultValue;
            }
        }
    }

    public static final <E extends Enum<E>> E getPropertyEnum(Properties properties, String propName,
            Class<E> enumType, E defaultValue) {
        String opt = properties.getProperty(propName);
        if (opt == null || opt.isEmpty()) {
            return defaultValue;
        } else {
            try {
                return Enum.valueOf(enumType, opt);
            } catch (Exception e) {
                Log.warning("Illegal property value: " + propName + "="
                        + GribbitProperties.properties.getProperty(propName));
                return defaultValue;
            }
        }
    }

}
