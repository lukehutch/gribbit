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
package gribbit.model;

import gribbit.auth.CSRF;
import gribbit.request.Request;
import gribbit.response.exception.BadRequestException;
import gribbit.response.exception.ExceptionResponse;
import gribbit.response.exception.InternalServerErrorException;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.DataModelLoader;
import gribbit.util.Log;
import gribbit.util.StringUtils;
import gribbit.util.WebUtils;
import io.netty.handler.codec.http.multipart.FileUpload;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * A model that can be bound from the parameters in a POST request, and/or that can be used to populate an HTML
 * template.
 * 
 * You can apply data constraint annotations to the fields of subclasses of DataModel, such MaxLength or Email, and
 * these constraints will be applied when submitted form data is bound to the model.
 */
public abstract class DataModel {

    private static boolean isBasicFieldType(Class<?> fieldType) {
        return fieldType == String.class //
                || fieldType == Integer.class || fieldType == Integer.TYPE //
                || fieldType == Long.class || fieldType == Long.TYPE //
                || fieldType == Short.class || fieldType == Short.TYPE //
                || fieldType == Float.class || fieldType == Float.TYPE //
                || fieldType == Double.class || fieldType == Double.TYPE //
                || fieldType == Boolean.class || fieldType == Boolean.TYPE //
                || fieldType == Character.class || fieldType == Character.TYPE //
                || fieldType == LocalDate.class // 
                || fieldType.isEnum();
    }

    /**
     * Returns true if the DataModel's public fields consist only of basic types (builtins or their wrapper classes,
     * String, LocalDate, or enum).
     */
    public static boolean isFlatModel(Class<? extends DataModel> dataModelClass) {
        for (Field formField : dataModelClass.getFields()) {
            if (!DataModelLoader.fieldIsPrivate(formField, /* checkGet = */true, /* checkSet = */true)) {
                Class<?> formFieldType = formField.getType();
                if (!isBasicFieldType(formFieldType)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * TODO: Call this from classpath scanner (but allow List and Array types, and TemplateModel params inside
     * TemplateModel types).
     * 
     * Should probably split out field type checking for all 3 DataModel types
     */
    public static void checkPostParamFieldTypes(Class<? extends DataModel> postParamDataModel) {
        for (Field field : postParamDataModel.getFields()) {
            if (!DataModelLoader.fieldIsPrivate(field, /* checkGet = */false, /* checkSet = */true)) {
                Class<?> fieldType = field.getType();
                if (!isBasicFieldType(fieldType)) {
                    throw new RuntimeException("Unsupported field type " + fieldType.getSimpleName() + " for field "
                            + postParamDataModel.getName() + "." + field.getName());
                }
            }
        }
    }

    /**
     * Bind formModelInstance data object from an HTTP request. The name of each field in formModelInstance object is
     * looked up in the POST parameters. If the named value is found, it is trimmed (unless annotated with NoTrim), and
     * integers etc. are parsed as needed before the value is stored in the field. Fields marked with Required
     * constraint have to not only not be null, but also not be empty after trimming. Note that any constraint such as
     * MinLength also implies Required. (NormalizeSpacing is a data transformation annotation, not a constraint, so it
     * will only be applied if a value is provided.)
     * 
     * @param request
     *            The HTTP request
     * @param formModel
     *            The form model to bind
     * @throws AppException
     *             if any of the constraint annotations are not specified
     */
    public void bindFromPost(Request request) throws ExceptionResponse {

        // Match field names against POST param names, and set values of fields whenever there is a match
        HashSet<String> unusedPostParams = new HashSet<String>(request.getPostParamNames());
        for (Field field : getClass().getFields()) {
            String fieldName = field.getName();
            unusedPostParams.remove(fieldName);

            if (DataModelLoader.fieldIsPrivate(field, /* checkGet = */false, /* checkSet = */true)) {
                // Ignore attempts to set fields annotated with @Private or @OnlySend,
                // or where the field is a DBModel id field
                // Log.warning("Attempt to bind POST param \"" + field.getName() + "\" to field marked @"
                //         + Private.class.getName() + " or @" + OnlySend.class.getName()
                //         + " (or an id field of a subclass of " + DBModel.class.getName() + ") in class "
                //         + this.getClass().getName() + "; ignoring");

            } else {
                Class<?> fieldType = field.getType();

                try {
                    // For each field in class, look up field name in POST parameters and then
                    // URL query parameters
                    String postParamValForField = request.getPostParam(fieldName);
                    if (postParamValForField == null) {

                        FileUpload postFileUploadForField = request.getPostFileUploadParam(fieldName);
                        if (postFileUploadForField != null) {

                            // There was a file uploaded
                            if (fieldType == FileUpload.class) {
                                // Save the FileUpload object in the field. This doesn't actually read the
                                // file contents (loading the file into RAM could kill the server). The
                                // request handler must read the file before responding, or increment the
                                // reference count if they want to keep the file after the request has been
                                // sent, because the file will otherwise be released.
                                field.set(this, postFileUploadForField);
                            }

                        } else {

                            // There is a field in formModelInstance DataModel that is not in the
                            // POST request
                            if (DataModelLoader.fieldIsRequired(field)) {
                                throw new BadRequestException(request, "Field " + getClass().getName() + "."
                                        + fieldName + " required, but not sent in POST request");
                            }
                        }

                    } else {
                        // Try binding POST request param to correspondingly-named field in
                        // the formModelInstance DataModel

                        if (fieldType == String.class) {
                            field.set(this, postParamValForField);

                        } else if (fieldType == Integer.class) {
                            field.set(this, new Integer(postParamValForField));

                        } else if (fieldType == Integer.TYPE) {
                            field.setInt(this, Integer.parseInt(postParamValForField));

                        } else if (fieldType == Long.class) {
                            field.set(this, new Long(postParamValForField));

                        } else if (fieldType == Long.TYPE) {
                            field.setLong(this, Long.parseLong(postParamValForField));

                        } else if (fieldType == Short.class) {
                            field.set(this, new Short(postParamValForField));

                        } else if (fieldType == Short.TYPE) {
                            field.setShort(this, Short.parseShort(postParamValForField));

                        } else if (fieldType == Float.class) {
                            field.set(this, new Float(postParamValForField));

                        } else if (fieldType == Float.TYPE) {
                            field.setFloat(this, Float.parseFloat(postParamValForField));

                        } else if (fieldType == Double.class) {
                            field.set(this, new Double(postParamValForField));

                        } else if (fieldType == Double.TYPE) {
                            field.setDouble(this, Double.parseDouble(postParamValForField));

                        } else if (fieldType == Boolean.class || fieldType == Boolean.TYPE) {
                            // Boolean fields are bound from checkbox form inputs

                            String lower = postParamValForField.toLowerCase();
                            boolean checkboxVal = lower.equals("yes") || lower.equals("on") || lower.equals("1")
                                    || lower.equals("true");
                            if (fieldType == Boolean.class) {
                                field.set(this, new Boolean(checkboxVal));
                            } else if (fieldType == Boolean.TYPE) {
                                field.setBoolean(this, checkboxVal);
                            }

                        } else if (fieldType == Character.class || fieldType == Character.TYPE) {
                            // Character fields are bound from text, but limited to a length of 1

                            if (postParamValForField.length() > 1) {
                                throw new BadRequestException(request, "Field " + getClass().getName() + "."
                                        + fieldName + " requires a single character, got "
                                        + postParamValForField.length() + " characters");
                            } else if (postParamValForField.length() == 1) {
                                char c = postParamValForField.charAt(0);
                                if (fieldType == Character.class) {
                                    field.set(this, new Character((char) c));
                                } else if (fieldType == Character.TYPE) {
                                    field.setChar(this, (char) c);
                                }
                            }

                        } else if (fieldType == LocalDate.class) {
                            // Dates must be in format yyyy-MM-dd
                            field.set(this, LocalDate.parse(postParamValForField));

                        } else if (fieldType.isEnum()) {
                            // Enum-typed fields are bound from radio button inputs

                            // If the field is an enum, try looking up the submitted form value as
                            // one of the enum constants.
                            try {
                                @SuppressWarnings({ "unchecked", "rawtypes" })
                                Enum<?> enumVal = Enum.valueOf((Class<Enum>) fieldType, postParamValForField);
                                field.set(this, enumVal);
                            } catch (IllegalArgumentException e) {
                                throw new BadRequestException(request, "Illegal value " + postParamValForField
                                        + " for field " + getClass().getName() + "." + fieldName);
                            }

                        } else {
                            throw new InternalServerErrorException(request, "Unsupported field type "
                                    + fieldType.getSimpleName() + " for field " + getClass().getName() + "."
                                    + fieldName);
                        }
                    }

                } catch (NumberFormatException | DateTimeParseException e) {
                    throw new BadRequestException(request, "Could not parse value " + getClass().getName() + "."
                            + fieldName + " from the request");

                } catch (IllegalArgumentException | IllegalAccessException e) {
                    throw new InternalServerErrorException(request, "Could not set field " + getClass().getName() + "."
                            + fieldName + " to the value passed in the request", e);
                }
            }
        }

        // Check that the values in the fields satisfy the constraints
        try {
            DataModelLoader.checkFieldValuesAgainstConstraints(this);
        } catch (Exception e) {
            throw new BadRequestException(request, "Form values invalid: " + e.getMessage());
        }

        for (String unusedParam : unusedPostParams) {
            if (!unusedParam.equals(CSRF.CSRF_PARAM_NAME)) {
                Log.warning("POST param \"" + unusedParam + "\" is not a public field in DataModel "
                        + this.getClass().getName() + ", ignoring");
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Recursively render JSON, skipping fields marked with @Private or @OnlyReceive, and id fields of DBModel objects.
     * This produces a JSON rendering that may be served over a Web connection without exposing internal server state.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void toJSONRec(Object obj, boolean prettyPrint, int depth, StringBuilder buf) {
        if (obj == null) {
            buf.append("null");
            return;
        } else {
            Class<? extends Object> klass = obj.getClass();
            try {
                boolean renderFieldsAsMap = false;
                if (DataModel.class.isAssignableFrom(klass)) {
                    renderFieldsAsMap = true;

                } else if (klass == String.class) {
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
                            // Save on time if not prettyprinting
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
                    // Some other class -- render fields as a JSON associative array using introspection
                    renderFieldsAsMap = true;
                }

                if (renderFieldsAsMap) {
                    // DataModel, DBModel, or some other class -- render fields as a JSON associative array
                    ArrayList<Field> fieldsToInclude = new ArrayList<>();
                    Field[] fields = klass.getFields();
                    for (int i = 0; i < fields.length; i++) {
                        Field field = fields[i];
                        // DataModel fields annotated with @Private or @OnlyReceive and
                        // DBModel id fields cannot be sent to the user
                        if (!(obj instanceof DataModel) || !DataModelLoader.fieldIsPrivate(field, //
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
     * Render this DataModel as JSON, skipping fields marked with @Private or @OnlyReceive, and id fields of DBModel
     * objects. This produces a JSON rendering that may be served over a Web connection without exposing internal server
     * state.
     */
    public String toJSON(boolean prettyPrint) {
        StringBuilder buf = new StringBuilder(8192);
        toJSONRec(this, prettyPrint, 0, buf);
        return buf.toString();
    }

    /**
     * Recursively render an Object (or array, list, map or set of objects) as JSON, skipping fields marked with the
     * annotanions Private or OnlyReceive, and id fields of DBModel objects. This produces a JSON rendering that may be
     * served over a Web connection without exposing internal server state.
     */
    public static String toJSON(Object obj, boolean prettyPrint) {
        StringBuilder buf = new StringBuilder(8192);
        toJSONRec(obj, prettyPrint, 0, buf);
        return buf.toString();
    }

    /** Render a DataModel object as a JSON string. */
    @Override
    public String toString() {
        return this.toJSON(GribbitProperties.PRETTY_PRINT_JSON);
    }

}
