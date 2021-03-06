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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import gribbit.util.StringUtils;
import gribbit.util.WebUtils;
import gribbit.util.WebUtils.EscapeAmpersand;

/**
 * Sanitized HTML.
 * 
 * Used to clean HTML-typed data received from the user.
 * 
 * Can also be used to render custom dynamic HTML directly into a template if needed, although:
 * 
 * (1) Using sanitized HTML is slower than template rendering, because the HTML has to be parsed by Jsoup, cleaned
 * of illegal tags and escaped before it can be rendered into the template in string format.
 * 
 * (2) Custom-generating dynamic HTML rather than creating a template is akin to the failure to separate content and
 * presentation (in this case, the failure to separate model and view). It is almost always better to create a new
 * template and corresponding DataModel rather than dynamically generating ad-hoc HTML.
 */
public class SanitizedHTML {

    private final String sanitizedHTML;

    /**
     * Sanitize HTML, allowing only elements that are in the tagAttrWhitelist. Call #toString() to get the sanitized
     * HTML.
     * 
     * @param unsafeHTML
     *            The unsafe HTML to sanitize.
     * @param tagAttrWhitelist
     *            A list of whitelisted elements that are allowed to take no attributes ("elt") and/or whitelisted
     *            elements with whitelisted attributes ("a.href"). If null, the default of { "a.href", "br", "b",
     *            "i" } is used. Note that the default intentionally does not include "img.src" because image
     *            sources can be used to track the viewer, so if this HTML is user-supplied, users could track other
     *            users on the site that view their content.
     */
    public SanitizedHTML(String unsafeHTML, String[] tagAttrWhitelist) {
        HashMap<String, HashSet<String>> whitelist = tagAttrWhitelist == null ? defaultWhitelist
                : createWhitelist(tagAttrWhitelist);
        StringBuilder buf = new StringBuilder();
        List<Node> nodes = Jsoup.parseBodyFragment(unsafeHTML).body().childNodes();
        sanitize(nodes, whitelist, buf);
        sanitizedHTML = buf.toString();
    }

    /**
     * Sanitize HTML, allowing only elements that are in the default whitelist { "a.href", "br", "b", "i" }. Call
     * #toString() to get the sanitized HTML.
     * 
     * @param unsafeHTML
     *            The unsafe HTML to sanitize.
     */
    public SanitizedHTML(String unsafeHTML) {
        this(unsafeHTML, null);
    }

    private static HashMap<String, HashSet<String>> defaultWhitelist = //
            createWhitelist(new String[] { "a.href", "br", "b", "i" });

    private static HashMap<String, HashSet<String>> createWhitelist(String[] strings) {
        HashMap<String, HashSet<String>> whitelist = new HashMap<>();
        for (String str : strings) {
            String[] parts = StringUtils.split(str, ".");
            String elt = parts[0].toLowerCase();
            HashSet<String> attrs = whitelist.get(elt);
            if (attrs == null) {
                whitelist.put(elt, attrs = new HashSet<>());
            }
            if (parts.length > 1) {
                String attr = parts[1].toLowerCase();
                attrs.add(attr);
            }
        }
        return whitelist;
    }

    private static void sanitize(List<Node> nodes, HashMap<String, HashSet<String>> whitelist, StringBuilder buf) {
        for (Node node : nodes) {
            if (node instanceof TextNode) {
                String text = ((TextNode) node).text();
                WebUtils.encodeForHTML(text, //
                        // Since this text is supposed to be valid HTML already, if an ampersand is present,
                        // it is assumed to be a valid entity reference => don't double-escape it.
                        /* escapeAmpersand = */EscapeAmpersand.IF_NOT_VALID_ENTITY, //
                        /* preserveWhitespaceRuns = */false, //
                        // Turn newlines into <br>
                        /* preserveNewline = */false, /* turnNewlineIntoBreak = */true, buf);
            } else if (node instanceof Element) {
                Element e = (Element) node;
                String tagName = e.tagName().toLowerCase();
                if (tagName.equals("script") || tagName.equals("style") || tagName.equals("object")
                        || tagName.equals("applet")) {
                    // Specifically exclude these tags and don't recurse on their contents, they are the most unsafe
                } else {
                    // Check if element is whitelisted
                    HashSet<String> whitelistedAttrs = whitelist.get(tagName);
                    if (whitelistedAttrs == null) {
                        // Tagname is not whitelisted -- don't include the tag. However, if it's a block element,
                        // then at least insert a space to break up text spans that shouldn't be joined.
                        if (!WebUtils.INLINE_ELEMENTS.contains(tagName)) {
                            if (buf.length() > 0 && buf.charAt(buf.length() - 1) != ' ') {
                                buf.append(' ');
                            }
                        }
                    } else {
                        // Tagname is whitelisted -- include the tag
                        buf.append('<');
                        buf.append(tagName);
                        // Include any whitelisted attributes
                        for (Attribute a : e.attributes()) {
                            String attrName = a.getKey().toLowerCase();
                            if (whitelistedAttrs.contains(attrName)) {
                                buf.append(' ');
                                buf.append(attrName);
                                String attrVal = a.getValue();
                                if (attrVal != null && !attrVal.isEmpty()) {
                                    buf.append("=\"");

                                    if (WebUtils.isURLAttr(tagName, attrName)) {
                                        // FIXME: make URLs absolute (need to add a baseURL param to sanitize())
                                    }

                                    // If an ampersand is present in an attribute value, don't escape it,
                                    // because for example it could be used in a URL parameter.
                                    WebUtils.encodeForHTML(attrVal, //
                                            // Since this text is supposed to be valid HTML already, if an ampersand
                                            // is present, it is assumed to be a valid entity reference
                                            // => don't double-escape it.
                                            /* escapeAmpersand = */EscapeAmpersand.IF_NOT_VALID_ENTITY, //
                                            /* preserveWhitespaceRuns = */false, /* preserveNewline = */false,
                                            /* turnNewlineIntoBreak = */false, buf);
                                    buf.append("\"");
                                }
                            }
                        }
                        buf.append('>');
                    }
                    // Void elements don't have child nodes or a close tag
                    if (!WebUtils.VOID_ELEMENTS.contains(tagName)) {
                        // Whether or not tag was included, recurse on child nodes so the text content is
                        // included in the output
                        sanitize(e.childNodes(), whitelist, buf);
                        // Include a close tag if an open tag was added
                        if (whitelistedAttrs != null) {
                            buf.append("</");
                            buf.append(tagName);
                            buf.append(">");
                        }
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return sanitizedHTML;
    }

    //    public static void main(String[] args) throws IOException {
    //        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
    //        StringBuilder buf = new StringBuilder();
    //        do {
    //            String inp = reader.readLine();
    //            if (inp.equals("*")) {
    //                break;
    //            }
    //            buf.append(inp + "\n");
    //        } while (true);
    //        System.out.println(new SafeHTML(buf.toString()));
    //    }
}
