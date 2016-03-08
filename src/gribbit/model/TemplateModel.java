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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.nodes.XmlDeclaration;

import gribbit.model.field.annotation.IsURL;
import gribbit.model.util.FieldChecker;
import gribbit.route.RouteHandler;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.server.siteresources.CacheExtension;
import gribbit.server.siteresources.CacheExtension.HashInfo;
import gribbit.util.JSON;
import gribbit.util.Log;
import gribbit.util.StringUtils;
import gribbit.util.WebUtils;
import gribbit.util.WebUtils.EscapeAmpersand;

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
 */

public abstract class TemplateModel {

    /** Pattern for template parameters, of the form "${name}" */
    public static final Pattern TEMPLATE_PARAM_PATTERN = Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9_]*)\\}");

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Wrapper for a list of content objects. This is needed so that individual TemplateModel objects and lists of
     * TemplateModel objects can both be supported in a typesafe way.
     */
    public static class ContentList extends TemplateModel {
        public List<? extends TemplateModel> contentList;

        public ContentList(List<? extends TemplateModel> contentList) {
            this.contentList = contentList;
        }

        public static final String _template = "${contentList}";
    }

    /**
     * Wrapper for a vararg list or an array of content objects. This is needed so that individual TemplateModel
     * objects and arrays of TemplateModel objects can both be supported in a typesafe way.
     */
    public static class ContentArray extends TemplateModel {
        public TemplateModel[] contentList;

        public ContentArray(TemplateModel... contentList) {
            this.contentList = contentList;
        }

        public static final String _template = "${contentList}";
    }

    /**
     * Wrap a vararg list or array of TemplateModel objects into a single template that renders each of the
     * arguments in sequence.
     */
    public static TemplateModel templateSequence(TemplateModel... contentArgs) {
        return new ContentArray(contentArgs);
    }

    /**
     * Wrap a list of TemplateModel objects into a single template that renders each of the arguments in sequence.
     */
    public static TemplateModel templateSequence(List<? extends TemplateModel> contentList) {
        return new ContentList(contentList);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Escape text for an HTML attribute value or for an HTML text node. */
    private static void encodeParamText(boolean isAttrVal, boolean isURLAttr, String unsafeStr, StringBuilder buf) {
        if (isAttrVal) {
            if (isURLAttr) {
                // This parameter may be just one part of a URI, so we need to check the whole composed URI string
                // for validity after all params have been substituted -- just insert unsafe param string directly
                // for now. URI attribute values are validated after all parameters have been substituted. 
                buf.append(unsafeStr);
            } else {
                // OWASP Rule #2:
                //     Attribute Escape Before Inserting Untrusted Data into HTML Common Attributes.
                WebUtils.encodeForHTMLAttribute(unsafeStr, buf);
            }
        } else {
            // OWASP Rule #1:
            //     HTML Escape Before Inserting Untrusted Data into HTML Element Content
            WebUtils.encodeForHTML(unsafeStr, //
                    // See http://stackoverflow.com/questions/3705591/do-i-encode-ampersands-in-a-href
                    /* escapeAmpersand = */EscapeAmpersand.ALWAYS, //
                    /* preserveWhitespaceRuns = */false, /* preserveNewline = */false, //
                    // Turn "\n" within the text that is substituted into param into <br> for convenience
                    /* turnNewlineIntoBreak = */true, buf);
        }
    }

    /**
     * Append a text part to the buffer. If prettyPrint is true, and the buffer already ends in a space, and the
     * text part starts in a space, skip initial spaces in the text part so as not to create a run of spaces, which
     * can throw off indenting if the text is at the beginning of an indented line.
     */
    private void encodeForHTMLNormalizingInitialSpace(CharSequence textPart, boolean prettyPrint,
            StringBuilder buf) {
        CharSequence suffixAfterInitialSpaces = textPart;
        if (prettyPrint && (buf.length() == 0 || buf.charAt(buf.length() - 1) == ' ') && textPart.length() > 0
                && textPart.charAt(0) == ' ') {
            boolean hasNonSpace = false;
            for (int i = 0, n = textPart.length(); i < n; i++) {
                char c = textPart.charAt(i);
                if (c != ' ') {
                    hasNonSpace = true;
                    suffixAfterInitialSpaces = textPart.subSequence(i, textPart.length());
                    break;
                }
            }
            if (!hasNonSpace) {
                suffixAfterInitialSpaces = "";
            }
        }
        // Encode and insert the part of the text after the initial spaces into the buffer 
        WebUtils.encodeForHTML(suffixAfterInitialSpaces, //
                // See http://stackoverflow.com/questions/3705591/do-i-encode-ampersands-in-a-href
                /* escapeAmpersand = */EscapeAmpersand.ALWAYS, //
                /* preserveWhitespaceRuns = */false, /* preserveNewline = */false, //
                /* turnNewlineIntoBreak = */false, buf);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Substitute params from this TemplateModel object into the text, performing proper HTML escaping as needed.
     */
    private boolean substituteTemplateParamsAndEscapeText(String tagName, String attrName, String textWithParams,
            String reqURLPath, int indentLevel, boolean prettyPrint, StringBuilder buf) {
        boolean wasIndented = false;
        boolean isAttrVal = attrName != null;
        boolean isURLAttr = isAttrVal && WebUtils.isURLAttr(tagName, attrName);

        Matcher matcher = TEMPLATE_PARAM_PATTERN.matcher(textWithParams);
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
            Object fieldValue = null;
            try {
                Field field = this.getClass().getField(paramName);
                // DataModel fields annotated with @Private or @OnlyReceive and DBModel id fields
                // cannot be sent to the user, just ignore them
                if (!FieldChecker.fieldIsPrivate(field, /* checkGet = */true, /* checkSet = */
                        false)) {

                    // Turn primitive types into strings, they have their own getter methods
                    Class<?> fieldType = field.getType();
                    if (fieldType == Integer.TYPE) {
                        fieldValue = Integer.toString(field.getInt(this));
                    } else if (fieldType == Boolean.TYPE) {
                        fieldValue = Boolean.toString(field.getBoolean(this));
                    } else if (fieldType == Character.TYPE) {
                        fieldValue = field.getChar(this);
                    } else if (fieldType == Long.TYPE) {
                        fieldValue = Long.toString(field.getLong(this));
                    } else if (fieldType == Float.TYPE) {
                        fieldValue = Float.toString(field.getFloat(this));
                    } else if (fieldType == Double.TYPE) {
                        fieldValue = Double.toString(field.getDouble(this));
                    } else if (fieldType == Byte.TYPE) {
                        byte b = field.getByte(this);
                        fieldValue = "0x" + Integer.toString(b, 16);
                    } else if (fieldType == Short.TYPE) {
                        fieldValue = Short.toString(field.getShort(this));
                    } else {
                        // Render non-primitive type
                        fieldValue = field.get(this);
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
            if (fieldValue != null) {
                Class<?> fieldType = fieldValue.getClass();

                if (fieldType == String.class) {
                    // Expand a string parameter (would be caught by the last "else" clause, but is included
                    // here for speed, since this is the most common case).
                    String unsafeStr = (String) fieldValue;
                    if (!unsafeStr.isEmpty()) {
                        encodeParamText(isAttrVal, isURLAttr, unsafeStr, buf);
                    }

                } else if (TemplateModel.class.isAssignableFrom(fieldType)) {
                    if (isAttrVal) {
                        // Shouldn't happen, this was checked for on template load, but included here for XSS safety
                        throw new RuntimeException("Can't include HTML inside an attribute value");
                    }

                    // Recursively render nested template
                    wasIndented |= ((TemplateModel) fieldValue).renderTemplate(reqURLPath, indentLevel, prettyPrint,
                            buf);

                } else if (fieldType.isArray() || List.class.isAssignableFrom(fieldType)) {
                    if (isAttrVal) {
                        // Shouldn't happen, this was checked for on template load, but included here for XSS safety
                        throw new RuntimeException("Can't include HTML inside an attribute value");
                    }
                    // We checked the element types of list and array fields when the TemplateModel class was
                    // loaded, so we can assume the type of each element is TemplateModel.
                    boolean isArray = fieldType.isArray();
                    @SuppressWarnings("unchecked")
                    List<? extends TemplateModel> list = isArray ? null
                            : (List<? extends TemplateModel>) fieldValue;
                    int n = isArray ? Array.getLength(fieldValue) : list.size();
                    // Render each item in the list or array
                    for (int i = 0; i < n; i++) {
                        TemplateModel elt = isArray ? (TemplateModel) Array.get(fieldValue, i) : list.get(i);
                        if (elt != null) {
                            // Recursively render nested template
                            wasIndented |= elt.renderTemplate(reqURLPath, indentLevel, prettyPrint, buf);
                        }
                    }

                } else if (DataModel.class.isAssignableFrom(fieldType)) {
                    // Should never happen, this was disallowed on template loading
                    throw new RuntimeException("Form-bound " + DataModel.class.getSimpleName() + " subclass "
                            + fieldType.getName() + " cannot be substituted into a template parameter");

                } else if (Class.class.isAssignableFrom(fieldType)) {
                    // Special case: if a field type is a Class<? extends RouteHandler>, then insert the URL of the
                    // RouteHandler's route as a string, so that routes can be inserted into href attributes.
                    //
                    // i.e. URI routes can be inserted into URI attributes by defining a field in a TemplateModel
                    // like: "public Class<? extends RestHandler> myUrl = MyURLHandler.class;"
                    // then including a parameter in HTML like: "<a href='${myUrl}'>Click here</a>"
                    //
                    // (The concrete type parameter of all Class<?> fields were checked on template loading.)
                    @SuppressWarnings("unchecked")
                    Class<? extends RouteHandler> routeHandlerClass = (Class<? extends RouteHandler>) fieldValue;
                    String uriForClass = GribbitServer.siteResources.routeForClass(routeHandlerClass).getRoutePath()
                            .getNormalizedPath();
                    buf.append(uriForClass);

                } else {
                    // For all other non-builtin types, call the toString() method. (We checked already during template
                    // loading that all these field types have their own toString() method defined.)
                    String unsafeStr = fieldValue.toString();
                    encodeParamText(isAttrVal, isURLAttr, unsafeStr, buf);
                }
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
                String urlStr = escapedTextWithSubstitutedParams.toString();

                // This is a URL attribute -- check URL contains only valid characters.
                //
                // OWASP Rule #5:
                //     URL Escape Before Inserting Untrusted Data into HTML URL Parameter Values.
                //     See: http://goo.gl/PTX0ul
                //
                // We leave it up to the user to escape their URLs, so that we avoid double-escaping.
                // StringUtils.encodeURI() can be used for this purpose. Instead, we run the URI through
                // the Java URI parser class to validate the URL.
                URI url = WebUtils.parseURI(urlStr);
                if (url == null) {
                    throw new RuntimeException(
                            "String substituted into URI attr \"" + attrName + "\" is not a valid URI: " + urlStr);
                }

                String replacementURL = null;
                String scheme = url.getScheme();
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
                        throw new RuntimeException("URL " + urlStr + " uses an unsafe protocol");

                    } else if (scheme.equals("mailto") || scheme.equals("tel")) {
                        // OWASP Rule #6:
                        //     See http://goo.gl/cqealh
                        if (!(tagName.equals("a") && isAttrVal && attrName.equals("href"))) {
                            throw new RuntimeException("URL " + urlStr + " should be used in an a.href attribute");
                        }

                    } else if (!(scheme.equals("http") || scheme.equals("https"))) {
                        // If it's not http: or https:, it's probably unsafe
                        throw new RuntimeException("URL " + urlStr + " uses an unsupported URL protocol");
                    }

                } else if (url.getHost() == null && url.getPort() == -1) {
                    // For local URIs, see if there is an MD5-hashed version of the URL, and if so,
                    // replace the URL with the hashed version.
                    // TODO: extend URL-rewriting to CSS image resources 
                    HashInfo hashInfo = CacheExtension.getHashInfo(urlStr);
                    replacementURL = hashInfo == null ? null : hashInfo.getHashURI();
                }

                // Make URLs absolute (this is important for hash URIs to be able to be properly resolved).
                String urlStrResolved = WebUtils.resolveHREF(urlStr, reqURLPath);
                if (!urlStrResolved.equals(urlStr)) {
                    replacementURL = urlStrResolved;
                }

                // Replace the URI that was rendered into the buffer with the hashed version, if there is a hashed
                // version of the resource
                if (replacementURL != null) {
                    buf.setLength(bufLenOnEntry);
                    buf.append(replacementURL);
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

            } else if (isAttrVal
                    && (attrName.equals("id") || attrName.equals("name") || attrName.equals("class"))) {
                // OWASP Rule #1:
                //     Strictly validate unsafe attributes such as background, id and name.
                // See also http://goo.gl/fKZhFA -- we disallow '.' and ':' because they can cause
                // problems with jQuery.

                if (!(attrName.equals("class") ? WebUtils.VALID_CSS_ID : WebUtils.VALID_HTML_NAME_OR_ID)
                        .matcher(escapedTextWithSubstitutedParams).matches()) {
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

    /**
     * Append a string representation of the attributes of an element to the buffer.
     * 
     * Sets initial values of inputs in a form to the corresponding values in the passed DataModel, if the DataModel
     * and its field with the same name as the "name" attribute of a form input are both non-null.
     */
    private void renderAttrs(Element e, Element enclosingForm, DataModel formModel, String selectName,
            String reqURLPath, int indentLevel, boolean prettyPrint, StringBuilder buf) {

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
                    if (!FieldChecker.fieldIsPrivate(formModelField, /* checkGet = */true, //
                            /* checkSet = */false)) {
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
                buf.append(' ');
                buf.append(attrName);
                if (!attrVal.isEmpty()) {
                    buf.append("=\"");
                    substituteTemplateParamsAndEscapeText(tagName, attrName, attrVal, reqURLPath, indentLevel,
                            prettyPrint, buf);
                    buf.append('"');
                }
            }
        }

        // Append any attrs that were added for input forms that didn't replace a value already
        // in the input element
        if (replaceAttrVal != null && !replaceAttrVal.isEmpty()) {
            for (Entry<String, String> ent : replaceAttrVal.entrySet()) {
                String attrName = ent.getKey();
                String attrVal = ent.getValue();
                buf.append(' ');
                buf.append(attrName);
                if (!attrVal.isEmpty()) {
                    buf.append("=\"");
                    substituteTemplateParamsAndEscapeText(tagName, attrName, attrVal, reqURLPath, indentLevel,
                            prettyPrint, buf);
                    buf.append('"');
                }
            }
        }
    }

    /**
     * Recursively traverse the DOM of a template, rendering each node into HTML. Returns true if the node was
     * indented (which happens when prettyPrint == true and the node or one of its children is a block element).
     */
    private boolean renderDOMNode(Node node, Element enclosingForm, DataModel formModel, String selectName,
            String reqURLPath, boolean normalizeTextSpacing, int indentLevel, boolean prettyPrint,
            StringBuilder buf) {
        boolean nodeWasIndented = false;

        if (node instanceof Element) {
            Element e = (Element) node;
            String tagName = e.tagName();

            if (tagName.equals("form")) {
                // For form elements, match form id against field names of this TemplateModel
                String formId = e.attr("id");
                if (!formId.isEmpty()) {
                    try {
                        Field formField = this.getClass().getField(formId);
                        DataModel thisFormModel = (DataModel) formField.get(this);

                        // Set enclosingForm and formModel for child nodes
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
            boolean isBlockElement = !WebUtils.INLINE_ELEMENTS.contains(tagName)
                    && !GribbitServer.siteResources.getCustomInlineElements().contains(tagName);

            // Render the open tag for this element
            if (prettyPrint && isBlockElement) {
                StringUtils.indent(indentLevel, buf);
                nodeWasIndented = true;
            }
            buf.append('<');
            buf.append(tagName);
            renderAttrs(e, enclosingForm, formModel, selectName, reqURLPath, indentLevel, prettyPrint, buf);
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
                    hasIndentedChild |= renderDOMNode(child, enclosingForm, formModel, selectName, reqURLPath,
                            normalizeTextSpacing, indentLevel + 1, prettyPrint, buf);
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
            nodeWasIndented |= substituteTemplateParamsAndEscapeText(null, null, nodeText, reqURLPath, indentLevel,
                    prettyPrint, buf);

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
            Log.warning("Unknown node type " + node.getClass().getName() + " when rendering template for "
                    + getClass().getName());
        }
        return nodeWasIndented;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Render nested templates recursively. */
    private boolean renderTemplate(String reqURLPath, int indentLevel, boolean prettyPrint, StringBuilder buf) {
        List<Node> templateNodes = GribbitServer.siteResources.getTemplateForClass(this.getClass());
        if (templateNodes == null) {
            // Shouldn't happen
            throw new RuntimeException("Could not template for TemplateModel \"" + this.getClass().getName());
        }

        boolean wasIndented = false;
        // Render template DOM nodes (they are children of doc body element)
        for (Node node : templateNodes) {
            wasIndented |= renderDOMNode(node, null, null, null, reqURLPath, true, indentLevel, prettyPrint, buf);
        }
        return wasIndented;
    }

    /**
     * Render this template model into HTML by substituting its field values into the correspondingly-named template
     * parameters in the associated HTML template.
     */
    public String renderTemplate(String reqURLPath, boolean prettyPrint) {
        StringBuilder buf = new StringBuilder(8192);
        renderTemplate(reqURLPath, 0, prettyPrint, buf);
        return buf.toString();
    }

    /**
     * Render this template model into HTML by substituting its field values into the correspondingly-named template
     * parameters in the associated HTML template.
     */
    public String renderTemplate(String reqURLPath) {
        return renderTemplate(reqURLPath, GribbitProperties.PRETTY_PRINT_HTML);
    }

    /**
     * Render a TemplateModel object into HTML.
     */
    @Override
    public final String toString() {
        return renderTemplate("");
    }

    /**
     * Render a TemplateModel object into JSON.
     */
    public final String toStringJSON() {
        return JSON.toJSON(this);
    }

}
