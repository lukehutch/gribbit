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
package gribbit.server.siteresources;

import gribbit.model.DBModel;
import gribbit.model.DataModel;
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

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class DataModelLoader {

    // TODO: This template stuff should be in TemplateLoader, not DataModelLoader

    HashMap<String, String> classNameToInlineTemplate = new HashMap<>();

    HashMap<String, String> classNameToInlineTemplateOverride = new HashMap<>();

    // -----------------------------------------------------------------------------------------------------

    private static ConcurrentHashMap<Class<? extends DataModel>, ArrayList<FieldValueConstraintChecker>> constraintCheckersForClass = new ConcurrentHashMap<>();

    private static abstract class FieldValueConstraintChecker {
        /**
         * Check the field values of the DataModel against the constraint annotations for its class, possibly modifying
         * the value of the field according to constraints (e.g. normalizing spacing).
         */
        public abstract void checkFieldsAgainstConstraints(DataModel object);
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Found a static initializer value in a classfile on a second or subsequent loading of site resources. Use this
     * value instead of the one read using reflection, so that hot changes of static constant values is supported.
     */
    public void registerTemplateStaticFieldValue(String className, String templateString) {
        classNameToInlineTemplateOverride.put(className, templateString);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Fields annotated with Private in a DataModel instance, or id fields of a DBModel instance, cannot be rendered
     * into a template, served as JSON or bound from a POST request, to ensure the value is never sent to the user if it
     * shouldn't be, and/or cannot be set in a DBModel subclass by the user when they submit data in a POST request.
     */
    // FIXME: this is used by TemplateModelMapping too, move to a utility class
    // FIXME: Will checkFieldAccessible() always return true if this method returns true, now that we're checking if the class is public? (What about inner classes, static classes, etc.?) 
    public static boolean fieldIsPrivate(Field field, boolean checkGet, boolean checkSet) {
        if (field == null) {
            return true;
        }

        int modifiers = field.getModifiers();
        return
        // Class is not public
        (!Modifier.isPublic(field.getDeclaringClass().getModifiers()))
        // Field is not public, or is abstract, static or final => treat as private
                || (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers) || Modifier.isStatic(modifiers) || Modifier
                        .isFinal(modifiers))
                //
                // DBModel id fields are private, they can't be sent to user or bound from forms
                || (DBModel.class.isAssignableFrom(field.getDeclaringClass()) && field.getName().equals("id"))

                // Field is annotated with Private
                || field.isAnnotationPresent(Private.class)

                // Field cannot be set from received value, but an attempt has been made to set it in an object that is
                // bound from a POST request
                || (field.isAnnotationPresent(PrivateSet.class) && checkSet)

                // Cannot get field value, but an attempt has been made to get value to render it as HTML or JSON
                || (field.isAnnotationPresent(PrivateGet.class) && checkGet);
    }

    // TODO: Move this to a utility class?
    public static boolean fieldIsRequired(Field f) {
        return f.isAnnotationPresent(Required.class) || f.isAnnotationPresent(DBIndex.class)
                || f.isAnnotationPresent(MinLength.class) || f.isAnnotationPresent(MaxLength.class)
                || f.isAnnotationPresent(MinIntegerValue.class) || f.isAnnotationPresent(MaxIntegerValue.class);
    }

    /**
     * Check if a field of the same name as the param exists and is accessible. Throws an exception if the field cannot
     * be substituted into the template parameter for any reason.
     * 
     * @return Returns the field corresponding with fieldName in the class matching templateName.
     */
    public static Field checkFieldAccessible(String templateName, Class<? extends DataModel> templateClass,
            String fieldName, String attrName) {
        try {
            // Check field exists (will throw NoSuchFieldException if not)
            Field field = templateClass.getField(fieldName);
            if (field == null) {
                throw new RuntimeException("Field " + fieldName + " not found");
            }

            // Check field is readable (will throw SecurityException or IllegalAccessException if not)
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers)) {
                throw new RuntimeException("Field \"" + fieldName + "\" in " + DataModel.class.getSimpleName()
                        + " subclass " + templateClass.getName() + " is not public or is abstract");
            }

            boolean isAttr = attrName != null;
            if (isAttr) {
                // If this field is to be used in an HTML attribute value, need to also make sure that the
                // field is not of DataModel, List or array type, since those are rendered into HTML, not
                // escaped string form. Attribute values can take only strings, not rendered HTML.  
                Class<?> fieldType = field.getType();
                if (DataModel.class.isAssignableFrom(fieldType) || List.class.isAssignableFrom(fieldType)
                        || Array.class.isAssignableFrom(fieldType)) {
                    throw new RuntimeException("Template \"" + templateName + "\" contains param \"" + fieldName
                            + "\" of type " + fieldType.getName() + " in attribute \"" + attrName
                            + "\", but you can't insert HTML content into an HTML attribute, " + "only String content");
                }
            }
            return field;

        } catch (NoSuchFieldException e) {
            try {
                templateClass.getDeclaredField(fieldName);
                throw new RuntimeException("Template \"" + templateName + "\" contains param \"" + fieldName
                        + "\" but the field of that name in the class " + templateClass.getName() + " is not public");
            } catch (NoSuchFieldException e1) {
                throw new RuntimeException("Template \"" + templateName + "\" contains param \"" + fieldName
                        + "\" but there is no public field of that name in the class " + templateClass.getName());
            }
        } catch (SecurityException e) {
            throw new RuntimeException("Template \"" + templateName + "\" contains param \"" + fieldName
                    + "\" but the field of that name in the class " + templateClass.getName() + " is not public");
        } catch (IllegalArgumentException e) {
            // Should not happen if templateInstance is not null
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Statically check types of fields against their data constraint annotations to make sure the annotations make
     * sense given the types. Called by TemplateLoader on all DataModel objects when they are found on the classpath.
     * 
     * @throws RuntimeException
     *             if constraints don't match field types.
     */
    public static void checkFieldTypesAgainstAnnotations(Class<? extends DataModel> dataModelClass)
            throws RuntimeException {
        ArrayList<FieldValueConstraintChecker> valueCheckers = new ArrayList<>();
        for (Field field : dataModelClass.getFields()) {
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
                // Go through constraint annotations on this field
                for (Annotation annotation : fieldAnnotations) {
                    Class<? extends Annotation> annotationType = annotation.annotationType();
                    if ((annotationType == Email.class || annotationType == MinLength.class
                            || annotationType == MaxLength.class || annotationType == Regex.class)
                            && fieldType != String.class) {
                        throw new RuntimeException("Field " + dataModelClass.getName() + "." + fieldName
                                + " must be of type String");

                    } else if ((annotationType == MinIntegerValue.class || //
                            annotationType == MaxIntegerValue.class)
                            && !(fieldType == Integer.class || fieldType == Integer.TYPE)) {
                        throw new RuntimeException("Field " + dataModelClass.getName() + "." + fieldName
                                + " must be of type int or Integer");
                    } else if (annotationType == Required.class && fieldIsPrimitiveType) {
                        throw new RuntimeException("Field " + dataModelClass.getName() + "." + fieldName
                                + " has a Required annotation, but the field has a primitive type,"
                                + " so it can never be null");
                    }
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
                    throw new RuntimeException(Regex.class.getName() + " annotation on field "
                            + dataModelClass.getName() + "." + fieldName + " is not a valid regular expression");
                }
            } else {
                regex = null;
            }
            boolean isInt = fieldType == Integer.class || fieldType == Integer.TYPE;
            boolean checkMinValue = isInt && field.isAnnotationPresent(MinIntegerValue.class);
            int minValue = checkMinValue ? ((MinIntegerValue) field.getAnnotation(MinIntegerValue.class)).value() : 0;
            boolean checkMaxValue = isInt && field.isAnnotationPresent(MinIntegerValue.class);
            int maxValue = checkMaxValue ? ((MaxIntegerValue) field.getAnnotation(MaxIntegerValue.class)).value() : 0;

            if (isRequired || normalizeSpacing || needToTrim || toLowerCase || checkMinLength || checkMaxLength
                    || checkMinValue || checkMaxValue) {
                valueCheckers.add(new FieldValueConstraintChecker() {
                    @Override
                    public void checkFieldsAgainstConstraints(DataModel dataModelObj) {
                        if (!fieldIsPrimitiveType) {
                            Object fieldValue;
                            try {
                                fieldValue = field.get(dataModelObj);
                            } catch (IllegalArgumentException | IllegalAccessException e) {
                                throw new RuntimeException(e);
                            }

                            if (isRequired) {
                                if (fieldValue == null
                                        || (fieldValue instanceof String && ((String) fieldValue).isEmpty())) {
                                    throw new RuntimeException("Required field " + dataModelClass.getName() + "."
                                            + fieldName + " is null or empty");
                                }
                            }

                            if (checkMinValue || checkMaxValue) {
                                // Handle min/max value constraint on Integer-typed field
                                Integer intValue = (Integer) fieldValue;
                                if (checkMinValue && (intValue == null || intValue < minValue)) {
                                    throw new RuntimeException("Parameter " + dataModelClass.getName() + "."
                                            + fieldName + " must have minimum value " + minValue + ", but has value "
                                            + intValue);
                                } else if (checkMaxValue && (intValue == null || intValue > maxValue)) {
                                    throw new RuntimeException("Parameter " + dataModelClass.getName() + "."
                                            + fieldName + " must have maximum value " + maxValue + ", but has value "
                                            + intValue);
                                }

                            } else if (isString) {
                                // Handle constraints, trimming and normalization on String-typed fields
                                String str = (String) fieldValue;
                                if (normalizeSpacing && str != null) {
                                    str = StringUtils.normalizeSpacing(str);
                                }
                                if (needToTrim && str != null) {
                                    str = StringUtils.unicodeTrim(str);
                                }
                                if (toLowerCase && str != null) {
                                    String lower = str.toLowerCase();
                                    if (!lower.equals(str)) {
                                        str = lower;
                                    }
                                }
                                if (isEmail) {
                                    // If there's an @Email annotation, the field cannot be null
                                    if (str == null
                                    // Validate email addresses
                                            || !WebUtils.isValidEmailAddr(str)) {
                                        throw new RuntimeException("Value of field " + dataModelClass.getName() + "."
                                                + fieldName + " is not a valid email address"
                                                + (str == null ? ", its value is null" : ""));
                                    }
                                }
                                // Check regex after normalizing spacing and trimming
                                if (isRegex) {
                                    if (!regex.matcher(str).matches()) {
                                        throw new RuntimeException("Value for parameter " + fieldName
                                                + " does not match the regex constraint");
                                    }
                                }
                                // Check min/max len after normalizing spacing and trimming
                                if (checkMinLength && (str == null || str.length() < minLength)) {
                                    throw new RuntimeException("Value of field " + dataModelClass.getName() + "."
                                            + fieldName + " must have minimum length "
                                            + (needToTrim ? "(after trimming) " : "") + "of " + minLength);
                                } else if (checkMaxLength && (str == null || str.length() < maxLength)) {
                                    throw new RuntimeException("Value of field " + dataModelClass.getName() + "."
                                            + fieldName + " must have maximum length "
                                            + (needToTrim ? "(after trimming) " : "") + "of " + maxLength
                                            + (str == null ? ", but the value is null" : ""));
                                }

                                // If value of field has changed, update field
                                // This has a side-effect (i.e. it modifies the field value in-place)
                                if (fieldValue != null && !fieldValue.equals(str)) {
                                    try {
                                        field.set(dataModelObj, str);
                                    } catch (IllegalArgumentException | IllegalAccessException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            }

                        } else if ((checkMinValue || checkMaxValue) && fieldType == Integer.TYPE) {
                            // Handle min/max value constraint on int-typed field
                            int intValue;
                            try {
                                intValue = field.getInt(dataModelObj);
                            } catch (IllegalArgumentException | IllegalAccessException e) {
                                throw new RuntimeException(e);
                            }
                            if (checkMinValue && intValue < minValue) {
                                throw new RuntimeException("Parameter " + dataModelClass.getName() + "." + fieldName
                                        + " must have minimum value " + minValue + ", but has value " + intValue);
                            } else if (checkMaxValue && intValue > maxValue) {
                                throw new RuntimeException("Parameter " + dataModelClass.getName() + "." + fieldName
                                        + " must have maximum value " + maxValue + ", but has value " + intValue);
                            }
                        }
                    }
                });
            }
        }
        constraintCheckersForClass.put(dataModelClass, valueCheckers);
    }

    /**
     * Check the values of fields against the constraint annotations. Called when DataModel objects are bound from a
     * POST request, and when DBModel.save() is called to save an object in the database, so needs to be fast.
     * 
     * Side-effects: forces the values of fields marked with Email annotation to lowercase; trims String fields not
     * marked with NoTrim; normalizes spacing in fields marked with NormalizeSpacing.
     * 
     * @throws RuntimeException
     *             if one or more constraints are violated.
     */
    public static void checkFieldValuesAgainstConstraints(DataModel dataModelObject) throws RuntimeException {
        Class<? extends DataModel> dataModelClass = dataModelObject.getClass();
        ArrayList<FieldValueConstraintChecker> checkers = constraintCheckersForClass.get(dataModelClass);
        if (checkers != null) {
            // Class has not yet been checked against annotations (won't happen in the web server, because DataModel
            // subclasses are all registered on startup, but will happening if using this class from non-server code)
            checkFieldTypesAgainstAnnotations(dataModelClass);
            // Try getting the constraint checkers again now that the class has been registered
            checkers = constraintCheckersForClass.get(dataModelClass);
        }
        // Check the values of all fields in this object against constraints
        for (FieldValueConstraintChecker checker : checkers) {
            checker.checkFieldsAgainstConstraints(dataModelObject);
        }
    }

}
