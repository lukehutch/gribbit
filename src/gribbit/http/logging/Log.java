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
package gribbit.http.logging;

import gribbit.http.request.Request;
import gribbit.http.response.Response;
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
import java.util.regex.Pattern;

public class Log {

    private static final Logger logger = Logger.getGlobal();

    public static Level logLevel = Level.INFO;
    static {
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

    private static final Pattern FAVICON_PATTERN = Pattern.compile("^(.*/)?favicon\\.(ico|png|gif|jpeg|jpg|apng)$");

    private static DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");

    /** Produce log line in Common Log Format -- https://en.wikipedia.org/wiki/Common_Log_Format */
    private static String produceLogLine(Request request, Response response) {
        StringBuilder buf = new StringBuilder();
        buf.append(request == null ? "-" : request.getOrigin());
        buf.append(" - - [");
        buf.append(ZonedDateTime.now().format(LOG_TIME_FORMATTER));
        buf.append("] \"");
        buf.append(request == null ? "-" : request.getMethod().toString());
        buf.append(' ');
        buf.append(request == null ? "-" : request.getRawURL());
        buf.append(' ');
        buf.append(request == null ? "-" : request.getHttpVersion());
        buf.append("\" ");
        buf.append(response == null ? "-" : Integer.toString(response.getStatus().code()));
        buf.append(' ');
        buf.append(response == null ? "-" : Long.toString(response.getContentLength()));
        return buf.toString();
    }

    public static void request(Request request, Response response) {
        // Don't log favicon requests
        if (!FAVICON_PATTERN.matcher(request.getRawURL().toString()).matches()) {
            String msg = produceLogLine(request, response);
            logger.log(Level.INFO, msg);
        }
    }

    public static void request(Request request, Response response, Exception exception) {
        // Don't log favicon requests
        if (!FAVICON_PATTERN.matcher(request.getRawURL().toString()).matches()) {
            String msg = produceLogLine(request, response);
            logger.log(Level.INFO, msg, exception);
        }
    }

    public static void request(Request request, Exception exception) {
        // Don't log favicon requests
        if (!FAVICON_PATTERN.matcher(request.getRawURL().toString()).matches()) {
            String msg = produceLogLine(request, null);
            logger.log(Level.INFO, msg, exception);
        }
    }
}
