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

import gribbit.auth.User;
import gribbit.thirdparty.BCrypt;

/**
 */
public class Hash {
    /**
     * A password must be longer than this minimum length to pass hashing, otherwise an AppException is thrown.
     */
    public static final int MIN_PASSWORD_LENGTH = 6;

    /**
     * Create an encrypted password from a clear string.
     * 
     * @param cleartextPassword
     *            the cleartext password
     * @return the salted, hashed password.
     * @throws AppException
     *             Thrown if password is null or too short.
     */
    public static String hashPassword(String cleartextPassword) throws AppException {
        if (cleartextPassword == null || cleartextPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new AppException("Password is too short");
        }
        return BCrypt.hashpw(cleartextPassword, BCrypt.gensalt());
    }

    /**
     * @param candidate
     *            the clear text
     * @param passwordHash
     *            the hashed password
     * @return true if the candidate matches, false otherwise.
     */
    public static boolean checkPassword(String candidate, String passwordHash) {
        if (candidate == null || passwordHash == null //
                // Can't validate username/password combination if user created account with Persona 
                || passwordHash.equals(User.FEDERATED_LOGIN_PASSWORD_HASH_PLACEHOLDER)) {
            return false;
        }
        // Takes ~80ms
        return BCrypt.checkpw(candidate, passwordHash);
    }
}
