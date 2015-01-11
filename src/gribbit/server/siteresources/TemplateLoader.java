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

import gribbit.auth.CSRF;
import gribbit.model.DataModel;
import gribbit.model.field.annotation.Email;
import gribbit.model.field.annotation.IsURL;
import gribbit.model.field.annotation.MaxIntegerValue;
import gribbit.model.field.annotation.MaxLength;
import gribbit.model.field.annotation.MinIntegerValue;
import gribbit.model.field.annotation.MinLength;
import gribbit.model.field.annotation.NoTrim;
import gribbit.model.field.annotation.NormalizeSpacing;
import gribbit.model.field.annotation.Regex;
import gribbit.util.Log;
import gribbit.util.StringUtils;
import gribbit.util.WebUtils;
import io.netty.handler.codec.http.multipart.FileUpload;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

public class TemplateLoader {
    private SiteResources siteResources;

    private DataModelLoader dataModelLoader;

    private StringBuilder headContent = new StringBuilder(16384), tailContent = new StringBuilder(16384);

    private HashMap<String, List<Node>> templateNameToTemplateNodes = new HashMap<>();

    private HashMap<Class<? extends DataModel>, List<Node>> templateClassToTemplateNodes = new HashMap<>();

    private HashMap<String, String> templateNameToTemplateStr = new HashMap<>();

    private HashSet<String> customInlineElements = new HashSet<>();

    /** Templates that consist of complete HTML docs, with head and body elements */
    private ArrayList<Document> pageTemplateDocs = new ArrayList<>();

    /** Pattern for template parameters, of the form "${name}" */
    public static final Pattern TEMPLATE_PARAM_PATTERN = Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9_]*)\\}");

    // -----------------------------------------------------------------------------------------------------

    TemplateLoader(SiteResources siteResources, DataModelLoader dataModelLoader) {
        this.siteResources = siteResources;
        this.dataModelLoader = dataModelLoader;
    }

    /**
     * Return custom Polymer element tagnames whose templates consist of only inline elements, not block elements (for
     * prettyprinting)
     */
    HashSet<String> getCustomInlineElements() {
        return customInlineElements;
    }

    /**
     * Register a custom element (e.g. a Polymer or X-Tags element) as an inline element for prettyprinting (otherwise
     * it will be prettyprinted as a block element, with newlines and indentation).
     */
    void registerCustomInlineElement(String elementName) {
        if (!elementName.contains("-")) {
            throw new IllegalArgumentException("Custom element names must include a hyphen, got \"" + elementName
                    + "\"");
        }
        customInlineElements.add(elementName);
    }

    /** Return the template corresponding to the given template class as a list of nodes, or null if it doesn't exist. */
    List<Node> getTemplateNodes(Class<? extends DataModel> templateClass) {
        return templateClassToTemplateNodes.get(templateClass);
    }

    /** Return the templates corresponding to each class as HTML strings, for clientside rendering. */
    public HashMap<String, String> getTemplateNameToTemplateStr() {
        return templateNameToTemplateStr;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Add constraints to an HTML form given the constraint annotations on the fields of the given DataModel object.
     * Also checks the fields of the DataModel to make sure that there is an input in the form with the appropriate
     * name.
     */
    private static void addConstraintsToForm(String templateName, Element formElement,
            Class<? extends DataModel> formModel) {
        // Get all input elements in this form, grouped by matching name (radio buttons have
        // multiple input fields with the same value in the "name" attribute)
        HashMap<String, ArrayList<Element>> nameToInputs = new HashMap<>();
        for (Element elt : formElement.getAllElements()) {
            String name;
            if (elt.tagName().equals("select")) {
                // Ignore name on <select> elements, instead read parent name when <option> elements
                // are encountered
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

            // Don't add field to form if the field is private or it's marked with OnlySend, i.e. if it cannot
            // be bound to a field when the form is submitted
            if (!DataModelLoader.fieldIsPrivate(field, /* checkSendability = */false, /* checkReceivability = */true)) {
                Class<?> fieldType = field.getType();
                boolean required = DataModelLoader.fieldIsRequired(field);

                // Check if there is an input element with a name attribute that matches the
                // DataModel field name
                ArrayList<Element> eltsWithNameMatchingField = nameToInputs.get(field.getName());
                if (eltsWithNameMatchingField == null) {
                    // DataModel has an extra field that the form doesn't have
                    if (required) {
                        throw new RuntimeException("Template \"" + templateName + "\" contains a form with id=\""
                                + formElement.attr("id") + "\" that is bound to the model " + formModel.getName()
                                + ", but the model contains a required field named \"" + field.getName()
                                + "\", and there is no input with that name in the form");
                    } else {
                        // Warn about extra field in form model that is not in form template
                        Log.warning("Template \"" + templateName + "\" contains a form with id=\""
                                + formElement.attr("id") + "\" that is bound to the model " + formModel.getName()
                                + ", but the model contains an extra field named \"" + field.getName()
                                + "\" that is not in the form");
                    }

                } else {
                    // Go through all input elements that have the same name as this DataModel field,
                    // and update the element to have constraint attributes matching the DataModel
                    // constraint annotations
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
                                //TODO: make sure that Java regexes are compatible with JS regexes.
                                // See also http://html5pattern.com/
                                matchingElt.attr("pattern", ((Regex) annotation).regex());
                            } else if (annotationType == NormalizeSpacing.class) {
                                needsSpaceNormalization = true;
                            } else if (annotationType == NoTrim.class) {
                                needsTrimming = false;
                            }
                        }

                        // Add type constraints to form inputs based on the type of fields in the DataModel
                        if (fieldType == LocalDate.class) {
                            matchingElt.attr("type", "date");
                            // TODO: also add regex pattern to constrain date to the isoDate format?
                            // TODO: (This doesn't seem to work currently.)
                            // TODO: add date picker popup.

                        } else if (fieldType == Integer.class || fieldType == Integer.TYPE || fieldType == Long.class
                                || fieldType == Long.TYPE || fieldType == Short.class || fieldType == Short.TYPE
                                || fieldType == Float.class || fieldType == Float.TYPE || fieldType == Double.class
                                || fieldType == Double.TYPE) {
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
                                throw new RuntimeException("Field \"" + fieldName + "\" in form \""
                                        + formElement.attr("id") + "\" in template \"" + templateName
                                        + "\" needs to be of type \"checkbox\", since "
                                        + "it is bound to a Boolean field");

                            matchingElt.attr("type", "checkbox");

                        } else if (fieldType.isEnum()) {
                            // Enum-typed fields are bound to radio buttons

                            if (!matchingEltType.isEmpty()
                                    && !(matchingEltType.equals("radio") || matchingEltType.equals("select"))) {
                                throw new RuntimeException("Field \"" + fieldName + "\" in form \""
                                        + formElement.attr("id") + "\" in template \"" + templateName
                                        + "\" needs to be of type \"radio\", "
                                        + "since it is bound to an enum-typed field");
                            }

                            // Make sure all radio or option values map to a valid enum value
                            String radioVal = matchingElt.attr("value");
                            if (radioVal == null) {
                                throw new RuntimeException("Missing attribute \"value\" for field \"" + fieldName
                                        + "\" in form \"" + formElement.attr("id") + "\" of template \"" + templateName
                                        + "\"");
                            }
                            boolean enumOK = false;
                            try {
                                @SuppressWarnings({ "unchecked", "rawtypes", "unused" })
                                Enum<?> enumVal = Enum.valueOf((Class<Enum>) fieldType, radioVal);
                                enumOK = true;
                            } catch (IllegalArgumentException e) {
                            }
                            if (!enumOK) {
                                throw new RuntimeException("Illegal value \"" + radioVal
                                        + "\" for radio or option field \"" + fieldName + "\" in form \""
                                        + formElement.attr("id") + "\" of template \"" + templateName + "\"");
                            }

                        } else if (fieldType == String.class) {
                            // Password fields get marked as type="password", everything else that is 
                            // bound to a String gets marked as type="text"

                            if (fieldName.equals("password")) {
                                matchingElt.attr("type", "password");
                            } else {
                                if (isEmail) {
                                    matchingElt.attr("type", "email");
                                } else {
                                    matchingElt.attr("type", "text");
                                }

                                //FIXME: need to validate minlength and maxlength using JS, and reflect 
                                // validity in Bootstrap. For all forms:
                                // -- load the following jQuery plugin if there are forms on the page:
                                //    http://docs.jquery.com/Plugins/Validation
                                // -- add in head: $("#commentForm").validate();
                                if (minLength != null) {
                                    matchingElt.attr("minlength", "" + minLength);
                                }
                                if (maxLength != null) {
                                    matchingElt.attr("maxlength", "" + maxLength);
                                }

                                if (needsTrimming) {
                                    //TODO: Add JS that trims spacing in form fields upon focus loss
                                    // unless @NoTrim annotation is present
                                }
                                if (needsSpaceNormalization) {
                                    //TODO: Add JS that auto-normalizes spacing in form fields if
                                    // @NormalizeSpacing annotation is present
                                }
                            }

                        } else if (fieldType == Character.class || fieldType == Character.TYPE) {
                            matchingElt.attr("type", "text").attr("maxlength", "1");

                        } else if (fieldType == FileUpload.class) {
                            // Force form type to multipart/form-data if one of the DataModel fields
                            // is of type FileUpload
                            matchingElt.attr("type", "file");
                            formElement.attr("enctype", "multipart/form-data");

                        } else {
                            throw new RuntimeException("Illegal type " + fieldType.getName() + " for field "
                                    + fieldName + " in " + DataModel.class.getSimpleName() + " subclass");
                        }
                    }
                }
            }
        }

        for (String name : unmappedInputNames) {
            Log.warning("Template \"" + templateName + "\" but has an extra input element named \"" + name
                    + "\" that is not present as a public field of " + formModel.getName());
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Recursively cross-check HTML template parameters against fields in the correspondingly-named DataModel subclass.
     */
    private static void crossCheckDataModelAndView(SiteResources siteResources, String templateName,
            Class<? extends DataModel> templateDataModel, Node node, HashSet<String> paramNamesUsedInHTMLOut) {

        if (node instanceof Element) {
            Element element = ((Element) node);
            // Check if the DataModel fields that have the same name as template params are accessible
            String tagName = element.tagName().toLowerCase();

            // Make sure tag name doesn't contain a parameter
            if (TemplateLoader.TEMPLATE_PARAM_PATTERN.matcher(tagName).find()) {
                throw new RuntimeException("The template \"" + templateName
                        + "\" contains an element with a parameter in a tag name: " + tagName);
            }

            // Check attributes of element
            for (Attribute a : element.attributes()) {
                String attrName = a.getKey().toLowerCase();
                String attrValue = a.getValue();
                StringBuilder attrValueWithoutParams = new StringBuilder();

                // Look for template params in attribute values
                Matcher matcher = TemplateLoader.TEMPLATE_PARAM_PATTERN.matcher(attrValue);
                boolean attrValueContainsParam = false;
                boolean isCustomURLAttr = false;
                int firstMatchStart = 0, lastMatchEnd = 0;
                while (matcher.find()) {
                    if (!attrValueContainsParam) {
                        // First match
                        firstMatchStart = matcher.start();
                        attrValueContainsParam = true;
                    }
                    attrValueWithoutParams.append(attrValue.subSequence(lastMatchEnd, matcher.start()));
                    lastMatchEnd = matcher.end();

                    // Check that the field in the DataModel class with the same name as the parameter
                    // is publicly accessible
                    String paramName = matcher.group(1);
                    Field field = DataModelLoader.checkFieldAccessible(templateName, templateDataModel, /* fieldName = */
                            paramName, attrName);
                    paramNamesUsedInHTMLOut.add(paramName);

                    // Check if the field is to be substituted into a custom URL attribute
                    if (field.isAnnotationPresent(IsURL.class)) {
                        isCustomURLAttr = true;
                    }
                }
                attrValueWithoutParams.append(attrValue.subSequence(lastMatchEnd, attrValue.length()));

                // Check non-parameter content of id, name and class attributes
                if (attrValueWithoutParams.length() > 0) {
                    if (attrName.equals("id") || attrName.equals("name") || attrName.equals("class")) {
                        // OWASP Rule #1:
                        //     Strictly validate unsafe attributes such as background, id and name.
                        //
                        // (We don't worry about the unsafe "background" attribute here, it is simply
                        // blocked by not being listed in XSS_SAFE_ATTRS, as checked below.)
                        // 
                        // See also http://goo.gl/fKZhFA -- we disallow '.' and ':' because they cause
                        // problems with jQuery.
                        if (!(attrName.equals("class") ? WebUtils.VALID_CSS_ID : WebUtils.VALID_HTML_NAME_OR_ID)
                                .matcher(attrValueWithoutParams).matches()) {
                            throw new RuntimeException("The template \"" + templateName
                                    + "\" contains bad characters in id, name or class attribute value: " + attrName
                                    + "=\"" + attrValue + "\"");
                        }
                        if (StringUtils.containsUppercaseChar(attrValueWithoutParams)) {
                            throw new RuntimeException("The template \"" + templateName
                                    + "\" contains uppercase characters in attribute value: " + attrName + "=\""
                                    + attrValue + "\", but browsers implement "
                                    + "case sensitivity differently. Identifiers for id, "
                                    + "name and class should all be lowercase.");
                        }
                    }
                }

                if (attrValueContainsParam) {
                    // OWASP Rule #2:
                    //     Only place untrusted data into a whitelist of safe attributes.
                    if (isCustomURLAttr || WebUtils.isURLAttr(tagName, attrName)) {
                        // URLs will be tested for validity later
                    } else if (attrName.equals("id") || attrName.equals("name")) {
                        // name and id are XSS-unsafe, because injected text can be made to 
                        // refer to any element on the page. Mitigate the effect by requiring
                        // that the attr value have a non-parameterized prefix.
                        if (firstMatchStart == 0) {
                            throw new RuntimeException("The template \"" + templateName
                                    + "\" contains a template parameter in the value "
                                    + "of an XSS-unsafe attribute \"" + attrName
                                    + "\". To parameterize id and name attributes in particular, "
                                    + "the parameter must be prefixed with some non-parameter value "
                                    + "to mitigate the impact of an injection attack, e.g. "
                                    + "<div id=\"widget${widgetNum}\"> is OK, but "
                                    + "<div id=\"${widgetName}\"> is not.");
                        }
                    } else if (!WebUtils.XSS_SAFE_ATTRS.contains(attrName)) {
                        // Disable XSS safety check for custom Polymer element attributes -- it is the
                        // user's responsibility to ensure that a given attribute is XSS-safe with Polymer
                        // elements. If attributes are not provably XSS-safe, they should not be set using
                        // a template parameter value.
                        //
                        // Note that custom elemnets are an attack vector if the programmer is not
                        // careful, because custom elements' attribute values can be used to insert content
                        // anywhere into the shadow DOM, so we can't contextually escape these values.
                        if (!tagName.contains("-")) {
                            // Throw exception for non-whitelisted attrs.
                            throw new RuntimeException("Template \"" + templateName
                                    + "\" contains a template parameter "
                                    + "in the value of an XSS-unsafe attribute \"" + attrName + "\"");
                        }
                    }
                    // OWASP Rule #3:
                    //     JavaScript Escape Before Inserting Untrusted Data into JavaScript Data Values.
                    // OWASP Rule #4:
                    //     CSS Escape And Strictly Validate Before Inserting Untrusted Data into HTML
                    //     Style Property Values.
                    // We currently completely disallow inserting params into style and script
                    // attributes for these safety reasons. 
                    if (tagName.equals("applet") || tagName.equals("object")) {
                        throw new RuntimeException("Tried to use a template param inside <" + tagName
                                + "> in template \"" + templateName + "\" -- this is unsafe");
                    }
                    if (attrName.equals("style") || attrName.equals("data") || attrName.equals("action")
                            || attrName.startsWith("on")) {
                        // TODO: double-check there are not more attributes that can take style or script
                        throw new RuntimeException("Tried to use a template param in XSS-unsafe attribute \""
                                + attrName + "\" in template \"" + templateName + "\"");
                    }

                    if (attrName.equals("id") || attrName.equals("name") || attrName.equals("class")) {
                        if (attrValue.contains(".") || attrValue.contains(":")) {
                            throw new RuntimeException("Value of attribute " + attrName + " in template \""
                                    + templateName + "\" contains '.' or ':', which can cause problems with jQuery");
                        }
                        a.setValue(StringUtils.unicodeTrim(StringUtils.normalizeSpacing(attrValue)));
                    }
                }
            }

            // Cross-check DataModel fields that have type DataModel against form inputs where the form's
            // id attribute matches the name of the DataModel 
            if (tagName.equals("form")) {

                // Add CSRF input to form with placeholder value that will be replaced with the real CSRF
                // value when the page is served
                Elements csrfElts = element.getElementsByAttributeValue("name", CSRF.CSRF_PARAM_NAME);
                if (csrfElts.size() > 0) {
                    throw new RuntimeException("Form in template " + templateName
                            + " should not include its own elements with name " + CSRF.CSRF_PARAM_NAME);
                }
                Element csrfElt = element.appendElement("input");
                csrfElt.attr("name", CSRF.CSRF_PARAM_NAME);
                csrfElt.attr("type", "hidden");
                csrfElt.attr("value", CSRF.CSRF_TOKEN_PLACEHOLDER);

                // Check if the id attribute of the form matches a DataModel-typed field in the template 
                // class associated with the form. If so, this field's own fields are cross-checked against
                // the input values in the form, as a special case of template parameter matching. 
                String id = element.attr("id");
                if (id != null && !id.isEmpty()) {
                    Field field = null;
                    try {
                        field = templateDataModel.getField(id);
                    } catch (NoSuchFieldException e1) {
                        // This from doesn't match a field in the DataModel object, so it must be used for
                        // some other purpose
                    } catch (SecurityException e1) {
                        throw new RuntimeException("Form with id \"" + id + "\" in template \"" + templateName
                                + "\" matches name of field in class " + templateDataModel.getName()
                                + " but that field is not accessible");
                    }
                    if (field != null) {
                        // This form's id attribute matches the name of a DataModel-typed field
                        // in templateClass

                        // Make sure field is accessible, and that it's a DataModel subclass
                        DataModelLoader.checkFieldAccessible(templateName, templateDataModel, id, null);

                        // Check that the field that matches the form id is a DataModel subclass
                        if (DataModel.class.isAssignableFrom(field.getType())) {
                            @SuppressWarnings("unchecked")
                            Class<? extends DataModel> formModel = (Class<? extends DataModel>) field.getType();

                            // The form id is a special case of template parameter name
                            paramNamesUsedInHTMLOut.add(id);

                            // Cross-check the names of inputs in the form with fields in the DataModel,
                            // and copy any constraint annotations in this DataModel across to the elements
                            // in the form.
                            addConstraintsToForm(templateName, element, formModel);

                            // If form is to be submitted via POST, try substituting the route URL for the
                            // RestHandler that handles the POST into the action attribute
                            String submitURI = siteResources.routeURIForDataModel(formModel);
                            String method = element.attr("method");
                            if (method.isEmpty()) {
                                // Method was not specified
                                if (submitURI == null) {
                                    // Fill in missing (default) GET action if there is no POST handler
                                    // that accepts this form
                                    method = "GET";
                                    element.attr("method", method);
                                } else {
                                    // Fill in missing POST action if there is a POST handler that accepts
                                    // this form. This overrides the HTML default action of GET, but it is
                                    // more likely what was intended, and it is safer since any constraint
                                    // annotations will be checked on POST requests, whereas for GET
                                    // requests the user has to do binding and param checking themselves.
                                    method = "POST";
                                    element.attr("method", method);
                                }
                            } else {
                                // Method was specified
                                if (method.equalsIgnoreCase("GET")) {
                                    if (submitURI != null) {
                                        Log.warning("Form in template \"" + templateName + "\" has id \"" + id
                                                + "\" which matches a param of type " + formModel.getName()
                                                + ", and that class is accepted as a " + "POST param on the route "
                                                + submitURI + " , but this form is submitted with GET, "
                                                + "not POST. This means the post() method on "
                                                + "that route will not receive, bind or "
                                                + "validate the submitted form values, they "
                                                + "will have to be manually parsed by the "
                                                + "user in the get() method.");
                                    }
                                } else if (method.equalsIgnoreCase("POST")) {
                                    // Form is submitted via POST, and field type that matches form id is
                                    // accepted by the post() method of a RestHandler

                                    String action = element.attr("action");
                                    if (action.isEmpty()) {
                                        // Fill in missing action attribute value with route path of the
                                        // RestHandler that has a post() method that takes a parameter of
                                        // the type bound to this form
                                        element.attr("action", submitURI);
                                    } else if (!action.equals(submitURI)) {
                                        // Maybe this is heavy-handed (probably the user should be able to
                                        // override with a different URI), but they can manually construct
                                        // a form that takes regular HTML params if they want to submit to
                                        // a URI on say a different server, they don't need to fill in the
                                        // form using the DataModel-to-form binding.
                                        Log.warning("Form in template \"" + templateName + "\" has id \"" + id
                                                + "\" which matches a param of type " + formModel.getName()
                                                + ", and that class is accepted as a POST param on the route "
                                                + submitURI + " , but this form is submitted to the route " + action
                                                + " , not " + submitURI + " . Overriding " + action + " with "
                                                + submitURI);
                                        element.attr("action", submitURI);
                                    }

                                } else {
                                    throw new RuntimeException("Invalid form submission method \"" + method
                                            + "\" in template \"" + templateName + "\"");
                                }
                            }

                        } else {
                            throw new RuntimeException("Template \"" + templateName + "\" contains a form with id \""
                                    + id + "\" that matches a field in class " + templateDataModel.getName()
                                    + ", however that field is not a subclass of " + DataModel.class.getName());
                        }
                    }
                }
            }

            // Recurse on child nodes
            for (Node childNode : element.childNodes()) {
                crossCheckDataModelAndView(siteResources, templateName, templateDataModel, childNode,
                        paramNamesUsedInHTMLOut);
            }

        } else if (node instanceof TextNode) {
            String text = ((TextNode) node).text();

            // Check for attempt to put param in the element name, e.g. <${elt}>,
            // or malformed HTML like <>. In these cases, Jsoup just inserts a text node with
            // unescaped text. 
            if (text.indexOf('<') > 0 || text.indexOf('>') > 0) {
                throw new RuntimeException("The template \"" + templateName + "\" contains invalid HTML: " + text);
            }

            // Look for template params in text
            Matcher matcher = TemplateLoader.TEMPLATE_PARAM_PATTERN.matcher(text);
            boolean containsParam = false;
            while (matcher.find()) {
                containsParam = true;
                String paramName = matcher.group(1);
                DataModelLoader.checkFieldAccessible(templateName, templateDataModel, paramName, null);
                paramNamesUsedInHTMLOut.add(paramName);
            }
            if (containsParam) {
                String parentTagName = node.parent() == null ? "body" : ((Element) node.parent()).tagName();

                if (parentTagName.equals("#comment")) {
                    // Ignore params in comments
                    // TODO: these could be supported by escaping "<!--" and "-->"
                } else {
                    // OWASP Rule #3:
                    //     JavaScript Escape Before Inserting Untrusted Data into JavaScript
                    //     Data Values
                    // OWASP Rule #4:
                    //     CSS Escape And Strictly Validate Before Inserting Untrusted Data into
                    //     HTML Style Property Values.
                    //
                    // We currently completely disallow inserting params into style and script
                    // attributes for safety reasons. All parameter insertion into JS contexts or
                    // sub-contexts is disabled right now.
                    //
                    // See https://www.owasp.org/index.php/DOM_based_XSS_Prevention_Cheat_Sheet :
                    // "DOM based XSS is extremely difficult to mitigate against because of its
                    // large attack surface and lack of standardization across browsers."
                    //
                    // TODO: CSS parsing is not supported by jsoup, so we can't do context-sensitive
                    // escaping in CSS. Therefore, parameters are not supported in style tags or
                    // attributes at this time. CSS parsers:
                    // -- http://cssparser.sourceforge.net/
                    // -- https://code.google.com/p/phloc-css/
                    // -- http://www.w3.org/Style/CSS/SAC/
                    // -- https://github.com/corgrath/osbcp-css-parser
                    // Note: see http://goo.gl/n0sWup -- sanitizers can be used to let through
                    // only sanitized CSS.
                    if (parentTagName.equals("script") || parentTagName.equals("style")
                            || parentTagName.equals("applet") || parentTagName.equals("object")) {
                        throw new RuntimeException("The template \"" + templateName
                                + "\" contains a template param inside the XSS-unsafe element <" + parentTagName + ">");
                    }
                }
            }
        }
    }

    /**
     * Recursively cross-check HTML template parameters against fields in the correspondingly-named DataModel subclass.
     */
    private static void crossCheckDataModelAndView(SiteResources siteResources, String templateName,
            Class<? extends DataModel> templateDataModel, List<Node> templateNodes) {
        HashSet<String> paramNamesUsedInHTML = new HashSet<>();
        for (Node node : templateNodes) {
            crossCheckDataModelAndView(siteResources, templateName, templateDataModel, node, paramNamesUsedInHTML);
        }

        // Get names of template fields in DataModel
        HashSet<String> fieldNamesInClass = new HashSet<>();
        for (Field field : templateDataModel.getFields()) {
            // Private fields don't get bound to templates
            if (!DataModelLoader.fieldIsPrivate(field, /* checkSendability = */true, /* checkReceivability = */false)) {
                fieldNamesInClass.add(field.getName());
            }
        }

        // Make sure there's a 1-to-1 correspondence between param names used in this HTML template
        // and field names in the associated DataModel subclass
        HashSet<String> paramNamesOnlyInHTML = new HashSet<>(paramNamesUsedInHTML);
        paramNamesOnlyInHTML.removeAll(fieldNamesInClass);
        HashSet<String> fieldNamesOnlyInClass = new HashSet<>(fieldNamesInClass);
        fieldNamesOnlyInClass.removeAll(paramNamesUsedInHTML);
        if (!paramNamesOnlyInHTML.isEmpty()) {
            throw new RuntimeException("There is no correspondingly-named public field in class "
                    + templateDataModel.getName() + " for the HTML parameter name"
                    + (paramNamesOnlyInHTML.size() > 1 ? "s" : "") + " "
                    + StringUtils.join(paramNamesOnlyInHTML, ", ", s -> "\"" + s + "\"") + " in template \""
                    + templateName + "\"");
        }

        if (!fieldNamesOnlyInClass.isEmpty()) {
            throw new RuntimeException(
                    "There is no correspondingly-named parameter or form field name in the HTML template \""
                            + templateName + "\" for the public field" + (fieldNamesOnlyInClass.size() > 1 ? "s" : "")
                            + " " + StringUtils.join(fieldNamesOnlyInClass, ", ", s -> "\"" + s + "\"") + " in class "
                            + templateDataModel.getName());
        }
    }

    // -----------------------------------------------------------------------------------------------------

    private static boolean isLocalURI(String hrefURI) {
        return
        //Not empty/null/'#'
        hrefURI != null && !hrefURI.isEmpty() && !hrefURI.startsWith("#") //
                // Not external/absolute URI
                && !WebUtils.EXTERNAL_URI.matcher(hrefURI).matches() //
                // Not template param
                && !hrefURI.contains("${") //
                // Not Polymer param
                && !hrefURI.contains("{{");
    }

    /**
     * Resolve a relative path in an URI attribute to an absolute path.
     * 
     * @param hrefURI
     *            the URI to resolve, e.g. "../css/main.css"
     * @param baseURI
     *            the base URI to resolve relative to, without a trailing slash, e.g. "/site/res"
     * @return the absolute path of the URI, e.g. "/site/css/main.css"
     */
    private static String resolveHREF(String hrefURI, String baseURI) {
        // Return original URI if it is empty, starts with "#", is a template param, or starts with
        // a Polymer param (i.e. starts with "{{")
        if (isLocalURI(hrefURI)) {
            // Build new path for the linked resource
            StringBuilder hrefURIResolved = new StringBuilder(hrefURI.startsWith("//") ? "//"
                    : hrefURI.startsWith("/") ? "/" : baseURI);
            for (CharSequence part : StringUtils.splitAsList(hrefURI, "/")) {
                if (part.length() == 0 || part.equals(".")) {
                    // Ignore
                } else if (part.equals("..")) {
                    // Move up one level (ignoring if we get back to root)
                    int lastIdx = hrefURIResolved.lastIndexOf("/");
                    hrefURIResolved.setLength(lastIdx < 0 ? 0 : lastIdx);
                } else {
                    if (hrefURIResolved.length() > 0 && hrefURIResolved.charAt(hrefURIResolved.length() - 1) != '/') {
                        hrefURIResolved.append('/');
                    }
                    hrefURIResolved.append(part);
                }
            }
            return hrefURIResolved.toString();
        }
        return hrefURI;
    }

    // -----------------------------------------------------------------------------------------------------

    /** Got an HTML file on the classpath, or a static final inline template. */
    void registerWebResource(String absolutePath, String relativePath, String html) {
        try {
            if (absolutePath.endsWith("/head-content.html")) {
                // Load header HTML content from the classpath
                headContent.append(html);

            } else if (absolutePath.endsWith("/tail-content.html")) {
                // Load footer HTML content from the classpath
                tailContent.append(html);

            } else {
                // Use the leaf name (less the file extension) as the name of the template
                String idLeaf = relativePath.substring(relativePath.lastIndexOf('/') + 1);
                int lastDot = idLeaf.lastIndexOf('.');
                if (lastDot < 0) {
                    lastDot = idLeaf.length();
                }
                String templateName = idLeaf.substring(0, lastDot);

                // See if this is a whole-page HTML document, as opposed to an HTML fragment
                int firstTagIdx = html.indexOf("<");
                boolean isWholeDocument = firstTagIdx >= 0
                        && ((html.length() >= 5 && html.substring(firstTagIdx, firstTagIdx + 5).toLowerCase()
                                .equals("<html")) //
                        || (html.length() >= 9 && html.substring(firstTagIdx, firstTagIdx + 9).toLowerCase()
                                .equals("<!doctype")));

                // Page templates need to be run through Jsoup.parse(), not Jsoup.parseBodyFragment()
                Document doc = isWholeDocument ? Jsoup.parse(html) : Jsoup.parseBodyFragment(html);

                String baseURI = "/" + relativePath;
                for (Element e : doc.getAllElements()) {
                    // Strip HTML comments
                    ArrayList<Node> childNodes = new ArrayList<>(e.childNodes());
                    for (Node n : childNodes) {
                        if (n instanceof Comment) {
                            String commentStr = ((Comment) n).getData();
                            // Remove all comments that do not start with "[if " or " [if "
                            // (which test for IE versions), and which do not contain "@license"
                            // (since we shouldn't be stripping away license information)
                            if (!commentStr.startsWith("[if ") && !commentStr.startsWith(" [if ")
                                    && !commentStr.contains("@license")) {
                                n.remove();
                            }
                        }
                    }

                    // Make URLs absolute (this is important for hash URIs to be able to be properly resolved)
                    if (e.hasAttr("href")) {
                        e.attr("href", resolveHREF(e.attr("href"), baseURI));
                    }
                    if (e.hasAttr("src")) {
                        e.attr("src", resolveHREF(e.attr("src"), baseURI));
                    }
                }

                // Store mapping from template name to nodes in template doc
                List<Node> prevTemplate = templateNameToTemplateNodes.put(templateName, //
                        isWholeDocument ? doc.childNodes() : doc.body().childNodes());
                if (prevTemplate != null) {
                    // We only match template names and DataModel names on the leafname currently, so html
                    // files in classpath have to have unique names
                    throw new RuntimeException("Two HTML template files found in classpath with same name \""
                            + templateName + "\"");
                }

                // If this is a whole-page template with head and body elements, record that
                if (isWholeDocument) {
                    pageTemplateDocs.add(doc);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Could not read web resource " + absolutePath, e);
        }
        Log.info("Registering web resource: " + relativePath);
    }

    // -----------------------------------------------------------------------------------------------------

    void initializeTemplates() {
        // Register static final inline templates
        for (Entry<String, String> ent : dataModelLoader.classNameToInlineTemplate.entrySet()) {
            // Get class name
            String className = ent.getKey();

            // Allow dynamically-loaded static constants to override the version obtained by reflection
            String templateStr = dataModelLoader.classNameToInlineTemplateOverride.get(className);
            if (templateStr == null) {
                templateStr = ent.getValue();
            }

            // Register inline template
            // (also replacing com.pkg.ClassName$InnerClass -> com.pkg.ClassName.InnerClass)
            String fakePath = "/" + className.replace('$', '.').replace('.', '/') + ".class";
            registerWebResource(fakePath, fakePath, templateStr);
        }

        // The set of template names is the intersection between html file names (less the file extension)
        // and the simple class names of DataModel subclasses
        HashSet<String> nameIntersection = new HashSet<>(templateNameToTemplateNodes.keySet());
        nameIntersection.retainAll(dataModelLoader.classNameToDataModel.keySet());

        for (String templateName : nameIntersection) {
            Class<? extends DataModel> templateClass = dataModelLoader.classNameToDataModel.get(templateName);
            List<Node> templateNodes = templateNameToTemplateNodes.get(templateName);

            // Cross-check parameter names between HTML templates and DataModel subclasses 
            crossCheckDataModelAndView(siteResources, templateName, templateClass, templateNodes);

            // Create a mapping from DataModel class to the HTML doc that holds the template contents
            templateClassToTemplateNodes.put(templateClass, templateNodes);

            // Render the template nodes into a string, for use with clientside template rendering
            StringBuilder buf = new StringBuilder(16384);
            for (Node n : templateNodes) {
                buf.append(n.toString());
            }
            templateNameToTemplateStr.put(templateName, buf.toString());
        }

        // For whole-page templates, add head-content.html to the end of the head element and tail-content.html
        // to the end of the body element
        for (Document wholePageDoc : pageTemplateDocs) {
            wholePageDoc.head().append(headContent.toString());
            wholePageDoc.body().append(tailContent.toString());
        }
    }

}
