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
package gribbit.util;

import gribbit.thirdparty.UTF8;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WebUtils {

    /** Pattern for recognizing external URIs. */
    public static final Pattern EXTERNAL_URI = Pattern.compile("^((data:)|(http[s]?:))?\\/\\/.*");

    // -----------------------------------------------------------------------------------------------------

    /**
     * Pattern for valid id or name attribute values. NB '.' and ':' are also technically allowed in the standard, but
     * they cause problems with jQuery, so they are disallowed here. Also note that both character cases are allowed,
     * but browsers may not handle case sensitivity correctly in all related contexts. See
     * http://stackoverflow.com/questions/70579/what-are-valid-values-for-the-id-attribute-in-html .
     */
    public static final Pattern VALID_HTML_NAME_OR_ID = Pattern.compile("[a-zA-Z][\\w\\-]*");

    /**
     * Pattern for CSS class name (includes ' ', because class attributes can list multiple classes).
     * 
     * NB CSS class names can start with '-' or '_', but this is technically reserved for vendor-specific extensions, so
     * we don't allow that here.
     */
    public static final Pattern VALID_CSS_ID = Pattern
            .compile("\\s*[a-zA-Z][_a-zA-Z0-9\\-]*(\\s+[a-zA-Z][_a-zA-Z0-9\\-]*)*\\s*");

    /**
     * Pattern for valid email address: from http://www.regular-expressions.info/email.html .
     * 
     * N.B. this is different than the validation performed by Chrome's type="email" form field validation (it pretty
     * much only requires an '@' character somewhere), so it's possible to submit form data like "x@y" that passes
     * Chrome's validation but fails serverside validation.
     */
    public static final Pattern VALID_EMAIL_ADDRESS = Pattern
            .compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");

    // -----------------------------------------------------------------------------------------------------

    /** HTML tags that should not be closed. http://www.w3.org/TR/html-markup/syntax.html#void-element */
    public static final HashSet<String> VOID_ELEMENTS = Stream.of("area", "base", "br", "col", "command", "embed",
            "hr", "img", "input", "keygen", "link", "meta", "param", "source", "track", "wbr", "!doctype", "!DOCTYPE")
            .collect(Collectors.toCollection(HashSet::new));

    /** HTML inline elements. https://developer.mozilla.org/en-US/docs/Web/HTML/Inline_elemente */
    public static final HashSet<String> INLINE_ELEMENTS = Stream.of("a", "abbr", "acronym", "b", "bdo", "big", "br",
            "button", "cite", "code", "dfn", "em", "i", "img", "input", "kbd", "label", "map", "object", "q", "samp",
            "select", "small", "span", "strong", "sub", "sup", "textarea", "title", "tt", "var").collect(
            Collectors.toCollection(HashSet::new));

    // -----------------------------------------------------------------------------------------------------

    /**
     * HTML5 attributes that can take a URL: http://stackoverflow.com/questions/2725156/complete-list-of-html
     * -tag-attributes-which-have-a-url-value
     * 
     * (Applet and object tags are rejected during template loading, so those tags' attributes are not listed here, but
     * they also take URL params.)
     */
    private static final HashMap<String, HashSet<String>> URL_ELT_ATTRS = new HashMap<>();
    static {
        for (String s : new String[] { "a.href", "area.href", "base.href", "blockquote.cite", "body.background",
                "del.cite", "form.action", "frame.longdesc", "frame.src", "head.profile", "iframe.longdesc",
                "iframe.src", "img.longdesc", "img.src", "img.usemap", "input.src", "input.usemap", "ins.cite",
                "link.href", "q.cite", "script.src", "audio.src", "button.formaction", "command.icon", "embed.src",
                "html.manifest", "input.formaction", "source.src", "video.poster", "video.src" }) {
            String[] parts = StringUtils.split(s, ".");
            String eltName = parts[0], attrName = parts[1];
            HashSet<String> set = URL_ELT_ATTRS.get(eltName);
            if (set == null) {
                URL_ELT_ATTRS.put(eltName, set = new HashSet<>());
            }
            set.add(attrName);
        }
    }

    /** Return true if the given HTML attribute takes a URL as a value */
    public static boolean isURLAttr(String tagName, String attrName) {
        HashSet<String> attrs = URL_ELT_ATTRS.get(tagName.toLowerCase());
        return attrs == null ? false : attrs.contains(attrName.toLowerCase());
    }

    /**
     * Whitelisted attributes that can't be exploited in an XSS attack -- source: https://www.owasp.org/index.
     * php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet#XSS_Prevention_Rules_Summary
     */
    public static final HashSet<String> XSS_SAFE_ATTRS = Stream.of("align", "alink", "alt", "bgcolor", "border",
            "cellpadding", "cellspacing", "class", "color", "cols", "colspan", "coords", "dir", "face", "height",
            "hspace", "ismap", "lang", "marginheight", "marginwidth", "multiple", "nohref", "noresize", "noshade",
            "nowrap", "ref", "rel", "rev", "rows", "rowspan", "scrolling", "shape", "span", "summary", "tabindex",
            "title", "usemap", "valign", "value", "vlink", "vspace", "width").collect(
            Collectors.toCollection(HashSet::new));

    // -----------------------------------------------------------------------------------------------------

    public static final Map<String, String> EXTENSION_TO_MIMETYPE = Arrays.stream(new String[][] {
            // See https://github.com/h5bp/server-configs-nginx/blob/master/mime.types for more
            { "txt", "text/plain" }, //
            { "htm", "text/html" }, //
            { "html", "text/html" }, //
            { "js", "application/javascript" }, //
            { "json", "application/json" }, //
            { "css", "text/css" }, //
            { "xml", "application/xml" }, //
            { "ico", "image/x-icon" }, //
            { "png", "image/png" }, //
            { "webp", "image/webp" }, //
            { "gif", "image/gif" }, //
            { "mng", "image/mng" }, //
            { "jpg", "image/jpeg" }, //
            { "jpeg", "image/jpeg" }, //
            { "svg", "image/svg+xml" }, //
            { "svgz", "image/svg+xml" }, //
            { "woff", "application/font-woff" }, //
            { "woff2", "application/font-woff2" }, //
            { "ttf", "application/x-font-ttf" }, //
            { "ttc", "application/x-font-ttf" }, //
            { "otf", "font/opentype" }, //
            { "gz", "application/x-gzip" }, //
            { "bz2", "application/x-bzip2" }, //
            { "zip", "application/zip" }, //
            { "pdf", "application/pdf" }, //
            { "ogg", "audio/ogg" }, //
            { "mp3", "audio/mpeg" }, //
            { "wav", "audio/x-wav" }, //
    }).collect(Collectors.toMap(kv -> kv[0], kv -> kv[1]));

    // -----------------------------------------------------------------------------------------------------

    /** Unescape a URL segment, and turn it from UTF-8 bytes into a Java string. TODO: test this. */
    public static String unescapeURISegment(String segment) {
        if (segment.indexOf('%') < 0)
            // URLs with no escaped characters must be valid ASCII, and are therefore valid UTF-8
            return segment;
        byte[] buf = new byte[segment.length()];
        int bufIdx = 0;
        for (int segIdx = 0, escapePos = -1, escapedByte = 0, nSeg = segment.length(); //
        segIdx < nSeg; segIdx++) {
            char c = segment.charAt(segIdx);
            if (escapePos == 0 || escapePos == 1) {
                int digit =
                        c >= '0' && c <= '9' ? (c - '0') : c >= 'a' && c <= 'f' ? (c - 'a' + 10) : c >= 'A' && c <= 'F'
                                ? (c - 'A' + 10) : -1;
                if (digit < 0) {
                    // Got an invalid digit, cancel unescaping and ignore everything from '%' to
                    // the invalid digit inclusive 
                    escapePos = -1;
                } else {
                    escapedByte = (escapedByte << 4) | digit;
                }
                if (escapePos == 0) {
                    escapePos = 1;
                } else {
                    buf[bufIdx++] = (byte) escapedByte;
                    escapePos = -1;
                }
            } else if (c == '%') {
                // Start decoding escaped char sequence, e.g. %5D
                escapePos = 0;
            } else if (c < 0xff) {
                buf[bufIdx++] = (byte) c;
            } else {
                // Should never get high-bit-set chars here, because of urlSegmentMatcher
                throw new RuntimeException("Got invalid character in URL segment: '" + c + "'");
            }
        }
        try {
            // Decode UTF-8 (UTF-8 bytes for non-ASCII characters are encoded into URLs using %-encoding) 
            return new String(Arrays.copyOf(buf, bufIdx), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /** Encode unsafe characters using %-encoding */
    private static void percentEncode(StringBuilder buf, char c) {
        buf.append('%');
        int b1 = ((c & 0xf0) >> 4), b2 = c & 0x0f;
        buf.append((char) (b1 <= 9 ? '0' + b1 : 'a' + b1 - 10));
        buf.append((char) (b2 <= 9 ? '0' + b2 : 'a' + b2 - 10));
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Convert a single URL segment (between slashes) to UTF-8, then encode any unsafe bytes. TODO: test this.
     */
    public static String escapeURISegment(String str) {
        byte[] utf8Bytes = UTF8.stringToUTF8(str);
        StringBuilder buf = new StringBuilder(utf8Bytes.length * 3);
        for (int i = 0; i < utf8Bytes.length; i++) {
            char c = (char) utf8Bytes[i];
            // See http://goo.gl/JNmVMa
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                buf.append(c);
            } else {
                percentEncode(buf, c);
            }
        }
        return buf.toString();
    }

    /**
     * Convert a URI query param key of the form "q" in "?q=v", %-encoding of UTF8 bytes for unusual characters.
     */
    public static void escapeQueryParamKey(String str, StringBuilder buf) {
        byte[] utf8Bytes = UTF8.stringToUTF8(str);
        for (int i = 0; i < utf8Bytes.length; i++) {
            char c = (char) utf8Bytes[i];
            // See http://goo.gl/OZ9OOZ
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                buf.append(c);
            } else {
                percentEncode(buf, c);
            }
        }
    }

    /**
     * Convert a URI query param value of the form "v" in "?q=v". We use '+' to escape spaces, by convention, and
     * %-encoding of UTF8 bytes for unusual characters.
     */
    public static void escapeQueryParamVal(String str, StringBuilder buf) {
        byte[] utf8Bytes = UTF8.stringToUTF8(str);
        for (int i = 0; i < utf8Bytes.length; i++) {
            char c = (char) utf8Bytes[i];
            // See http://goo.gl/s2qntq
            if (c == ' ') {
                buf.append('+');
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-'
                    || c == '.' || c == '_' || c == '~' || c == '/' || c == ':' || c == '@') {
                buf.append(c);
            } else {
                percentEncode(buf, c);
            }
        }
    }

    public static void escapeQueryParamKeyVal(String key, String val, StringBuilder buf) {
        escapeQueryParamKey(key, buf);
        buf.append('=');
        escapeQueryParamVal(val, buf);
    }

    /**
     * Split a URI into pieces and encode each piece appropriately to make it safe for use as an HTML attribute value.
     * See:
     * 
     * https://www.owasp.org/index.php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet#RULE_
     * .235_-_URL_Escape_Before_Inserting_Untrusted_Data_into_HTML_URL_Parameter_Values
     */
    public static String encodeURI(String unsafeURI) {
        // Look for scheme
        int startIdx = 0;
        int colonIdx = unsafeURI.indexOf(':');
        if (colonIdx > 0) {
            int firstSlashIdx = unsafeURI.indexOf('/');
            if (firstSlashIdx > 0 && firstSlashIdx > colonIdx) {
                startIdx = colonIdx + 1;
            }
        }
        // Look for query params
        String[] parts;
        int endIdx = unsafeURI.length();
        int paramIdx = unsafeURI.indexOf('?');
        if (paramIdx >= 0) {
            endIdx = paramIdx;
        }
        // Split part between scheme and query params at "/"
        if (startIdx == 0 && endIdx == unsafeURI.length()) {
            parts = StringUtils.split(unsafeURI, "/");
        } else {
            parts = StringUtils.split(unsafeURI.substring(startIdx, endIdx), "/");
        }
        StringBuilder buf = new StringBuilder(unsafeURI.length() * 2);
        // Add scheme, if present
        buf.append(unsafeURI.substring(0, startIdx));
        // Escape each segment of URI separately until query params 
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i > 0) {
                buf.append('/');
            }
            // FIXME: This will %-encode any unusual characters in the domain name, which will later
            // be rejected by java.net.URI. Need to use Punycode to represent general Unicode domains. 
            buf.append(escapeURISegment(part));
        }
        // Add query params, if present
        if (paramIdx >= 0) {
            buf.append('?');
            String[] qparts = StringUtils.split(unsafeURI.substring(paramIdx + 1), "&");
            for (int i = 0; i < qparts.length; i++) {
                if (i > 0) {
                    buf.append('&');
                }
                int eqIdx = qparts[i].indexOf('=');
                String key = eqIdx < 0 ? qparts[i] : qparts[i].substring(0, eqIdx);
                String val = eqIdx < 0 ? "" : qparts[i].substring(eqIdx + 1);
                escapeQueryParamKeyVal(key, val, buf);
            }
        }
        return buf.toString();
    }

    /**
     * Run a URI through the Java URI parser class to validate the URI.
     * 
     * @return the parsed URI, or null if the URI is invalid.
     */
    public static final URI parseURI(String uriStr) {
        // Double-check for XSS-unsafe characters in URIs. Most of these (except for single quote) are
        // caught by the URI parser, but just to be safe we also manually check here.
        for (int i = 0; i < uriStr.length(); i++) {
            char c = uriStr.charAt(i);
            if (c < (char) 33 || c > (char) 126 || c == '<' || c == '>' || c == '\'' || c == '"') {
                return null;
            }
        }
        try {
            return new URI(uriStr);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static final boolean isValidURI(String uriStr) {
        return parseURI(uriStr) != null;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Encode (percent-escape) any illegal characters (or UTF-8 bytes of non-ASCII characters) in a PLAIN-type cookie
     * value.
     * 
     * See: http://stackoverflow.com/questions/1969232/allowed-characters-in-cookies
     * 
     * i.e. encode if (c <= 32 || c == '"' || c == ',' || c == ';' || c == '\' || c == '%'),
     * 
     * where the additional last test (c == '%') is needed to allow for '%' to itself be escaped.
     * 
     * N.B. the list of unsafe characters is larger if cookie values are not properly double-quoted.
     */
    public static String escapeCookieValue(String str) {
        byte[] utf8Bytes = UTF8.stringToUTF8(str);
        StringBuilder buf = new StringBuilder(utf8Bytes.length * 3);
        for (int i = 0; i < utf8Bytes.length; i++) {
            char c = (char) utf8Bytes[i];
            if (c <= 32 || c == '"' || c == ',' || c == ';' || c == '\\' || c == '%') {
                percentEncode(buf, c);
            } else {
                buf.append(c);
            }
        }
        return buf.toString();
    }

    /** Decode (percent-unescape) a PLAIN-type cookie value that was encoded using escapeCookieValue(). */
    public static String unescapeCookieValue(String str) {
        return unescapeURISegment(str);
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Encodes HTML-unsafe characters as HTML entities.
     * 
     * See OWASP XSS Rule #1 at https://www.owasp.org/index.php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet
     */
    public static String encodeForHTML(CharSequence unsafeStr) {
        StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            char c = unsafeStr.charAt(i);
            switch (c) {
            case '&':
                buf.append("&amp;");
                break;
            case '<':
                buf.append("&lt;");
                break;
            case '>':
                buf.append("&gt;");
                break;
            case '"':
                buf.append("&quot;");
                break;
            case '\'':
                // See http://goo.gl/FzoP6m
                buf.append("&#x27;");
                break;
            // We don't escape '/', since this is not a dangerous char if attr values are always quoted
            //            case '/':
            //                buf.append("&#x2F;");
            //                break;

            // Encode a few common characters that like to get screwed up in some charset/browser variants
            case '—':
                buf.append("&mdash;");
                break;
            case '–':
                buf.append("&ndash;");
                break;
            case '“':
                buf.append("&ldquo;");
                break;
            case '”':
                buf.append("&rdquo;");
                break;
            case '‘':
                buf.append("&lsquo;");
                break;
            case '’':
                buf.append("&rsquo;");
                break;
            case StringUtils.NBSP_CHAR:
                buf.append("&nbsp;");
                break;

            default:
                // Non-escaped characters
                buf.append(c);
                break;
            }
        }
        return buf.toString();
    }

    /**
     * Encodes HTML-attr-unsafe chars (all non-alphanumeric chars less than 0xff) as hex codes or entities.
     */
    public static String encodeForHTMLAttribute(CharSequence unsafeStr) {
        /**
         * From OWASP XSS prevention Rule #2: "Except for alphanumeric characters, escape all characters with ASCII
         * values less than 256 with the &#xHH; format (or a named entity if available) to prevent switching out of the
         * attribute. The reason this rule is so broad is that developers frequently leave attributes unquoted. Properly
         * quoted attributes can only be escaped with the corresponding quote. Unquoted attributes can be broken out of
         * with many characters, including [space] % * + , - / ; < = > ^ and |."
         * 
         * However, if we escape as aggressively as this, then we get URLs like href="&#x2F;action&#x2F;log&#x2D;out".
         * Since attributes are all being quoted, and URL attrs are handled specially, just perform regular HTML
         * escaping inside HTML attribute values.
         */
        return encodeForHTML(unsafeStr);

        //        StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        //        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
        //            char c = unsafeStr.charAt(i);
        //            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
        //                    || ((int) c > 0xff)) {
        //                buf.append(c);
        //            } else {
        //                switch (c) {
        //                case '&':
        //                    buf.append("&amp;");
        //                    break;
        //                case '<':
        //                    buf.append("&lt;");
        //                    break;
        //                case '>':
        //                    buf.append("&gt;");
        //                    break;
        //                case '"':
        //                    buf.append("&quot;");
        //                    break;
        //                case '\'':
        //                    buf.append("&#x27;");
        //                    break;
        //                case '/':
        //                    buf.append("&#x2F;");
        //                    break;
        //                default:
        //                    buf.append("&#x");
        //                    int d1 = ((int) c) >> 4;
        //                    buf.append(d1 <= 9 ? (char) ('0' + d1) : (char) ('A' + d1 - 10));
        //                    int d2 = ((int) c) & 0xf;
        //                    buf.append(d2 <= 9 ? (char) ('0' + d2) : (char) ('A' + d2 - 10));
        //                    buf.append(';');
        //                    break;
        //                }
        //            }
        //        }
        //        return buf.toString();
    }

    // -----------------------------------------------------------------------------------------------------

    /** Escape a string to be surrounded in double quotes in JSON. */
    public static String escapeJSONString(String unsafeStr) {
        StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        escapeJSONString(unsafeStr, buf);
        return buf.toString();
    }

    /** Escape a string to be surrounded in double quotes in JSON. */
    public static void escapeJSONString(String unsafeStr, StringBuilder buf) {
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            char c = unsafeStr.charAt(i);
            // See http://www.json.org/ under "string"
            switch (c) {
            case '\\':
            case '"':
                // Forward slash can be escaped, but doesn't have to be.
                // Jackson doesn't escape it, and it makes URLs ugly.
                // case '/':
                buf.append('\\');
                buf.append(c);
                break;
            case '\b':
                buf.append("\\b");
                break;
            case '\t':
                buf.append("\\t");
                break;
            case '\n':
                buf.append("\\n");
                break;
            case '\f':
                buf.append("\\f");
                break;
            case '\r':
                buf.append("\\r");
                break;
            default:
                if (c < ' ') {
                    buf.append("\\u00");
                    int d1 = ((int) c) >> 4;
                    buf.append(d1 <= 9 ? (char) ('0' + d1) : (char) ('A' + d1 - 10));
                    int d2 = ((int) c) & 0xf;
                    buf.append(d2 <= 9 ? (char) ('0' + d2) : (char) ('A' + d2 - 10));
                } else {
                    buf.append(c);
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /** Returns true iff str is a valid email address. */
    public static final boolean isValidEmailAddr(String str) {
        return str != null && VALID_EMAIL_ADDRESS.matcher(str).matches();
    }

    /**
     * Trim whitespace from an email address, make it lowercase, and make sure it is valid. If it is not valid, return
     * null.
     */
    public static String validateAndNormalizeEmailAddr(String email) {
        if (email == null)
            return null;
        String emailNormalized = StringUtils.unicodeTrim(email).toLowerCase();
        if (isValidEmailAddr(emailNormalized))
            return emailNormalized;
        return null;
    }

}
