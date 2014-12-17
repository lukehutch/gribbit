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
import gribbit.model.field.annotation.DBIndex;
import gribbit.model.field.annotation.Email;
import gribbit.model.field.annotation.MaxIntegerValue;
import gribbit.model.field.annotation.MaxLength;
import gribbit.model.field.annotation.MinIntegerValue;
import gribbit.model.field.annotation.MinLength;
import gribbit.model.field.annotation.NoTrim;
import gribbit.model.field.annotation.NormalizeSpacing;
import gribbit.model.field.annotation.Regex;
import gribbit.model.field.annotation.Required;
import gribbit.model.field.visibility.annotation.OnlyReceive;
import gribbit.model.field.visibility.annotation.OnlySend;
import gribbit.model.field.visibility.annotation.Private;
import gribbit.server.GribbitServer;
import gribbit.server.Request;
import gribbit.server.RestHandler;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.Database;
import gribbit.server.siteresources.SiteResources;
import gribbit.server.siteresources.TemplateLoader;
import gribbit.util.AppException;
import gribbit.util.Log;
import gribbit.util.StringUtils;
import gribbit.util.WebUtils;
import io.netty.handler.codec.http.multipart.FileUpload;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.nodes.XmlDeclaration;
import org.jsoup.select.Elements;

/**
 * A model that can be bound from the parameters in a POST request, and/or that can be used to populate an HTML template.
 * 
 * You can apply data constraint annotations to the fields of subclasses of DataModel, such @MaxLength or @Email, and these constraints will be applied when submitted form data is
 * bound to the model.
 */
public abstract class DataModel {

    /**
     * Statically check types of fields of against their data constraint annotations.
     * 
     * @throws RuntimeException
     *             if constraints don't match field types.
     */
    public static void checkFieldTypesAgainstAnnotations(Class<? extends DataModel> klass) throws RuntimeException {
        for (Field field : klass.getFields()) {
            Annotation[] fieldAnnotations = field.getAnnotations();
            Class<?> fieldType = field.getType();
            String fieldName = field.getName();

            // If field has a @Private annotation, then it can't have other constraint annotations
            boolean hasConstraintAnnotations = false;
            for (Annotation annotation : fieldAnnotations) {
                Class<? extends Annotation> type = annotation.annotationType();
                if (type == Email.class || type == MaxIntegerValue.class || type == MaxLength.class || type == MinIntegerValue.class || type == MinLength.class
                        || type == NormalizeSpacing.class || type == NoTrim.class || type == Regex.class || type == Required.class) {
                    hasConstraintAnnotations = true;
                }
            }
            boolean isPrivate = fieldIsPrivate(klass, field);
            if (isPrivate && hasConstraintAnnotations) {
                // Private fields cannot be bound from forms, so shouldn't have data constraint annotations, because data constraints are only checked 
                // when binding from a POST request.
                throw new RuntimeException("Field " + fieldName + " in class " + klass.getName() + " is annotated with @" + Private.class.getSimpleName()
                        + " or is a DBModel id field, so it cannot have other data constraint annotations, because it cannot be bound from a POST request");
            }

            // Ignore fields annotated with @Private
            if (!isPrivate) {
                // Go through constraint annotations on this field
                for (Annotation annotation : fieldAnnotations) {
                    Class<? extends Annotation> annotationType = annotation.annotationType();
                    if ((annotationType == Email.class || annotationType == MinLength.class || annotationType == MaxLength.class || annotationType == Regex.class)
                            && fieldType != String.class) {
                        throw new RuntimeException("Field " + fieldName + " in class " + klass.getName() + " must be of type String");

                    } else if ((annotationType == MinIntegerValue.class || annotationType == MaxIntegerValue.class) && !(fieldType == Integer.class || fieldType == Integer.TYPE)) {
                        throw new RuntimeException("Field " + fieldName + " in class " + klass.getName() + " must be of type int or Integer");

                    }

                    if (annotationType == Regex.class) {
                        try {
                            Pattern.compile(((Regex) annotation).regex());
                        } catch (PatternSyntaxException e) {
                            throw new RuntimeException("@" + annotation.annotationType().getName() + "(\"" + ((Regex) annotation).regex() + "\") annotation on field " + fieldName
                                    + " in class " + klass.getName() + " is not a valid regular expression");
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------

    private static boolean isRequired(Field f) {
        return f.getAnnotation(Required.class) != null || f.getAnnotation(DBIndex.class) != null || f.getAnnotation(MinLength.class) != null
                || f.getAnnotation(MaxLength.class) != null || f.getAnnotation(MinIntegerValue.class) != null || f.getAnnotation(MaxIntegerValue.class) != null;
    }

    /**
     * Check the values of fields against the constraint annotations.
     *
     * Side-effects: forces fields marked with Email annotation to lowercase; trims String fields not marked with NoTrim; normalizes spacing in fields marked with NormalizeSpacing.
     * 
     * @throws RuntimeException
     *             if one or more constraints are violated.
     */
    protected void checkFieldValuesAgainstConstraints() throws RuntimeException {
        try {
            for (Field field : getClass().getFields()) {
                // Skip fields marked with @Private and DBModel id fields, they can't have constraint annotations
                if (!fieldIsPrivate(getClass(), field)) {
                    Annotation[] fieldAnnotations = field.getAnnotations();
                    Object fieldVal;
                    fieldVal = field.get(this);
                    String fieldName = field.getName();

                    boolean required = isRequired(field);
                    if (required && (fieldVal == null || (fieldVal instanceof String && ((String) fieldVal).isEmpty()))) {
                        throw new RuntimeException("Required field " + fieldName + " is null or empty");
                    }

                    boolean needToTrim = true;
                    boolean normalizeSpacing = false;
                    boolean isEmail = false;
                    for (Annotation annotation : fieldAnnotations) {
                        Class<? extends Annotation> type = annotation.annotationType();
                        if (type == NoTrim.class) {
                            needToTrim = false;
                        } else if (type == NormalizeSpacing.class) {
                            normalizeSpacing = true;
                        } else if (type == Email.class) {
                            isEmail = false;
                        }
                    }

                    // Modify non-null String fields as needed to conform with constraints.
                    if (fieldVal != null && fieldVal instanceof String) {
                        String val = (String) fieldVal;
                        if (isEmail) {
                            // Force email to lowercase
                            val = val.toLowerCase();
                        }
                        if (needToTrim || isEmail) {
                            // Trim all String fields by default, unless marked with @NoTrim, and always trim @Email fields
                            val = StringUtils.unicodeTrim(val);
                        }
                        if (normalizeSpacing) {
                            // Normalize spacing if needed
                            val = StringUtils.normalizeSpacing(val);
                        }
                        if (!val.equals(fieldVal)) {
                            // Set field to modified string val if it has changed 
                            field.set(this, val);
                        }
                    }

                    // Go through constraint annotations on this field
                    for (Annotation annotation : fieldAnnotations) {

                        Class<? extends Annotation> annotationType = annotation.annotationType();

                        // N.B. we trimmed and normalized spaces above before checking against @MinLength constraint value.
                        if (annotationType == MinLength.class && fieldVal instanceof String && ((String) fieldVal).length() < ((MinLength) annotation).value()) {
                            throw new RuntimeException("Parameter " + fieldName + " must have minimum length " + (needToTrim ? "(after trimming) " : "") + "of "
                                    + ((MinLength) annotation).value());

                        } else if (annotationType == MaxLength.class && fieldVal instanceof String && ((String) fieldVal).length() > ((MaxLength) annotation).value()) {
                            throw new RuntimeException("Parameter " + fieldName + " must have maximum length " + (needToTrim ? "(after trimming) " : "") + "of "
                                    + ((MaxLength) annotation).value());

                        } else if (annotationType == MinIntegerValue.class && //
                                (fieldVal instanceof Integer && ((Integer) fieldVal).intValue() < ((MinIntegerValue) annotation).value())) {
                            throw new RuntimeException("Parameter " + fieldName + " must have minimum value " + ((MinIntegerValue) annotation).value());

                        } else if (annotationType == MaxIntegerValue.class && //
                                (fieldVal instanceof Integer && ((Integer) fieldVal).intValue() > ((MaxIntegerValue) annotation).value())) {
                            throw new RuntimeException("Parameter " + fieldName + " must have maximum value " + ((MinIntegerValue) annotation).value());

                        } else if (annotationType == Email.class && fieldVal instanceof String) {
                            if (fieldVal != null && fieldVal instanceof String && !WebUtils.isValidEmailAddr((String) fieldVal)) {
                                throw new RuntimeException("Parameter " + fieldName + " must be a valid email address");
                            }

                        } else if (annotationType == Regex.class && fieldVal instanceof String) {
                            try {
                                // TODO: should pre-compile these regexes and cache them somewhere
                                if (fieldVal != null || !Pattern.compile(((Regex) annotation).regex()).matcher((String) fieldVal).matches()) {
                                    // Value submitted in form does not match regex
                                    throw new RuntimeException("Parameter " + fieldName + " is not in the correct format");
                                }
                            } catch (PatternSyntaxException e) {
                                // Should never happen, we have already successfully compiled this pattern in checkFields()
                                throw new RuntimeException(e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Bind formModelInstance data object from an HTTP request. The name of each field in formModelInstance object is looked up in the POST parameters. If the named value is found,
     * it is trimmed (unless annotated with NoTrim), and integers etc. are parsed as needed before the value is stored in the field. Fields marked with Required constraint have to
     * not only not be null, but also not be empty after trimming. Note that any constraint such as MinLength also implies Required. (NormalizeSpacing is a data transformation
     * annotation, not a constraint, so it will only be applied if a value is provided.)
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

            if (fieldIsPrivate(getClass(), field) || field.getAnnotation(OnlySend.class) != null) {
                // Ignore attempts to set fields annotated with @Private or @OnlySend, or where the field is a DBModel id field
                //                Log.warning("Attempt to bind POST param \"" + field.getName() + "\" to field marked @" + Private.class.getName() + " or @" + OnlySend.class.getName()
                //                        + " (or an id field of a subclass of " + DBModel.class.getName() + ") in class " + this.getClass().getName() + "; ignoring");

            } else {
                Class<?> fieldType = field.getType();

                try {
                    // For each field in class, look up field name in POST parameters and then URL query parameters
                    String postParamValForField = req.getPostParam(fieldName);
                    if (postParamValForField == null) {

                        FileUpload postFileUploadForField = req.getPostFileUploadParam(fieldName);
                        if (postFileUploadForField != null) {

                            // There was a file uploaded
                            if (fieldType == FileUpload.class) {
                                // Save the FileUpload object in the field. This doesn't actually read the file contents
                                // (loading the file into RAM could kill the server). The request handler must read the file
                                // before responding, or increment the reference count if they want to keep the file after
                                // the request has been sent, because the file will otherwise be released.
                                field.set(this, postFileUploadForField);
                            }

                        } else {

                            // There is a field in formModelInstance DataModel that is not in the POST request
                            if (isRequired(field)) {
                                throw new AppException("Field " + fieldName + " required, but not sent in POST request");
                            }
                        }

                    } else {
                        // Try binding POST request param to correspondingly-named field in formModelInstance DataModel

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
                            boolean checkboxVal = lower.equals("yes") || lower.equals("on") || lower.equals("1") || lower.equals("true");
                            if (fieldType == Boolean.class) {
                                field.set(this, new Boolean(checkboxVal));
                            } else if (fieldType == Boolean.TYPE) {
                                field.setBoolean(this, checkboxVal);
                            }

                        } else if (fieldType == Character.class || fieldType == Character.TYPE) {
                            // Character fields are bound from text, but limited to a length of 1

                            if (postParamValForField.length() > 1) {
                                throw new AppException("Field " + fieldName + " requires a single character, got " + postParamValForField.length() + " characters");
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

                            // If the field is an enum, try looking up the submitted form value as one of the enum constants.
                            try {
                                @SuppressWarnings({ "unchecked", "rawtypes" })
                                Enum<?> enumVal = Enum.valueOf((Class<Enum>) fieldType, postParamValForField);
                                field.set(this, enumVal);
                            } catch (IllegalArgumentException e) {
                                throw new AppException("Illegal value " + postParamValForField + " for field " + fieldName);
                            }

                        } else {
                            throw new RuntimeException("Unsupported field type " + fieldType.getSimpleName());
                        }
                    }

                } catch (NumberFormatException | DateTimeParseException e) {
                    throw new AppException("Could not parse value " + fieldName + " from the request", e);

                } catch (IllegalArgumentException | IllegalAccessException e) {
                    throw new RuntimeException("Could not set field " + fieldName + " to the value passed in the request", e);
                }
            }
        }

        // Check that the values in the fields satisfy the constraints
        try {
            checkFieldValuesAgainstConstraints();
        } catch (Exception e) {
            throw new AppException("Form values invalid: " + e.getMessage());
        }

        for (String unusedParam : unusedPostParams) {
            if (!unusedParam.equals(CSRF.CSRF_PARAM_NAME)) {
                Log.warning("POST param \"" + unusedParam + "\" is not a public field in DataModel " + this.getClass().getName() + ", ignoring");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Add constraints to an HTML form given the constraint annotations on the fields of the given DataModel object. Also checks the fields of the DataModel to make sure that there
     * is an input in the form with the appropriate name.
     */
    private static void addConstraintsToForm(String templateName, Element formElement, Class<? extends DataModel> formModel) {
        // Get all input elements in this form, grouped by matching name (radio buttons have multiple inputs with the same name)
        HashMap<String, ArrayList<Element>> nameToInputs = new HashMap<>();
        for (Element elt : formElement.getAllElements()) {
            String name;
            if (elt.tagName().equals("select")) {
                // Ignore name on <select> elements, instead read parent name when <option> elements are encountered
                name = "";
            } else if (elt.tagName().equals("option")) {
                // Option elements are named in their parent select element
                name = elt.parent().attr("name");
            } else {
                name = elt.attr("name");
            }
            if (!name.isEmpty() && !name.equals(CSRF.CSRF_PARAM_NAME)) {
                ArrayList<Element> inputs = nameToInputs.get(name);
                if (inputs == null)
                    nameToInputs.put(name, inputs = new ArrayList<>());
                inputs.add(elt);
            }
        }

        // Iterate through fields of the DataModel
        HashSet<String> unmappedInputNames = new HashSet<>(nameToInputs.keySet());
        for (Field field : formModel.getFields()) {
            String fieldName = field.getName();
            unmappedInputNames.remove(fieldName);

            if (!fieldIsPrivate(formModel, field) && field.getAnnotation(OnlySend.class) == null) {
                Class<?> fieldType = field.getType();
                boolean required = isRequired(field);

                // Check if there is an input element with a name attribute that matches the DataModel field name
                ArrayList<Element> eltsWithNameMatchingField = nameToInputs.get(field.getName());
                if (eltsWithNameMatchingField == null) {
                    // DataModel has an extra field that the form doesn't have
                    if (required) {
                        throw new RuntimeException("Template " + templateName + ".html contains a form with id=\"" + formElement.attr("id") + "\" that is bound to the model "
                                + formModel.getName() + ", but the model contains a required field named \"" + field.getName()
                                + "\", and there is no input with that name in the form");
                    } else {
                        // Warn about extra field in form model that is not in form template
                        Log.warning("Template " + templateName + ".html contains a form with id=\"" + formElement.attr("id") + "\" that is bound to the model "
                                + formModel.getName() + ", but the model contains an extra field named \"" + field.getName() + "\" that is not in the form");
                    }

                } else {
                    // Go through all input elements that have the same name as this DataModel field, and update the element to have constraint attributes matching the DataModel constraint annotations
                    for (Element matchingElt : eltsWithNameMatchingField) {
                        String matchingEltTagName = matchingElt.tagName();
                        String matchingEltType = matchingElt.attr("type");

                        if (matchingEltTagName.equals("option")) {
                            // This is an <option> inside a <select> inside a <form>
                            matchingEltType = "select";
                            if (required) {
                                // Set "required" attr on the <select>, not the <option>
                                matchingElt.parent().attr("required", "");
                            }
                        } else {
                            // Everything else should be an <input> inside a <form>
                            if (required) {
                                matchingElt.attr("required", "");
                            }
                        }

                        // Get data constraint annotations for DataModel field
                        boolean needsTrimming = true;
                        boolean needsSpaceNormalization = false;
                        boolean isEmail = false;
                        Integer minLength = null, maxLength = null, min = null, max = null;
                        for (Annotation annotation : field.getAnnotations()) {
                            Class<? extends Annotation> annotationType = annotation.annotationType();
                            if (annotationType == MinLength.class) {
                                minLength = ((MinLength) annotation).value();
                            } else if (annotationType == MaxLength.class) {
                                maxLength = ((MaxLength) annotation).value();
                            } else if (annotationType == MinIntegerValue.class) {
                                min = ((MinIntegerValue) annotation).value();
                            } else if (annotationType == MaxIntegerValue.class) {
                                max = ((MaxIntegerValue) annotation).value();
                            } else if (annotationType == Email.class) {
                                isEmail = true;
                            } else if (annotationType == Regex.class) {
                                //TODO: make sure that Java regexes are compatible with JS regexes. See also http://html5pattern.com/
                                matchingElt.attr("pattern", ((Regex) annotation).regex());
                            } else if (annotationType == NormalizeSpacing.class) {
                                needsSpaceNormalization = true;
                            } else if (annotationType == NoTrim.class) {
                                needsTrimming = false;
                            }
                        }

                        // Add type constraints to form inputs based on the Java type of fields in the DataModel
                        if (fieldType == LocalDate.class) {
                            matchingElt.attr("type", "date");
                            // TODO: also add regex pattern to constrain date to the isoDate format? (This doesn't seem to work currently.)
                            // TODO: add date picker popup.

                        } else if (fieldType == Integer.class || fieldType == Integer.TYPE || fieldType == Long.class || fieldType == Long.TYPE || fieldType == Short.class
                                || fieldType == Short.TYPE || fieldType == Float.class || fieldType == Float.TYPE || fieldType == Double.class || fieldType == Double.TYPE) {
                            // TODO: does "type='number'" work for float/double?
                            matchingElt.attr("type", "number");

                            if (min != null) {
                                matchingElt.attr("min", "" + min);
                            }
                            if (max != null) {
                                matchingElt.attr("max", "" + max);
                            }

                        } else if (fieldType == Boolean.class || fieldType == Boolean.TYPE) {
                            // Boolean fields are bound to checkboxes

                            if (!matchingEltType.isEmpty() && !matchingEltType.equals("checkbox"))
                                throw new RuntimeException("Field \"" + fieldName + "\" in form \"" + formElement.attr("id") + "\" in template " + templateName
                                        + ".html needs to be of type \"checkbox\", since it is bound to a Boolean field");

                            matchingElt.attr("type", "checkbox");

                        } else if (fieldType.isEnum()) {
                            // Enum-typed fields are bound to radio buttons

                            if (!matchingEltType.isEmpty() && !(matchingEltType.equals("radio") || matchingEltType.equals("select"))) {
                                throw new RuntimeException("Field \"" + fieldName + "\" in form \"" + formElement.attr("id") + "\" in template " + templateName
                                        + ".html needs to be of type \"radio\", since it is bound to an enum-typed field");
                            }

                            // Make sure all radio or option values map to a valid enum value
                            String radioVal = matchingElt.attr("value");
                            if (radioVal == null) {
                                throw new RuntimeException("Missing attribute \"value\" for field \"" + fieldName + "\" in form \"" + formElement.attr("id") + "\" of template "
                                        + templateName + ".html");
                            }
                            boolean enumOK = false;
                            try {
                                @SuppressWarnings({ "unchecked", "rawtypes", "unused" })
                                Enum<?> enumVal = Enum.valueOf((Class<Enum>) fieldType, radioVal);
                                enumOK = true;
                            } catch (IllegalArgumentException e) {
                            }
                            if (!enumOK) {
                                throw new RuntimeException("Illegal value \"" + radioVal + "\" for radio or option field \"" + fieldName + "\" in form \"" + formElement.attr("id")
                                        + "\" of template " + templateName + ".html");
                            }

                        } else if (fieldType == String.class) {
                            // Password fields get marked as type="password", everything else that is bound to a String gets marked as type="text"

                            if (fieldName.equals("password")) {
                                matchingElt.attr("type", "password");
                            } else {
                                if (isEmail) {
                                    matchingElt.attr("type", "email");
                                } else {
                                    matchingElt.attr("type", "text");
                                }

                                //FIXME: need to validate minlength and maxlength using JS, and reflect validity in Bootstrap. For all forms:
                                // -- load the following jQuery plugin if there are forms on the page: http://docs.jquery.com/Plugins/Validation
                                // -- add in head: $("#commentForm").validate();
                                if (minLength != null) {
                                    matchingElt.attr("minlength", "" + minLength);
                                }
                                if (maxLength != null) {
                                    matchingElt.attr("maxlength", "" + maxLength);
                                }

                                if (needsTrimming) {
                                    //TODO: Add JS that trims spacing in form fields upon focus loss unless @NoTrim annotation is present
                                }
                                if (needsSpaceNormalization) {
                                    //TODO: Add JS that auto-normalizes spacing in form fields if @NormalizeSpacing annotation is present
                                }
                            }

                        } else if (fieldType == Character.class || fieldType == Character.TYPE) {
                            matchingElt.attr("type", "text").attr("maxlength", "1");

                        } else if (fieldType == FileUpload.class) {
                            // Force form type to multipart/form-data if one of the DataModel fields is of type FileUpload
                            matchingElt.attr("type", "file");
                            formElement.attr("enctype", "multipart/form-data");

                        } else {
                            throw new RuntimeException("Illegal type " + fieldType.getName() + " for field " + fieldName + " in " + DataModel.class.getSimpleName() + " subclass");
                        }
                    }
                }
            }
        }

        for (String name : unmappedInputNames) {
            Log.warning("Template " + templateName + ".html but has an extra input element named \"" + name + "\" that is not present as a public field of " + formModel.getName());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Fields annotated with @Private in a DataModel instance, or id fields of a DBModel instance, cannot be rendered into a template, served as JSON or bound from a POST request,
     * to ensure the value is never revealed to the user and cannot be set by the user.
     */
    @SuppressWarnings("unchecked")
    private static boolean fieldIsPrivate(Class<? extends DataModel> enclosingType, Field field) {
        if (field == null || //
                field.getAnnotation(Private.class) != null || //
                (DBModel.class.isAssignableFrom(enclosingType) && field.equals(Database.getIdFieldForDBModel((Class<? extends DBModel>) enclosingType)))) {
            return true;
        } else {
            // Check field is readable (will throw SecurityException or IllegalAccessException if not)
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /** Check if a field of the same name as the param exists and is accessible. */
    private static void checkFieldAccessible(String templateName, Class<? extends DataModel> templateClass, String fieldName, String attrName) {
        try {
            // Check field exists (will throw NoSuchFieldException if not)
            Field field = templateClass.getField(fieldName);
            if (field == null) {
                throw new RuntimeException("Field " + fieldName + " not found");
            }

            // Check field is readable (will throw SecurityException or IllegalAccessException if not)
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers)) {
                throw new RuntimeException("Field \"" + fieldName + "\" in " + DataModel.class.getSimpleName() + " subclass " + templateClass.getName()
                        + " is not public or is abstract");
            }

            // If this field is to be used in an HTML attribute value, need to also make sure that the field is not of DataModel, List or array type,
            // since those are rendered into HTML, not escaped string form
            boolean isAttr = attrName != null;
            if (isAttr) {
                Class<?> fieldType = field.getType();
                if (DataModel.class.isAssignableFrom(fieldType) || List.class.isAssignableFrom(fieldType) || Array.class.isAssignableFrom(fieldType)) {
                    throw new RuntimeException("Template \"" + templateName + ".html\" contains param \"" + fieldName + "\" of type " + fieldType.getName() + " in attribute \""
                            + attrName + "\", but you can't insert HTML content into an HTML attribute, only String content");
                }
            }

        } catch (NoSuchFieldException e) {
            try {
                templateClass.getDeclaredField(fieldName);
                throw new RuntimeException("Template \"" + templateName + ".html\" contains param \"" + fieldName + "\" but the field of that name in the class "
                        + templateClass.getName() + " is not public");
            } catch (NoSuchFieldException e1) {
                throw new RuntimeException("Template \"" + templateName + ".html\" contains param \"" + fieldName + "\" but there is no public field of that name in the class "
                        + templateClass.getName());
            }
        } catch (SecurityException e) {
            throw new RuntimeException("Template \"" + templateName + ".html\" contains param \"" + fieldName + "\" but the field of that name in the class "
                    + templateClass.getName() + " is not public");
        } catch (IllegalArgumentException e) {
            // Should not happen if templateInstance is not null
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Cross-check HTML template names against DataModel subclass names, and HTML template parameter names against names of fields in the correspondingly-named DataModel subclass.
     */
    public static void crossCheckDataModelAndView(SiteResources siteResources, String templateName, Class<? extends DataModel> templateDataModel, Document templateDoc) {
        // Compare template HTML files against DataModel class fields so that these checks don't have to be performed during template rendering
        HashSet<String> paramNamesUsedInHTML = new HashSet<>();
        for (Element e : templateDoc.getAllElements()) {

            // Check if the DataModel fields that have the same name as template params are accessible
            String tagName = e.tagName().toLowerCase();
            for (Node n : e.childNodes()) {
                if (n instanceof TextNode) {
                    String text = ((TextNode) n).text();

                    // Check for attempt to put param in the element name, e.g. <${elt}>, or malformed HTML like <>
                    // in these cases, Jsoup just inserts a text node with unescaped text 
                    if (text.indexOf('<') > 0 || text.indexOf('>') > 0) {
                        throw new RuntimeException("The template " + templateName + ".html contains invalid HTML: " + text);
                    }

                    Matcher matcher = TemplateLoader.TEMPLATE_PARAM_PATTERN.matcher(text);
                    boolean containsParam = false;
                    while (matcher.find()) {
                        containsParam = true;
                        String paramName = matcher.group(1);
                        checkFieldAccessible(templateName, templateDataModel, paramName, null);
                        paramNamesUsedInHTML.add(paramName);
                    }
                    if (containsParam) {
                        if (tagName.equals("#comment")) {
                            // Ignore params in comments
                        } else {
                            // OWASP Rule #3: JavaScript Escape Before Inserting Untrusted Data into JavaScript Data Values.
                            // OWASP Rule #4: CSS Escape And Strictly Validate Before Inserting Untrusted Data into HTML Style Property Values.

                            // We currently completely disallow inserting params into style and script attributes for safety reasons. 
                            // All parameter insertion into JS contexts or sub-contexts is disabled right now.

                            // See https://www.owasp.org/index.php/DOM_based_XSS_Prevention_Cheat_Sheet :
                            // "DOM based XSS is extremely difficult to mitigate against because of its large attack surface and lack of standardization across browsers."

                            // CSS parsing is not supported by jsoup, so we can't do context-sensitive escaping in CSS. Therefore, parameters are not supported in style tags or attributes at this time.
                            // CSS parsers: http://cssparser.sourceforge.net/ ; https://code.google.com/p/phloc-css/ ; http://www.w3.org/Style/CSS/SAC/ ; https://github.com/corgrath/osbcp-css-parser
                            // Note: see http://owasp-java-html-sanitizer.googlecode.com/svn/trunk/distrib/javadoc/org/owasp/html/Sanitizers.html -- sanitizers can be used to let through only sanitized CSS.

                            if (tagName.equals("script") || tagName.equals("style") || tagName.equals("applet") || tagName.equals("object")) {
                                throw new RuntimeException("The template " + templateName + ".html contains a template param inside the unsafe element <" + tagName + ">");
                            }
                        }
                    }
                }
            }
            for (Attribute a : e.attributes()) {
                String attrName = a.getKey().toLowerCase();
                String attrValue = a.getValue();
                StringBuilder attrValueWithoutParams = new StringBuilder();

                Matcher matcher = TemplateLoader.TEMPLATE_PARAM_PATTERN.matcher(attrValue);
                boolean attrValueContainsParam = false;
                int firstMatchStart = 0, lastMatchEnd = 0;
                while (matcher.find()) {
                    if (!attrValueContainsParam) {
                        // First match
                        firstMatchStart = matcher.start();
                        attrValueContainsParam = true;
                    }
                    attrValueWithoutParams.append(attrValue.subSequence(lastMatchEnd, matcher.start()));
                    lastMatchEnd = matcher.end();

                    // Check that the field in the DataModel class with the same name as the parameter is publicly accessible
                    String paramName = matcher.group(1);
                    checkFieldAccessible(templateName, templateDataModel, paramName, attrName);
                    paramNamesUsedInHTML.add(paramName);
                }
                attrValueWithoutParams.append(attrValue.subSequence(lastMatchEnd, attrValue.length()));

                // Check non-parameter content of id, name and class attributes
                if (attrValueWithoutParams.length() > 0) {
                    if (attrName.equals("id") || attrName.equals("name") || attrName.equals("class")) {
                        // OWASP Rule #1 under XSS Prevention Rules Summary: "Strictly validate unsafe attributes such as background, id and name."
                        // (We don't worry about background here, it is simply blocked by not being listed in XSS_SAFE_ATTRS, as checked below.)
                        // See also http://stackoverflow.com/questions/70579/what-are-valid-values-for-the-id-attribute-in-html
                        // -- we disallow '.' and ':' because they cause problems with jQuery.
                        if (!(attrName.equals("class") ? WebUtils.VALID_CSS_ID : WebUtils.VALID_HTML_NAME_OR_ID).matcher(attrValueWithoutParams).matches()) {
                            throw new RuntimeException("The template " + templateName + ".html contains bad characters in id, name or class attribute value: " + attrName + "=\""
                                    + attrValue + "\"");
                        }
                        if (StringUtils.containsUppercaseChar(attrValueWithoutParams)) {
                            throw new RuntimeException("The template " + templateName + ".html contains uppercase characters in attribute value: " + attrName + "=\"" + attrValue
                                    + "\", but browsers implement case sensitivity differently. Identifiers for id, name and class should all be lowercase.");
                        }
                    }
                }

                if (attrValueContainsParam) {
                    // OWASP Rule #2: "Only place untrusted data into a whitelist of safe attributes."
                    if (WebUtils.isURLAttr(tagName, attrName)) {
                        // URLs will be tested for validity later
                    } else if (attrName.equals("id") || attrName.equals("name")) {
                        // name and id are XSS-unsafe, because injected text can be made to refer to any element on the page.
                        // Mitigate the effect by requiring that the attr value have a non-parameterized prefix.
                        if (firstMatchStart == 0) {
                            throw new RuntimeException("The template " + templateName + ".html contains a template parameter in the value of an XSS-unsafe attribute \"" + attrName
                                    + "\". To parameterize id and name attributes in particular, the parameter must be prefixed with some non-parameter value "
                                    + "to mitigate the impact of an injection attack, e.g. <div id=\"widget${widgetNum}\"> is OK, but <div id=\"${widgetName}\"> is not.");
                        }
                    } else if (!WebUtils.XSS_SAFE_ATTRS.contains(attrName)) {
                        // Disable check for custom elements -- Polymer allows the user to define their own attributes.
                        // Note that these are an attack vector if the programmer is not careful.
                        if (!tagName.contains("-")) {
                            // Throw exception for non-whitelisted attrs.
                            throw new RuntimeException("Template " + templateName + ".html contains a template parameter in the value of an XSS-unsafe attribute \"" + attrName
                                    + "\"");
                        }
                    }
                    // OWASP Rule #3: JavaScript Escape Before Inserting Untrusted Data into JavaScript Data Values.
                    // OWASP Rule #4: CSS Escape And Strictly Validate Before Inserting Untrusted Data into HTML Style Property Values.
                    // We currently completely disallow inserting params into style and script attributes for safety reasons. 
                    if (tagName.equals("applet") || tagName.equals("object")) {
                        throw new RuntimeException("Tried to use a template param inside <" + tagName + "> in template " + templateName + ".html -- this is unsafe");
                    }
                    if (attrName.equals("style") || attrName.equals("data") || attrName.equals("action") || attrName.startsWith("on")) {
                        throw new RuntimeException("Tried to use a template param in attribute \"" + attrName + "\" in template " + templateName + ".html -- this is unsafe");
                    }

                    if (attrName.equals("id") || attrName.equals("name") || attrName.equals("class")) {
                        if (attrValue.contains(".") || attrValue.contains(":")) {
                            throw new RuntimeException("Value of attribute " + attrName + " in template " + templateName
                                    + ".html contains '.' or ':', which can cause problems with jQuery");
                        }
                        a.setValue(StringUtils.unicodeTrim(StringUtils.normalizeSpacing(attrValue)));
                    }
                }
            }

            // Cross-check DataModel fields that have type DataModel against form inputs where the form's id attribute matches the name of the DataModel 
            if (tagName.equals("form")) {

                // Add CSRF input to form with placeholder value that will be replaced with the real CSRF value when the page is served
                Elements csrfElts = e.getElementsByAttributeValue("name", CSRF.CSRF_PARAM_NAME);
                if (csrfElts.size() > 0) {
                    throw new RuntimeException("Form in template " + templateName + " should not include its own elements with name " + CSRF.CSRF_PARAM_NAME);
                }
                Element csrfElt = e.appendElement("input");
                csrfElt.attr("name", CSRF.CSRF_PARAM_NAME);
                csrfElt.attr("type", "hidden");
                csrfElt.attr("value", CSRF.CSRF_TOKEN_PLACEHOLDER);

                // Check if the id attribute of the form matches a DataModel-typed field in the template class associated with the form.
                // If so, this field's own fields are cross-checked against the input values in the form, as a special case of template parameter matching. 
                String id = e.attr("id");
                if (id != null && !id.isEmpty()) {
                    Field field = null;
                    try {
                        field = templateDataModel.getField(id);
                    } catch (NoSuchFieldException e1) {
                        // This from doesn't match a field in the DataModel object, so it must be used for some other purpose
                    } catch (SecurityException e1) {
                        throw new RuntimeException("Form with id \"" + id + "\" in template " + templateName + ".html matches name of field in class "
                                + templateDataModel.getName() + " but that field is not accessible");
                    }
                    if (field != null) {
                        // This form's id attribute matches the name of a DataModel-typed field in templateClass

                        // Make sure field is accessible, and that it's a DataModel subclass
                        checkFieldAccessible(templateName, templateDataModel, id, null);

                        // Check that the field that matches the form id is a DataModel subclass
                        if (DataModel.class.isAssignableFrom(field.getType())) {
                            @SuppressWarnings("unchecked")
                            Class<? extends DataModel> formModel = (Class<? extends DataModel>) field.getType();

                            // The form id is a special case of template parameter name
                            paramNamesUsedInHTML.add(id);

                            // Cross-check the names of inputs in the form with fields in the DataModel, and copy any
                            // constraint annotations in this DataModel across to the elements in the form
                            addConstraintsToForm(templateName, e, formModel);

                            // If form is to be submitted via POST, try substituting the route URL for the RestHandler that handles the POST into the action attribute
                            String submitURI = siteResources.routeURIForDataModel(formModel);
                            String method = e.attr("method");
                            if (method.isEmpty()) {
                                // Method was not specified
                                if (submitURI == null) {
                                    // Fill in missing (default) GET action if there is no POST handler that accepts this form
                                    method = "GET";
                                    e.attr("method", method);
                                } else {
                                    // Fill in missing POST action if there is a POST handler that accepts this form.
                                    // This overrides the HTML default action of GET, but it is more likely what was intended,
                                    // and it is safer since any constraint annotations will be checked on POST requests,
                                    // whereas for GET requests the user has to do binding and param checking themselves.
                                    method = "POST";
                                    e.attr("method", method);
                                }
                            } else {
                                // Method was specified
                                if (method.equalsIgnoreCase("GET")) {
                                    if (submitURI != null) {
                                        Log.warning("Form in template " + templateName + ".html has id \"" + id + "\" which matches a param of type " + formModel.getName()
                                                + ", and that class is accepted as a POST param on the route " + submitURI
                                                + " , but this form is submitted with GET, not POST. This means the post() method on "
                                                + "that route will not receive, bind or validate the submitted form values, they will "
                                                + "have to be manually parsed by the user in the get() method.");
                                    }
                                } else if (method.equalsIgnoreCase("POST")) {
                                    // Form is submitted via POST, and field type that matches form id is accepted by the post() method of a RestHandler

                                    String action = e.attr("action");
                                    if (action.isEmpty()) {
                                        // Fill in missing action attribute value with route path of the RestHandler that has a post() method that takes
                                        // a parameter of the type bound to this form
                                        e.attr("action", submitURI);
                                    } else if (!action.equals(submitURI)) {
                                        // Maybe this is heavy-handed (probably the user should be able to override with a different URI), but they can manually construct
                                        // a form that takes regular HTML params if they want to submit to a URI on say a different server, they don't need to fill in
                                        // the form using the DataModel-to-form binding.
                                        Log.warning("Form in template " + templateName + ".html has id \"" + id + "\" which matches a param of type " + formModel.getName()
                                                + ", and that class is accepted as a POST param on the route " + submitURI + " , but this form is submitted to the route " + action
                                                + " , not " + submitURI + " . Overriding " + action + " with " + submitURI);
                                        e.attr("action", submitURI);
                                    }

                                } else {
                                    throw new RuntimeException("Invalid form submission method \"" + method + "\" in template " + templateName + ".html");
                                }
                            }

                        } else {
                            throw new RuntimeException("Template " + templateName + ".html contains a form with id \"" + id + "\" that matches a field in class "
                                    + templateDataModel.getName() + ", however that field is not a subclass of " + DataModel.class.getName());
                        }
                    }
                }
            }
        }

        // Get names of template fields in DataModel
        HashSet<String> fieldNamesInClass = new HashSet<>();
        for (Field field : templateDataModel.getFields()) {
            // Private fields don't get bound to templates
            if (!fieldIsPrivate(templateDataModel, field)) {
                fieldNamesInClass.add(field.getName());
            }
        }

        // Make sure there's a 1-to-1 correspondence between param names used in this HTML template and field names in the associated DataModel subclass
        HashSet<String> paramNamesOnlyInHTML = new HashSet<>(paramNamesUsedInHTML);
        paramNamesOnlyInHTML.removeAll(fieldNamesInClass);
        HashSet<String> fieldNamesOnlyInClass = new HashSet<>(fieldNamesInClass);
        fieldNamesOnlyInClass.removeAll(paramNamesUsedInHTML);
        if (!paramNamesOnlyInHTML.isEmpty()) {
            throw new RuntimeException("There is no correspondingly-named public field in class " + templateDataModel.getName() + " for the HTML parameter name"
                    + (paramNamesOnlyInHTML.size() > 1 ? "s" : "") + " " + StringUtils.join(paramNamesOnlyInHTML, ", ", s -> "\"" + s + "\"") + " in HTML template " + templateName
                    + ".html");
        }

        if (!fieldNamesOnlyInClass.isEmpty()) {
            throw new RuntimeException("There is no correspondingly-named parameter or form field name in the HTML template " + templateName + ".html for the public field"
                    + (fieldNamesOnlyInClass.size() > 1 ? "s" : "") + " " + StringUtils.join(fieldNamesOnlyInClass, ", ", s -> "\"" + s + "\"") + " in class "
                    + templateDataModel.getName());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Recursively render JSON, skipping fields marked with @Private or @OnlyReceive, and id fields of DBModel objects. This produces a JSON rendering that may be served over a Web
     * connection without exposing internal server state.
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
                    buf.append(WebUtils.escapeJSONString((String) obj));
                    buf.append('"');

                } else if (klass == Integer.class || klass == Boolean.class || klass == Long.class || klass == Float.class || klass == Double.class || klass == Short.class) {
                    buf.append(obj.toString());

                } else if (klass == Byte.class) {
                    buf.append("0x");
                    buf.append(Integer.toString((Byte) obj, 16));

                } else if (klass == Character.class) {
                    buf.append('"');
                    buf.append(WebUtils.escapeJSONString(((Character) obj).toString()));
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

                } else if (Set.class.isAssignableFrom(klass)) {
                    // JSON can't enforce uniqueness of items, so sets are not renderable
                    throw new RuntimeException("Sets cannot be rendered into JSON");

                } else if (Map.class.isAssignableFrom(klass)) {
                    // Render a Map as a JSON associative array
                    Map<?, ?> map = (Map<?, ?>) obj;
                    ArrayList<?> keys = new ArrayList<>(map.keySet());
                    int n = keys.size();
                    if (n == 0) {
                        buf.append(prettyPrint ? "{ }" : "{}");
                    } else {
                        buf.append(prettyPrint ? "{\n" : "{");
                        if (prettyPrint) {
                            // If prettyprinting, get first non-null key and see if it implements Comparable, and if so, sort map keys
                            Object key = keys.get(0);
                            if (key == null && n > 1) {
                                key = keys.get(1);
                            }
                            if (key != null) {
                                if (Comparable.class.isAssignableFrom(key.getClass())) {
                                    Collections.sort((ArrayList<Comparable>) keys);
                                }
                            }
                        }
                        for (int i = 0; i < n; i++) {
                            // "key" : 
                            Object k = keys.get(i);
                            Object v = map.get(k);
                            if (prettyPrint) {
                                buf.append(StringUtils.spaces(depth + 1));
                            }
                            buf.append('"');
                            buf.append(WebUtils.escapeJSONString(k.toString()));
                            buf.append(prettyPrint ? "\" : " : "\":");
                            // Recursively render value
                            toJSONRec(v, prettyPrint, depth + 1, buf);
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

                } else {
                    // DataModel, DBModel, or some other class -- render fields as a JSON associative array using introspection
                    ArrayList<Field> fieldsToInclude = new ArrayList<>();
                    Field[] fields = klass.getFields();
                    for (int i = 0; i < fields.length; i++) {
                        Field field = fields[i];
                        // DataModel fields annotated with @Private or @OnlyReceive and DBModel id fields cannot be sent to the user
                        if (!(obj instanceof DataModel) || !DataModel.fieldIsPrivate(((DataModel) obj).getClass(), field) && field.getAnnotation(OnlyReceive.class) == null) {
                            // In case class is not itself public, need to call setAccessible(true)
                            // FIXME: Need to do the same everywhere else we get fields, or alternatively catch IllegalAccessException and tell the user that the class needs to be public.
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

                            if (prettyPrint) {
                                buf.append(StringUtils.spaces(depth + 1));
                            }
                            buf.append('"');
                            buf.append(WebUtils.escapeJSONString(field.getName()));
                            buf.append(prettyPrint ? "\" : " : "\":");

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
                                buf.append("0x");
                                buf.append(Integer.toString(field.getByte(obj), 16));
                            } else if (fieldType == Character.TYPE) {
                                buf.append('"');
                                buf.append(WebUtils.escapeJSONString(Character.toString(field.getChar(obj))));
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
     * Render this DataModel as JSON, skipping fields marked with @Private or @OnlyReceive, and id fields of DBModel objects. This produces a JSON rendering that may be served over
     * a Web connection without exposing internal server state.
     */
    public String toJSON(boolean prettyPrint) {
        StringBuilder buf = new StringBuilder(1024);
        toJSONRec(this, prettyPrint, 0, buf);
        return buf.toString();
    }

    /**
     * Render a list of objects as JSON, skipping fields marked with @Private or @OnlyReceive, and id fields of DBModel objects. This produces a JSON rendering that may be served
     * over a Web connection without exposing internal server state.
     */
    public static String toJSON(List<?> list, boolean prettyPrint) {
        StringBuilder buf = new StringBuilder(1024);
        toJSONRec(list, prettyPrint, 0, buf);
        return buf.toString();
    }

    /**
     * Render an array of objects as JSON, skipping fields marked with @Private or @OnlyReceive, and id fields of DBModel objects. This produces a JSON rendering that may be served
     * over a Web connection without exposing internal server state.
     */
    public static String toJSON(Object[] arr, boolean prettyPrint) {
        StringBuilder buf = new StringBuilder(1024);
        toJSONRec(arr, prettyPrint, 0, buf);
        return buf.toString();
    }

    /**
     * Recursively render an Object as JSON, skipping fields marked with @Private or @OnlyReceive, and id fields of DBModel objects. This produces a JSON rendering that may be
     * served over a Web connection without exposing internal server state.
     */
    public static String toJSON(Object obj, boolean prettyPrint) {
        StringBuilder buf = new StringBuilder(1024);
        toJSONRec(obj, prettyPrint, 0, buf);
        return buf.toString();
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------

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
     * Extends DataModel so that it can be used for both filling in a form template based on previous values, and being bound to the submission results of the form.
     */

    /** Escape text for an HTML attribute value or for an HTML text node. */
    private static String encodeParamText(String tagName, String attrName, String unsafeStr) {
        boolean isAttrVal = attrName != null;
        if (isAttrVal) {
            if (WebUtils.isURLAttr(tagName, attrName)) {
                // This parameter may be just part of a URI, so we need to check the whole string for validity
                // after all params have been substituted -- just insert param string directly for now. 
                return unsafeStr;
            } else {
                // OWASP Rule #2: Attribute Escape Before Inserting Untrusted Data into HTML Common Attributes
                return WebUtils.encodeForHTMLAttribute(unsafeStr);
            }
        } else {
            // OWASP Rule #1: HTML Escape Before Inserting Untrusted Data into HTML Element Content
            if (unsafeStr.indexOf("\n") >= 0) {
                // Turn "\n" within text params into <br> for convenience
                ArrayList<CharSequence> parts = StringUtils.splitAsList(unsafeStr, "\n");
                StringBuilder buf = new StringBuilder(unsafeStr.length() + parts.size() * 3);
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) {
                        // Can insert a raw <br> here because this text is not an attribute val, it is part of a text node.
                        buf.append("<br>");
                    }
                    // Separately escape each part split by a newline character
                    buf.append(WebUtils.encodeForHTML(parts.get(i)));
                }
                return buf.toString();

            } else {
                // No newline characters, HTML-escape the whole parameter string
                return WebUtils.encodeForHTML(unsafeStr);
            }
        }
    }

    /** Escape and render a field value (recursively rendering any lists or arrays). */
    private void recursivelyRender(String tagName, String attrName, boolean isAttrVal, Object fieldValue, boolean prettyPrint, int indentLevel, StringBuilder buf) {
        Class<?> type = fieldValue.getClass();
        if (type == String.class) {
            // Expand a string parameter

            String unsafeStr = (String) fieldValue;
            if (unsafeStr != null) {
                // Parameter is being expanded in non-URI attribute, or in a text node -- use regular HTML escaping
                buf.append(encodeParamText(tagName, attrName, unsafeStr));
            }

        } else if (DataModel.class.isAssignableFrom(type)) {
            // Expand an HTML template into the parameter position (param must not be in an attribute value)

            if (isAttrVal) {
                // Shouldn't happen, this was checked for on template load, but included here for safety
                throw new RuntimeException("Can't include HTML inside an attribute value");
            }
            DataModel template = (DataModel) fieldValue;
            if (template != null) {
                // Recursively add template content to this buffer from nested template
                template.renderTemplate(prettyPrint, indentLevel, buf);
            }

        } else if (List.class.isAssignableFrom(type) || type.isArray()) {
            // Expand a list or array of values

            if (isAttrVal) {
                // Shouldn't happen, this was checked for on template load, but included here for safety
                throw new RuntimeException("Can't include HTML inside an attribute value");
            }
            List<?> list = List.class.isAssignableFrom(type) ? (List<?>) fieldValue : null;
            Object[] array = type.isArray() ? (Object[]) fieldValue : null;
            if (list != null || array != null) {
                int n = list != null ? list.size() : array.length;
                // Render each item in the list
                for (int i = 0; i < n; i++) {
                    Object elt = list != null ? list.get(i) : array[i];
                    if (elt != null) {
                        if (elt instanceof DataModel) {
                            // Render HTML template for DataModel-typed list item
                            ((DataModel) elt).renderTemplate(prettyPrint, indentLevel, buf);

                        } else {
                            // For any other list or array element type, recursively render list/array elements
                            recursivelyRender(tagName, attrName, isAttrVal, elt, prettyPrint, indentLevel, buf);

                            if (!prettyPrint && i > 0) {
                                // Insert a space between adjacent values stringified from a list or array to ensure they are separated.
                                // (Hopefully this is the most useful behavior; if you need {"a", "b", "c"} -> "abc" without spaces,
                                // you need to do the join of the parameters manually before inserting into the template.)
                                buf.append(' ');
                            }
                        }
                    }
                }
            }

        } else if (Class.class.isAssignableFrom(type)) {
            // Special case: if a field type is a Class<? extends RestHandler>, then insert the URL of the RestHandler's route as a string,
            // so that routes can be inserted into href attributes
            Class<?> concreteClass = (Class<?>) fieldValue;
            if (concreteClass != null) {
                if (RestHandler.class.isAssignableFrom(concreteClass)) {
                    // Put URI for RestHandler into buf -- this is not escaped, since the RestHandler URIs should all be valid without escaping
                    // (they are either safely derived from the class name, or from the RouteOverride annotation, which is checked for validity)
                    @SuppressWarnings("unchecked")
                    Class<? extends RestHandler> restHandler = (Class<? extends RestHandler>) concreteClass;
                    String uriForClass = GribbitServer.siteResources.routeForHandler(restHandler).getRoutePath();
                    buf.append(uriForClass);

                } else {
                    // Due to type erasure, can't check until runtime if the right class type is passed in.
                    throw new RuntimeException("Got template parameter of type Class<" + concreteClass.getName() + ">, but should be of type Class<? extends "
                            + RestHandler.class.getName() + ">");
                }
            }

        } else {
            // For all other non-builtin types, call the toString() method and then HTML-escape the result
            if (fieldValue != null) {
                String unsafeStr = fieldValue.toString();
                buf.append(encodeParamText(tagName, attrName, unsafeStr));
            }
        }
    }

    /**
     * Substitute params from this DataModel object into the text, performing proper HTML escaping as needed.
     */
    private void substituteTemplateParamsAndEscapeText(String tagName, String attrName, String textWithParams, boolean prettyPrint, int indentLevel, StringBuilder buf) {
        boolean isAttrVal = attrName != null;

        Matcher matcher = TemplateLoader.TEMPLATE_PARAM_PATTERN.matcher(textWithParams);
        int prevMatchIdx = 0;
        int bufLenOnEntry = buf.length();
        while (matcher.find()) {
            // Append content before the match to the buffer
            CharSequence beforeMatch = textWithParams.subSequence(prevMatchIdx, matcher.start());
            buf.append(isAttrVal ? WebUtils.encodeForHTMLAttribute(beforeMatch) : WebUtils.encodeForHTML(beforeMatch));
            prevMatchIdx = matcher.end();

            // Render the content of the Template field with the same name as the HTML parameter into String format, then
            // HTML-escape it unless it's already HTML (i.e. unless the parameter is a Template field inside a Template
            // object). Null field values are ignored, i.e. they result in a parameter substitution of "".  
            String paramName = matcher.group(1);
            Object fieldVal = null;
            try {
                Field field = this.getClass().getField(paramName);

                // DataModel fields annotated with @Private or @OnlyReceive and DBModel id fields cannot be sent to the user
                if (!fieldIsPrivate(this.getClass(), field) && field.getAnnotation(OnlyReceive.class) == null) {

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
                }
            } catch (NoSuchFieldException | SecurityException | IllegalAccessException e1) {
                // Should not happen, NoSuchFieldException and SecurityException were checked for when templates were loaded 
                throw new RuntimeException(e1);
            }

            // Null items produce no output
            if (fieldVal != null) {
                // Escape and render a field value (recursively rendering any lists or arrays) 
                recursivelyRender(tagName, attrName, isAttrVal, fieldVal, prettyPrint, indentLevel, buf);
            }
        }
        // Append last unmatched text
        CharSequence afterLastMatch = textWithParams.subSequence(prevMatchIdx, textWithParams.length());
        buf.append(isAttrVal ? WebUtils.encodeForHTMLAttribute(afterLastMatch) : WebUtils.encodeForHTML(afterLastMatch));

        // Check validity of entirety of text value (template text with substituted param values) for URL attributes
        CharSequence escapedTextWithSubstitutedParams = buf.subSequence(bufLenOnEntry, buf.length());
        if (escapedTextWithSubstitutedParams.length() > 0) {
            if (isAttrVal && WebUtils.isURLAttr(tagName, attrName)) {
                // This is a URI attribute -- check URL contains only valid characters (URIs must already be escaped)
                String uriStr = escapedTextWithSubstitutedParams.toString();

                // OWASP Rule #5: URL Escape Before Inserting Untrusted Data into HTML URL Parameter Values.
                // https://www.owasp.org/index.php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet#RULE_.235_-_URL_Escape_Before_Inserting_Untrusted_Data_into_HTML_URL_Parameter_Values
                // We leave it up to the user to escape their URIs. StringUtils.encodeURI() can be used for this purpose.
                // Instead, we run the URI through the Java URI parser class to validate the URI.
                URI uri = WebUtils.parseURI(uriStr);
                if (uri == null) {
                    throw new RuntimeException("String substituted into URI attr \"" + attrName + "\" is not a valid URI: " + uriStr);
                }

                String scheme = uri.getScheme();
                if (scheme != null) {
                    scheme = scheme.toLowerCase();
                    // Specifically blacklist a few protocols.
                    // OWASP Rule #5: "Including untrusted data in data: URLs should not be allowed as there is no good way to disable attacks with escaping to prevent switching out of the URL.
                    // [...] If untrusted input is meant to be placed into href, src or other URL-based attributes, it should be validated to make sure it does not point to an unexpected protocol,
                    // especially Javascript links."  See also: http://googleonlinesecurity.blogspot.com/2011/03/mhtml-vulnerability-under-active.html
                    if (scheme.equals("javascript") || scheme.equals("data") || scheme.equals("mhtml")) {
                        throw new RuntimeException("URI " + uriStr + " uses an unsafe protocol");
                    }

                    if (scheme.equals("mailto") || scheme.equals("tel")) {
                        // See http://owasp-java-html-sanitizer.googlecode.com/svn/trunk/distrib/javadoc/org/owasp/html/Sanitizers.html
                        // (suggested in OWASP Rule #6)
                        if (!(tagName.equals("a") && isAttrVal && attrName.equals("href"))) {
                            throw new RuntimeException("URL " + uriStr + " should be used in an a.href attribute");
                        }
                    } else if (!(scheme.equals("http") || scheme.equals("https"))) {
                        // If it's not http: or https:, it's probably unsafe
                        throw new RuntimeException("URI " + uriStr + " uses an unsupported URL protocol");
                    }
                }

                // Log.warning("Using parameter in URI attribute \"" + attrName + "\" is unsafe due to the possibility for SVG script injection: https://www.owasp.org/images/0/03/Mario_Heiderich_OWASP_Sweden_The_image_that_called_me.pdf");

            } else if (isAttrVal && (attrName.equals("id") || attrName.equals("name") || attrName.equals("class"))) {
                // OWASP Rule #1 under XSS Prevention Rules Summary: "Strictly validate unsafe attributes such as background, id and name."
                // See also http://stackoverflow.com/questions/70579/what-are-valid-values-for-the-id-attribute-in-html -- we disallow '.' and ':' because they cause problems with jQuery.

                if (!(attrName.equals("class") ? WebUtils.VALID_CSS_ID : WebUtils.VALID_HTML_NAME_OR_ID).matcher(escapedTextWithSubstitutedParams).matches()) {
                    throw new RuntimeException("Bad characters in attribute value: " + attrName + "=\"" + escapedTextWithSubstitutedParams + "\"");
                }
                if (StringUtils.containsUppercaseChar(escapedTextWithSubstitutedParams)) {
                    throw new RuntimeException("There are uppercase characters in attribute " + attrName + "=\"" + escapedTextWithSubstitutedParams
                            + "\", but browsers implement case sensitivity differently. Identifiers should all be lowercase.");
                }
            }
        }
    }

    /** Append an attribute name and escaped value to the inside of a tag. */
    private void renderAttrKeyValue(String tagName, String attrName, String attrVal, boolean prettyPrint, int indentLevel, StringBuilder buf) {
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
     * Sets initial values of inputs in a form to the corresponding values in the passed DataModel, if the DataModel and its field with the same name as the "name" attribute of a
     * form input are both non-null.
     */
    private void renderAttrs(Element e, Element enclosingForm, DataModel formModel, String selectName, boolean prettyPrint, int indentLevel, StringBuilder buf) {

        // Replace the attribute values in the template with overridden values if this is an 'input' or 'option' element in a form,
        // and if a non-null formModel has been provided to pre-populate these values
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
                    // DataModel fields annotated with @Private or @OnlyReceive and DBModel id fields cannot be sent to the user
                    if (!fieldIsPrivate(formModel.getClass(), formModelField) && formModelField.getAnnotation(OnlyReceive.class) == null) {
                        Object formModelFieldValue = formModelField.get(formModel);
                        if (formModelFieldValue != null) {
                            // The DataModel associated with this form is non-null, and the field in the data model with the same name as this input is non-null
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
                                // Intentionally ignore this -- should not fill in password field from a DataModel object
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
                                // Should not have a field named "submit" (or any of these others) in a DataModel -- ignore
                                break;

                            default:
                            }
                        }
                    }
                } catch (Exception e1) {
                    // If there are inputs that can't be populated from named fields, don't try to set their default value
                }
            }
        }

        // For elements that are not input elements in a form backed by a DataModel, just append the attributes and values.
        // For input elements, substitute any values replaced above.
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

        // Append any attrs that were added for input forms that didn't replace a value already in the input element
        if (replaceAttrVal != null) {
            for (Entry<String, String> ent : replaceAttrVal.entrySet()) {
                renderAttrKeyValue(tagName, ent.getKey(), ent.getValue(), prettyPrint, indentLevel, buf);
            }
        }
    }

    /**
     * Recursively traverse the DOM of a template, rendering each node into HTML. Returns true if the node was indented (which happens when prettyPrint == true and the node or one
     * of its children is a block element).
     */
    private boolean renderDOMNode(Node node, Element enclosingForm, DataModel formModel, String selectName, boolean prettyPrint, boolean normalizeTextSpacing, int indentLevel,
            StringBuilder buf) {
        boolean nodeWasIndented = false;

        if (node instanceof Element) {
            Element e = (Element) node;
            String tagName = e.tagName();

            if (tagName.equals("form")) {
                // For form elements, match form id against field names of this DataModel to see if there's a match
                String formId = e.attr("id");
                if (!formId.isEmpty()) {
                    try {
                        Field formField = this.getClass().getField(formId);
                        DataModel thisFormModel = (DataModel) formField.get(this);

                        // Overwrite current formValue for child nodes (in case of nested forms)
                        enclosingForm = e;
                        formModel = thisFormModel;

                    } catch (Exception e1) {
                        // This form doesn't match a field with the same name as the form id -- don't do any special processing of this form
                    }
                }
            } else if (tagName.equals("select")) {
                // For <select> elements, use name of select to identify <option> elements inside select 
                selectName = e.attr("name");
            }

            // Only indent block elements, inline elements should not be indented because it would introduce extra whitespace, potentially in the wrong place
            boolean isBlockElement = !WebUtils.INLINE_ELEMENTS.contains(tagName) && !GribbitServer.siteResources.getCustomInlineElements().contains(tagName);

            // Render the open tag for this element
            if (prettyPrint && isBlockElement) {
                StringUtils.indent(indentLevel, buf);
                nodeWasIndented = true;
            }
            buf.append('<');
            buf.append(tagName);
            renderAttrs(e, enclosingForm, formModel, selectName, prettyPrint, indentLevel, buf);
            buf.append('>');

            // Switch off prettyprinting and text spacing normalization inside the pre element
            if (tagName.equals("pre")) {
                // FIXME: See http://www.impressivewebs.com/css-white-space/ too, you can also specify whitespace significance in CSS
                prettyPrint = false;
                normalizeTextSpacing = false;
            }

            // Don't render contents or close tag for void elements
            if (!WebUtils.VOID_ELEMENTS.contains(tagName)) {
                // Recursively render child nodes of this element
                boolean hasIndentedChild = renderChildrenOfElement(e, enclosingForm, formModel, selectName, prettyPrint, normalizeTextSpacing, indentLevel, buf);
                nodeWasIndented |= hasIndentedChild;

                // Indent close tag on its own separate line if anything after start tag was prettyprinted,
                // otherwise render close tag on same line since content was all inline (or there were no child nodes) 
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
                // We never indent text nodes (they are inline), but unless we're in a <pre> element, if prettyprinting is turned on, we at least normalize the spacing
                nodeText = StringUtils.normalizeSpacing(nodeText);
            }
            // OWASP Rule #1: HTML Escape Before Inserting Untrusted Data into HTML Element Content
            substituteTemplateParamsAndEscapeText(null, null, nodeText, prettyPrint, indentLevel, buf);

        } else if (node instanceof DataNode) {

            // FIXME: Probably need to properly escape DataNode contents (differently for each of style and script elements?)
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

        } else if (node instanceof DocumentType || node instanceof XmlDeclaration) {

            StringUtils.indent(indentLevel, buf);
            nodeWasIndented = true;

            buf.append(node.toString());

        } else if (node instanceof Comment) {

            // Only add comments to output if we're prettyprinting
            if (prettyPrint) {
                StringUtils.append(node.toString(), indentLevel, buf);
            }

        } else {
            throw new RuntimeException("Unknown node type: " + node.getClass().getName());
        }
        return nodeWasIndented;
    }

    /** Recursively render the children of a DOM node into HTML. Returns true if a child of this node was indented on its own line. */
    private boolean renderChildrenOfElement(Element e, Element enclosingForm, DataModel formModel, String selectName, boolean prettyPrint, boolean normalizeTextSpacing,
            int indentLevel, StringBuilder buf) {
        boolean childWasIndented = false;
        for (Node child : e.childNodes()) {
            // Recursively render each child node
            childWasIndented |= renderDOMNode(child, enclosingForm, formModel, selectName, prettyPrint, normalizeTextSpacing, indentLevel + 1, buf);
        }
        return childWasIndented;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------

    /** Get the template document associated with this DataModel, or null if there is no associated template */
    private Document getAssociatedTemplateDoc() {
        return GribbitServer.siteResources.getTemplateDocForClass(this.getClass());
    }

    /** Render a template recursively (i.e. substitute templates inside of templates, if present). */
    private void renderTemplate(Document doc, boolean prettyPrint, int indentLevel, StringBuilder buf) {
        // Render template DOM nodes (they are children of doc body element)
        renderChildrenOfElement(doc.body(), null, null, null, prettyPrint, true, indentLevel, buf);
    }

    /** Render a template recursively (i.e. substitute templates inside of templates, if present). */
    public void renderTemplate(boolean prettyPrint, int indentLevel, StringBuilder buf) {
        Document doc = getAssociatedTemplateDoc();
        if (doc == null) {
            throw new RuntimeException("Could not find a template named " + this.getClass().getSimpleName() + ".html to render model " + this.getClass().getName());
        }
        renderTemplate(doc, prettyPrint, indentLevel, buf);
    }

    /**
     * Render a DataModel object by rendering the associated template, substituting the values in the DataModel, if there is an associated template, otherwise rendering as a JSON
     * string. This is marked as final so that subclasses can't generate unsafe HTML by overriding this method.
     */
    @Override
    public final String toString() {
        // See if there's a template associated with this DataModel 
        Document doc = getAssociatedTemplateDoc();
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
