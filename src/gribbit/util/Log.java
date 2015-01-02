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

import gribbit.server.config.GribbitProperties;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Log4JLoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class Log {

    private static final Logger logger = Logger.getGlobal();

    private static Level logLevel;
    static {
        logLevel = GribbitProperties.LOG_LEVEL;

        InternalLoggerFactory.setDefaultFactory(new Log4JLoggerFactory());

        // Remove all the default handlers (usually just one console handler)
        Logger rootLogger = Logger.getLogger("");
        Handler[] rootHandlers = rootLogger.getHandlers();
        for (Handler handler : rootHandlers) {
            rootLogger.removeHandler(handler);
        }

        // Add our own handler
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(logLevel);
        handler.setFormatter(new LogFormatter());
        logger.addHandler(handler);
        logger.setLevel(logLevel);
    }

    public static class LogFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String stackTrace = "";
            Throwable thrown = record.getThrown();
            if (thrown != null) {
                StringWriter stacktraceWriter = new StringWriter();
                try (PrintWriter writer = new PrintWriter(stacktraceWriter)) {
                    thrown.printStackTrace(writer);
                }
                stackTrace = stacktraceWriter.toString();
            }
            return ZonedDateTime.ofInstant(Instant.ofEpochMilli(record.getMillis()), ZoneId.of("UTC")).format(
                    DateTimeFormatter.ISO_ZONED_DATE_TIME)
                    + "\t" + record.getLevel() + "\t" + record.getMessage() + "\n" + stackTrace;
        }
    }

    private static final String classname = Log.class.getName();

    private static String callerRef() {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        if (stackTraceElements.length < 4) {
            return "";
        } else {
            int i = 1;
            for (; i < stackTraceElements.length; i++) {
                if (stackTraceElements[i].getClassName().equals(classname)) {
                    break;
                }
            }
            for (; i < stackTraceElements.length; i++) {
                if (!stackTraceElements[i].getClassName().equals(classname)) {
                    break;
                }
            }
            if (i < stackTraceElements.length) {
                return stackTraceElements[i].toString();
            } else {
                return "[in unknown method]";
            }
        }
    }

    public static void setLogLevel(Level newLogLevel) {
        logLevel = newLogLevel;
        for (Handler handler : logger.getHandlers()) {
            handler.setLevel(newLogLevel);
        }
        Log.logger.setLevel(newLogLevel);
    }

    public static int getLevelNum() {
        return logLevel.intValue();
    }

    public static int getLevelNum(Level level) {
        return level.intValue();
    }

    public static void fine(String msg) {
        logger.log(Level.FINE, msg);
    }

    public static void info(String msg) {
        logger.log(Level.INFO, msg);
    }

    public static void warning(String msg) {
        logger.log(Level.WARNING, msg + "\t " + callerRef());
    }

    public static void warningWithoutCallerRef(String msg) {
        logger.log(Level.WARNING, msg);
    }

    public static void error(String msg) {
        logger.log(Level.SEVERE, msg + "\t " + callerRef());
    }

    public static void exception(String msg, Throwable cause) {
        logger.log(Level.SEVERE, msg + "\t " + callerRef(), cause);
    }

    public static void exceptionWithoutCallerRef(String msg, Throwable cause) {
        logger.log(Level.SEVERE, msg, cause);
    }

}
