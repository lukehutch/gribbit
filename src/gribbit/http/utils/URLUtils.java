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
package gribbit.http.utils;

import gribbit.http.utils.UTF8.UTF8Exception;

import java.util.Arrays;

public class URLUtils {

    /** Unescape a URL segment, and turn it from UTF-8 bytes into a Java string. */
    public static String unescapeURLSegment(String str) {
        boolean hasEscapedChar = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '-' && c != '.'
                    && c != '_') {
                hasEscapedChar = true;
                break;
            }
        }
        if (!hasEscapedChar) {
            return str;
        } else {
            byte[] buf = new byte[str.length()];
            int bufIdx = 0;
            for (int segIdx = 0, nSeg = str.length(); segIdx < nSeg; segIdx++) {
                char c = str.charAt(segIdx);
                if (c == '%') {
                    // Decode %-escaped char sequence, e.g. %5D
                    if (segIdx > nSeg - 3) {
                        // Ignore truncated %-seq at end of string
                    } else {
                        char c1 = str.charAt(++segIdx);
                        int digit1 = c1 >= '0' && c1 <= '9' ? (c1 - '0') : c1 >= 'a' && c1 <= 'f' ? (c1 - 'a' + 10)
                                : c1 >= 'A' && c1 <= 'F' ? (c1 - 'A' + 10) : -1;
                        char c2 = str.charAt(++segIdx);
                        int digit2 = c2 >= '0' && c2 <= '9' ? (c2 - '0') : c2 >= 'a' && c2 <= 'f' ? (c2 - 'a' + 10)
                                : c2 >= 'A' && c2 <= 'F' ? (c2 - 'A' + 10) : -1;
                        if (digit1 < 0 || digit2 < 0) {
                            // Ignore invalid %-sequence
                        } else {
                            buf[bufIdx++] = (byte) ((digit1 << 4) | digit2);
                        }
                    }
                } else if (c <= 0x7f) {
                    buf[bufIdx++] = (byte) c;
                } else {
                    // Ignore invalid chars
                }
            }
            if (bufIdx < buf.length) {
                buf = Arrays.copyOf(buf, bufIdx);
            }
            try {
                return UTF8.utf8ToString(buf);
            } catch (UTF8Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /** Encode unsafe characters using %-encoding */
    private static void percentEncode(StringBuilder buf, char c) {
        buf.append('%');
        int b1 = ((c & 0xf0) >> 4), b2 = c & 0x0f;
        buf.append((char) (b1 <= 9 ? '0' + b1 : 'a' + b1 - 10));
        buf.append((char) (b2 <= 9 ? '0' + b2 : 'a' + b2 - 10));
    }

    // Valid URL characters: see
    // http://goo.gl/JNmVMa
    // http://goo.gl/OZ9OOZ
    // http://goo.gl/QFk9R7

    /**
     * Convert a single URL segment (between slashes) to UTF-8, then encode any unsafe bytes.
     */
    public static String escapeURLSegment(String str) {
        if (str == null) {
            return str;
        }
        boolean hasEscapedChar = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '-' && c != '.'
                    && c != '_') {
                hasEscapedChar = true;
                break;
            }
        }
        if (hasEscapedChar) {
            StringBuilder buf = new StringBuilder(str.length() * 4);
            escapeURLSegment(str, buf);
            return buf.toString();
        } else {
            return str;
        }
    }

    /**
     * Convert a single URL segment (between slashes) to UTF-8, then encode any unsafe bytes.
     */
    public static void escapeURLSegment(String str, StringBuilder buf) {
        if (str == null) {
            return;
        }
        byte[] utf8Bytes = UTF8.stringToUTF8(str);
        for (int i = 0; i < utf8Bytes.length; i++) {
            char c = (char) utf8Bytes[i];
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') //
                    || c == '-' || c == '.' || c == '_') {
                buf.append(c);
            } else {
                percentEncode(buf, c);
            }
        }
    }

    /**
     * Convert a URI query param key of the form "q" in "?q=v", %-encoding of UTF8 bytes for unusual characters.
     */
    public static void escapeQueryParamKey(String str, StringBuilder buf) {
        escapeURLSegment(str, buf);
    }

    /**
     * Convert a URI query param value of the form "v" in "?q=v". We use '+' to escape spaces, by convention, and
     * %-encoding of UTF8 bytes for unusual characters.
     */
    public static void escapeQueryParamVal(String str, StringBuilder buf) {
        if (str == null) {
            return;
        }
        escapeURLSegment(str.indexOf(' ') >= 0 ? str.replace(' ', '+') : str, buf);
    }

    public static void escapeQueryParamKeyVal(String key, String val, StringBuilder buf) {
        if (key == null || key.isEmpty()) {
            return;
        }
        escapeQueryParamKey(key, buf);
        buf.append('=');
        escapeQueryParamVal(val, buf);
    }

    /**
     * Build a string of escaped URL query param key-value pairs. Keys are in even indices, values are in the
     * following odd indices. The keys and values are URL-escaped and concatenated with '&' as a delimiter.
     */
    public static String buildQueryString(String... keyValuePairs) {
        if (keyValuePairs == null || keyValuePairs.length == 0) {
            return "";
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (buf.length() > 0) {
                buf.append("&");
            }
            URLUtils.escapeQueryParamKeyVal(keyValuePairs[i], i < keyValuePairs.length - 1 ? keyValuePairs[i + 1]
                    : "", buf);
        }
        return buf.toString();
    }
}
