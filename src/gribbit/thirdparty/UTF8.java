/**
 * This file is part of the Gribbit Web Framework.
 * 
 *     https://github.com/lukehutch/gribbit
 *     
 * Originally from:
 * 
 *     https://github.com/webbit/webbit/blob/master/src/main/java/org/webbitserver/helpers/UTF8Output.java
 * 
 * Which is an adaptation of:
 * 
 *     http://bjoern.hoehrmann.de/utf-8/decoder/dfa/
 * 
 * Copyright (c) 2008-2009 Bjoern Hoehrmann <bjoern@hoehrmann.de>
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package gribbit.thirdparty;

import java.io.UnsupportedEncodingException;

public class UTF8 {
    private static final int UTF8_ACCEPT = 0;
    private static final int UTF8_REJECT = 12;

    private static final byte[] TYPES = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 10, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 3, 3, 11,
            6, 6, 6, 5, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8 };

    private static final byte[] STATES = { 0, 12, 24, 36, 60, 96, 84, 12, 12, 12, 48, 72, 12, 12, 12, 12, 12, 12, 12,
            12, 12, 12, 12, 12, 12, 0, 12, 12, 12, 12, 12, 0, 12, 0, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 24,
            12, 12, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12,
            12, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12,
            12, 12, 12, 12, 12, 12, 12, 12 };

    private int state = UTF8_ACCEPT;
    private int codep = 0;

    private final StringBuilder stringBuilder = new StringBuilder();

    public class UTF8Exception extends UnsupportedEncodingException {
        private static final long serialVersionUID = 1L;

        public UTF8Exception(String reason) {
            super(reason);
        }

        public UTF8Exception(Exception e) {
            this(e.getMessage());
        }
    }

    public void append(byte[] bytes) throws UTF8Exception {
        for (int i = 0; i < bytes.length; i++) {
            append(bytes[i]);
        }
    }

    public void append(int b) throws UTF8Exception {
        byte type = TYPES[b & 0xFF];

        codep = (state != UTF8_ACCEPT) ? (b & 0x3f) | (codep << 6) : (0xff >> type) & (b);

        state = STATES[state + type];

        if (state == UTF8_ACCEPT) {
            // See http://goo.gl/JdIVSu
            if (codep < Character.MIN_HIGH_SURROGATE) {
                stringBuilder.append((char) codep);
            } else {
                for (char c : Character.toChars(codep)) {
                    stringBuilder.append(c);
                }
            }
        } else if (state == UTF8_REJECT) {
            throw new UTF8Exception("bytes are not UTF-8");
        }
    }

    public String getStringAndRecycle() throws UTF8Exception {
        if (state == UTF8_ACCEPT) {
            String string = stringBuilder.toString();
            stringBuilder.setLength(0);
            return string;
        } else {
            throw new UTF8Exception("bytes are not UTF-8");
        }
    }

    public static String utf8ToString(byte[] bytes) throws UTF8Exception {
        UTF8 decoder = new UTF8();
        decoder.append(bytes);
        return decoder.getStringAndRecycle();
    }

    public static byte[] stringToUTF8(String str) {
        try {
            return str.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
