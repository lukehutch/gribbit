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

import gribbit.thirdparty.UTF8;
import gribbit.util.RandomTokenGenerator;

/**
 * CSRF token generation support.
 */
public class CSRF {

    /**
     * Special CSRF token POST parameter -- used for the name of the form field that is automatically inserted before a
     * form is submitted.
     */
    public static final String CSRF_PARAM_NAME = "csrf";

    /**
     * Placeholder token used to hold a fixed number of characters in the csrf hidden input in forms on an HTML page.
     */
    public static final String CSRF_TOKEN_PLACEHOLDER = generateRandomCSRFToken();

    /** Placeholder for CSRF token, in bytes. */
    public static final byte[] CSRF_TOKEN_PLACEHOLDER_BYTES = UTF8.stringToUTF8(CSRF_TOKEN_PLACEHOLDER);

    /**
     * Placeholder used for substitution into forms when the user is not logged in (because if the user is not logged
     * in, the appropriate CSRF token for the user is not known).
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

}
