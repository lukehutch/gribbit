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
import gribbit.model.field.annotation.IsURL;
import gribbit.request.Request;
import gribbit.route.Route;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.CacheExtension;
import gribbit.server.siteresources.DataModelLoader;
import gribbit.server.siteresources.TemplateLoader;
import gribbit.util.AppException;
import gribbit.util.Log;
import gribbit.util.StringUtils;
import gribbit.util.WebUtils;
import io.netty.handler.codec.http.multipart.FileUpload;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.nodes.XmlDeclaration;

/**
 * A model that can be bound from the parameters in a POST request, and/or that can be used to populate an HTML
 * template.
 * 
 * You can apply data constraint annotations to the fields of subclasses of DataModel, such MaxLength or Email, and
 * these constraints will be applied when submitted form data is bound to the model.
 */
public abstract class DataModel {

    /**
     * Bind formModelInstance data object from an HTTP request. The name of each field in formModelInstance object is
     * looked up in the POST parameters. If the named value is found, it is trimmed (unless annotated with NoTrim), and
     * integers etc. are parsed as needed before the value is stored in the field. Fields marked with Required
     * constraint have to not only not be null, but also not be empty after trimming. Note that any constraint such as
     * MinLength also implies Required. (NormalizeSpacing is a data transformation annotation, not a constraint, so it
     * will only be applied if a value is provided.)
     * 
     * @param req
     *            The HTTP request
     * @param formModel
     *            The form model to bind
     * @throws AppException
     *             if any of the constraint annotations are not specified
     */
    public void bindFromPost(Request req) throws AppException {

        // Match field names against POST param names, and set values of fields whenever there is a match
        HashSet<String> unusedPostParams = new HashSet<String>(req.getPostParamNames());
        for (Field field : getClass().getFields()) {
            String fieldName = field.getName();
            unusedPostParams.remove(fieldName);

            if (DataModelLoader.fieldIsPrivate(field, /* checkSendability = */false, /* checkReceivability = */true)) {
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
                    String postParamValForField = req.getPostParam(fieldName);
                    if (postParamValForField == null) {

                        FileUpload postFileUploadForField = req.getPostFileUploadParam(fieldName);
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
                                throw new AppException("Field " + fieldName + " required, but not sent in POST request");
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
                            boolean checkboxVal =
                                    lower.equals("yes") || lower.equals("on") || lower.equals("1")
                                            || lower.equals("true");
                            if (fieldType == Boolean.class) {
                                field.set(this, new Boolean(checkboxVal));
                            } else if (fieldType == Boolean.TYPE) {
                                field.setBoolean(this, checkboxVal);
                            }

                        } else if (fieldType == Character.class || fieldType == Character.TYPE) {
                            // Character fields are bound from text, but limited to a length of 1

                            if (postParamValForField.length() > 1) {
                                throw new AppException("Field " + fieldName + " requires a single character, got "
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
                                throw new AppException("Illegal value " + postParamValForField + " for field "
                                        + fieldName);
                            }

                        } else {
                            throw new RuntimeException("Unsupported field type " + fieldType.getSimpleName());
                        }
                    }

                } catch (NumberFormatException | DateTimeParseException e) {
                    throw new AppException("Could not parse value " + fieldName + " from the request", e);

                } catch (IllegalArgumentException | IllegalAccessException e) {
                    throw new RuntimeException("Could not set field " + fieldName
                            + " to the value passed in the request", e);
                }
            }
        }

        // Check that the values in the fields satisfy the constraints
        try {
            DataModelLoader.checkFieldValuesAgainstConstraints(this);
        } catch (Exception e) {
            throw new AppException("Form values invalid: " + e.getMessage());
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
                                /* checkSendability = */true, /* checkReceivability = */false)) {
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

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Templates with context-aware escaping for near-complete protection against stored and reflected XSS attacks.
     * 
     * See:
     * 
     * https://www.owasp.org/index.php/XSS#Stored_and_Reflected_XSS_Attacks
     * 
     * https://www.owasp.org/index.php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet
     * 
     * https://www.owasp.org/index.php/XSS_Filter_Evasion_Cheat_Sheet
     * 
     * https://www.owasp.org/index.php/DOM_based_XSS_Prevention_Cheat_Sheet
     * 
     * Extends DataModel so that it can be used for both filling in a form template based on previous values, and being
     * bound to the submission results of the form.
     */

    /** Escape text for an HTML attribute value or for an HTML text node. */
    private static void encodeParamText(boolean isAttrVal, boolean isURLAttr, String unsafeStr, StringBuilder buf) {
        if (isAttrVal) {
            if (isURLAttr) {
                // This parameter may be just one part of a URI, so we need to check the whole composed URI string
                // for validity after all params have been substituted -- just insert unsafe param string directly
                // for now. URI attributes are checked after all parameters have been substituted. 
                buf.append(unsafeStr);
            } else {
                // OWASP Rule #2:
                //     Attribute Escape Before Inserting Untrusted Data into HTML Common Attributes.
                WebUtils.encodeForHTMLAttribute(unsafeStr, buf);
            }
        } else {
            // OWASP Rule #1:
            //     HTML Escape Before Inserting Untrusted Data into HTML Element Content
            if (unsafeStr.indexOf("\n") >= 0) {
                // Turn "\n" within text params into <br> for convenience
                ArrayList<CharSequence> parts = StringUtils.splitAsList(unsafeStr, "\n");
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) {
                        // Can insert a raw <br> here because this text is not an attribute val,
                        // it is part of a text node.
                        buf.append("<br>");
                    }
                    // Separately escape each part split by a newline character
                    WebUtils.encodeForHTML(parts.get(i), buf);
                }

            } else {
                // No newline characters, HTML-escape the whole parameter string
                WebUtils.encodeForHTML(unsafeStr, buf);
            }
        }
    }

    /** Escape and render a field value (recursively rendering any lists or arrays). */
    private boolean recursivelyRender(boolean isAttrVal, boolean isURLAttr, Object fieldValue, boolean prettyPrint,
            int indentLevel, StringBuilder buf) {
        boolean wasIndented = false;
        if (fieldValue != null) {
            Class<?> fieldType = fieldValue.getClass();
            if (fieldType == String.class) {
                // Expand a string parameter

                String unsafeStr = (String) fieldValue;
                if (unsafeStr != null && !unsafeStr.isEmpty()) {
                    // Parameter is being expanded in non-URI attribute, or in a text node -- use regular HTML escaping
                    encodeParamText(isAttrVal, isURLAttr, unsafeStr, buf);
                }

            } else if (DataModel.class.isAssignableFrom(fieldType)) {
                // Expand an HTML template into the parameter position (param must not be in an attribute value)

                if (isAttrVal) {
                    // Shouldn't happen, this was checked for on template load, but included here for safety
                    throw new RuntimeException("Can't include HTML inside an attribute value");
                }
                // Recursively add template content to this buffer from nested template
                wasIndented = ((DataModel) fieldValue).renderTemplate(prettyPrint, indentLevel, buf);

            } else if (List.class.isAssignableFrom(fieldType) || fieldType.isArray()) {
                // Expand a list or array of values

                if (isAttrVal) {
                    // Shouldn't happen, this was checked for on template load, but included here for safety
                    throw new RuntimeException("Can't include HTML inside an attribute value");
                }
                List<?> list = List.class.isAssignableFrom(fieldType) ? (List<?>) fieldValue : null;
                Object[] array = fieldType.isArray() ? (Object[]) fieldValue : null;
                if (list != null || array != null) {
                    int n = list != null ? list.size() : array.length;
                    // Render each item in the list
                    for (int i = 0; i < n; i++) {
                        Object elt = list != null ? list.get(i) : array[i];
                        if (elt != null) {
                            if (elt instanceof DataModel) {
                                // Render HTML template for DataModel-typed list item
                                wasIndented |= ((DataModel) elt).renderTemplate(prettyPrint, indentLevel, buf);

                            } else {
                                // For any other list or array element type, recursively render
                                // list/array elements
                                wasIndented |=
                                        recursivelyRender(isAttrVal, isURLAttr, elt, prettyPrint, indentLevel, buf);

                                if (!prettyPrint && i > 0) {
                                    // Insert a space between adjacent values stringified from a list or array
                                    // to ensure they are separated. (Hopefully this is the most useful
                                    // behavior; if you need {"a", "b", "c"} -> "abc" without spaces, you need
                                    // to do the join of the parameters manually before inserting into the
                                    // template.)
                                    buf.append(' ');
                                }
                            }
                        }
                    }
                }

            } else if (Class.class.isAssignableFrom(fieldType)) {
                // Special case: if a field type is a Class<? extends RestHandler>, then insert the URL of the
                // RestHandler's route as a string, so that routes can be inserted into href attributes
                Class<?> concreteClass = (Class<?>) fieldValue;
                if (concreteClass != null) {
                    if (Route.class.isAssignableFrom(concreteClass)) {
                        // URI routes can be inserted into URI attributes by defining a field in a DataModel
                        // like: "public Class<? extends RestHandler> myUrl = MyURLHandler.class;"
                        // then including a parameter in HTML like: "<a href='${myUrl}'>Click here</a>"
                        //
                        // Put URI for RestHandler into buf -- this is not escaped, since the RestHandler URIs
                        // should all be valid without escaping (they are either safely derived from the class
                        // name, or from the RouteOverride annotation, which is checked for validity)
                        @SuppressWarnings("unchecked")
                        Class<? extends Route> routeClass = (Class<? extends Route>) concreteClass;
                        String uriForClass = GribbitServer.siteResources.routeForClass(routeClass).getRoutePath();
                        buf.append(uriForClass);

                    } else {
                        // Due to type erasure, can't check until runtime if the right class type is passed in.
                        throw new RuntimeException("Got template parameter of type Class<" + concreteClass.getName()
                                + ">, but should be of type Class<? extends " + Route.class.getName() + ">");
                    }
                }

            } else {
                // For all other non-builtin types, call the toString() method and then HTML-escape the result.
                // But first check if the method has defined its own toString() method.
                try {
                    if (fieldType.getMethod("toString").getDeclaringClass() != Object.class) {
                        String unsafeStr = fieldValue.toString();
                        encodeParamText(isAttrVal, isURLAttr, unsafeStr, buf);
                    } else {
                        throw new RuntimeException("The class " + fieldType.getName()
                                + " does not override Object.toString(), and is not a subclass of "
                                + DataModel.class.getName()
                                + ", so it cannot be rendered into an HTML template as text or HTML respectively");
                    }
                } catch (NoSuchMethodException | SecurityException e) {
                    // Shouldn't happen, all classes have a public toString() method
                    throw new RuntimeException(e);
                }
            }
        }
        return wasIndented;
    }

    /**
     * Append a text part to the buffer. If prettyPrint is true, and the buffer already ends in a space, and the text
     * part starts in a space, skip initial spaces in the text part so as not to create a run of spaces, which can throw
     * off indenting if the text is at the beginning of an indented line.
     */
    private void encodeForHTMLNormalizingInitialSpace(CharSequence textPart, boolean prettyPrint, StringBuilder buf) {
        if (prettyPrint && (buf.length() == 0 || buf.charAt(buf.length() - 1) == ' ') && textPart.length() > 0
                && textPart.charAt(0) == ' ') {
            boolean hasNonSpace = false;
            for (int i = 0, n = textPart.length(); i < n; i++) {
                char c = textPart.charAt(i);
                if (c != ' ') {
                    hasNonSpace = true;
                    textPart = textPart.subSequence(i, textPart.length());
                    break;
                }
            }
            if (!hasNonSpace) {
                textPart = "";
            }
        }
        // Encode and insert string into the buffer 
        WebUtils.encodeForHTML(textPart, buf);
    }

    /**
     * Substitute params from this DataModel object into the text, performing proper HTML escaping as needed.
     */
    private boolean substituteTemplateParamsAndEscapeText(String tagName, String attrName, String textWithParams,
            boolean prettyPrint, int indentLevel, StringBuilder buf) {
        boolean wasIndented = false;
        boolean isAttrVal = attrName != null;
        boolean isURLAttr = isAttrVal && WebUtils.isURLAttr(tagName, attrName);

        Matcher matcher = TemplateLoader.TEMPLATE_PARAM_PATTERN.matcher(textWithParams);
        int prevMatchIdx = 0;
        int bufLenOnEntry = buf.length();
        while (matcher.find()) {
            // Append content before the match to the buffer
            CharSequence beforeMatch = textWithParams.subSequence(prevMatchIdx, matcher.start());
            if (isAttrVal) {
                WebUtils.encodeForHTMLAttribute(beforeMatch, buf);
            } else {
                encodeForHTMLNormalizingInitialSpace(beforeMatch, prettyPrint, buf);
            }
            prevMatchIdx = matcher.end();

            // Render the content of the Template field with the same name as the HTML parameter into
            // String format, then HTML-escape it unless it's already HTML (i.e. unless the parameter
            // is a Template field inside a Template object). Null field values are ignored, i.e. they
            // result in a parameter substitution of "".  
            String paramName = matcher.group(1);
            Object fieldVal = null;
            try {
                Field field = this.getClass().getField(paramName);

                // DataModel fields annotated with @Private or @OnlyReceive and DBModel id fields
                // cannot be sent to the user, just ignore them
                if (!DataModelLoader.fieldIsPrivate(field, /* checkSendability = */true, /* checkReceivability = */
                        false)) {

                    // Turn primitive types into strings, they have their own getter methods
                    Class<?> fieldType = field.getType();
                    if (fieldType == Integer.TYPE) {
                        fieldVal = Integer.toString(field.getInt(this));
                    } else if (fieldType == Boolean.TYPE) {
                        fieldVal = Boolean.toString(field.getBoolean(this));
                    } else if (fieldType == Character.TYPE) {
                        fieldVal = field.getChar(this);
                    } else if (fieldType == Long.TYPE) {
                        fieldVal = Long.toString(field.getLong(this));
                    } else if (fieldType == Float.TYPE) {
                        fieldVal = Float.toString(field.getFloat(this));
                    } else if (fieldType == Double.TYPE) {
                        fieldVal = Double.toString(field.getDouble(this));
                    } else if (fieldType == Byte.TYPE) {
                        byte b = field.getByte(this);
                        fieldVal = "0x" + Integer.toString(b, 16);
                    } else if (fieldType == Short.TYPE) {
                        fieldVal = Short.toString(field.getShort(this));
                    } else {
                        // Render non-primitive type in recursivelyRender() according to its class
                        fieldVal = field.get(this);
                    }

                    // If this attribute contains a param that is bound to a field that has the IsURL annotation,
                    // then the whole attr val becomes URL-typed
                    if (field.isAnnotationPresent(IsURL.class)) {
                        isURLAttr = true;
                    }
                }
            } catch (NoSuchFieldException | SecurityException | IllegalAccessException e1) {
                // Should not happen, NoSuchFieldException and SecurityException were checked for when
                // templates were loaded 
                throw new RuntimeException(e1);
            }

            // Recursively render content into params contained within text nodes
            if (fieldVal != null) {
                // Escape and render a field value (recursively rendering any lists or arrays) 
                wasIndented |= recursivelyRender(isAttrVal, isURLAttr, fieldVal, prettyPrint, indentLevel, buf);
            }
        }
        // Append last unmatched text
        CharSequence afterLastMatch = textWithParams.subSequence(prevMatchIdx, textWithParams.length());
        if (isAttrVal) {
            WebUtils.encodeForHTMLAttribute(afterLastMatch, buf);
        } else {
            encodeForHTMLNormalizingInitialSpace(afterLastMatch, prettyPrint, buf);
        }

        // Check validity of entirety of text value (template text with substituted param values)
        // for URL attributes
        CharSequence escapedTextWithSubstitutedParams = buf.subSequence(bufLenOnEntry, buf.length());
        if (escapedTextWithSubstitutedParams.length() > 0) {
            if (isURLAttr) {
                // This is a URI attribute -- check URL contains only valid characters
                // (URIs must already be escaped)
                String uriStr = escapedTextWithSubstitutedParams.toString();

                // OWASP Rule #5:
                //     URL Escape Before Inserting Untrusted Data into HTML URL Parameter Values.
                //     See: http://goo.gl/PTX0ul
                //
                // We leave it up to the user to escape their URIs, so that we avoid double-escaping.
                // StringUtils.encodeURI() can be used for this purpose. Instead, we run the URI through
                // the Java URI parser class to validate the URI.
                URI uri = WebUtils.parseURI(uriStr);
                if (uri == null) {
                    throw new RuntimeException("String substituted into URI attr \"" + attrName
                            + "\" is not a valid URI: " + uriStr);
                }

                String replacementURI = null;
                String scheme = uri.getScheme();
                if (scheme != null) {
                    scheme = scheme.toLowerCase();
                    // Specifically blacklist a few protocols.
                    // 
                    // OWASP Rule #5:
                    // "Including untrusted data in data: URLs should not be allowed as there is no good way
                    // to disable attacks with escaping to prevent switching out of the URL. [...]
                    // If untrusted input is meant to be placed into href, src or other URL-based
                    // attributes, it should be validated to make sure it does not point to an unexpected
                    // protocol, especially Javascript links."
                    // See also: http://goo.gl/UcO36V
                    if (scheme.equals("javascript") || scheme.equals("data") || scheme.equals("mhtml")
                            || scheme.equals("file")) {
                        throw new RuntimeException("URI " + uriStr + " uses an unsafe protocol");

                    } else if (scheme.equals("mailto") || scheme.equals("tel")) {
                        // OWASP Rule #6:
                        //     See http://goo.gl/cqealh
                        if (!(tagName.equals("a") && isAttrVal && attrName.equals("href"))) {
                            throw new RuntimeException("URL " + uriStr + " should be used in an a.href attribute");
                        }

                    } else if (!(scheme.equals("http") || scheme.equals("https"))) {
                        // If it's not http: or https:, it's probably unsafe
                        throw new RuntimeException("URI " + uriStr + " uses an unsupported URL protocol");
                    }

                } else if (uri.getHost() == null && uri.getPort() == -1) {
                    // For local URIs, see if there is an MD5-hashed version of the URI, and if so,
                    // replace the URI with the hashed version.
                    // TODO: extend URI-rewriting to CSS image resources 
                    replacementURI = CacheExtension.getHashURI(uriStr);
                }

                // Replace the URI that was rendered into the buffer with the hashed version, if there is a hashed
                // version of the resource
                if (replacementURI != null) {
                    buf.setLength(bufLenOnEntry);
                    buf.append(replacementURI);
                }

                // SVG script injection attacks: See http://goo.gl/cx16TR
                //
                // Allowing URIs that serve SVG content to be substituted into a URI attribute of
                // a template could be an attack vector. However, there is  no way to know for sure
                // what content type is being served by a URI without the server fetching content from
                // the URI itself, which is prohibitive (and then there would still be no guarantee 
                // that the user wouldn't be served different content than the web server sees).
                // We could impose the same-origin policy for SVG, but then we couldn't supply custom
                // URI parameters for images and link hrefs. We will rely on the expectation that
                // browsers are not supposed to run JS code for SVG files that are linked to in the
                // src attribute of an image tag, SVG is (hopefully) only unsafe when linked from an
                // <object> element or similar context where JS is allowed to run. (We don't allow
                // template parameters inside an object or applet element, so we should be safe.)

                // Log.warning("Using parameter in URI attribute \"" + attrName + 
                // "\" is unsafe due to the possibility for SVG script injection: http://goo.gl/cx16TR");

            } else if (isAttrVal && (attrName.equals("id") || attrName.equals("name") || attrName.equals("class"))) {
                // OWASP Rule #1:
                //     Strictly validate unsafe attributes such as background, id and name.
                // See also http://goo.gl/fKZhFA -- we disallow '.' and ':' because they can cause
                // problems with jQuery.

                if (!(attrName.equals("class") ? WebUtils.VALID_CSS_ID : WebUtils.VALID_HTML_NAME_OR_ID).matcher(
                        escapedTextWithSubstitutedParams).matches()) {
                    throw new RuntimeException("Bad characters in attribute value: " + attrName + "=\""
                            + escapedTextWithSubstitutedParams + "\"");
                }
                if (StringUtils.containsUppercaseChar(escapedTextWithSubstitutedParams)) {
                    throw new RuntimeException("There are uppercase characters in attribute " + attrName + "=\""
                            + escapedTextWithSubstitutedParams
                            + "\", but browsers implement case sensitivity differently. "
                            + "Identifiers should all be lowercase.");
                }
            }
        }
        return wasIndented;
    }

    /** Append an attribute name and escaped value to the inside of a tag. */
    private void renderAttrKeyValue(String tagName, String attrName, String attrVal, boolean prettyPrint,
            int indentLevel, StringBuilder buf) {
        buf.append(' ');
        buf.append(attrName);
        if (!attrVal.isEmpty()) {
            buf.append("=\"");
            substituteTemplateParamsAndEscapeText(tagName, attrName, attrVal, prettyPrint, indentLevel, buf);
            buf.append('"');
        }
    }

    /**
     * Append a string representation of the attributes of an element to the buffer.
     * 
     * Sets initial values of inputs in a form to the corresponding values in the passed DataModel, if the DataModel and
     * its field with the same name as the "name" attribute of a form input are both non-null.
     */
    private void renderAttrs(Element e, Element enclosingForm, DataModel formModel, String selectName,
            boolean prettyPrint, int indentLevel, StringBuilder buf) {

        // Replace the attribute values in the template with overridden values if this is an 'input'
        // or 'option' element in a form, and if a non-null formModel has been provided to pre-populate
        // these values.
        HashMap<String, String> replaceAttrVal = null;
        ArrayList<String> removeAttrName = null;
        String tagName = e.tagName();
        if (formModel != null && (tagName.equals("input") || tagName.equals("option"))) {
            // If there is a DataModel associated with the form that this input element is part of,
            // then use the DataModel's fields to populate the default values of the form inputs
            // (used to pre-fill-in the values in a form)

            // Inside a <select> tag, use the name attr of the select tag for all <option> elements
            String name = selectName == null || selectName.isEmpty() ? e.attr("name") : selectName;

            Field formModelField = null;
            if (name != null && !name.isEmpty()) {
                try {
                    formModelField = formModel.getClass().getField(name);
                    // DataModel fields annotated with @Private or @OnlyReceive and DBModel id fields
                    // cannot be sent to the user
                    if (!DataModelLoader.fieldIsPrivate(formModelField, /* checkSendability = */true, /*
                                                                                                       * checkReceivability
                                                                                                       * =
                                                                                                       */false)) {
                        Object formModelFieldValue = formModelField.get(formModel);
                        if (formModelFieldValue != null) {
                            // The DataModel associated with this form is non-null, and the field in the
                            // data model with the same name as this input is non-null.

                            // HTML-escape the field
                            String type = e.attr("type");
                            if (type.isEmpty() && tagName.equals("option")) {
                                // Handle "option" inside a "select" element similarly to "radio"
                                type = "select";
                            }
                            switch (type) {
                            case "text":
                            case "hidden":
                            case "email":
                            case "tel":
                            case "number":
                            case "url":
                                if (replaceAttrVal == null) {
                                    replaceAttrVal = new HashMap<>();
                                }
                                replaceAttrVal.put("value", formModelFieldValue.toString());
                                break;

                            case "radio":
                            case "select":
                                String selectedAttr = type.equals("radio") ? "checked" : "selected";
                                if (e.attr("value").equals(formModelFieldValue.toString())) {
                                    if (replaceAttrVal == null) {
                                        replaceAttrVal = new HashMap<>();
                                    }
                                    replaceAttrVal.put(selectedAttr, "");
                                } else {
                                    if (removeAttrName == null) {
                                        removeAttrName = new ArrayList<>();
                                    }
                                    removeAttrName.add(selectedAttr);
                                }
                                break;

                            case "checkbox":
                                if (formModelFieldValue.toString().equals("true")) {
                                    if (replaceAttrVal == null) {
                                        replaceAttrVal = new HashMap<>();
                                    }
                                    replaceAttrVal.put("checked", "");
                                } else {
                                    if (removeAttrName == null) {
                                        removeAttrName = new ArrayList<>();
                                    }
                                    removeAttrName.add("checked");
                                }
                                break;

                            case "password":
                                // Intentionally ignore this -- should not fill in password field
                                // from a DataModel object
                                break;

                            case "date":
                            case "datetime":
                            case "datetime-local":
                            case "month":
                            case "time":
                            case "week":
                                // TODO: Not yet supported
                                break;

                            case "color":
                            case "range":
                                // TODO: Not yet supported
                                break;

                            case "submit":
                            case "reset":
                            case "search":
                            case "button":
                            case "image":
                            case "file":
                                // Should not have a field named "submit" (or any of these others)
                                // in a DataModel -- ignore
                                break;

                            default:
                            }
                        }
                    }
                } catch (Exception e1) {
                    // If there are inputs that can't be populated from named fields,
                    // don't try to set their default value
                }
            }
        }

        // For elements that are not input elements in a form backed by a DataModel, just append
        // the attributes and values. For input elements, substitute any values replaced above.
        for (Attribute attr : e.attributes()) {
            String attrName = attr.getKey();
            String attrVal = attr.getValue();
            if (removeAttrName == null || !removeAttrName.contains(attrName)) {
                if (replaceAttrVal != null) {
                    String replacement = replaceAttrVal.get(attrName);
                    if (replacement != null) {
                        attrVal = replacement;
                        replaceAttrVal.remove(attrName);
                    }
                }
                renderAttrKeyValue(tagName, attrName, attrVal, prettyPrint, indentLevel, buf);
            }
        }

        // Append any attrs that were added for input forms that didn't replace a value already
        // in the input element
        if (replaceAttrVal != null) {
            for (Entry<String, String> ent : replaceAttrVal.entrySet()) {
                renderAttrKeyValue(tagName, ent.getKey(), ent.getValue(), prettyPrint, indentLevel, buf);
            }
        }
    }

    /**
     * Recursively traverse the DOM of a template, rendering each node into HTML. Returns true if the node was indented
     * (which happens when prettyPrint == true and the node or one of its children is a block element).
     */
    private boolean renderDOMNode(Node node, Element enclosingForm, DataModel formModel, String selectName,
            boolean prettyPrint, boolean normalizeTextSpacing, int indentLevel, StringBuilder buf) {
        boolean nodeWasIndented = false;

        if (node instanceof Element) {
            Element e = (Element) node;
            String tagName = e.tagName();

            if (tagName.equals("form")) {
                // For form elements, match form id against field names of this DataModel to see if
                // there's a match
                String formId = e.attr("id");
                if (!formId.isEmpty()) {
                    try {
                        Field formField = this.getClass().getField(formId);
                        DataModel thisFormModel = (DataModel) formField.get(this);

                        // Overwrite current formValue for child nodes (in case of nested forms)
                        enclosingForm = e;
                        formModel = thisFormModel;

                    } catch (Exception e1) {
                        // This form doesn't match a field with the same name as the form id -- don't do
                        // any special processing of this form
                    }
                }
            } else if (tagName.equals("select")) {
                // For <select> elements, use name of select to identify <option> elements inside select 
                selectName = e.attr("name");
            }

            // Only indent block elements, inline elements should not be indented because it would introduce
            // extra whitespace, potentially in the wrong place
            boolean isBlockElement =
                    !WebUtils.INLINE_ELEMENTS.contains(tagName)
                            && !GribbitServer.siteResources.getCustomInlineElements().contains(tagName);

            // Render the open tag for this element
            if (prettyPrint && isBlockElement) {
                StringUtils.indent(indentLevel, buf);
                nodeWasIndented = true;
            }
            buf.append('<');
            buf.append(tagName);
            renderAttrs(e, enclosingForm, formModel, selectName, prettyPrint, indentLevel, buf);
            buf.append('>');
            if (prettyPrint && (tagName.equals("head") || tagName.equals("body"))) {
                // Always indent after body and head elements
                StringUtils.indent(indentLevel + 1, buf);
            }

            // Switch off prettyprinting and text spacing normalization inside the pre element
            if (tagName.equals("pre")) {
                // FIXME: See http://www.impressivewebs.com/css-white-space/ too, you can also specify
                // whitespace significance in CSS
                prettyPrint = false;
                normalizeTextSpacing = false;
            }

            // Don't render contents or close tag for void elements
            if (!WebUtils.VOID_ELEMENTS.contains(tagName)) {
                // Recursively render child nodes of this element
                boolean hasIndentedChild = false;
                for (Node child : e.childNodes()) {
                    hasIndentedChild |=
                            renderDOMNode(child, enclosingForm, formModel, selectName, prettyPrint,
                                    normalizeTextSpacing, indentLevel + 1, buf);
                }
                nodeWasIndented |= hasIndentedChild;

                // Indent close tag on its own separate line if anything after start tag was prettyprinted,
                // otherwise render close tag on same line since content was all inline (or there were no 
                // child nodes) 
                if (prettyPrint && hasIndentedChild) {
                    StringUtils.indent(indentLevel, buf);
                }
                // Render close tag for this element
                buf.append("</");
                buf.append(tagName);
                buf.append('>');
            }

        } else if (node instanceof TextNode) {

            String nodeText = ((TextNode) node).getWholeText();
            if (normalizeTextSpacing) {
                // We never indent text nodes (they are inline), but unless we're in a <pre> element, if
                // prettyprinting is turned on, we at least normalize the spacing
                nodeText = StringUtils.normalizeSpacing(nodeText);
            }
            // OWASP Rule #1:
            //     HTML Escape Before Inserting Untrusted Data into HTML Element Content.
            nodeWasIndented |=
                    substituteTemplateParamsAndEscapeText(null, null, nodeText, prettyPrint, indentLevel, buf);

        } else if (node instanceof DataNode) {

            // FIXME: Probably need to properly escape DataNode contents (differently for each of style 
            // and script elements?)
            DataNode dataNode = (DataNode) node;
            String data = dataNode.getWholeData();

            if (prettyPrint) {
                // If prettyprinting, indent each line separately at the current indent level
                for (CharSequence line : StringUtils.splitAsList(data, "\n")) {
                    StringUtils.append(line, indentLevel, buf);
                }
            } else {
                // If not prettyprinting, insert whole data node text
                buf.append(data);
            }
            nodeWasIndented = true;

        } else if (node instanceof DocumentType || node instanceof XmlDeclaration) {

            buf.append(node.toString());
            nodeWasIndented = true;

        } else if (node instanceof Comment) {

            // Only add comments to output if we're prettyprinting, unless comment is an IE conditional
            String contents = ((Comment) node).getData();
            if (prettyPrint || contents.startsWith("[if ") || contents.startsWith(" [if ")) {
                StringUtils.append("<!-- ", indentLevel, buf);
                buf.append(contents);
                buf.append(" -->");
                nodeWasIndented = true;
            }

        } else {
            throw new RuntimeException("Unknown node type: " + node.getClass().getName());
        }
        return nodeWasIndented;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Get the template document associated with this DataModel, or null if there is no associated template.
     */
    private List<Node> getAssociatedTemplateDoc() {
        return GribbitServer.siteResources.getTemplateDocForClass(this.getClass());
    }

    /** Render a template recursively (i.e. substitute templates inside of templates, if present). */
    private boolean renderTemplate(List<Node> nodes, boolean prettyPrint, int indentLevel, StringBuilder buf) {
        boolean wasIndented = false;
        // Render template DOM nodes (they are children of doc body element)
        for (Node node : nodes) {
            wasIndented |= renderDOMNode(node, null, null, null, prettyPrint, true, indentLevel, buf);
        }
        return wasIndented;
    }

    /** Render a template recursively (i.e. substitute templates inside of templates, if present). */
    private boolean renderTemplate(boolean prettyPrint, int indentLevel, StringBuilder buf) {
        List<Node> templateNodes = getAssociatedTemplateDoc();
        if (templateNodes == null) {
            throw new RuntimeException("Could not find an HTML template named \"" + this.getClass().getSimpleName()
                    + "\" to render model " + this.getClass().getName());
        }
        return renderTemplate(templateNodes, prettyPrint, indentLevel, buf);
    }

    /** Render a template recursively (i.e. substitute templates inside of templates, if present). */
    public String renderTemplate(boolean prettyPrint) {
        StringBuilder buf = new StringBuilder(8192);
        renderTemplate(prettyPrint, 0, buf);
        return buf.toString();
    }

    /**
     * Render a template (or list or array of templates) recursively.
     */
    public static String renderTemplate(Object content, boolean prettyPrint) {
        if (content == null) {
            return "";
        } else {
            StringBuilder buf = new StringBuilder(8192);
            if (content instanceof DataModel) {
                ((DataModel) content).renderTemplate(prettyPrint, 0, buf);
            } else if (content instanceof List) {
                for (Object o : (List<?>) content) {
                    if (o instanceof DataModel) {
                        ((DataModel) o).renderTemplate(prettyPrint, 0, buf);
                    } else {
                        throw new IllegalArgumentException("Content has type List<" + o.getClass().getName()
                                + ">, needs to be type List<? extends " + DataModel.class.getName() + ">");
                    }
                }
            } else if (content.getClass().isArray()) {
                int n = Array.getLength(content);
                if (n > 0) {
                    for (int i = 0; i < n; i++) {
                        Object o = Array.get(content, i);
                        if (o instanceof DataModel) {
                            ((DataModel) o).renderTemplate(prettyPrint, 0, buf);
                        } else {
                            throw new IllegalArgumentException("Content has type " + o.getClass().getName()
                                    + "[], needs to be type " + DataModel.class.getName() + "[]");
                        }
                    }
                }
            } else {
                throw new IllegalArgumentException("Content must be of type " + DataModel.class.getName() + ", "
                        + DataModel.class.getName() + "[], or List<? extends " + DataModel.class.getName() + ">");
            }
            return buf.toString();
        }
    }

    /**
     * Render a DataModel object by rendering the associated template, substituting the values in the DataModel, if
     * there is an associated template, otherwise rendering as a JSON string. This is marked as final so that subclasses
     * can't generate unsafe HTML by overriding this method.
     */
    @Override
    public final String toString() {
        // See if there's a template associated with this DataModel 
        List<Node> doc = getAssociatedTemplateDoc();
        if (doc != null) {
            // Render the associated template, if there is one
            StringBuilder buf = new StringBuilder(8192);
            renderTemplate(doc, GribbitProperties.PRETTY_PRINT_HTML, 0, buf);
            return buf.toString();

        } else {
            // If there's no associated template, render DataModel as JSON instead
            return this.toJSON(GribbitProperties.PRETTY_PRINT_JSON);
        }
    }

}
