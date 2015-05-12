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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

/** Simple multimap class. */
public class MultiMapKeyToSet<S, T> {

    HashMap<S, HashSet<T>> map = new HashMap<S, HashSet<T>>();

    public static <K, V> MultiMapKeyToSet<K, V> make() {
        return new MultiMapKeyToSet<K, V>();
    }

    /** Return true if this multimap did not already contain the specified value at the specified key. */
    public boolean put(S key, T value) {
        HashSet<T> set = map.get(key);
        if (set == null) {
            set = new HashSet<T>();
            map.put(key, set);
        }
        return set.add(value);
    }

    public void putAll(S key, Iterable<T> values) {
        boolean putSomething = false;
        for (T val : values) {
            put(key, val);
            putSomething = true;
        }
        if (!putSomething && !map.containsKey(key)) {
            // If putting an empty collection, need to create an empty set at the key 
            map.put(key, new HashSet<T>());
        }
    }

    public void putAll(S key, T[] values) {
        if (values.length == 0 && !map.containsKey(key)) {
            // If putting an empty collection, need to create an empty set at the key 
            map.put(key, new HashSet<T>());
        } else {
            for (T val : values) {
                put(key, val);
            }
        }
    }

    public HashSet<T> get(S key) {
        return map.get(key);
    }

    public boolean containsKey(S key) {
        return map.containsKey(key);
    }

    public int sizeKeys() {
        return map.size();
    }

    public Set<Entry<S, HashSet<T>>> entrySet() {
        return map.entrySet();
    }

    public HashMap<S, HashSet<T>> getRawMap() {
        return map;
    }

    public Set<S> keySet() {
        return map.keySet();
    }

    public ArrayList<HashSet<T>> valueSets() {
        return new ArrayList<>(map.values());
    }

    /** Invert the mapping */
    public MultiMapKeyToSet<T, S> invert() {
        MultiMapKeyToSet<T, S> inv = new MultiMapKeyToSet<T, S>();
        for (Entry<S, HashSet<T>> ent : map.entrySet()) {
            S key = ent.getKey();
            for (T val : ent.getValue())
                inv.put(val, key);
        }
        return inv;
    }
}
