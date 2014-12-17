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

import gribbit.handler.vulcanized.VulcanizedJSHandler;
import gribbit.util.StringUtils;
import gribbit.util.UTF8;
import gribbit.util.WebUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

/**
 * Vulcanize HTML, CSS and JS resources, with special handling for Polymer elements and modules.
 * 
 * (This is basically a re-implementation of https://github.com/Polymer/vulcanize/ )
 * 
 * Concatenates a set of Web resources into two files: one HTML file and one JS file. (The JS file is separate for CSP compliance.)
 */
public class Vulcanizer {

    SiteResources siteResources;

    public byte[] vulcanizedHTMLBytes, vulcanizedJSBytes;

    public HashMap<String, Document> templateNameToDocument = new HashMap<>();

    // ------------------------------------------------------------------------------------------------------------------------------------

    private static final String POLYMER_PREFIX = "/polymer/";

    // Taken from https://github.com/Polymer/vulcanize/blob/master/lib/constants.js
    // Output match is 'Polymer(', NAME_OF_ELEMENT OR undefined, '{' OR ')'
    // Polymer() -> "Polymer()", null, ")"
    // Polymer({x}) -> "Polymer({", null, "{"
    // Polymer(y,{x}) -> "Polymer(y,{", "y", "{"
    // Polymer('y',{x}) -> "Polymer('y',{", "'y'", "{"
    // Polymer('y') -> "Polymer('y')", "'y'", ")"
    // See also https://github.com/Polymer/vulcanize/blob/master/lib/vulcan.js
    private static final Pattern POLYMER_INVOCATION = Pattern.compile("Polymer\\(([^,{]+)?(?:,\\s*)?(\\{|\\))");
    private static final Pattern CSS_URL = Pattern.compile("url\\(([^)]*)\\)");
    private static final Pattern CSS_IMPORT = Pattern.compile("@import\\s*url\\(([^)]*)\\)[;]?");

    private static final Pattern SOURCE_MAPPING_URL_COMMENT = Pattern.compile("//#[\\s]*sourceMappingURL=[^\\n]*");

    private HashSet<String> polymerTagnames = new HashSet<>();

    private HashMap<String, ArrayList<Element>> polymerTagnameToTemplateElements = new HashMap<>();

    private HashMap<String, Dep> idToDep = new HashMap<>();

    private HashMap<String, DepsAfter> idToDepsAfter = new HashMap<>();

    private HashMap<String, String> cssShimType = new HashMap<>();

    private File polymerModuleRootDir;

    final HashSet<String> customInlineElements = new HashSet<>();

    // ------------------------------------------------------------------------------------------------------------------------------------

    public Vulcanizer(SiteResources siteResources, File polymerModuleRootDir) {
        this.siteResources = siteResources;
        this.polymerModuleRootDir = polymerModuleRootDir;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

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
        // Return original URI if it is empty, starts with "#", is a template param,  or starts with a Polymer param (starts with "{{")
        if (hrefURI != null && !hrefURI.isEmpty() && !hrefURI.startsWith("#") && !WebUtils.EXTERNAL_URI.matcher(hrefURI).matches()
                && !TemplateLoader.TEMPLATE_PARAM_PATTERN.matcher(hrefURI).find() && !hrefURI.startsWith("{{")) {
            // Build new path for the linked resource
            StringBuilder hrefURIResolved = new StringBuilder(hrefURI.startsWith("//") ? "//" : hrefURI.startsWith("/") ? "/" : baseURI);
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

    // ------------------------------------------------------------------------------------------------------------------------------------

    private static class DepsAfter {
        public String originURI;
        public ArrayList<String> idsAfter = new ArrayList<>();

        public DepsAfter(String originURI, String idAfter) {
            this.idsAfter.add(idAfter);
            this.originURI = originURI;
        }
    }

    private void addDepOrder(String originURI, String idBefore, String idAfter) {
        DepsAfter depsAfter = idToDepsAfter.get(idBefore);
        if (depsAfter == null) {
            idToDepsAfter.put(idBefore, depsAfter = new DepsAfter(originURI, idAfter));
        } else {
            depsAfter.idsAfter.add(idAfter);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    private static class Dep {
        public String id;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    private static class HTMLDep extends Dep {
        public Document doc;

        public HTMLDep(String id, Document doc) {
            this.id = id;
            this.doc = doc;
        }
    }

    private void addHTML(String id, String html, String baseURI) {
        Document bodyFragment = Jsoup.parseBodyFragment(html);

        HTMLDep dep = new HTMLDep(id, bodyFragment);
        idToDep.put(dep.id, dep);

        for (Element e : dep.doc.getAllElements()) {
            // Strip HTML comments
            ArrayList<Node> childNodes = new ArrayList<>(e.childNodes());
            for (Node n : childNodes) {
                if (n instanceof Comment) {
                    String commentStr = ((Comment) n).getData();
                    // Remove all comments that do not start with "[if " or " [if " (which test for IE versions),
                    // and which do not contain "@license" (since we shouldn't be stripping away license information)
                    if (!commentStr.startsWith("[if ") && !commentStr.startsWith(" [if ") && !commentStr.contains("@license")) {
                        n.remove();
                    }
                }
            }

            // Make URLs absolute
            if (e.hasAttr("href")) {
                e.attr("href", resolveHREF(e.attr("href"), baseURI));
            }
            if (e.hasAttr("src")) {
                e.attr("src", resolveHREF(e.attr("src"), baseURI));

                // TODO: inline Polymer image resources as data: URIs
            }

            // Create dependency for linked stylesheets
            if (e.tagName().equalsIgnoreCase("link")) {
                String rel = e.attr("rel");
                if (rel != null && !rel.isEmpty()) {
                    if (rel.equalsIgnoreCase("stylesheet")) {
                        String href = e.attr("href");
                        if (href != null && !href.isEmpty()) {
                            // FIXME: if we vulcanize linked stylesheets, then the styles will be moved out of the scope of the element.
                            // FIXME: I think Polymer does the opposite: it inlines the stylesheet into the custom element.
                            // FIXME: for now, leave the link in place.

                            //    // Not currently vulcanizing absolute URIs
                            //    if (!EXTERNAL_URI.matcher(href).matches()) {
                            //        if (e.hasAttr("no-shim")) {
                            //            cssShimType.put(href, "no-shim");
                            //        } else if (e.hasAttr("shim-shadowdom")) {
                            //            cssShimType.put(href, "shim-shadowdom");
                            //        }
                            //        // Put CSS before this HTML
                            //        addDepOrder(id, href, id);
                            //        // Remove CSS link from doc
                            //        e.remove();
                            //        // Enqueue the linked stylesheet
                            //        enqueue(href);
                            //    }
                        }
                    }
                }
            }
        }

        // Extract script elements
        int jsIdCounter = 0;
        for (Element e : dep.doc.getElementsByTag("script")) {
            // Get script element's src attribute and/or content 
            DataNode scriptContentNode = e.childNodeSize() > 0 ? (DataNode) e.childNode(0) : null;
            String scriptContent = scriptContentNode == null ? null : scriptContentNode.getWholeData();
            String scriptContentTrimmed = scriptContent == null ? null : StringUtils.unicodeTrim(scriptContent);
            if (scriptContentTrimmed != null && scriptContentTrimmed.isEmpty()) {
                scriptContent = scriptContentTrimmed = null;
            }
            String src = e.hasAttr("src") ? e.attr("src") : null;
            if (src != null && StringUtils.unicodeTrim(src).isEmpty()) {
                src = null;
            }
            if (src != null && scriptContent != null) {
                throw new RuntimeException("Script element in template " + id + " has both a src attribute and script content");
            }

            // Handle scripts inside polymer-elements
            String scriptDependency = id;
            if (e.parent().tagName().equalsIgnoreCase("polymer-element")) {
                // Script is included as the child of a polymer-element
                String polymerElementName = e.parent().attr("name");
                if (polymerElementName == null) {
                    throw new RuntimeException("Polymer element in template " + id + " is missing the required attribute \"name\"");
                }
                polymerElementName = polymerElementName.toLowerCase();
                // Schedule scripts extracted from this polymer-element as dependencies of the polymer-element, not the parent
                scriptDependency = id + ":" + polymerElementName;

                if (e.parent().hasAttr("noscript")) {
                    throw new RuntimeException("Polymer element in template " + id + " has \"noscript\" attribute, but contains a script");

                } else {
                    // Adjust Polymer() invocation to explicitly include the tag name as the first parameter if it's not present.
                    // Ported from https://github.com/Polymer/vulcanize/blob/master/lib/vulcan.js , function handleMainDocument()
                    boolean hasMatch = false;
                    if (scriptContent != null) {
                        Matcher matcher = POLYMER_INVOCATION.matcher(scriptContent);
                        hasMatch = matcher.find();
                        if (hasMatch) {
                            // Skip Polymer() calls that already have the tag name
                            if (matcher.group(1) == null) {
                                // build the named Polymer invocation
                                String namedInvocation = "Polymer('" + polymerElementName + "'" + (matcher.group(2).equals("{") ? ",{" : ")");
                                scriptContent = scriptContent.replace(matcher.group(0), namedInvocation);
                            }
                        }
                    }
                    if (!hasMatch) {
                        throw new RuntimeException("Polymer element " + polymerElementName + " includes a script tag that does not call Polymer()");
                    }
                }
            }

            if (scriptContent != null) {
                // Remove the script tag, and add a dependency between this HTML file and the extracted script
                String extractedJSId = id + ":js" + jsIdCounter++;
                addJS(extractedJSId, scriptContent);
                addDepOrder(id, scriptDependency, extractedJSId);
                e.remove();

            } else if (src != null) {
                // Not currently vulcanizing absolute URIs
                if (!WebUtils.EXTERNAL_URI.matcher(src).matches()) {
                    // This script tag has a site-local src attribute, schedule the JS to be included after this HTML
                    addDepOrder(id, id, src);
                    // Remove this script tag from doc
                    e.remove();
                    // Enqueue the linked script
                    enqueue(src);
                }
            }
        }

        // Extract style elements that are not part of a polymer-element and do not have an HTML "scoped" attribute
        int cssIdCounter = 0;
        for (Element e : dep.doc.getElementsByTag("style")) {
            DataNode styleContentNode = e.childNodeSize() > 0 ? (DataNode) e.childNode(0) : null;
            String styleContent = styleContentNode == null ? null : styleContentNode.getWholeData();
            if (styleContent != null) {
                String parentTag = e.parent().tagName().toLowerCase();
                if (!parentTag.equals("polymer-element") && !e.hasAttr("scoped") && (!e.hasAttr("type") || e.attr("type").equalsIgnoreCase("text/css"))) {
                    String cssId = id + ":css" + cssIdCounter++;
                    if (e.hasAttr("no-shim")) {
                        cssShimType.put(cssId, "no-shim");
                    } else if (e.hasAttr("shim-shadowdom")) {
                        cssShimType.put(cssId, "shim-shadowdom");
                    }
                    // Remove CSS from this HTML doc and make this HTML doc a dependency of the CSS
                    addCSS(cssId, styleContent, baseURI);
                    addDepOrder(id, cssId, id);
                    e.remove();
                }
            }
        }

        // Extract remaining nodes
        boolean templateHasOnlyEmptyNodes = true;
        String uriLeaf = id.substring(id.lastIndexOf('/') + 1);
        String uriLeafBaseName = uriLeaf.substring(0, uriLeaf.lastIndexOf('.'));
        ArrayList<Node> remainingChildNodes = new ArrayList<>(dep.doc.body().childNodes());
        Document templateDoc = Jsoup.parseBodyFragment("");
        for (Node n : remainingChildNodes) {
            Element e = n instanceof Element ? (Element) n : null;
            String tagName = e != null ? e.tagName() : null;
            if ("polymer-element".equals(tagName)) {
                // Extract polymer-element elements

                // Check validity of polymer-element tag name
                String polymerElementName = n.attr("name");
                if (polymerElementName == null) {
                    throw new RuntimeException("Polymer element in template " + id + " is missing the required attribute \"name\"");
                }
                polymerElementName = polymerElementName.toLowerCase();
                if (polymerElementName.indexOf('-') < 0) {
                    throw new RuntimeException("The Polymer tag name \"" + polymerElementName + "\" in template " + id + " does not include a hyphen character");
                } else if (!WebUtils.VALID_HTML_NAME_OR_ID.matcher(polymerElementName).matches()) {
                    throw new RuntimeException("Polymer element name \"" + polymerElementName + "\" in template " + id + " is invalid");
                }
                if (!polymerTagnames.add(polymerElementName)) {
                    throw new RuntimeException("Multiple Polymer elements define the same tag name \"" + polymerElementName + "\"");
                }

                // Handle Polymer "noscript" elements by injecting an explicit initializer in the JS to preserve initialization order
                String polymerEltId = id + ":" + polymerElementName;
                if (n.hasAttr("noscript")) {
                    String injectedJSId = id + ":js" + cssIdCounter++;
                    addJS(injectedJSId, "Polymer('" + polymerElementName + "');");
                    addDepOrder(id, polymerEltId, injectedJSId);
                    n.removeAttr("noscript");
                }

                // Move the Polymer element into its own doc, and add it as a dependant of the original doc
                Document polymerEltDoc = Jsoup.parseBodyFragment("");
                n.remove();
                polymerEltDoc.body().appendChild(n);
                HTMLDep polymerDep = new HTMLDep(polymerEltId, polymerEltDoc);
                idToDep.put(polymerEltId, polymerDep);
                addDepOrder(id, id, polymerEltId);

                // Clone the element template for later use in determining if template is block or inline
                Elements template = e.getElementsByTag("template");
                ArrayList<Element> templateElements = new ArrayList<>();
                for (Element et : template) {
                    templateElements.addAll(et.children());
                }
                polymerTagnameToTemplateElements.put(polymerElementName, templateElements);

            } else if ("link".equals(tagName)) {
                // Create a dependency for HTML5 imports

                String rel = n.attr("rel");
                if (rel != null && !rel.isEmpty()) {
                    if (rel.equalsIgnoreCase("import")) {
                        String href = n.attr("href");
                        if (href != null && !href.isEmpty()) {
                            if (!WebUtils.EXTERNAL_URI.matcher(href).matches()) {
                                // Put this page before the linked page
                                addDepOrder(id, id, href);
                                // Remove link from doc
                                n.remove();
                                // Enqueue the linked HTML file
                                enqueue(href);
                            }
                        }
                    }
                }

            } else {
                // Element is not a polymer-element and not an HTML import.
                // Concatenate all such elements into a new template document. This will later be paired with a sub-class
                // of the DataModel class that has the same name as the leaf name of this HTML doc without the .html suffix.

                // Move this HTML element into the template
                n.remove();
                templateDoc.body().appendChild(n);

                if (!(n instanceof TextNode) || StringUtils.unicodeTrimCharSequence(((TextNode) n).text()).length() > 0) {
                    templateHasOnlyEmptyNodes = false;
                }
            }
        }
        if (!templateHasOnlyEmptyNodes) {
            // Don't add template if there are only empty text nodes
            Document oldDoc = templateNameToDocument.put(uriLeafBaseName, templateDoc);
            if (oldDoc != null) {
                // We only match template names and DataModel names on the leafname currently, so html files in classpath have to have unique names
                String leaf = StringUtils.leafName(baseURI);
                throw new RuntimeException("Two HTML template files found in classpath with same name \"" + leaf + "\"");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    private static class CSSDep extends Dep {
        public String css;

        public CSSDep(String id, String css) {
            this.id = id;
            this.css = css;
        }
    }

    private void addCSS(String id, String css, String baseURI) {
        CSSDep dep = new CSSDep(id, css);
        idToDep.put(id, dep);

        // Resolve relative "url()" entries in CSS
        Matcher urlMatcher = CSS_URL.matcher(dep.css);
        while (urlMatcher.find()) {
            dep.css = dep.css.replace(urlMatcher.group(0), resolveHREF(urlMatcher.group(1), baseURI));

            // TODO: inline Polymer image resources as data: URIs
        }

        // Resolve CSS imports (not supported by all browsers).
        Matcher importMatcher = CSS_IMPORT.matcher(css);
        while (importMatcher.find()) {
            String importURI = importMatcher.group(1);
            // Not currently vulcanizing absolute URIs
            if (!WebUtils.EXTERNAL_URI.matcher(importURI).matches()) {
                // Copy over any shim mode info to the import (shim mode is sticky across imports)
                String shimType = cssShimType.get(id);
                if (shimType != null) {
                    cssShimType.put(importURI, shimType);
                }
                // Schedule import to be included before this css file
                addDepOrder(id, importURI, id);
                // Remove the import from this css file
                dep.css = dep.css.replace(importMatcher.group(0), "");
                // Enqueue the linked CSS file
                enqueue(importURI);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    private static class JSDep extends Dep {
        public String script;

        public JSDep(String id, String script) {
            this.id = id;
            this.script = script;
        }
    }

    private void addJS(String id, String script) {
        JSDep dep = new JSDep(id, script);
        idToDep.put(id, dep);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    private void topologicalSort(Dep currDep, LinkedList<Dep> topoOrder, HashSet<String> path, HashSet<String> visited) {
        if (visited.add(currDep.id)) {
            if (!path.add(currDep.id)) {
                throw new RuntimeException("Circular dependency in HTML imports, involving the nodes: " + path);
            }
            DepsAfter depsAfter = idToDepsAfter.get(currDep.id);
            if (depsAfter != null) {
                // Recurse to dependants
                ArrayList<String> dependants = depsAfter.idsAfter;
                for (int i = dependants.size() - 1; i >= 0; --i) {
                    Dep dependantDep = idToDep.get(dependants.get(i));
                    if (dependantDep == null) {
                        throw new RuntimeException("Could not find resource " + dependants.get(i));
                    }
                    topologicalSort(dependantDep, topoOrder, path, visited);
                }
            }
            topoOrder.addFirst(currDep);
            path.remove(currDep.id);
        }
    }

    private LinkedList<Dep> topologicalSort() {
        // Check that all ids exist (i.e. that all referenced URIs were loaded),
        // and determine all roots (HTMLDeps that are not scheduled after any other HTMLDep)
        HashSet<String> rootIds = new HashSet<>();
        HashSet<String> nonRootIds = new HashSet<>();
        for (Entry<String, DepsAfter> ent : idToDepsAfter.entrySet()) {
            String idBefore = ent.getKey();
            rootIds.add(idBefore);
            if (!idToDep.containsKey(idBefore)) {
                throw new RuntimeException("Resource " + idBefore + " does not exist, referenced in " + ent.getValue().originURI);
            }
            for (String idAfter : ent.getValue().idsAfter) {
                nonRootIds.add(idAfter);
                if (!idToDep.containsKey(idAfter)) {
                    throw new RuntimeException("Resource " + idAfter + " does not exist, cited in " + ent.getValue().originURI);
                }
            }
        }
        rootIds.removeAll(nonRootIds);

        // Sort root nodes, putting Polymer resources first then the rest in alphabetical order by id for consistency
        ArrayList<String> rootIdsSorted = new ArrayList<>(rootIds);
        Collections.sort(rootIdsSorted, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                int p1 = o1.startsWith(POLYMER_PREFIX) ? 0 : 1;
                int p2 = o2.startsWith(POLYMER_PREFIX) ? 0 : 1;
                int diff = p1 - p2;
                if (diff != 0) {
                    return diff;
                } else {
                    return o1.compareTo(o2);
                }
            }
        });

        // Perform topological sort from roots
        LinkedList<Dep> topoOrder = new LinkedList<>();
        HashSet<String> visited = new HashSet<>();
        for (int i = rootIdsSorted.size() - 1; i >= 0; --i) {
            Dep dep = idToDep.get(rootIdsSorted.get(i));
            topologicalSort(dep, topoOrder, new HashSet<>(), visited);
        }
        return topoOrder;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    private HashMap<String, String> uriToContent = new HashMap<>();

    private LinkedList<String> uriQueue = new LinkedList<>();

    /** Enqueue a web resource for vulcanization, when the complete content is available. */
    private void enqueue(String uri, String content) {
        if (uriToContent.put(uri, content) != null) {
            throw new RuntimeException("URI " + uri + " occurs twice in different classpath elements");
        }
        uriQueue.add(uri);
    }

    /** Enqueue a web resource for vulcanization, given the local URI. */
    private void enqueue(String uri) {
        File file = null;
        if (uri.startsWith(POLYMER_PREFIX)) {
            // Only enqueue this URI if it hasn't already been enqueued
            if (!uriToContent.containsKey(uri)) {
                // Enqueue a Polymer component
                file = new File(polymerModuleRootDir, uri.substring(POLYMER_PREFIX.length()));
                if (!file.exists()) {
                    throw new RuntimeException("Polymer resource doesn't exist: " + uri);
                }
            }
        } else {
            // See if there is a static resource with this URI
            file = siteResources.getStaticResource(uri);
        }

        if (file != null) {
            String content;
            try {
                content = FileUtils.readFileToString(file, Charset.forName("UTF-8"));
            } catch (IOException e) {
                throw new RuntimeException("Could not read linked web resource " + uri + ": " + e.getMessage());
            }
            // Enqueue the resource
            enqueue(uri, content);

        } else {
            // Ignore resources not in the Polymer component or static resource root.
            // These resources should also be on the classpath, so should be loaded during classpath scanning.
            // If they don't exist, an exception will be thrown during dependency resolution.
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Vulcanize HTML/CSS/JS resources.
     */
    void vulcanize() {
        String vulcanizedJSHandlerURI = siteResources.routeURIForHandler(VulcanizedJSHandler.class);

        // Always enqueue the main Polymer files. TODO: these are added as two separate dependencies right now, replacing the single import of polymer.html. Should probably just import polymer.html.
        String webcomponentsJS = POLYMER_PREFIX + "polymer/polymer.min.js";
        String polymerHTML = POLYMER_PREFIX + "polymer/layout.html";
        enqueue(webcomponentsJS);
        enqueue(polymerHTML);

        // Process URI queue, enqueuing any other linked resources found along the way
        while (!uriQueue.isEmpty()) {
            String uri = uriQueue.removeFirst();
            String content = uriToContent.get(uri);

            String baseURI = uri.substring(0, uri.lastIndexOf('/'));
            if (uri.endsWith(".html") ||
            // For HTML templates embedded inline in a static _template field in a DataModel class:
                    uri.endsWith(".java")) {
                addHTML(uri, content, baseURI);
            } else if (uri.endsWith(".css")) {
                addCSS(uri, content, baseURI);
            } else if (uri.endsWith(".js")) {
                addJS(uri, content);
            } else {
                // Should not happen (matcher should only pass the above filetypes to this method)
                throw new RuntimeException("Bad file extension on end of URI " + uri);
            }
        }

        // Schedule all JS and HTML files for inclusion after the main Polymer files
        for (Dep dep : idToDep.values()) {
            if (dep instanceof HTMLDep && !dep.id.equals(polymerHTML)) {
                addDepOrder("/", polymerHTML, dep.id);
            } else if (dep instanceof JSDep && !dep.id.equals(webcomponentsJS)) {
                addDepOrder("/", webcomponentsJS, dep.id);
            }
        }

        // Perform a topological sort of the dependency graph
        LinkedList<Dep> depOrder = topologicalSort();

        // Produce a single HTML, CSS and JS file out of segments in the topological sort order of the dependency graph.
        // Vulcanized HTML content is added within a <div hidden> element.
        Document htmlCombined = Jsoup.parseBodyFragment("<div hidden></div>");
        Element htmlDiv = htmlCombined.getElementsByTag("div").get(0);
        StringBuilder cssCombined = new StringBuilder(65536);
        StringBuilder cssCombinedNoShim = new StringBuilder(65536);
        StringBuilder cssCombinedShimShadowDom = new StringBuilder(65536);
        int cssCombinedOrder = -1, cssCombinedNoShimOrder = -1, cssCombinedShimShadowDomOrder = -1, numCSSShimTypes = 0;
        StringBuilder jsCombined = new StringBuilder(1000000);
        for (Dep dep : depOrder) {
            if (dep instanceof HTMLDep) {
                // Vulcanize HTML in dependency order

                ArrayList<Node> childNodes = new ArrayList<>(((HTMLDep) dep).doc.body().childNodes());
                for (Node child : childNodes) {
                    child.remove();
                    htmlDiv.appendChild(child);
                }

            } else if (dep instanceof CSSDep) {
                // Vulcanize CSS in dependency order

                CSSDep cssDep = (CSSDep) dep;
                // Group together CSS content by shim type, see http://www.polymer-project.org/docs/polymer/styling.html#stylingattrs
                // Also figure out what order to include different shim types in, to preserve relative ordering of the first instance
                // of each shim type (CSS includes are the only way to add a dependency between two CSS files, and the shim type defined
                // by the HTML style element that included the first CSS file is sticky, so CSS files can be grouped by shim type)
                String shimType = cssShimType.get(dep.id);
                if (shimType != null && shimType.equals("no-shim")) {
                    cssCombinedNoShim.append(cssDep.css);
                    cssCombinedNoShim.append('\n');
                    if (cssCombinedNoShimOrder == -1) {
                        cssCombinedNoShimOrder = Math.max(cssCombinedOrder, cssCombinedShimShadowDomOrder) + 1;
                        numCSSShimTypes++;
                    }
                } else if (shimType != null && shimType.equals("shim-shadowdom")) {
                    cssCombinedShimShadowDom.append(cssDep.css);
                    cssCombinedShimShadowDom.append('\n');
                    if (cssCombinedShimShadowDomOrder == -1) {
                        cssCombinedShimShadowDomOrder = Math.max(cssCombinedOrder, cssCombinedNoShimOrder) + 1;
                        numCSSShimTypes++;
                    }
                } else {
                    cssCombined.append(cssDep.css);
                    cssCombined.append('\n');
                    if (cssCombinedOrder == -1) {
                        cssCombinedOrder = Math.max(cssCombinedNoShimOrder, cssCombinedShimShadowDomOrder) + 1;
                        numCSSShimTypes++;
                    }
                }

            } else if (dep instanceof JSDep) {
                // Vulcanize JS in dependency order

                String script = ((JSDep) dep).script;
                boolean hasFinalSemicolon = false;
                for (int i = script.length() - 1; i >= 0; --i) {
                    char c = script.charAt(i);
                    if (!StringUtils.isUnicodeWhitespace(c)) {
                        if (c == ';') {
                            hasFinalSemicolon = true;
                            break;
                        }
                    }
                }
                jsCombined.append(script);
                if (!hasFinalSemicolon) {
                    // Make sure there is a semicolon after the last statement in every script
                    jsCombined.append(';');
                }
                jsCombined.append('\n');
            }
        }
        String cssCombinedStr = StringUtils.unicodeTrim(cssCombined.toString());
        String cssCombinedNoShimStr = StringUtils.unicodeTrim(cssCombinedNoShim.toString());
        String cssCombinedShimShadowDomStr = StringUtils.unicodeTrim(cssCombinedShimShadowDom.toString());

        // TODO: minify the CSS and JS

        // Add the vulcanized CSS to the beginning of the vulcanized HTML doc as one or more style elements
        for (int i = 0; i < numCSSShimTypes; i++) {
            if (cssCombinedOrder == i && !cssCombinedStr.isEmpty()) {
                Element styleElt = htmlCombined.body().prependElement("style");
                styleElt.appendChild(new DataNode(cssCombinedStr, ""));
            } else if (cssCombinedNoShimOrder == i && cssCombinedNoShimStr.isEmpty()) {
                Element styleElt = htmlCombined.body().prependElement("style");
                styleElt.attr("no-shim", "");
                styleElt.appendChild(new DataNode(cssCombinedNoShimStr, ""));
            } else if (cssCombinedShimShadowDomOrder == i && !cssCombinedShimShadowDomStr.isEmpty()) {
                Element styleElt = htmlCombined.body().prependElement("style");
                styleElt.attr("shim-shadowdom", "");
                styleElt.appendChild(new DataNode(cssCombinedShimShadowDomStr, ""));
            }
        }

        // Add link to the vulcanized JS to the end of the vulcanized HTML doc.
        // JS is served on a different URI for CSP compliance.
        String jsCombinedStr = "";
        if (jsCombined.length() > 0) {
            htmlCombined.body().prependElement("script").attr("type", "application/javascript").attr("src", vulcanizedJSHandlerURI);

            // Remove source mapping URLs from vulcanized content because they will no longer work and just give 404 errors if they're not available 
            jsCombinedStr = SOURCE_MAPPING_URL_COMMENT.matcher(jsCombined).replaceAll("");
        }
        String htmlCombinedStr = "<!DOCTYPE html>\n" + htmlCombined.body().html();

        // Check if Polymer elements are block or inline
        HashSet<String> blockElementTagNames = new HashSet<>();
        boolean somethingChanged;
        do {
            somethingChanged = false;
            for (Entry<String, ArrayList<Element>> ent : polymerTagnameToTemplateElements.entrySet()) {
                String tagName = ent.getKey();
                if (!blockElementTagNames.contains(tagName)) {
                    // Polymer element is not yet known to be a block element.
                    boolean isBlock = false;
                    // Check if any element inside the Polymer element's template is known to be a block element
                    for (Element templateElt : ent.getValue()) {
                        String templateEltTagName = templateElt.tagName();
                        // If this Polymer element contains a block Polymer element in its template, or
                        if (blockElementTagNames.contains(templateEltTagName) || //
                                // ..if this is a non-custom tag and it's not an inline element or Polymer's special "content" element, then 
                                (!templateEltTagName.contains("-") && !WebUtils.INLINE_ELEMENTS.contains(templateEltTagName) && !templateEltTagName.equals("content"))) {
                            // This element in the template is a block element, so the custom tag should be treated as a block element
                            isBlock = true;
                            break;
                        }
                    }
                    if (isBlock) {
                        // Element was not known to be a block element before, but now it is; add it to the set of known block elements
                        // Log.info("Found a Polymer block element: " + tagName);
                        blockElementTagNames.add(tagName);
                        somethingChanged = true;
                    }
                }
            }
            // Polymer elements' templates can contain other polymer element tags, but initially we don't know if those other elements are block elements or not,
            // so we need to iterate until we have discovered all block elements.
        } while (somethingChanged);
        for (String tagName : polymerTagnameToTemplateElements.keySet()) {
            if (!blockElementTagNames.contains(tagName)) {
                // Add tagnames of custom Polymer elements to the list of known inline elements if they do not contain block elements
                customInlineElements.add(tagName);
            }
        }

        // Convert vulcanized content to ByteBufs for fast serving
        vulcanizedJSBytes = UTF8.stringToUTF8(jsCombinedStr);
        vulcanizedHTMLBytes = UTF8.stringToUTF8(htmlCombinedStr);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /** Load a template from an InputStream. */
    void addResource(String uri, String contents) {
        enqueue(uri, contents);
    }
}
