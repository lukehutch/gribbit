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
package gribbit.model.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import gribbit.model.DBModel;
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
import gribbit.model.field.visibility.annotation.Private;
import gribbit.model.field.visibility.annotation.PrivateGet;
import gribbit.model.field.visibility.annotation.PrivateSet;
import gribbit.util.StringUtils;
import gribbit.util.WebUtils;
import io.netty.handler.codec.http.multipart.FileUpload;

public class FieldChecker {

    private ConcurrentHashMap<Class<?>, ArrayList<FieldValueConstraintChecker>> constraintCheckersForClass = new ConcurrentHashMap<>();

    private static abstract class FieldValueConstraintChecker {
        /**
         * Check the field values of the DataModel against the constraint annotations for its class, possibly
         * modifying the value of the field so that constraints are satisfied (e.g. normalizing spacing).
         */
        public abstract void checkFieldsAgainstConstraints(Object object);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Statically check types of the class' public fields against their data constraint annotations to make sure the
     * annotations make sense given the types. Called during resource loading on all DataModel and TemplateModel
     * objects found on the classpath. Also sets up constraint checkers for fast runtime checking of field values
     * against constraints.
     * 
     * @throws RuntimeException
     *             if constraints don't match field types.
     */
    public void registerClassForConstraintChecking(Class<?> klass) throws RuntimeException {
        ArrayList<FieldValueConstraintChecker> valueCheckers = new ArrayList<>();
        for (Field field : klass.getFields()) {
            Annotation[] fieldAnnotations = field.getAnnotations();
            Class<?> fieldType = field.getType();
            boolean fieldIsPrimitiveType = fieldType == Integer.TYPE || fieldType == Long.TYPE //
                    || fieldType == Short.TYPE || fieldType == Character.TYPE || fieldType == Byte.TYPE //
                    || fieldType == Float.TYPE || fieldType == Double.TYPE;
            String fieldName = field.getName();

            // --------------------------------------------------------
            // Check constraint annotations against field types
            // --------------------------------------------------------

            // Ignore private fields
            if (!fieldIsPrivate(field, /* checkGet = */false, /* checkSet = */false)) {

                // Statically check if constraint annotations are valid for this field
                for (Annotation annotation : fieldAnnotations) {
                    Class<? extends Annotation> annotationType = annotation.annotationType();
                    if ((annotationType == Email.class || annotationType == MinLength.class
                            || annotationType == MaxLength.class || annotationType == Regex.class)
                            && fieldType != String.class) {
                        throw new RuntimeException(
                                "Field " + klass.getName() + "." + fieldName + " must be of type String");

                    } else if ((annotationType == MinIntegerValue.class || //
                            annotationType == MaxIntegerValue.class)
                            && !(fieldType == Integer.class || fieldType == Integer.TYPE)) {
                        throw new RuntimeException(
                                "Field " + klass.getName() + "." + fieldName + " must be of type int or Integer");
                    } else if (annotationType == Required.class && fieldIsPrimitiveType) {
                        throw new RuntimeException("Field " + klass.getName() + "." + fieldName
                                + " has a Required annotation, but the field has a primitive type,"
                                + " so it can never be null");
                    }
                }

                // ----------------------------------------------------------------------
                // Create constraint checkers for fast validation of field values
                // ----------------------------------------------------------------------

                // Check that required fields are non-null, and in the case of Strings, non-empty
                boolean isRequired = fieldIsRequired(field);

                // Normalize spacing of string fields; trim all string fields unless the NoTrim annotation is present
                // (but always trim email addresses); lowercase email addresses
                boolean isString = fieldType == String.class;
                boolean normalizeSpacing = isString && field.isAnnotationPresent(NormalizeSpacing.class);
                boolean isEmail = isString && field.isAnnotationPresent(Email.class);
                boolean needToTrim = isString && (!field.isAnnotationPresent(NoTrim.class) || isEmail);
                boolean toLowerCase = isEmail;
                boolean checkMinLength = isString && field.isAnnotationPresent(MinLength.class);
                int minLength = checkMinLength ? ((MinLength) field.getAnnotation(MinLength.class)).value() : 0;
                boolean checkMaxLength = isString && field.isAnnotationPresent(MaxLength.class);
                int maxLength = checkMaxLength ? ((MaxLength) field.getAnnotation(MaxLength.class)).value() : 0;
                boolean isRegex = field.isAnnotationPresent(Regex.class);
                Pattern regex;
                if (isRegex) {
                    try {
                        regex = Pattern.compile(((Regex) field.getAnnotation(Regex.class)).regex());
                    } catch (PatternSyntaxException e) {
                        throw new RuntimeException(Regex.class.getName() + " annotation on field " + klass.getName()
                                + "." + fieldName + " is not a valid regular expression");
                    }
                } else {
                    regex = null;
                }
                boolean isInt = fieldType == Integer.class || fieldType == Integer.TYPE;
                boolean checkMinIntValue = isInt && field.isAnnotationPresent(MinIntegerValue.class);
                int minIntValue = checkMinIntValue
                        ? ((MinIntegerValue) field.getAnnotation(MinIntegerValue.class)).value() : 0;
                boolean checkMaxIntValue = isInt && field.isAnnotationPresent(MinIntegerValue.class);
                int maxIntValue = checkMaxIntValue
                        ? ((MaxIntegerValue) field.getAnnotation(MaxIntegerValue.class)).value() : 0;

                // Add one value checker per constrained field
                if (isString && (isRequired || normalizeSpacing || needToTrim || toLowerCase || checkMinLength
                        || checkMaxLength)) {
                    valueCheckers.add(new FieldValueConstraintChecker() {
                        @Override
                        public void checkFieldsAgainstConstraints(Object instance) {
                            String strValue = null;
                            try {
                                Object fieldValue = field.get(instance);
                                strValue = (String) fieldValue;
                            } catch (IllegalArgumentException | IllegalAccessException e) {
                                throw new RuntimeException(e);
                            }

                            if (isRequired && (strValue == null || (isString && strValue.isEmpty()))) {
                                throw new RuntimeException("Required field " + klass.getName() + "." + fieldName
                                        + " is null or empty");
                            }

                            // Handle constraints, trimming and normalization on String-typed fields
                            if (normalizeSpacing && strValue != null) {
                                strValue = StringUtils.normalizeSpacing(strValue);
                            }
                            if (needToTrim && strValue != null) {
                                strValue = StringUtils.unicodeTrim(strValue);
                            }
                            if (toLowerCase && strValue != null) {
                                String lower = strValue.toLowerCase();
                                if (!lower.equals(strValue)) {
                                    strValue = lower;
                                }
                            }
                            if (isEmail) {
                                // If there's an @Email annotation, the field cannot be null
                                if (strValue == null
                                        // Validate email addresses
                                        || !WebUtils.isValidEmailAddr(strValue)) {
                                    throw new RuntimeException("Value of field " + klass.getName() + "." + fieldName
                                            + " is not a valid email address"
                                            + (strValue == null ? ", its value is null" : ""));
                                }
                            }
                            // Check regex after normalizing spacing and trimming
                            if (isRegex) {
                                if (!regex.matcher(strValue).matches()) {
                                    throw new RuntimeException("Value for parameter " + fieldName
                                            + " does not match the regex constraint");
                                }
                            }
                            // Check min/max len after normalizing spacing and trimming
                            if (checkMinLength && (strValue == null || strValue.length() < minLength)) {
                                throw new RuntimeException("Value of field " + klass.getName() + "." + fieldName
                                        + " must have minimum length " + (needToTrim ? "(after trimming) " : "")
                                        + "of " + minLength);
                            } else if (checkMaxLength && (strValue == null || strValue.length() > maxLength)) {
                                throw new RuntimeException("Value of field " + klass.getName() + "." + fieldName
                                        + " must have maximum length " + (needToTrim ? "(after trimming) " : "")
                                        + "of " + maxLength + (strValue == null ? ", but the value is null" : ""));
                            }

                            // If value of field has changed; update field
                            // This has a side-effect (i.e. it modifies the field value in-place)
                            if (strValue != null && !strValue.equals(strValue)) {
                                try {
                                    field.set(instance, strValue);
                                } catch (IllegalArgumentException | IllegalAccessException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    });

                } else if (isInt && (isRequired || checkMinIntValue || checkMaxIntValue)) {
                    valueCheckers.add(new FieldValueConstraintChecker() {
                        @Override
                        public void checkFieldsAgainstConstraints(Object instance) {
                            int intValue = 0;
                            if (fieldIsPrimitiveType) {
                                try {
                                    intValue = field.getInt(instance);
                                } catch (IllegalArgumentException | IllegalAccessException e) {
                                    throw new RuntimeException(
                                            "Could not read field " + klass.getName() + "." + fieldName, e);
                                }
                            } else {
                                Object fieldValue;
                                try {
                                    fieldValue = field.get(instance);
                                } catch (IllegalArgumentException | IllegalAccessException e) {
                                    throw new RuntimeException(e);
                                }
                                // All of @Required, @MinIntegerValue and @MaxIntegerValue require non-null values
                                if (fieldValue == null) {
                                    throw new RuntimeException(
                                            "Required field " + klass.getName() + "." + fieldName + " is null");
                                }
                                intValue = (Integer) fieldValue;
                            }

                            // Handle min/max value constraint on int-typed field
                            if (checkMinIntValue && intValue < minIntValue) {
                                throw new RuntimeException("Parameter " + klass.getName() + "." + fieldName
                                        + " must have minimum value " + minIntValue + ", but has value "
                                        + intValue);
                            } else if (checkMaxIntValue && intValue > maxIntValue) {
                                throw new RuntimeException("Parameter " + klass.getName() + "." + fieldName
                                        + " must have maximum value " + maxIntValue + ", but has value "
                                        + intValue);
                            }
                        }
                    });
                }
            }
        }
        constraintCheckersForClass.put(klass, valueCheckers);
    }

    /**
     * Check the values of fields against the constraint annotations. Called when DataModel objects are bound from a
     * POST request, and when DBModel.save() is called to save an object in the database.
     * 
     * Side-effects: forces the values of fields marked with Email annotation to lowercase; trims String fields not
     * marked with NoTrim annotation; normalizes spacing in fields marked with NormalizeSpacing annotation.
     * 
     * @throws RuntimeException
     *             if one or more constraints are violated by the values in this object instance.
     */
    public void checkFieldValuesAgainstConstraintAnnotations(Object instance) throws RuntimeException {
        ArrayList<FieldValueConstraintChecker> valueCheckers = constraintCheckersForClass.get(instance.getClass());
        if (valueCheckers != null) {
            for (FieldValueConstraintChecker checker : valueCheckers) {
                checker.checkFieldsAgainstConstraints(instance);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Returns true if the class declaring the field is not itself public, or if the field is not public, or if the
     * field: (1) is abstract, static or final; (2) is the id field of a DBModel; (3) has the Private annotation, or
     * (if checkPrivateSet is true) the PrivateSet annotation, or (if checkPrivateGet is true) the PrivateGet
     * annotation.
     * 
     * Fields annotated with Private in a DataModel instance, or id fields of a DBModel instance, cannot be rendered
     * into a template, served as JSON or bound from a POST request, to ensure the value is never sent to the user
     * if it shouldn't be, and/or cannot be set in a DBModel subclass by the user when they submit data in a POST
     * request.
     */
    public static boolean fieldIsPrivate(Field field, boolean checkPrivateGet, boolean checkPrivateSet) {
        if (field == null) {
            return true;
        }

        int modifiers = field.getModifiers();
        return
        // Class is not public
        (!Modifier.isPublic(field.getDeclaringClass().getModifiers()))
                // Field is not public, or is abstract, static or final => treat as private
                || (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers) || Modifier.isStatic(modifiers) //
                        || Modifier.isFinal(modifiers))
                //
                // DBModel id fields are private, they can't be sent to user or bound from forms
                || (DBModel.class.isAssignableFrom(field.getDeclaringClass()) && field.getName().equals("id"))

                // Field is annotated with Private
                || field.isAnnotationPresent(Private.class)

                // Field cannot be set from received value, but an attempt has been made to set it in an object that is
                // bound from a POST request
                || (checkPrivateSet && field.isAnnotationPresent(PrivateSet.class))

                // Cannot get field value, but an attempt has been made to get value to render it as HTML or JSON
                || (checkPrivateGet && field.isAnnotationPresent(PrivateGet.class));
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Return true if the field has one of the following annotations: Required, DBIndex, MinLength, MinIntegerValue,
     * MaxIntegerValue.
     */
    public static boolean fieldIsRequired(Field f) {
        return f.isAnnotationPresent(Required.class) || f.isAnnotationPresent(DBIndex.class)
                || f.isAnnotationPresent(MinLength.class) || f.isAnnotationPresent(MinIntegerValue.class)
                || f.isAnnotationPresent(MaxIntegerValue.class);
    }

    // -----------------------------------------------------------------------------------------------------------------

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
                || fieldType.isEnum() //
                || fieldType == FileUpload.class;
    }

    /**
     * Returns true if the Class's public fields consist only of basic types (builtins or their wrapper classes,
     * String, LocalDate, enum, or FileUpload).
     */
    public static boolean isFlatModel(Class<?> klass) {
        for (Field formField : klass.getFields()) {
            if (!FieldChecker.fieldIsPrivate(formField, /* checkGet = */true, /* checkSet = */true)) {
                Class<?> formFieldType = formField.getType();
                if (!isBasicFieldType(formFieldType)) {
                    return false;
                }
            }
        }
        return true;
    }
}
