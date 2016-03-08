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
import gribbit.model.TemplateModel;
import gribbit.model.field.annotation.Email;
import gribbit.model.field.annotation.IsURL;
import gribbit.model.field.annotation.MaxIntegerValue;
import gribbit.model.field.annotation.MaxLength;
import gribbit.model.field.annotation.MinIntegerValue;
import gribbit.model.field.annotation.MinLength;
import gribbit.model.field.annotation.NoTrim;
import gribbit.model.field.annotation.NormalizeSpacing;
import gribbit.model.field.annotation.Regex;
import gribbit.model.util.FieldChecker;
import gribbit.response.HTMLPageTemplateModel;
import gribbit.route.RouteHandler;
import gribbit.util.Log;
import gribbit.util.MultiMapKeyToSet;
import gribbit.util.StringUtils;
import gribbit.util.WebUtils;
import io.netty.handler.codec.http.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Matcher;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

class TemplateModelLoader {

    private SiteResources siteResources;

    private StringBuilder headContent = new StringBuilder(16384), tailContent = new StringBuilder(16384);

    private HashMap<String, String> templateClassNameToTemplateStr = new HashMap<>();

    private HashSet<String> inlineTemplateStaticFieldNames = new HashSet<>();

    private HashSet<String> customInlineElements = new HashSet<>();

    /** Templates that consist of complete HTML docs, with head and body elements */
    private HashSet<Document> wholePageTemplateDocs = new HashSet<>();

    private HashMap<Class<? extends TemplateModel>, Document> templateClassToDoc = new HashMap<>();

    private HashMap<Class<? extends TemplateModel>, List<Node>> templateClassToTemplateNodes = new HashMap<>();

    // private HashMap<Class<?>, ArrayList<Field>> templateClassToFields = new HashMap<>();

    static final String TEMPLATE_MODEL_INLINE_TEMPLATE_FIELD_NAME = "_template";

    // -----------------------------------------------------------------------------------------------------

    TemplateModelLoader(SiteResources siteResources) {
        this.siteResources = siteResources;
    }

    /**
     * Get the names of all the static fields containing inline templates that were discovered during classpath
     * scanning, so that when there are changes on the classpath, we can dynamically reload the constant values in
     * these static fields.
     */
    public HashSet<String> getInlineTemplateStaticFieldNames() {
        return inlineTemplateStaticFieldNames;
    }

    /**
     * Return custom Polymer element tagnames whose templates consist of only inline elements, not block elements
     * (for prettyprinting)
     */
    HashSet<String> getCustomInlineElements() {
        return customInlineElements;
    }

    /**
     * Register a custom element (e.g. a Polymer or X-Tags element) as an inline element for prettyprinting
     * (otherwise it will be prettyprinted as a block element, with newlines and indentation).
     */
    void registerCustomInlineElement(String elementName) {
        if (!elementName.contains("-")) {
            throw new IllegalArgumentException("Custom element names must include a hyphen, got \"" + elementName
                    + "\"");
        }
        customInlineElements.add(elementName);
    }

    /**
     * Return the template corresponding to the given template class as a list of nodes, or null if it doesn't
     * exist.
     */
    List<Node> getTemplateNodes(Class<? extends TemplateModel> templateClass) {
        return templateClassToTemplateNodes.get(templateClass);
    }

    //    /** Return the template corresponding to the given template class as a list of nodes, or null if it doesn't exist. */
    //    ArrayList<Field> getTemplateFields(Class<? extends TemplateModel> templateClass) {
    //        return templateClassToFields.get(templateClass);
    //    }

    /** Return the templates corresponding to each class as HTML strings, for clientside rendering. */
    public HashMap<String, String> getTemplateNameToTemplateStr() {
        return templateClassNameToTemplateStr;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Initialize templates after all classpath scanning is complete.
     */
    void initializeTemplates() {

        for (Entry<Class<? extends TemplateModel>, Document> ent : templateClassToDoc.entrySet()) {
            Class<? extends TemplateModel> templateClass = ent.getKey();
            String templateClassName = templateClass.getName();
            Document templateDoc = ent.getValue();

            // ---------------------------------------------------------------------------------------------------------
            // Find DataModel-typed public fields within TemplateModels that are bound to a form
            // ---------------------------------------------------------------------------------------------------------

            // Get a list of public fields, and for any fields that are a subclass of DataModel (i.e. for
            // fields that are bound to forms), create a mapping from the field name to the names of the
            // fields of the referenced type.
            MultiMapKeyToSet<String, String> formIdToReferencedDataModelTypeFieldNames = new MultiMapKeyToSet<>();
            HashMap<String, Class<? extends DataModel>> formFieldNameToReferencedDataModelType = new HashMap<>();
            ArrayList<Field> publicFields = new ArrayList<>();
            // templateClassToFields.put(templateClass, publicFields);
            HashSet<String> allFieldNames = new HashSet<>();
            HashSet<String> urlFields = new HashSet<>();
            for (Field field : templateClass.getFields()) {

                // Only check public fields 
                if (!FieldChecker.fieldIsPrivate(field, /* checkGet = */true, /* checkSet = */false)) {
                    String fieldName = field.getName();
                    Class<?> fieldType = field.getType();
                    allFieldNames.add(fieldName);
                    publicFields.add(field);

                    // Also identify DataModel-typed fields, they are bound to a form
                    if (DataModel.class.isAssignableFrom(fieldType)) {
                        @SuppressWarnings("unchecked")
                        Class<? extends DataModel> fieldTypeAsDataModel = (Class<? extends DataModel>) fieldType;

                        if (!FieldChecker.isFlatModel(fieldTypeAsDataModel)) {
                            throw new RuntimeException("Field " + templateClassName + "." + fieldName
                                    + " has type " + fieldType.getSimpleName() + ", which is a subclass of "
                                    + DataModel.class.getName()
                                    + ", marking it as being bound to a form. However, the class has one or more "
                                    + "fields with a type that is not supported for binding from POST requests");
                        }

                        formFieldNameToReferencedDataModelType.put(fieldName, fieldTypeAsDataModel);

                        // Find public fields of the referenced type -- these are bound to the inputs of the form
                        ArrayList<Field> referencedTypePublicFields = new ArrayList<>();
                        // templateClassToFields.put(fieldTypeAsDataModel, referencedTypePublicFields);
                        for (Field referencedTypeField : fieldType.getFields()) {
                            if (!FieldChecker.fieldIsPrivate(referencedTypeField, //
                                    /* checkGet = */true, /* checkSet = */true)) {
                                formIdToReferencedDataModelTypeFieldNames.put(fieldName,
                                        referencedTypeField.getName());
                                referencedTypePublicFields.add(referencedTypeField);
                            }
                        }

                        // Check if the field is to be substituted into a custom URL attribute
                        // (e.g. an attribute of a Polymer template or of a custom element that expects a URL)
                        if (field.isAnnotationPresent(IsURL.class)) {
                            urlFields.add(field.getName());
                        }
                    }
                }
            }

            // Get a list of forms in the HTML template, and create a mapping from the form name to the names of the
            // inputs in the form. Also add an input to the form that will hold the CSRF token when the form is rendered
            MultiMapKeyToSet<String, String> formIdToInputNames = new MultiMapKeyToSet<>();
            HashMap<String, Element> formIdToElement = new HashMap<>();
            for (Element formElt : templateDoc.getElementsByTag("form")) {
                // Remove any CSRF input elements that are already in the form (shouldn't be there)
                for (Element csrfElt : formElt.getElementsByAttributeValue("name", CSRF.CSRF_PARAM_NAME)) {
                    csrfElt.remove();
                }
                // Add CSRF input to form with placeholder value that will be overwritten with the real CSRF token
                // when the page is served
                Element csrfElt = formElt.appendElement("input");
                csrfElt.attr("name", CSRF.CSRF_PARAM_NAME);
                csrfElt.attr("type", "hidden");
                csrfElt.attr("value", CSRF.CSRF_TOKEN_PLACEHOLDER);
                
                // Ignore forms that don't have an id
                String formId = formElt.attr("id");
                if (!formId.isEmpty()) {
                    // Ignore forms that post to a different domain (although cross-domain posting is pretty
                    // useless these days, because CSRF protection is needed)
                    if (formElt.attr("action").isEmpty() || WebUtils.isLocalURL(formElt.attr("action"))) {
                        formIdToElement.put(formId, formElt);

                        // Create a mapping from the form id to the names of the input elements
                        if (formIdToInputNames.containsKey(formId)) {
                            // Form ids must be unique, because they are bound to fields in the model
                            throw new RuntimeException("There is more than one form with the same id \"" + formId
                                    + "\" in the template for class " + templateClass.getName());
                        }
                        for (Element input : formElt.getElementsByTag("input")) {
                            String name = input.attr("name");
                            if (!name.isEmpty()) {
                                formIdToInputNames.put(formId, name);
                            }
                        }
                        for (Element input : formElt.getElementsByTag("select")) {
                            String name = input.attr("name");
                            if (!name.isEmpty()) {
                                formIdToInputNames.put(formId, name);
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------------------------------------------------------
            // Validate and clean up HTML, and find the locations of parameters of the form ${parameterName}
            // ---------------------------------------------------------------------------------------------------------

            HashSet<String> allParamNames = new HashSet<>();
            HashSet<String> paramsInText = new HashSet<>();
            HashSet<String> paramsInAttrVals = new HashSet<>();
            for (Element e : templateDoc.getAllElements()) {
                String tagName = e.tagName();
                // Look for the use of template parameters in attribute vals
                for (Attribute a : e.attributes()) {
                    String attrName = a.getKey().toLowerCase();
                    String attrValue = a.getValue();
                    boolean expectsURL = false;
                    Matcher matcher = TemplateModel.TEMPLATE_PARAM_PATTERN.matcher(attrValue);

                    StringBuilder attrValueWithoutParams = new StringBuilder();
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

                        String paramName = matcher.group(1);

                        if (formIdToInputNames.containsKey(paramName)) {
                            // You can't use a form-bound field as a template parameter
                            throw new RuntimeException("The template for " + templateClassName
                                    + " contains a parameter reference ${" + paramName
                                    + "}, but the field of the same name is bound to the form with id " + paramName);
                        }

                        // Check that the field in the DataModel class with the same name as the parameter
                        // is publicly accessible
                        allParamNames.add(paramName);
                        paramsInAttrVals.add(paramName);
                        if (urlFields.contains(paramName) || WebUtils.isURLAttr(tagName, attrName)) {
                            expectsURL = true;
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
                            //
                            // Here we only statically check any non-parameter content of these special attribute values.
                            // Once parameter values have been substituted at runtime, the value is checked again, and
                            // a runtime error is generated if an attempt is made to insert illegal characters via the
                            // template parameter.
                            if (!(attrName.equals("class") ? WebUtils.VALID_CSS_ID : WebUtils.VALID_HTML_NAME_OR_ID)
                                    .matcher(attrValueWithoutParams).matches()) {
                                throw new RuntimeException("The template for " + templateClassName
                                        + " contains bad characters in id, name or class attribute value: "
                                        + attrName + "=\"" + attrValue + "\"");
                            }
                            if (StringUtils.containsUppercaseChar(attrValueWithoutParams)) {
                                throw new RuntimeException("The template for " + templateClassName
                                        + " contains uppercase characters in attribute value: " + attrName + "=\""
                                        + attrValue + "\". Browsers implement "
                                        + "case sensitivity differently, so values for id, "
                                        + "name and class should all be lowercase.");
                            }
                        }

                        if (expectsURL) {
                            // Check the non-parameter content of the URL for validity. Assumes that if there are any
                            // parameters in this URL, and they have the empty string submitted into them, that the
                            // URL will be valid. This means you can't do anything too tricky with parameterized URLs,
                            // but it allows us to do some static checking of URL-typed params.
                            if (!WebUtils.isValidURL(attrValueWithoutParams.toString())) {
                                throw new RuntimeException("The template for " + templateClassName
                                        + " contains an invalid string in a URL-typed attribute: " + attrName
                                        + "=\"" + attrValue + "\"");
                            }
                        }
                    }

                    if (attrValueContainsParam) {
                        // OWASP Rule #2:
                        //     Only place untrusted data into a whitelist of safe attributes.
                        // This is extremely restrictive for some usages, e.g. href is XSS-unsafe, but you need to
                        // insert a template parameter value in the href attribute of anchor elements all the time.
                        // Require that the value is prefixed by some string to limit the effect of XSS attacks,
                        // e.g. in the case of href, can use <a href="/img/${imgid}">, but not <a href="${imgurl}">.
                        if (attrName.equals("id") || attrName.equals("name") //
                                || attrName.equals("href") || attrName.equals("src")) {
                            // name and id are XSS-unsafe, because injected text can be made to 
                            // refer to any element on the page. Mitigate the effect by requiring
                            // that the attr value have a non-parameterized prefix.
                            if (firstMatchStart == 0) {
                                throw new RuntimeException(
                                        "The template for "
                                                + templateClassName
                                                + " contains a template parameter in the value of the XSS-unsafe attribute \""
                                                + attrName
                                                + "\". To mitigate XSS attacks, the parameter must be prefixed with "
                                                + "some non-parameter characters, e.g. <div id=\"widget${widgetNum}\"> and "
                                                + "<a href=\"/img/${imgurl}\"> are OK, but <div id=\"${widgetName}\"> and "
                                                + "<a href=\"${imgurl}\"> are not.");
                            }
                        } else if (!WebUtils.XSS_SAFE_ATTRS.contains(attrName)) {
                            // Disable XSS safety check for the attributes of custom elements (these contain a
                            // hyphen) -- it is the user's responsibility to ensure that a given attribute is
                            // XSS-safe with Polymer elements. If attributes are not provably XSS-safe, they
                            // should not be set using a template parameter value.
                            //
                            // Note that custom elements are an attack vector if the programmer is not
                            // careful, because custom elements' attribute values can be used to insert content
                            // anywhere into the shadow DOM, so we can't contextually escape these values.
                            if (!tagName.contains("-")) {
                                // Throw exception for non-whitelisted attrs.
                                throw new RuntimeException(
                                        "The template for "
                                                + templateClassName
                                                + " contains a template parameter in the value of an XSS-unsafe attribute \""
                                                + attrName + "\"");
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
                                    + "> in template \"" + templateClassName + "\" -- this is unsafe");
                        }
                        if (attrName.equals("style") || attrName.equals("data") || attrName.equals("action")
                                || attrName.startsWith("on")) {
                            // TODO: double-check there are not more attributes that can take style or script
                            throw new RuntimeException("Tried to use a template param in XSS-unsafe attribute \""
                                    + attrName + "\" in template \"" + templateClassName + "\"");
                        }

                        if (attrName.equals("id") || attrName.equals("name") || attrName.equals("class")) {
                            if (attrValue.contains(".") || attrValue.contains(":")) {
                                throw new RuntimeException("Value of attribute " + attrName + " in template \""
                                        + templateClassName
                                        + "\" contains '.' or ':', which can cause problems with jQuery");
                            }
                            a.setValue(StringUtils.unicodeTrim(StringUtils.normalizeSpacing(attrValue)));
                        }
                    }

                }

                ArrayList<Node> childNodes = new ArrayList<>(e.childNodes());
                for (Node n : childNodes) {
                    // Strip HTML comments
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

                    if (n instanceof TextNode) {
                        // Check for attempt to put param in the element name, e.g. <${elt}>,
                        // or malformed HTML like <>. In these cases, Jsoup just inserts a text node with
                        // unescaped text. 
                        String text = ((TextNode) n).text();
                        if (text.indexOf('<') > 0 || text.indexOf('>') > 0) {
                            throw new RuntimeException("The template corresponding to class " + templateClassName
                                    + " contains malformed HTML");
                        }

                        // Look for the use of template parameters in text nodes
                        Matcher matcher = TemplateModel.TEMPLATE_PARAM_PATTERN.matcher(text);
                        boolean containsParam = false;
                        while (matcher.find()) {
                            containsParam = true;
                            String paramName = matcher.group(1);
                            allParamNames.add(paramName);
                            paramsInText.add(paramName);
                        }
                        if (containsParam) {
                            String parentTagName = n.parent() == null ? "body" : ((Element) n.parent()).tagName();

                            if (parentTagName.equals("#comment")) {
                                // Ignore params in comments
                                // (These could be supported by specially escaping "<!--" and "-->" inside comments
                                // when the template is rendered, but adding a special escaping type for commented
                                // text is probably not worth it. The downside is that if you comment out part of the
                                // template, you also have to comment out the fields corresponding to any template
                                // parameters used in the commented-out region. Without escaping comment characters,
                                // if we allowed template parameters inside comments, we'd be creating a potential
                                // XSS vulnerability.) 
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
                                    throw new RuntimeException("The template corresponding to "
                                            + templateClass.getName()
                                            + " contains a template param inside the XSS-unsafe element \""
                                            + parentTagName + "\"");
                                }
                            }
                        }
                    }
                }

                // Make sure tag name doesn't contain a parameter
                if (TemplateModel.TEMPLATE_PARAM_PATTERN.matcher(e.tagName()).find()) {
                    throw new RuntimeException("The template \"" + templateClass.getName()
                            + "\" contains an element with a parameter in a tag name: " + e.tagName());
                }
            }

            // ---------------------------------------------------------------------------------------------------------
            // Check forms against the DataModel type they are bound to
            // ---------------------------------------------------------------------------------------------------------

            // All template param names must map to a field name in the corresponding TemplateModel class
            HashSet<String> unusedParamNames = new HashSet<>(allParamNames);
            unusedParamNames.removeAll(allFieldNames);
            if (!unusedParamNames.isEmpty()) {
                throw new RuntimeException("Extra template params with no corresponding field in class "
                        + templateClassName + ": " + StringUtils.joinCommaSeparatedSorted(unusedParamNames));
            }

            // All fields in a TemplateModel subclass must map to a template param or form
            HashSet<String> unusedFieldNames = new HashSet<>(allFieldNames);
            unusedFieldNames.removeAll(allParamNames);
            HashSet<String> unusedFormFieldNames = new HashSet<>(formFieldNameToReferencedDataModelType.keySet());
            unusedFieldNames.removeAll(unusedFormFieldNames);
            if (!unusedFieldNames.isEmpty()) {
                throw new RuntimeException("Extra fields in class " + templateClassName
                        + " with no corresponding template parameter or form id: "
                        + StringUtils.joinCommaSeparatedSorted(unusedFieldNames));
            }

            // All DataModel-typed fields must correspond with a form id
            unusedFormFieldNames.removeAll(formIdToInputNames.keySet());
            if (!unusedFormFieldNames.isEmpty()) {
                throw new RuntimeException("Extra " + DataModel.class.getSimpleName() + "-typed fields in class "
                        + templateClassName + " with no corresponding form id: "
                        + StringUtils.joinCommaSeparatedSorted(unusedFormFieldNames));
            }

            // Compare form inputs to the fields of the DataModel that the form is bound to
            for (String formId : formFieldNameToReferencedDataModelType.keySet()) {
                HashSet<String> inputNames = formIdToInputNames.get(formId);
                HashSet<String> dataModelFieldNames = formIdToReferencedDataModelTypeFieldNames.get(formId);
                Class<? extends DataModel> formModel = formFieldNameToReferencedDataModelType.get(formId);
                Element formElt = formIdToElement.get(formId);

                inputNames.remove(CSRF.CSRF_PARAM_NAME);

                // Check there is a 1:1 mapping between the names of the from inputs in the template HTML
                // and the names of the public fields in the DataModel type that is bound to the form
                HashSet<String> unusedInputNames = new HashSet<>(inputNames);
                unusedInputNames.removeAll(dataModelFieldNames);
                HashSet<String> unusedDataModelFieldNames = new HashSet<>(dataModelFieldNames);
                unusedDataModelFieldNames.removeAll(inputNames);
                if (!unusedInputNames.isEmpty()) {
                    throw new RuntimeException("Extra inputs in form " + formId + " in template for "
                            + templateClassName + " with no corresponding field in " + formModel.getName() + ": "
                            + StringUtils.joinCommaSeparatedSorted(unusedInputNames));
                }
                if (!unusedDataModelFieldNames.isEmpty()) {
                    throw new RuntimeException("Extra fields in " + formModel.getName()
                            + " with no corresponding inputs in form " + formId + " in template for "
                            + templateClassName + ": "
                            + StringUtils.joinCommaSeparatedSorted(unusedDataModelFieldNames));
                }

                // -----------------------------------------------------------------------------------------------------
                // Add constraints to the form given the constraint annotations on the fields of the DataModel object
                // -----------------------------------------------------------------------------------------------------

                // Get all input elements in this form, grouped by matching name (radio buttons have
                // multiple input fields with the same value in the "name" attribute)
                HashMap<String, ArrayList<Element>> nameToInputElts = new HashMap<>();
                for (Element elt : formElt.getElementsByAttribute("name")) {
                    String name = elt.attr("name");
                    if (inputNames.contains(name)) {
                        ArrayList<Element> inputs = nameToInputElts.get(name);
                        if (inputs == null) {
                            nameToInputElts.put(name, inputs = new ArrayList<>());
                        }
                        if (elt.tagName().equals("select")) {
                            // For select elements, add all the child option elements instead
                            // TODO: test this
                            for (Element option : elt.getElementsByTag("option")) {
                                inputs.add(option);
                            }
                        } else {
                            inputs.add(elt);
                        }
                    }
                }

                // Iterate through fields of the DataModel that is bound to the form
                for (String fieldName : inputNames) {
                    Field field;
                    try {
                        field = formModel.getField(fieldName);
                    } catch (NoSuchFieldException | SecurityException e1) {
                        // Should not happen
                        throw new RuntimeException(e1);
                    }
                    Class<?> fieldType = field.getType();

                    // Go through all input elements that have the same name as this DataModel field,
                    // and update the element to have constraint attributes matching the DataModel
                    // constraint annotations
                    ArrayList<Element> inputsWithSameNameAsField = nameToInputElts.get(fieldName);
                    if (inputsWithSameNameAsField == null) {
                        // Should not happen
                        throw new RuntimeException("No inputs named " + fieldName + " in form model "
                                + formModel.getName() + " in template for " + templateClassName);
                    }
                    for (Element namedInput : inputsWithSameNameAsField) {
                        String tagName = namedInput.tagName();
                        String type = namedInput.attr("type");

                        // Add "required" attr if the field has a type or annotation that makes the value required 
                        if (FieldChecker.fieldIsRequired(field)) {
                            if (tagName.equals("option")) {
                                // This is an <option> inside a <select>
                                type = "select";
                                // Set "required" attr on the <select>, not the <option>
                                namedInput.parent().attr("required", "");
                            } else {
                                // Everything else should be an <input> inside a <form>
                                namedInput.attr("required", "");
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
                                namedInput.attr("pattern", ((Regex) annotation).regex());
                            } else if (annotationType == NormalizeSpacing.class) {
                                needsSpaceNormalization = true;
                            } else if (annotationType == NoTrim.class) {
                                needsTrimming = false;
                            }
                        }

                        // Add type constraints to form inputs based on the type of fields in the DataModel
                        if (fieldType == LocalDate.class) {
                            namedInput.attr("type", "date");
                            // TODO: also add regex pattern to constrain date to the isoDate format?
                            // TODO: (This doesn't seem to work currently.)
                            // TODO: add date picker popup.

                        } else if (fieldType == Integer.class || fieldType == Integer.TYPE
                                || fieldType == Long.class || fieldType == Long.TYPE || fieldType == Short.class
                                || fieldType == Short.TYPE || fieldType == Float.class || fieldType == Float.TYPE
                                || fieldType == Double.class || fieldType == Double.TYPE) {
                            // TODO: does "type='number'" work for float/double?
                            namedInput.attr("type", "number");

                            if (min != null) {
                                namedInput.attr("min", "" + min);
                            }
                            if (max != null) {
                                namedInput.attr("max", "" + max);
                            }

                        } else if (fieldType == Boolean.class || fieldType == Boolean.TYPE) {
                            // Boolean fields are bound to checkboxes

                            if (!type.isEmpty() && !type.equals("checkbox"))
                                throw new RuntimeException("Field \"" + fieldName + "\" in form \"" + formId
                                        + "\" in template for " + templateClassName
                                        + " needs to be of type \"checkbox\", since "
                                        + "it is bound to a Boolean field");

                            namedInput.attr("type", "checkbox");

                        } else if (fieldType.isEnum()) {
                            // Enum-typed fields are bound to radio buttons

                            if (!type.isEmpty() && !(type.equals("radio") || type.equals("select"))) {
                                throw new RuntimeException("Field \"" + fieldName + "\" in form \"" + formId
                                        + "\" in template for " + templateClassName
                                        + " needs to be of type \"radio\", "
                                        + "since it is bound to an enum-typed field");
                            }

                            // Make sure all radio or option values map to a valid enum value
                            String radioVal = namedInput.attr("value");
                            if (radioVal == null) {
                                throw new RuntimeException("Missing attribute \"value\" for field \"" + fieldName
                                        + "\" in form \"" + formId + "\" in template for " + templateClassName);
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
                                        + "\" for radio or option field \"" + fieldName + "\" in form \"" + formId
                                        + "\" of template for " + templateClassName);
                            }

                        } else if (fieldType == String.class) {
                            // Password fields get marked as type="password", everything else that is 
                            // bound to a String gets marked as type="text"

                            if (fieldName.equals("password")) {
                                namedInput.attr("type", "password");
                            } else {
                                if (isEmail) {
                                    namedInput.attr("type", "email");
                                } else {
                                    namedInput.attr("type", "text");
                                }

                                //FIXME: need to validate minlength and maxlength using JS, and reflect 
                                // validity in Bootstrap. For all forms:
                                // -- load the following jQuery plugin if there are forms on the page:
                                //    http://docs.jquery.com/Plugins/Validation
                                // -- add in head: $("#commentForm").validate();
                                if (minLength != null) {
                                    namedInput.attr("minlength", "" + minLength);
                                }
                                if (maxLength != null) {
                                    namedInput.attr("maxlength", "" + maxLength);
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
                            namedInput.attr("type", "text").attr("maxlength", "1");

                        } else if (fieldType == FileUpload.class) {
                            // Force form type to multipart/form-data if one of the DataModel fields
                            // is of type FileUpload
                            namedInput.attr("type", "file");
                            formElt.attr("enctype", "multipart/form-data");

                        } else {
                            throw new RuntimeException("Illegal type " + fieldType.getName() + " for field \""
                                    + fieldName + "\" in form \"" + formId + "\" of template for "
                                    + templateClassName);
                        }
                    }
                }

                // If form is to be submitted via POST, try substituting the route URL for the
                // RestHandler that handles the POST into the action attribute
                String submitURI = siteResources.routeURIForDataModel(formModel);
                if (!formElt.attr("action").equals(submitURI)) {
                    if (!formElt.attr("action").isEmpty()) {
                        Log.warning("Form corresponding to template field " + templateClassName + "." + formId
                                + " has action " + formElt.attr("action") + " , but based on its type, the form "
                                + "will be posted to the handler at " + submitURI + " instead");
                    }
                    formElt.attr("action", submitURI);
                }
            }

            // ---------------------------------------------------------------------------------------------------------
            // Statically check TemplateModel field types
            // ---------------------------------------------------------------------------------------------------------

            for (Field field : publicFields) {
                String fieldName = field.getName();
                Class<?> fieldType = field.getType();

                if (TemplateModel.class.isAssignableFrom(fieldType)) {
                    // A nested template is assumed to be rendered into non-text HTML (i.e. we assume it won't just result
                    // in an HTML text node). HTML cannot be rendered into attribute values, so we disallow the use of
                    // TemplateModel-typed template parameters into attribute values.
                    if (paramsInAttrVals.contains(fieldName)) {
                        // Nested template parameters can be rendered into text, but not attribute vals
                        throw new RuntimeException("Field " + templateClass.getName() + "." + fieldName
                                + " has type " + fieldType.getSimpleName() + ", which is a "
                                + TemplateModel.class.getSimpleName()
                                + ", and therefore will be rendered into HTML. "
                                + "However, the corresponding template parameter is used in an attribute value in "
                                + "the template, and attribute values cannot accept HTML.");
                    }

                } else if (List.class.isAssignableFrom(fieldType)) {
                    // Lists can only have element type <? extends TemplateModel>

                    Class<?> listEltClass = null;
                    try {
                        ParameterizedType fieldGenericType = (ParameterizedType) field.getGenericType();
                        Type typeArgument0 = fieldGenericType.getActualTypeArguments()[0];
                        if (typeArgument0 instanceof WildcardType) {
                            // List<? extends ListEltClass>
                            listEltClass = (Class<?>) (((WildcardType) typeArgument0).getUpperBounds()[0]);
                        } else {
                            // List<ListEltClass>
                            listEltClass = (Class<?>) typeArgument0;
                        }
                    } catch (Exception e) {
                    }
                    if (listEltClass == null || !TemplateModel.class.isAssignableFrom(listEltClass)) {
                        throw new RuntimeException("Type of " + templateClass.getName() + "." + fieldName
                                + " should extend List<? extends " + TemplateModel.class.getSimpleName() + ">");
                    }
                    if (paramsInAttrVals.contains(fieldName)) {
                        // Nested lists of template parameters can be rendered into text, but not attribute vals
                        throw new RuntimeException(
                                "Field "
                                        + templateClass.getName()
                                        + "."
                                        + fieldName
                                        + " will be rendered as HTML, but the corresponding template parameter is used in an "
                                        + "attribute value in the template, and attribute values cannot accept HTML, they can "
                                        + "only take Strings or stringifiable values.");
                    }

                } else if (fieldType.isArray()) {
                    // Arrays must be of type TemplateModel[]
                    Class<?> arrayEltType = fieldType.getComponentType();
                    if (!TemplateModel.class.isAssignableFrom(arrayEltType)) {
                        throw new RuntimeException("Type of " + templateClass.getName() + "." + fieldName
                                + " should extend " + TemplateModel.class.getSimpleName() + "[]");
                    }
                    if (paramsInAttrVals.contains(fieldName)) {
                        // Nested arrays of template parameters can be rendered into text, but not attribute vals
                        throw new RuntimeException(
                                "Field "
                                        + templateClass.getName()
                                        + "."
                                        + fieldName
                                        + " will be rendered as HTML, but the corresponding template parameter is used in an "
                                        + "attribute value in the template, and attribute values cannot accept HTML, they can "
                                        + "only take Strings or stringifiable values.");
                    }

                } else if (DataModel.class.isAssignableFrom(fieldType)) {
                    // Special case: Forms are bound to fields that are a subtype of DataModel, but not of TemplateModel
                    // (but this type was already checked).

                } else if (Class.class.isAssignableFrom(fieldType)) {
                    // Special case: if a field type is a Class<? extends RouteHandler>, then insert the URL of the
                    // RestHandler's route as a string, so that routes can be inserted into href attributes.

                    Class<?> classType;
                    try {
                        ParameterizedType fieldGenericType = (ParameterizedType) field.getGenericType();
                        classType = (Class<?>) fieldGenericType.getActualTypeArguments()[0];
                    } catch (Exception e) {
                        throw new RuntimeException("Could not determine generic type of Class<?> field "
                                + templateClass.getName() + "." + fieldName + " -- should be Class<? extends "
                                + RouteHandler.class.getSimpleName() + ">", e);
                    }
                    if (!RouteHandler.class.isAssignableFrom(classType)) {
                        throw new RuntimeException("Type of " + templateClass.getName() + "." + fieldName
                                + " should be Class<? extends " + RouteHandler.class.getSimpleName() + ">");
                    }

                } else if (fieldType.isPrimitive()) {
                    // Primitive-typed fields will be manually rendered into strings during template rendering

                } else {
                    // For all other types, check if the class has its own toString() method. If not, we'll get an ugly
                    // default Object.class toString() result that shows the type and hexadecimal object reference.
                    try {
                        if (fieldType.getMethod("toString").getDeclaringClass() == Object.class) {
                            throw new RuntimeException("The class " + fieldType.getName() + " (used in field "
                                    + templateClass.getName() + "." + fieldName
                                    + ") needs to override Object.toString(), "
                                    + "or it cannot be rendered into a template parameter");
                        }
                    } catch (NoSuchMethodException | SecurityException e) {
                        // Shouldn't happen, all classes have a public toString() method
                        throw new RuntimeException(e);
                    }
                }
            }

            // ---------------------------------------------------------------------------------------------------------
            // Add head and tail content to any whole-page HTML templates, and extract DOM nodes from template
            // ---------------------------------------------------------------------------------------------------------

            List<Node> templateNodes;
            if (wholePageTemplateDocs.contains(templateDoc)) {
                // For whole-page templates, add head-content.html to the end of the head element and tail-content.html
                // to the end of the body element
                templateDoc.head().append(headContent.toString());
                templateDoc.body().append(tailContent.toString());

                // For a complete HTML doc, use the entire HTML doc
                templateNodes = templateDoc.childNodes();
            } else {
                // For an HTML fragment, use only the child nodes of the body element
                templateNodes = templateDoc.body().childNodes();
            }
            templateClassToTemplateNodes.put(templateClass, templateNodes);

            // Render the template nodes into a string, for use with clientside template rendering.
            // TODO: This is not used yet.
            StringBuilder buf = new StringBuilder(16384);
            for (Node n : templateNodes) {
                buf.append(n.toString());
            }
            templateClassNameToTemplateStr.put(templateClass.getName(), buf.toString());
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /** Load header HTML content from the classpath */
    public void loadHeadContent(String fileContents) {
        headContent.append(fileContents);
    }

    /** Load footer HTML content from the classpath */
    public void loadTailContent(String fileContents) {
        tailContent.append(fileContents);
    }

    /**
     * Load an HTML template for a TemplateClass, overriding the content of the template with the given string if
     * non-null.
     */
    public void loadTemplate(Class<? extends TemplateModel> templateClass, String templateStrOverride) {

        // -------------------------------------------------------------------------------------------------------------
        // Load HTML template corresponding with TemplateModel class
        // -------------------------------------------------------------------------------------------------------------

        String templateStr = null;
        if (templateStrOverride != null) {
            // Class has a field "public static final String _template", and this is the second or subsequent time that the
            // templates have been loaded. Introspection will always return the same value for a static field, even if
            // the class is loaded again, see 
            // http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html ,
            // so to support hot-swap of template string values, we override the introspection value with the value
            // obtained directly from the classfile by FastClasspathScanner.
            templateStr = templateStrOverride;
            inlineTemplateStaticFieldNames.add(templateClass.getName() + "."
                    + TEMPLATE_MODEL_INLINE_TEMPLATE_FIELD_NAME);

        } else {
            // If the TemplateModel contains a "_template" field, use that value for the HTML template
            Field inlineTemplateField;
            try {
                inlineTemplateField = templateClass.getField(TEMPLATE_MODEL_INLINE_TEMPLATE_FIELD_NAME);
                if (inlineTemplateField != null) {
                    int modifiers = inlineTemplateField.getModifiers();
                    if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)) {
                        if (inlineTemplateField.getType().equals(String.class)) {
                            // Got an inline template in the static field named "_template" of a TemplateModel class
                            templateStr = (String) inlineTemplateField.get(null);
                            inlineTemplateStaticFieldNames.add(templateClass.getName() + "."
                                    + TEMPLATE_MODEL_INLINE_TEMPLATE_FIELD_NAME);
                        } else {
                            throw new RuntimeException("Field \"" + inlineTemplateField.getName() + "\" in class "
                                    + templateClass.getName() + " must be of type String");
                        }
                    } else {
                        throw new RuntimeException("Field \"" + inlineTemplateField.getName() + "\" in class "
                                + templateClass.getName() + " must have modifiers \"public static final\"");
                    }
                }
            } catch (NoSuchFieldException e) {
                // Class does not have a "_template" field, try looking for a .html file in the classpath with the same
                // path and name as the template class

                String templatePath = templateClass.getName().replace('.', '/') + ".html";
                InputStream templateStream = ClassLoader.getSystemResourceAsStream(templatePath);
                if (templateStream == null) {
                    throw new RuntimeException("Template class " + templateClass.getName()
                            + " does not have a public static final String field "
                            + TEMPLATE_MODEL_INLINE_TEMPLATE_FIELD_NAME + " and the template file " + templatePath
                            + " could not be found on the classpath");
                }
                try {
                    templateStr = StringUtils.readWholeFile(templateStream);
                } catch (IOException e1) {
                    throw new RuntimeException(e1);
                }

            } catch (SecurityException | IllegalAccessException | IllegalArgumentException | NullPointerException e) {
                throw new RuntimeException("Class " + templateClass.getName() + " has field "
                        + TEMPLATE_MODEL_INLINE_TEMPLATE_FIELD_NAME + " but it cannot be read or accessed", e);
            }
        }
        if (templateStr == null) {
            // Should not happen
            throw new RuntimeException("Could not find a valid HTML template for TemplateModel class "
                    + templateClass.getName());
        }

        // -------------------------------------------------------------------------------------------------------------
        // Parse the HTML in the template string
        // -------------------------------------------------------------------------------------------------------------

        // See if this is a whole-page HTML document, as opposed to an HTML fragment
        int firstTagIdx = templateStr.indexOf("<");
        boolean isWholeDocument = firstTagIdx >= 0
                && ((templateStr.length() >= 5 && templateStr.substring(firstTagIdx, firstTagIdx + 5).toLowerCase()
                        .equals("<html")) //
                || (templateStr.length() >= 9 && templateStr.substring(firstTagIdx, firstTagIdx + 9).toLowerCase()
                        .equals("<!doctype")));

        if (isWholeDocument && !HTMLPageTemplateModel.class.isAssignableFrom(templateClass)) {
            throw new RuntimeException("The HTML template corresponding to the TemplateModel "
                    + templateClass.getName() + " is a complete HTML document, rather than an HTML fragment. "
                    + "The TemplateModel for complete HTML documents must subclass "
                    + HTMLPageTemplateModel.class.getName());
        }

        // Parse the HTML -- whole-page templates need Jsoup.parse(), fragments need Jsoup.parseBodyFragment()
        Document doc = isWholeDocument ? Jsoup.parse(templateStr) : Jsoup.parseBodyFragment(templateStr);

        // If this is a whole-page template with head and body elements, record that
        if (isWholeDocument) {
            wholePageTemplateDocs.add(doc);
        }
        templateClassToDoc.put(templateClass, doc);

        Log.fine("Registering TemplateModel: " + templateClass.getName());
    }
}
