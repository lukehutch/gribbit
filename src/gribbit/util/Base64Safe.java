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

import gribbit.util.thirdparty.Base64;
import gribbit.util.thirdparty.UTF8;
import gribbit.util.thirdparty.UTF8.UTF8Exception;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Perform base 64 encoding that is compatible with URL and cookie encoding: replaces '+' with '-' and '/' with '_',
 * removes trailing '=' padding characters and prefixes the base 64 encoding with a single digit that gives the
 * number of padding characters removed. N.B. for "data:" URIs, regular Base64 encoding can be used unmodified, this
 * class is not needed.
 */
public class Base64Safe {

    /**
     * Convert a string to UTF8 bytes, then encode the bytes with URL/cookie-compatible base 64 encoding.
     */
    public static String base64EncodeFromString(String src) {
        return base64Encode(UTF8.stringToUTF8(src));
    }

    /**
     * Convert a byte array to URL/cookie-compatible base 64 encoding.
     */
    public static String base64Encode(byte[] src) {
        byte[] base64Orig = Base64.encodeToByte(src, false);
        StringBuilder base64Safe = new StringBuilder(base64Orig.length + 1);
        base64Safe.append('0');
        int numEq = 0;
        for (int i = 0; i < base64Orig.length; i++) {
            byte c = base64Orig[i];
            switch (c) {
            case '+':
                base64Safe.append('-');
                break;
            case '/':
                base64Safe.append('_');
                break;
            case '=':
                // These should only be at the very end (assume valid since we got the output straight
                // from Base64.encodeToByte())
                numEq++;
                break;
            default:
                base64Safe.append((char) c);
                break;
            }
        }
        base64Safe.setCharAt(0, (char) ('0' + numEq));
        String base64 = base64Safe.toString();
        return base64;
    }

    private static MessageDigest md5;
    static {
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Base64-encode the MD5 digest of a string. */
    public static String base64EncodeMD5Digest(String src) {
        byte[] bytes;
        try {
            bytes = src.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        byte[] digest = md5.digest(bytes);
        return base64Encode(digest);
    }

    /**
     * Convert a URL/cookie-compatible base 64 encoded UTF8 string into a UTF8 byte array, then back into a Java
     * UTF16 String.
     */
    public static String base64DecodeToString(String base64Safe) throws IllegalArgumentException {
        try {
            return UTF8.utf8ToString(base64Decode(base64Safe));
        } catch (UTF8Exception e) {
            // Should not happen if the string was encoded properly from UTF8 bytes
            throw new RuntimeException(e);
        }
    }

    /**
     * Convert a URL/cookie-compatible base 64 encoded string back into the original byte array.
     */
    public static byte[] base64Decode(String base64Safe) throws IllegalArgumentException {
        if (base64Safe == null || base64Safe.length() < 2)
            throw new IllegalArgumentException("Bad encoding length");
        char numEqChar = base64Safe.charAt(0);
        if (numEqChar < '0' || numEqChar > '2')
            throw new IllegalArgumentException("Bad number of padding characters");
        int numEq = (numEqChar - '0');
        int len = base64Safe.length() - 1 + numEq;
        byte[] base64Orig = new byte[len];
        for (int i = 1, n = base64Safe.length(); i < n; i++) {
            char c = base64Safe.charAt(i);
            switch (c) {
            case '-':
                base64Orig[i - 1] = '+';
                break;
            case '_':
                base64Orig[i - 1] = '/';
                break;
            default:
                if (c > 255 || Base64.IA[c] < 0)
                    throw new IllegalArgumentException("Illegal character in base 64 encoding: '" + c + "'");
                base64Orig[i - 1] = (byte) c;
                break;
            }
        }
        // Re-pad the end with '=' chars to restore proper base 64 encoding,
        // so that the correct length of the original byte array can be recovered
        for (int i = 0; i < numEq; i++)
            base64Orig[i + base64Safe.length() - 1] = '=';
        byte[] decoded = Base64.decodeFast(base64Orig);
        return decoded;
    }

}
