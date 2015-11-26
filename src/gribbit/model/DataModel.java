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
import gribbit.http.logging.Log;
import gribbit.http.request.Request;
import gribbit.model.util.FieldChecker;
import gribbit.response.exception.BadRequestException;
import gribbit.response.exception.InternalServerErrorException;
import gribbit.response.exception.RequestHandlingException;
import gribbit.server.GribbitServer;
import gribbit.util.JSON;
import io.netty.handler.codec.http.multipart.FileUpload;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;

/**
 * A model that can be bound from the parameters in a POST request, and/or that can be used to populate an HTML
 * template.
 * 
 * You can apply data constraint annotations to the fields of subclasses of DataModel, such MaxLength or Email, and
 * these constraints will be applied when submitted form data is bound to the model.
 */
public abstract class DataModel {

    /**
     * Bind formModelInstance data object from an HTTP request. The name of each field in formModelInstance object
     * is looked up in the POST parameters. If the named value is found, it is trimmed (unless annotated with
     * NoTrim), and integers etc. are parsed as needed before the value is stored in the field. Fields marked with
     * Required constraint have to not only not be null, but also not be empty after trimming. Note that any
     * constraint such as MinLength also implies Required. (NormalizeSpacing is a data transformation annotation,
     * not a constraint, so it will only be applied if a value is provided.)
     * 
     * @param request
     *            The HTTP request
     * @param formModel
     *            The form model to bind
     * @throws AppException
     *             if any of the constraint annotations are not specified
     */
    public void bindFromPost(Request request) throws RequestHandlingException {

        // Match field names against POST param names, and set values of fields whenever there is a match
        HashSet<String> unusedPostParams = new HashSet<String>(request.getPostParamNames());
        for (Field field : getClass().getFields()) {
            String fieldName = field.getName();
            unusedPostParams.remove(fieldName);

            if (FieldChecker.fieldIsPrivate(field, /* checkGet = */false, /* checkSet = */true)) {
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
                            if (FieldChecker.fieldIsRequired(field)) {
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
                    throw new InternalServerErrorException(request, "Could not set field " + getClass().getName()
                            + "." + fieldName + " to the value passed in the request", e);
                }
            }
        }

        // Check that the values in the fields satisfy the constraints
        try {
            GribbitServer.siteResources.checkFieldValuesAgainstConstraintAnnotations(this);
        } catch (Exception e) {
            throw new BadRequestException(request, "Form values do not satisfy constraints: " + e.getMessage());
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
     * Render this DataModel as JSON, skipping fields marked with @Private or @OnlyReceive, and id fields of DBModel
     * objects. This produces a JSON rendering that may be served over a Web connection without exposing internal
     * server state.
     */
    public String toJSON() {
        return JSON.toJSON(this);
    }

    /** Render a DataModel object as a JSON string. */
    @Override
    public String toString() {
        return toJSON();
    }

}
