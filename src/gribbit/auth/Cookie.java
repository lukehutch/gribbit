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
package gribbit.auth;

import gribbit.server.config.GribbitProperties;
import gribbit.util.Base64Safe;
import gribbit.util.Log;
import gribbit.util.RandomTokenGenerator;
import gribbit.util.UTF8;
import gribbit.util.UTF8.UTF8Exception;
import gribbit.util.WebUtils;
import io.netty.handler.codec.http.DefaultCookie;
import io.netty.handler.codec.http.ServerCookieEncoder;

/**
 * Cookies!
 */
public class Cookie {

    private final String name;

    public enum EncodingType {
        PLAIN, BASE64_ENCODED;
    }

    // ------------------------------------------------------------------------------------------------------------------------

    /** The name of the session cookie. */
    public static final String SESSION_COOKIE_NAME = "_session";

    /** How long a session cookie lasts for. */
    public static final int SESSION_COOKIE_MAX_AGE_SECONDS = 30 * 24 * 60 * 60;

    /** Session cookie length (number of random bytes generated before base 64 encoding) */
    public static final int SESSION_COOKIE_LENGTH = 20;

    /** The name of the email address cookie. Used to notify the Persona client code as to who is logged in. */
    public static final String EMAIL_COOKIE_NAME = "_email";

    /** The name of the flash cookie. */
    public static final String FLASH_COOKIE_NAME = "_flash";

    /** Cookie that records the origin of a redirect. */
    public static final String REDIRECT_ORIGIN_COOKIE_NAME = "_redir";

    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Private class for encapsulating access to cookie values so that we can lazily handle encoding / decoding of values (to save time if a cookie is not used in a given request).
     */
    private class CookieJar {
        /** The Netty cookie containing the possibly-base64-encoded UTF8 bytes representing the cookie's string value */
        private io.netty.handler.codec.http.Cookie encodedNettyCookie;

        /** The unencoded cookie value. */
        private String unencodedValue;

        /** Tracks whether encoded and/or decoded values are known. */
        private boolean hasEncodedValue, hasUnencodedValue;

        private EncodingType encodingType;

        private static final String BASE64_ENCODED_PREFIX = "!0!";

        public CookieJar(io.netty.handler.codec.http.Cookie nettyCookie) {
            hasEncodedValue = true;
            this.encodedNettyCookie = nettyCookie;
            String value = nettyCookie.getValue();
            encodingType = value.startsWith(BASE64_ENCODED_PREFIX) ? EncodingType.BASE64_ENCODED : EncodingType.PLAIN;
        }

        public CookieJar(String name, String unencodedValue, String path, long maxAgeInSeconds, EncodingType encodingType, boolean discardAtEndOfBrowserSession) {
            hasUnencodedValue = true;
            this.unencodedValue = unencodedValue;
            this.encodingType = encodingType;
            checkValidCookieFieldStr(name);
            checkValidCookieFieldStr(path);
            this.encodedNettyCookie = new DefaultCookie(name, "");
            if (maxAgeInSeconds <= 0 && maxAgeInSeconds != Long.MIN_VALUE) {
                // If maxAge <= 0, cookie is expired immediately (so there is nothing to encode);
                this.unencodedValue = "";
                encodedNettyCookie.setMaxAge(0);
                // Keep "" as encoded value
                this.hasEncodedValue = true;
            } else {
                if (maxAgeInSeconds == Long.MIN_VALUE || discardAtEndOfBrowserSession) {
                    // if maxAge == Long.MIN_VALUE, cookie is expired at end of session.
                    encodedNettyCookie.setDiscard(discardAtEndOfBrowserSession);
                }
                encodedNettyCookie.setMaxAge(maxAgeInSeconds);
            }

            // Disallow URL params in cookie path
            if (path.indexOf('?') >= 0 || path.indexOf('&') >= 0)
                throw new RuntimeException("Cookie path cannot have URL params: " + path);
            encodedNettyCookie.setPath(path);

            if (name.equals(SESSION_COOKIE_NAME)) {
                // Session cookie should not be accessible from Javascript for safety
                // TODO: Does adding HttpOnly prevent WebSockets from working?
                encodedNettyCookie.setHttpOnly(true);
            }
            // Cookies are only sent over HTTPS channels
            encodedNettyCookie.setSecure(GribbitProperties.SSL);
        }

        public String getUnencodedValue() {
            // Lazily unencode cookie if it hasn't been unencoded yet
            if (!hasUnencodedValue) {
                String encodedValue = encodedNettyCookie.getValue();
                if (encodedValue == null || encodedValue.isEmpty()) {
                    unencodedValue = "";
                } else {
                    try {
                        if (encodedValue.startsWith(BASE64_ENCODED_PREFIX)) {
                            // Base64-encoded UTF8 bytes
                            byte[] base64UTF8Bytes = Base64Safe.base64Decode(encodedValue.substring(BASE64_ENCODED_PREFIX.length()));
                            unencodedValue = UTF8.utf8ToString(base64UTF8Bytes);

                        } else /* PLAIN */{
                            unencodedValue = WebUtils.unescapeCookieValue(encodedValue);
                        }
                    } catch (UTF8Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                hasUnencodedValue = true;
            }
            return unencodedValue;
        }

        public io.netty.handler.codec.http.Cookie getEncodedCookie() {
            // Lazily encode cookie if it hasn't been encoded yet
            if (!hasEncodedValue) {
                String encodedValue;
                if (encodingType == EncodingType.PLAIN) {
                    encodedValue = WebUtils.escapeCookieValue(unencodedValue);

                } else {
                    // Base64-encode the value
                    byte[] utf8Bytes = UTF8.stringToUTF8(unencodedValue);
                    encodedValue = BASE64_ENCODED_PREFIX + Base64Safe.base64Encode(utf8Bytes);
                }
                if (encodedValue.length() > 3700) {
                    // > 4000 bytes total kills performance and/or doesn't work in many browsers
                    Log.warning("Cookie value too long once base64-encoded: " + encodedValue.length() + " characters");
                }
                encodedNettyCookie.setValue(encodedValue);
                hasEncodedValue = true;
            }
            return encodedNettyCookie;
        }

        public boolean cookieHasExpired() {
            long maxAge = encodedNettyCookie.getMaxAge();
            return maxAge <= 0 && maxAge != Long.MIN_VALUE;
        }

        public String getPath() {
            return encodedNettyCookie.getPath();
        }
    }

    private final CookieJar cookieJar;

    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Create a cookie and possibly base64-encode its value. If maxAgeInSeconds is Long.MIN_VALUE, then cookie will expire at end of browser session.
     */
    public Cookie(String name, String unencodedValue, String path, long maxAgeInSeconds, EncodingType encodingType, boolean discardAtEndOfBrowserSession) throws Exception {
        this.name = name;
        this.cookieJar = new CookieJar(name, unencodedValue, path, maxAgeInSeconds, encodingType, discardAtEndOfBrowserSession);
    }

    /**
     * Create a possibly-base64-encoded cookie with the discard flag set to false (cookie is not discarded when browser session closes).
     */
    public Cookie(String name, String valueCleartext, String path, long maxAgeInSeconds, EncodingType encodingType) throws Exception {
        this(name, valueCleartext, path, maxAgeInSeconds, encodingType, false);
    }

    /**
     * Create a base64-encoded cookie with the discard flag set to false (cookie is not discarded when browser session closes).
     */
    public Cookie(String name, String valueCleartext, String path, long maxAgeInSeconds) throws Exception {
        this(name, valueCleartext, path, maxAgeInSeconds, EncodingType.BASE64_ENCODED, false);
    }

    /**
     * Parse a cookie from a Netty Cookie. Will throw an exception if cookie decoding failed for some reason (in this case, ignore the cookie).
     */
    public Cookie(io.netty.handler.codec.http.Cookie nettyCookie) {
        this.name = nettyCookie.getName();
        this.cookieJar = new CookieJar(nettyCookie);
    }

    // ------------------------------------------------------------------------------------------------------------------------

    // Valid characters for cookie fields and values
    private static final boolean[] VALID_CHAR = new boolean[256];
    static {
        for (int i = 33; i <= 126; i++)
            VALID_CHAR[i] = true;
        for (char c : new char[] { '\'', '"', ',', ';', '\\' })
            VALID_CHAR[c] = false;
    }

    private static void checkValidCookieFieldStr(String str) {
        if (str.length() > 3500) {
            throw new RuntimeException("Cookie value too long: " + str);
        }
        for (int i = 0, n = str.length(); i < n; i++) {
            char c = str.charAt(i);
            if (c > 255 || !VALID_CHAR[c]) {
                throw new RuntimeException("Invalid cookie field: " + str);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Create a cookie that, if set in response, overwrites and deletes the named cookie (because maxAge is set to zero)
     */
    public static Cookie deleteCookie(String name) throws Exception {
        return new Cookie(name, "", "/", 0, EncodingType.PLAIN, false);
    }

    // ------------------------------------------------------------------------------------------------------------------------

    /** Get the cookie as an HTTP header string, including all cookie headers, with the value encoded in base64 */
    @Override
    public String toString() {
        return ServerCookieEncoder.encode(cookieJar.getEncodedCookie());
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return cookieJar.getPath();
    }

    /** Get unencoded value of cookie. */
    public String getValue() {
        return cookieJar.getUnencodedValue();
    }

    public boolean hasExpired() {
        return cookieJar.cookieHasExpired();
    }

    public io.netty.handler.codec.http.Cookie getNettyCookie() {
        return cookieJar.getEncodedCookie();
    }

    public static String generateRandomSessionToken() {
        return RandomTokenGenerator.generateRandomTokenBase64(Cookie.SESSION_COOKIE_LENGTH);
    }
}
