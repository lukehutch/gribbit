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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.BitSet;

public class StringUtils {

    public static final char NBSP_CHAR = (char) 0x00A0;

    private static final BitSet IS_UNICODE_WHITESPACE = new BitSet(1 << 16);

    static {
        // Valid unicode whitespace chars, see:
        // http://stackoverflow.com/questions/4731055/whitespace-matching-regex-java
        String wsChars = ""//
                + (char) 0x0009 // CHARACTER TABULATION
                + (char) 0x000A // LINE FEED (LF)
                + (char) 0x000B // LINE TABULATION
                + (char) 0x000C // FORM FEED (FF)
                + (char) 0x000D // CARRIAGE RETURN (CR)
                + (char) 0x0020 // SPACE
                + (char) 0x0085 // NEXT LINE (NEL) 
                + NBSP_CHAR // NO-BREAK SPACE
                + (char) 0x1680 // OGHAM SPACE MARK
                + (char) 0x180E // MONGOLIAN VOWEL SEPARATOR
                + (char) 0x2000 // EN QUAD 
                + (char) 0x2001 // EM QUAD 
                + (char) 0x2002 // EN SPACE
                + (char) 0x2003 // EM SPACE
                + (char) 0x2004 // THREE-PER-EM SPACE
                + (char) 0x2005 // FOUR-PER-EM SPACE
                + (char) 0x2006 // SIX-PER-EM SPACE
                + (char) 0x2007 // FIGURE SPACE
                + (char) 0x2008 // PUNCTUATION SPACE
                + (char) 0x2009 // THIN SPACE
                + (char) 0x200A // HAIR SPACE
                + (char) 0x2028 // LINE SEPARATOR
                + (char) 0x2029 // PARAGRAPH SEPARATOR
                + (char) 0x202F // NARROW NO-BREAK SPACE
                + (char) 0x205F // MEDIUM MATHEMATICAL SPACE
                + (char) 0x3000; // IDEOGRAPHIC SPACE
        for (int i = 0; i < wsChars.length(); i++) {
            IS_UNICODE_WHITESPACE.set((int) wsChars.charAt(i));
        }
    }

    public static boolean isUnicodeWhitespace(char c) {
        return IS_UNICODE_WHITESPACE.get((int) c);
    }

    public static boolean containsNonWhitespaceChar(CharSequence cs) {
        for (int i = 0; i < cs.length(); i++) {
            if (!isUnicodeWhitespace(cs.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsWhitespace(CharSequence cs) {
        for (int i = 0; i < cs.length(); i++) {
            if (isUnicodeWhitespace(cs.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsUppercaseChar(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static CharSequence unicodeTrimCharSequence(CharSequence cs) {
        int i;
        for (i = 0; i < cs.length(); i++) {
            if (!isUnicodeWhitespace(cs.charAt(i))) {
                break;
            }
        }
        int j;
        for (j = cs.length() - 1; j >= 0; --j) {
            if (!isUnicodeWhitespace(cs.charAt(j))) {
                break;
            }
        }
        return i <= j ? cs.subSequence(i, j + 1) : cs.subSequence(0, 0);
    }

    public static final String unicodeTrim(CharSequence str) {
        return unicodeTrimCharSequence(str).toString();
    }

    /**
     * Turn runs of one or more Unicode whitespace characters into a single space, with the exception of
     * non-breaking spaces, which are left alone (i.e. they are not absorbed into runs of whitespace).
     */
    public static final String normalizeSpacing(String val) {
        boolean prevWasWS = false, needToNormalize = false;
        for (int i = 0; i < val.length(); i++) {
            char c = val.charAt(i);
            boolean isWS = c != NBSP_CHAR && isUnicodeWhitespace(c);
            if ((isWS && prevWasWS) || (isWS && c != ' ')) {
                // Found a run of spaces, or non-space whitespace chars
                needToNormalize = true;
                break;
            }
            prevWasWS = isWS;
        }
        prevWasWS = false;
        if (needToNormalize) {
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < val.length(); i++) {
                char c = val.charAt(i);
                boolean isWS = c != NBSP_CHAR && isUnicodeWhitespace(c);
                if (isWS) {
                    if (!prevWasWS) {
                        // Replace a run of any whitespace characters with a single space
                        buf.append(' ');
                    }
                } else {
                    // Not whitespace
                    buf.append(c);
                }
                prevWasWS = isWS;
            }
            return buf.toString();
        } else {
            // Nothing to normalize
            return val;
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * String splitter, this fixes the problem that String.split() has of losing the last token if it's
     * empty. It also uses CharSequences rather than allocating new String objects. Also faster than
     * String.split() because it doesn't support regular expressions.
     */
    public static ArrayList<CharSequence> splitAsList(String str, String sep) {
        int strLen = str.length();
        int sepLen = sep.length();
        assert sepLen > 0;

        ArrayList<CharSequence> parts = new ArrayList<CharSequence>();
        for (int curr = 0; curr <= strLen;) {
            // Look for next token
            int next = str.indexOf(sep, curr);
            // Read to end if none
            if (next < 0)
                next = strLen;
            // Add next token
            parts.add(str.subSequence(curr, next));
            // Move to end of separator, or past end of string if we're at the end
            // (by stopping when curr <= strLen rather than when curr < strLen,
            // we avoid the problem inherent in the Java standard libraries of
            // dropping the last field if it's empty; fortunately
            // str.indexOf(sep, curr) still works when curr == str.length()
            // without throwing an index out of range exception).
            curr = next + sepLen;
        }
        return parts;
    }

    public static CharSequence[] splitAsArray(String str, String sep) {
        ArrayList<CharSequence> list = splitAsList(str, sep);
        CharSequence[] arr = new CharSequence[list.size()];
        list.toArray(arr);
        return arr;
    }

    public static ArrayList<String> splitAsListOfString(String str, String sep) {
        ArrayList<CharSequence> list = splitAsList(str, sep);
        ArrayList<String> listOfString = new ArrayList<String>(list.size());
        for (CharSequence cs : list)
            listOfString.add(cs.toString());
        return listOfString;
    }

    /** For compatibility only, slower because it creates new String objects for each CharSequence */
    public static String[] split(String str, String sep) {
        ArrayList<CharSequence> list = splitAsList(str, sep);
        String[] arr = new String[list.size()];
        for (int i = 0; i < list.size(); i++)
            arr[i] = list.get(i).toString();
        return arr;
    }

    public static String[] splitAndTrim(String str, String sep) {
        ArrayList<CharSequence> list = splitAsList(str, sep);
        String[] arr = new String[list.size()];
        for (int i = 0; i < list.size(); i++)
            arr[i] = unicodeTrimCharSequence(list.get(i)).toString();
        return arr;
    }

    @FunctionalInterface
    public interface StringToStringMapper {
        public String map(String str);
    }

    /**
     * Stringify elements of an Iterable, inserting a delimiter between adjacent elements after first
     * applying a given map function to each element.
     */
    public static <T> String join(Iterable<T> iterable, String delim, StringToStringMapper mapper) {
        if (iterable == null) {
            return null;
        }
        StringBuilder buf = new StringBuilder();
        int idx = 0;
        for (T item : iterable) {
            if (idx++ > 0) {
                buf.append(delim);
            }
            buf.append(mapper.map(item.toString()));
        }
        return buf.toString();
    }

    /** Stringify elements of an Iterable, inserting a delimiter between adjacent elements. */
    public static <T> String join(Iterable<T> iterable, String delim) {
        if (iterable == null) {
            return null;
        }
        StringBuilder buf = new StringBuilder();
        int idx = 0;
        for (T item : iterable) {
            if (idx++ > 0) {
                buf.append(delim);
            }
            buf.append(item.toString());
        }
        return buf.toString();
    }

    /** Stringify elements of an array, inserting a delimiter between adjacent elements. */
    public static <T> String join(T[] array, String delim) {
        if (array == null) {
            return null;
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0, n = array.length; i < n; i++) {
            if (i > 0) {
                buf.append(delim);
            }
            buf.append(array[i].toString());
        }
        return buf.toString();
    }

    // -----------------------------------------------------------------------------------------------------

    /** Return leaf name of a path or URI (part after last '/') */
    public static String leafName(String filePath) {
        return filePath.substring(filePath.lastIndexOf('/') + 1);
    }

    // -----------------------------------------------------------------------------------------------------

    public static String spaces(int n) {
        StringBuilder buf = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            buf.append(' ');
        }
        return buf.toString();
    }

    /** Perform prettyprinting indentation if enabled. */
    public static void indent(int indentDepth, StringBuilder buf) {
        if (GribbitProperties.PRETTY_PRINT_HTML) {
            int numTrailingSpaces = 0;
            boolean hasNewline = buf.length() == 0;
            // See if the line is already sufficiently indented
            for (int i = buf.length() - 1; i >= 0; --i) {
                char c = buf.charAt(i);
                if (c == '\n' || i == 0) {
                    hasNewline = true;
                    break;
                } else if (!isUnicodeWhitespace(c)) {
                    break;
                }
                numTrailingSpaces++;
            }
            if (!hasNewline) {
                buf.append('\n');
                numTrailingSpaces = 0;
            }
            if (numTrailingSpaces > indentDepth) {
                // Over-indented for element that turned out to be empty -- outdent again
                buf.setLength(buf.length() - (numTrailingSpaces - indentDepth));
            } else {
                for (int i = numTrailingSpaces; i < indentDepth; i++) {
                    // Indent
                    buf.append(' ');
                }
            }
        }
    }

    /** Append string to buffer, possibly prefixed by prettyprinting indentation. */
    public static void append(CharSequence str, int indentDepth, StringBuilder buf) {
        indent(indentDepth, buf);
        buf.append(str);
    }

    // -----------------------------------------------------------------------------------------------------

    /** Read all input from an InputStream and return it as a String. */
    public static String readWholeFile(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        StringBuilder buf = new StringBuilder();
        for (String line; (line = reader.readLine()) != null;) {
            buf.append(line);
            buf.append('\n');
        }
        return buf.toString();
    }
}
