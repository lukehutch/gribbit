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
import gribbit.util.WebUtils;
import gribbit.util.Log;
import gribbit.util.RandomTokenGenerator;
import gribbit.util.UTF8;
import gribbit.util.UTF8.UTF8Exception;
import io.netty.handler.codec.http.DefaultCookie;
import io.netty.handler.codec.http.ServerCookieEncoder;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cookies!
 */
public class Cookie {

    private final String name;

    public enum EncodingType {
        PLAIN, BASE64_ENCODED, ENCRYPTED;
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
     * Private class for encapsulating access to cookie values so that we can lazily handle encryption / decryption and encoding / decoding of values (to save time if a cookie is
     * not used in a given request).
     */
    private class CookieJar {
        /**
         * The Netty cookie containing the base64-encoded, possibly encrypted UTF8 bytes representing the cookie's string value
         */
        private io.netty.handler.codec.http.Cookie encodedNettyCookie;

        /** The unencrypted cookie value. */
        private String unencodedValue;

        /** Tracks whether encoded and/or decoded values are known. */
        private boolean hasEncodedValue, hasUnencodedValue;

        private EncodingType encodingType;

        private static final String BASE64_ENCODED_PREFIX = "!0!";
        private static final String ENCRYPTED_PREFIX = "!1!";

        public CookieJar(io.netty.handler.codec.http.Cookie nettyCookie) {
            hasEncodedValue = true;
            this.encodedNettyCookie = nettyCookie;
            String value = nettyCookie.getValue();
            encodingType = value.startsWith(BASE64_ENCODED_PREFIX) ? EncodingType.BASE64_ENCODED : value.startsWith(ENCRYPTED_PREFIX) ? EncodingType.ENCRYPTED : EncodingType.PLAIN;
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
                            byte[] unencryptedBytes = Base64Safe.base64Decode(encodedValue.substring(BASE64_ENCODED_PREFIX.length()));
                            unencodedValue = UTF8.utf8ToString(unencryptedBytes);

                        } else if (encodedValue.startsWith(ENCRYPTED_PREFIX)) {
                            // Base64-encoded, encrypted UTF8 bytes
                            byte[] encryptedBytes = Base64Safe.base64Decode(encodedValue.substring(ENCRYPTED_PREFIX.length()));
                            byte[] unencryptedBytes = decrypt(encryptedBytes);
                            unencodedValue = UTF8.utf8ToString(unencryptedBytes);

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
                    // Either encrypt and then base64-encode the value, or just base64-encode it
                    byte[] utf8Bytes = UTF8.stringToUTF8(unencodedValue);
                    byte[] maybeEncryptedBytes = encodingType == EncodingType.ENCRYPTED ? encrypt(utf8Bytes) : utf8Bytes;
                    String prefix = encodingType == EncodingType.ENCRYPTED ? ENCRYPTED_PREFIX : BASE64_ENCODED_PREFIX;
                    encodedValue = prefix + Base64Safe.base64Encode(maybeEncryptedBytes);
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
     * Create a cookie and encode/encrypt its value. If maxAgeInSeconds is Long.MIN_VALUE, then cookie will expire at end of browser session.
     */
    public Cookie(String name, String unencodedValue, String path, long maxAgeInSeconds, EncodingType encodingType, boolean discardAtEndOfBrowserSession) throws Exception {
        this.name = name;
        this.cookieJar = new CookieJar(name, unencodedValue, path, maxAgeInSeconds, encodingType, discardAtEndOfBrowserSession);
    }

    /**
     * Create a possibly-encrypted cookie with the discard flag set to false (cookie is not discarded when browser session closes).
     */
    public Cookie(String name, String valueCleartext, String path, long maxAgeInSeconds, EncodingType encodingType) throws Exception {
        this(name, valueCleartext, path, maxAgeInSeconds, encodingType, false);
    }

    /**
     * Create an encrypted cookie with the discard flag set to false (cookie is not discarded when browser session closes).
     */
    public Cookie(String name, String valueCleartext, String path, long maxAgeInSeconds) throws Exception {
        this(name, valueCleartext, path, maxAgeInSeconds, EncodingType.ENCRYPTED, false);
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

    private static final SecretKeySpec secretKeySpec = new SecretKeySpec(GribbitProperties.COOKIE_ENCRYPTION_KEY, "AES");
    private static Cipher encryptCipher = null, decryptCipher = null;
    static {
        try {
            // Takes about 175ms to start up
            encryptCipher = Cipher.getInstance("AES");
            encryptCipher.init(Cipher.ENCRYPT_MODE, secretKeySpec);
            decryptCipher = Cipher.getInstance("AES");
            decryptCipher.init(Cipher.DECRYPT_MODE, secretKeySpec);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------

    private static final byte COOKIE_FORMAT_CODE = 1;

    /** Increases security of cookie encryption by making content less predictable. */
    private static final int SALT_BYTES = 6;

    /**
     * Encrypt a cookie's contents.
     */
    public static byte[] encrypt(byte[] unencryptedCookieBytes) {
        try {
            byte[] saltBytes = RandomTokenGenerator.generateRandomBytes(SALT_BYTES);
            byte[] saltedCookieValue = new byte[1 + saltBytes.length + unencryptedCookieBytes.length];
            saltedCookieValue[0] = COOKIE_FORMAT_CODE;
            for (int i = 0; i < saltBytes.length; i++) {
                saltedCookieValue[1 + i] = saltBytes[i];
            }
            for (int i = 0; i < unencryptedCookieBytes.length; i++) {
                saltedCookieValue[1 + saltBytes.length + i] = unencryptedCookieBytes[i];
            }
            byte[] encryptedCookieBytes;
            synchronized (encryptCipher) {
                encryptedCookieBytes = encryptCipher.doFinal(saltedCookieValue);
            }
            return encryptedCookieBytes;

        } catch (Exception e) {
            // Should never happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Decrypt an encrypted cookie.
     */
    public static byte[] decrypt(byte[] encryptedCookieBytes) throws IllegalArgumentException {
        try {
            byte[] decrypted;
            synchronized (decryptCipher) {
                decrypted = decryptCipher.doFinal(encryptedCookieBytes);
            }
            if (decrypted[0] != COOKIE_FORMAT_CODE) {
                // Bad cookie format
                throw new RuntimeException("Bad cookie format");
            }
            return Arrays.copyOfRange(decrypted, 1 + SALT_BYTES, decrypted.length);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------

    // /** Test cookie encryption and decryption */
    // public static void main(String[] args) {
    // EncryptedSessionCookie cookie = new EncryptedSessionCookie("luke", 2, "test");
    // EncryptedSessionCookie cookieDec = new EncryptedSessionCookie(cookie.getEncryptedCookie());
    // if (Math.abs(cookie.getSessionExpiry().getTime() - cookieDec.getSessionExpiry().getTime()) > 2000)
    // throw new RuntimeException("Cookie mismatch");
    //
    // try {
    // long t2 = System.currentTimeMillis();
    //
    // int nreps = 1000;
    // for (int i = 0; i < nreps; i++) {
    // int n = (int) (3000 * Math.random());
    // StringBuilder buf = new StringBuilder(n);
    // for (int j = 0; j < n; j++) {
    // buf.append((char) (32 + 95 * Math.random()));
    // }
    // String plaintext = buf.toString();
    // String encCookie = encryptCookie(plaintext);
    // // System.out.println(encCookie);
    // String decCookie = decryptCookie(encCookie);
    // // System.out.println(decCookie + "\n");
    // if (!decCookie.equals(plaintext))
    // throw new RuntimeException("Cookie mismatch after decryption");
    // }
    //
    // long t3 = System.currentTimeMillis();
    // System.out.println("Encryption and decryption passed");
    // System.out.println((t3 - t2) / (float) nreps + " msec on average to encrypt + decrypt a cookie");
    //
    // } catch (Exception ex) {
    // ex.printStackTrace();
    // }
    // }

    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Create a cookie that, if set in response, overwrites and deletes the named cookie (because maxAge is set to zero)
     */
    public static Cookie deleteCookie(String name) throws Exception {
        return new Cookie(name, "", "/", 0);
    }

    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Get the cookie as an HTTP header string, including all cookie headers, with the value encoded in base64 and possibly also encrypted.
     */
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

    /** Get unencoded, unencrypted value of cookie. */
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
