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
package gribbit.server;

import gribbit.auth.Cookie;
import gribbit.auth.Cookie.EncodingType;
import gribbit.handler.vulcanized.VulcanizedHTMLHandler;
import gribbit.model.DataModel;
import gribbit.server.config.GribbitProperties;
import gribbit.template.flashmessage.FlashMessage;
import gribbit.thirdparty.UTF8;
import gribbit.util.Log;
import gribbit.util.StringUtils;
import gribbit.util.WebUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;

/**
 * Objects of this class may be called from each async worker to package up HTTP response fields so that
 * they can be sent back to the main Webbit thread and submitted from there via the HttpResponse.
 */
public class Response {
    private Content content = null;
    private Content contentCached = null;
    private ByteBuf contentCachedByteBuf = EMPTY_BYTE_BUF;
    private HashMap<String, Cookie> cookies = null;
    private String redirectURI;
    private String flashMessages;
    private long lastModifiedEpochSecond;
    private HttpResponseStatus status = HttpResponseStatus.OK;

    public Response() {
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    public ByteBuf getContent(boolean forceToJSON) {
        if (forceToJSON && !(content instanceof JSONContent) && !(content instanceof ByteBufContent)) {
            // For URLs ending in "?_getmodel=1" that result in a non-JSON response (HTML or text), wrap
            // the return type of the content in a JSONContent object, so that the result is returned as
            // JSON instead.
            content = new JSONContent(content);
        }
        // Content is cached so that getContentLength() and getContent() don't both end up generating
        // the final content bytes
        if (content != contentCached) {
            contentCached = content;
            contentCachedByteBuf = content == null ? EMPTY_BYTE_BUF : content.getAsByteBuf(this);
        }
        return contentCachedByteBuf;

    }

    public String getContentType() {
        return content == null ? "text/plain" : content.type();
    }

    public int getContentLength() {
        return getContent(false).readableBytes();
    }

    public String getRedirectURI() {
        return redirectURI;
    }

    public HashMap<String, Cookie> getCookies() {
        return cookies;
    }

    public void setLastModifiedEpochSecond(long modifiedEpochSecond) {
        this.lastModifiedEpochSecond = modifiedEpochSecond;
    }

    public long getLastModifiedEpochSecond() {
        return lastModifiedEpochSecond;
    }

    private static final ByteBuf EMPTY_BYTE_BUF = Unpooled.wrappedBuffer(new byte[0]);

    // -----------------------------------------------------------------------------------------------------

    public void setFlashMessages(String flashMessages) {
        this.flashMessages = flashMessages;
    }

    public void clearFlashMessages() {
        this.flashMessages = null;
    }

    /**
     * Get the flash messages and then clear them. This should only be called by a handler that plans to
     * display the flash messages or save them back in the cookie, since it clears the flash messages before
     * returning the initial value.
     */
    public String getFlashMessages() {
        String fm = flashMessages;
        flashMessages = null;
        return fm;
    }

    public enum FlashType {
        INFO, SUCCESS, WARNING, ERROR
    };

    /**
     * Add a flash message to the response (multiple flash messages can be added per response).
     * 
     * @param flashType
     *            the type of popup
     * @param strongText
     *            optional bolded text to put before the main message
     * @param flashMessage
     *            the flash message to pop up
     */
    public Response addFlashMessage(FlashType flashType, String strongText, String flashMessage) {
        String safeMsg =
                flashType.toString() + "\t"
                        + (strongText == null ? "" : strongText.replace('\n', ' ').replace('\t', ' '))
                        + "\t"
                        + (flashMessage == null ? "" : flashMessage.replace('\n', ' ').replace('\t', ' '));
        if (flashMessages == null) {
            flashMessages = safeMsg;
        } else {
            flashMessages += "\n" + safeMsg;
        }
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    private static abstract class Content {
        public abstract ByteBuf getAsByteBuf(Response res);

        public abstract String type();
    }

    // -----------------------------------------------------------------------------------------------------

    /** HTML content type. */
    private static class HTMLContent extends Content {
        public String title;
        public Object[] content;

        public HTMLContent(String title, Object[] content) {
            this.title = title;

            // Make sure all objects in content[] are either DataModel or List<DataModel> instances
            for (Object o : content) {
                if (!(o instanceof DataModel)) {
                    if (o instanceof List) {
                        for (Object ol : (List<?>) o) {
                            if (!(ol instanceof DataModel)) {
                                throw new IllegalArgumentException("Content has type List<"
                                        + ol.getClass().getName() + ">, needs to be List<? extends "
                                        + DataModel.class.getName() + ">");
                            }
                        }
                    } else {
                        throw new IllegalArgumentException("Content must be of type "
                                + DataModel.class.getName() + " or List<? extends "
                                + DataModel.class.getName() + ">");
                    }
                }
            }
            this.content = content;
        }

        /** Render response as complete HTML doc. */
        @Override
        public ByteBuf getAsByteBuf(Response res) {
            // Render the DataModel content to an HTML string with properly HTML-escaped substituted 
            // parameter values

            StringBuilder buf = new StringBuilder(32768);
            StringUtils.append("<!DOCTYPE html>", 0, buf);
            StringUtils.append("<html lang=\"en\">", 0, buf);

            StringUtils.append("<head>", 1, buf);
            StringUtils.append("<title>", 2, buf);
            StringUtils.append(WebUtils.encodeForHTML(title), 3, buf);
            StringUtils.append("</title>", 2, buf);

            // Insert any head content from files named head-content.html in classpath to the end of
            // the head element
            for (String line : GribbitServer.siteResources.getHeadContent()) {
                StringUtils.append(line, 2, buf);
            }

            StringUtils.append("</head>", 1, buf);

            // See https://www.polymer-project.org/articles/styling-elements.html#polyfilling-unresolved
            StringUtils.append("<body unresolved>", 1, buf);

            // Insert HTML import for vulcanized Polymer resources and templates
            StringUtils.append(
                    "<link rel=\"import\" href=\""
                            + GribbitServer.siteResources.routeURIForHandler(VulcanizedHTMLHandler.class)
                            + "\">", 2, buf);

            // Add flash messages
            String flashMessages = res.getFlashMessages();
            if (flashMessages != null) {
                for (String flashMessageStringEncoding : StringUtils.split(flashMessages, "\n")) {
                    new FlashMessage(flashMessageStringEncoding).renderTemplate(
                            GribbitProperties.PRETTY_PRINT_HTML, 2, buf);
                }
            }

            // Insert body content by rendering the associated template for all DataModels in the content
            for (int i = 0; i < content.length; i++) {
                Object o = content[i];
                if (o instanceof DataModel) {
                    // content[i] is a DataModel, render associated template
                    ((DataModel) o).renderTemplate(GribbitProperties.PRETTY_PRINT_HTML, 2, buf);
                } else {
                    if (o instanceof List) {
                        // content[i] is a List<DataModel>, render templates associated with each list item
                        List<?> list = (List<?>) o;
                        for (int j = 0; j < list.size(); j++) {
                            ((DataModel) list.get(j)).renderTemplate(GribbitProperties.PRETTY_PRINT_HTML,
                                    2, buf);
                        }
                    }
                }
            }

            // Insert any tail content from files named tail-content.html in classpath to the end of
            // the body element
            for (String line : GribbitServer.siteResources.getTailContent()) {
                StringUtils.append(line, 2, buf);
            }

            StringUtils.append("</body>", 1, buf);
            StringUtils.append("</html>", 0, buf);

            return Unpooled.wrappedBuffer(UTF8.stringToUTF8(buf.toString()));
        }

        @Override
        public String type() {
            return "text/html;charset=utf-8";
        }
    }

    /** Plain text content type. */
    private static class TextContent extends Content {
        public String content;

        public TextContent(String text) {
            this.content = text;
        }

        @Override
        public ByteBuf getAsByteBuf(Response res) {
            return Unpooled.wrappedBuffer(UTF8.stringToUTF8(content));
        }

        @Override
        public String type() {
            return "text/plain;charset=utf-8";
        }
    }

    /** JSON content type. If the response is not of type String or DataModel, it is serialized to JSON. */
    private static class JSONContent extends Content {
        public Object content;

        public JSONContent(Object object) {
            this.content = object;
        }

        @Override
        public ByteBuf getAsByteBuf(Response res) {
            return Unpooled.wrappedBuffer(UTF8.stringToUTF8(DataModel.toJSON(content,
                    GribbitProperties.PRETTY_PRINT_JSON)));
        }

        @Override
        public String type() {
            return "text/plain;charset=utf-8";
        }
    }

    /**
     * Content formed of raw bytes. For internal use only, as it can return content that is vulnerable to
     * XSS attacks etc. if it is used to return HTML without proper escaping.
     */
    private static class ByteBufContent extends Content {
        private ByteBuf buf;
        private String contentType;

        public ByteBufContent(ByteBuf buf, String contentType) {
            this.buf = buf;
            this.contentType = contentType;
        }

        @Override
        public ByteBuf getAsByteBuf(Response res) {
            return buf;
        }

        @Override
        public String type() {
            return contentType;
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Set the HTML content of the response by rendering one or more DataModels and/or lists of DataModels
     * (content will be escaped as needed to be HTML-safe).
     */
    public Response setContent(String title, Object... content) {
        this.content = new HTMLContent(title, content);
        return this;
    }

    /** Set the "text/plain" content of the response. (Escaped as needed to be HTML-safe.) */
    public Response setContent(String content) {
        this.content = new TextContent(content);
        return this;
    }

    /**
     * Set the content of the response to the JSON enconding of the passed object. (This is a fallthrough if
     * setContent(String) doesn't match.)
     */
    public Response setContent(Object content) {
        this.content = new JSONContent(content);
        return this;
    }

    /**
     * Set the content of the response as a byte array with the given mime type. For internal use only, as
     * it can return content that is vulnerable to XSS attacks etc., if it is used to return HTML without
     * proper escaping.
     */
    public Response setContentUnsafe(ByteBuf contentByteBuf, String mimeType) {
        this.content = new ByteBufContent(contentByteBuf, mimeType);
        return this;
    }

    public Response setStatus(HttpResponseStatus status) {
        this.status = status;
        return this;
    }

    /**
     * Set the "text/plain" content of the response, and set the status code
     */
    public Response error(HttpResponseStatus statusCode, String text) {
        setStatus(statusCode);
        setContent(text);
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Add a possibly-base64-encoded cookie to the response.
     */
    public Response setCookie(String cookieName, String val, String path, int maxAgeInSeconds,
            EncodingType encodingType) {
        if (cookies == null)
            cookies = new HashMap<>();
        try {
            Cookie oldVal =
                    cookies.put(cookieName,
                            new Cookie(cookieName, val, path, maxAgeInSeconds, encodingType));
            if (oldVal != null) {
                Log.warning("Cookie \"" + cookieName + "\" written twice during a single request");
            }

        } catch (Exception e) {
            Log.error("Could not add cookie: " + e.getMessage());
        }
        return this;
    }

    /**
     * Add base64-encoded cookie to the response.
     */
    public Response setCookie(String key, String val, String path, int maxAgeInSeconds) {
        return setCookie(key, val, path, maxAgeInSeconds, EncodingType.BASE64_ENCODED);
    }

    /**
     * Add a base64-encoded cookie to the response.
     */
    public Response setCookie(String key, String val, String path, Instant expiry, EncodingType encodingType) {
        long secondsLeft = Math.max(0, ChronoUnit.SECONDS.between(Instant.now(), expiry));
        secondsLeft =
                secondsLeft < 0 ? 0 : secondsLeft > Integer.MAX_VALUE ? Integer.MAX_VALUE : secondsLeft;
        return setCookie(key, val, path, (int) secondsLeft, encodingType);
    }

    /**
     * Add base64-encoded cookie to the response.
     */
    public Response setCookie(String key, String val, String path, Instant expiry) {
        return setCookie(key, val, path, expiry, EncodingType.BASE64_ENCODED);
    }

    /** Delete a cookie from the response */
    public Response deleteCookie(String cookieName) {
        if (cookies == null)
            cookies = new HashMap<>();
        try {
            Cookie oldVal = cookies.put(cookieName, Cookie.deleteCookie(cookieName));
            if (oldVal != null) {
                // Log.warning("Cookie \"" + cookieName + "\" written then deleted during the same request");
            }

        } catch (Exception e) {
            Log.error("Could not delete cookie: " + e.getMessage());
        }
        return this;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Takes a raw redirect URL string. Not recommended for site-local URLs; it's better to use one of the
     * other methods that binds to a RestHandler subclass.
     */
    public void redirectToTrustedURL(String redirectFromURI, String redirectToURI) {
        this.redirectURI = redirectToURI;
        this.setContentUnsafe(EMPTY_BYTE_BUF, "text/html");
        if (redirectFromURI != null)
            setCookie(Cookie.REDIRECT_ORIGIN_COOKIE_NAME, redirectFromURI, "/", 60);
        else
            deleteCookie(Cookie.REDIRECT_ORIGIN_COOKIE_NAME);
    }

    /**
     * Takes a raw redirect URL string. Not recommended for site-local URLs; it's better to use one of the
     * other methods that binds to a RestHandler subclass.
     */
    public void redirectToTrustedURL(String redirectToURI) {
        redirectToTrustedURL(null, redirectToURI);
    }

    /**
     * Redirect to the URL that the given RestHandler is annotated with (using the GET HTTP method),
     * substituting URL params into the URL for the handler. Can simply call without params if the handler
     * takes no URI params, e.g. res.redirect(MyHandler.class). If redirectFromURI is set, then the
     * RestHandler.REDIRECT_ORIGIN_COOKIE_NAME cookie will be set to the URI that the user was redirected
     * from.
     */
    public void redirect(String redirectFromURI, Class<? extends RestHandler> redirectToHandler,
            Object... urlParams) {
        redirectToTrustedURL(redirectFromURI, Route.forGet(redirectToHandler, (Object[]) urlParams));
    }

    /**
     * Redirect to the URL that the given RestHandler is annotated with (using the GET HTTP method),
     * substituting URL params into the URL for the handler. Can simply call without params if the handler
     * takes no URI params, e.g. res.redirect(MyHandler.class).
     */
    public void redirect(Class<? extends RestHandler> handlerToRedirectTo, Object... urlParams) {
        redirect(null, handlerToRedirectTo, (Object[]) urlParams);
    }
}
