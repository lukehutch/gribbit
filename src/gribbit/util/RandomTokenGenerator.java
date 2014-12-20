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

import java.util.Random;

public class RandomTokenGenerator {

    public static Random rng = new Random();

    /**
     * Generate a random token with the given number of characters after base64-encoding (using HTML/URL-safe base64
     * encoding).
     */
    public static String generateRandomTokenBase64(int numChars) {
        return Base64Safe.base64Encode(generateRandomBytes(numChars)).substring(0, numChars);
    }

    /** Generate random bytes, and return them as a byte array. */
    public static byte[] generateRandomBytes(int numBytes) {
        byte[] bytes = new byte[numBytes];
        rng.nextBytes(bytes);
        return bytes;
    }

}
