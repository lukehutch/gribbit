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

import gribbit.http.cookie.Cookie;
import gribbit.http.response.Response;
import gribbit.util.RandomTokenGenerator;
import gribbit.util.thirdparty.UTF8;

/**
 * CSRF token generation support.
 */
public class CSRF {

    // TODO: Think about ways to protect against same-origin CSRF attacks:
    //
    // http://www.christian-schneider.net/CsrfAndSameOriginXss.html
    //
    // (We don't need to worry about this as long as XSS proctection is guaranteed, and as long as there
    // isn't some unsecured service running on the same domain at a different URI.)

    /**
     * Special CSRF token POST parameter -- used for the name of the form field that is automatically inserted
     * before a form is submitted.
     */
    public static final String CSRF_PARAM_NAME = "csrf";

    /**
     * Placeholder token used to hold a fixed number of characters in the csrf hidden input in forms on an HTML
     * page.
     */
    public static final String CSRF_TOKEN_PLACEHOLDER = generateRandomCSRFToken();

    /** Placeholder for CSRF token, in bytes. */
    public static final byte[] CSRF_TOKEN_PLACEHOLDER_BYTES = UTF8.stringToUTF8(CSRF_TOKEN_PLACEHOLDER);

    /**
     * Placeholder used for substitution into forms when the user is not logged in (because if the user is not
     * logged in, the appropriate CSRF token for the user is not known).
     */
    public static final String CSRF_TOKEN_UNKNOWN;
    static {
        StringBuilder buf = new StringBuilder(CSRF_TOKEN_PLACEHOLDER.length());
        for (int i = 0; i < CSRF_TOKEN_PLACEHOLDER.length(); i++) {
            buf.append('_');
        }
        CSRF_TOKEN_UNKNOWN = buf.toString();
    }

    public static String generateRandomCSRFToken() {
        return RandomTokenGenerator.generateRandomTokenBase64(Cookie.SESSION_COOKIE_LENGTH);
    }

    /**
     * Test if the given CSRF token matches the user's CSRF token.
     * 
     * Returns true if the test CSRF token is not null or empty, and the user is not null, and the user's CSRF token
     * is not null or empty, and if the test CSRF token matches the user's CSRF token, and (for extra safety) if
     * neither token is a placeholder token.
     */
    public static boolean csrfTokMatches(String testCsrfTok, User user) {
        if (testCsrfTok == null || user == null) {
            return false;
        } else {
            String userCsrfTok = user.csrfTok;
            if (userCsrfTok != null //
                    && !userCsrfTok.isEmpty() && !testCsrfTok.isEmpty() //
                    && userCsrfTok.equals(testCsrfTok) //
                    && !userCsrfTok.equals(CSRF_TOKEN_UNKNOWN) && !userCsrfTok.equals(CSRF_TOKEN_PLACEHOLDER)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Sets a non-HTTP-only cookie "_csrf" with the given token. To implement CSRF protection, the cookie value
     * should be read by Javascript code and added to any same-origin Javascript requests in the X-Csrf-Token
     * header. See: https://en.wikipedia.org/wiki/Cross-site_request_forgery#Cookie-to-Header_Token
     */
    public static void setCsrfCookie(String csrfTok, String path, Response response) {
        response.setCookie("Csrf-Token", csrfTok, path, /* maxAge = */-1, /* httpOnly = */false);
    }
}
