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

import gribbit.model.util.FieldChecker;
import gribbit.server.config.GribbitProperties;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class JSON {
    /**
     * Recursively render JSON, skipping fields marked with Private or PrivateGet annotations, and id fields of DBModel
     * objects. This produces a JSON rendering that may be served over a Web connection without exposing internal server
     * state.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void toJSONRec(Object obj, boolean prettyPrint, int depth, StringBuilder buf) {
        if (obj == null) {
            buf.append("null");
            return;
        } else {
            Class<? extends Object> klass = obj.getClass();
            try {
                if (klass == String.class) {
                    buf.append('"');
                    WebUtils.escapeJSONString((String) obj, buf);
                    buf.append('"');

                } else if (klass == Integer.class || klass == Boolean.class || klass == Long.class
                        || klass == Float.class || klass == Double.class || klass == Short.class) {
                    buf.append(obj.toString());

                } else if (klass == Byte.class) {
                    buf.append(Integer.toString(((Byte) obj).intValue()));

                } else if (klass == Character.class) {
                    buf.append('"');
                    WebUtils.escapeJSONString(((Character) obj).toString(), buf);
                    buf.append('"');

                } else if (klass.isArray() || List.class.isAssignableFrom(klass)) {
                    // Render an array or list
                    boolean isList = List.class.isAssignableFrom(klass);
                    List<?> list = isList ? (List<?>) obj : null;
                    int n = isList ? list.size() : Array.getLength(obj);
                    if (n == 0) {
                        buf.append(prettyPrint ? "[ ]" : "[]");
                    } else {
                        buf.append(prettyPrint ? "[ " : "[");
                        for (int i = 0; i < n; i++) {
                            if (i > 0) {
                                buf.append(prettyPrint ? ", " : ",");
                            }
                            // Recursively render value
                            toJSONRec(isList ? list.get(i) : Array.get(obj, i), prettyPrint, depth + 1, buf);
                        }
                        buf.append(prettyPrint ? " ]" : "]");
                    }

                } else if (Iterable.class.isAssignableFrom(klass)) {
                    // Render an Iterable (e.g. a Set)
                    Iterable<?> iterable = (Iterable<?>) obj;
                    boolean empty = true;
                    buf.append(prettyPrint ? "[ " : "[");
                    int i = 0;
                    for (Object element : iterable) {
                        if (i++ > 0) {
                            buf.append(prettyPrint ? ", " : ",");
                        }
                        // Recursively render value
                        toJSONRec(element, prettyPrint, depth + 1, buf);
                        empty = false;
                    }
                    if (!empty && prettyPrint) {
                        buf.append(' ');
                    }
                    buf.append(prettyPrint ? (!empty ? " ]" : "]") : "]");

                } else if (Map.class.isAssignableFrom(klass)) {
                    // Render a Map as a JSON associative array.
                    Map<?, ?> map = (Map<?, ?>) obj;
                    if (map.size() == 0) {
                        buf.append(prettyPrint ? "{ }" : "{}");
                    } else {
                        buf.append(prettyPrint ? "{\n" : "{");
                        if (prettyPrint) {
                            // If prettyprinting, get first non-null key and see if it
                            // implements Comparable, and if so, sort the keys into order
                            ArrayList<?> keys = new ArrayList<>(map.keySet());
                            int n = keys.size();
                            Object firstKey = keys.get(0);
                            if (firstKey == null && n > 1) {
                                firstKey = keys.get(1);
                            }
                            if (firstKey != null) {
                                if (Comparable.class.isAssignableFrom(firstKey.getClass())) {
                                    Collections.sort((ArrayList<Comparable>) keys);
                                }
                            }
                            for (int i = 0; i < n; i++) {
                                Object key = keys.get(i);
                                Object val = map.get(key);

                                // Render key 
                                if (prettyPrint) {
                                    buf.append(StringUtils.spaces(depth + 1));
                                }
                                buf.append('"');
                                WebUtils.escapeJSONString(key.toString(), buf);
                                buf.append(prettyPrint ? "\" : " : "\":");

                                // Recursively render value
                                toJSONRec(val, prettyPrint, depth + 1, buf);
                                if (i < n - 1) {
                                    buf.append(prettyPrint ? ",\n" : ",");
                                } else if (prettyPrint) {
                                    buf.append('\n');
                                }
                            }
                        } else {
                            // Save time if not prettyprinting
                            int remaining = map.size();
                            for (Entry ent : map.entrySet()) {
                                Object key = ent.getKey();
                                Object val = ent.getValue();

                                // Render key 
                                if (prettyPrint) {
                                    buf.append(StringUtils.spaces(depth + 1));
                                }
                                buf.append('"');
                                WebUtils.escapeJSONString(key.toString(), buf);
                                buf.append(prettyPrint ? "\" : " : "\":");

                                // Recursively render value
                                toJSONRec(val, prettyPrint, depth + 1, buf);
                                if (--remaining > 0) {
                                    buf.append(prettyPrint ? ",\n" : ",");
                                } else if (prettyPrint) {
                                    buf.append('\n');
                                }
                            }
                        }
                        if (prettyPrint) {
                            buf.append(StringUtils.spaces(depth));
                        }
                        buf.append('}');
                    }

                } else {
                    // Some other class -- render fields as a JSON associative array using introspection.
                    ArrayList<Field> fieldsToInclude = new ArrayList<>();
                    Field[] fields = klass.getFields();
                    for (int i = 0; i < fields.length; i++) {
                        Field field = fields[i];
                        // DataModel fields annotated with @Private or @OnlyReceive and
                        // DBModel id fields cannot be sent to the user
                        if (!FieldChecker.fieldIsPrivate(field,
                        /* checkGet = */true, /* checkSet = */false)) {
                            // In case class is not itself public, need to call setAccessible(true)
                            // FIXME: Need to do the same everywhere else we get fields, or
                            // FIXME: alternatively catch IllegalAccessException and tell the user
                            // FIXME: that the class needs to be public.
                            field.setAccessible(true);
                            fieldsToInclude.add(field);
                        }
                    }
                    int n = fieldsToInclude.size();
                    if (n == 0) {
                        buf.append(prettyPrint ? "{ }" : "{}");
                    } else {
                        buf.append(prettyPrint ? "{\n" : "{");
                        for (int i = 0; i < n; i++) {
                            Field field = fieldsToInclude.get(i);

                            // Render field name as key
                            if (prettyPrint) {
                                buf.append(StringUtils.spaces(depth + 1));
                            }
                            buf.append('"');
                            WebUtils.escapeJSONString(field.getName(), buf);
                            buf.append(prettyPrint ? "\" : " : "\":");

                            // Render value
                            // Turn primitive types into strings, they have their own getter methods
                            Class<?> fieldType = field.getType();
                            if (fieldType == Integer.TYPE) {
                                buf.append(Integer.toString(field.getInt(obj)));
                            } else if (fieldType == Boolean.TYPE) {
                                buf.append(Boolean.toString(field.getBoolean(obj)));
                            } else if (fieldType == Long.TYPE) {
                                buf.append(Long.toString(field.getLong(obj)));
                            } else if (fieldType == Float.TYPE) {
                                buf.append(Float.toString(field.getFloat(obj)));
                            } else if (fieldType == Double.TYPE) {
                                buf.append(Double.toString(field.getDouble(obj)));
                            } else if (fieldType == Short.TYPE) {
                                buf.append(Short.toString(field.getShort(obj)));
                            } else if (fieldType == Byte.TYPE) {
                                buf.append(Integer.toString((int) field.getByte(obj)));
                            } else if (fieldType == Character.TYPE) {
                                buf.append('"');
                                WebUtils.escapeJSONString(Character.toString(field.getChar(obj)), buf);
                                buf.append('"');
                            } else {
                                // Not a primitive type; recursively render value
                                toJSONRec(field.get(obj), prettyPrint, depth + 1, buf);
                            }
                            if (i < n - 1) {
                                buf.append(prettyPrint ? ",\n" : ",");
                            } else if (prettyPrint) {
                                buf.append('\n');
                            }
                        }
                        if (prettyPrint) {
                            buf.append(StringUtils.spaces(depth));
                        }
                        buf.append('}');
                    }
                }
            } catch (IllegalArgumentException | IllegalAccessException e) {
                throw new RuntimeException("Could not render object into JSON", e);
            }
        }
    }

    /**
     * Recursively render an Object (or array, list, map or set of objects) as JSON, skipping fields marked with the
     * annotations Private or PrivateGet, and id fields of DBModel objects. This produces a JSON rendering that may be
     * served over a Web connection without exposing internal server state.
     */
    public static String toJSON(Object obj, boolean prettyPrint) {
        StringBuilder buf = new StringBuilder(8192);
        toJSONRec(obj, prettyPrint, 0, buf);
        return buf.toString();
    }

    /**
     * Recursively render an Object (or array, list, map or set of objects) as JSON, skipping fields marked with the
     * annotations Private or PrivateGet, and id fields of DBModel objects. This produces a JSON rendering that may be
     * served over a Web connection without exposing internal server state.
     */
    public static String toJSON(Object obj) {
        return toJSON(obj, GribbitProperties.PRETTY_PRINT_JSON);
    }

}
