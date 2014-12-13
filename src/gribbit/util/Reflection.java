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

import java.lang.reflect.Constructor;

public class Reflection {

    public static <T> T instantiateWithDefaultConstructor(Class<T> klass) throws InstantiationException {
        try {
            // Try calling type.newInstance() first
            return klass.newInstance();

        } catch (Exception e) {
            // If that fails, try calling constructor.newInstance()
            try {
                // Get the default constructor of the post param type class
                Constructor<?>[] constructors = klass.getConstructors();
                if (constructors == null || constructors.length == 0) {
                    throw new InstantiationException("No constructors found");
                }
                Constructor<?> constructor = null;
                for (int i = 0; i < constructors.length; i++) {
                    if (constructors[i].getParameterCount() == 0) {
                        constructor = constructors[i];
                        break;
                    }
                }
                if (constructor == null) {
                    throw new InstantiationException("No default (zero-argument) constructor");
                }
                
                // Try calling default constructor
                @SuppressWarnings("unchecked")
                T instance = (T) constructor.newInstance();
                return instance;

            } catch (Exception e2) {
                throw new InstantiationException("Could not instantiate class of type " + klass.getName() + " with default (zero-argument) constructor");
            }
        }
    }

}
