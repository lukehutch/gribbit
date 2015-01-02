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
import gribbit.route.RouteHandler;
import gribbit.route.Route;
import gribbit.route.RouteMapping;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.util.StringUtils;
import gribbit.util.WebUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.jsoup.nodes.Node;

import com.lukehutch.fastclasspathscanner.FastClasspathScanner;
import com.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import com.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import com.lukehutch.fastclasspathscanner.matchprocessor.SubclassMatchProcessor;

public class SiteResources {

    // Location of static resources on filesystem
    private File staticResourceRootDir;

    private final FastClasspathScanner classpathScanner;

    private RouteMapping routeMapping;

    private TemplateLoader templateLoader;

    private DataModelLoader dataModelLoader;

    private long resourcesLoadedEpochSeconds;

    // -----------------------------------------------------------------------------------------------------

    public ArrayList<Route> getAllRoutes() {
        return routeMapping.getAllRoutes();
    }

    public Route getInternalServerErrorRoute() {
        return routeMapping.getInternalServerErrorRoute();
    }

    public Route getBadRequestRoute() {
        return routeMapping.getBadRequestRoute();
    }

    public Route getNotFoundRoute() {
        return routeMapping.getNotFoundRoute();
    }

    public Route getUnauthorizedRoute() {
        return routeMapping.getUnauthorizedRoute();
    }

    public Route getUnauthorizedEmailNotValidatedRoute() {
        return routeMapping.getUnauthorizedEmailNotValidatedRoute();
    }

    /**
     * Get the Route corresponding to a given RestHandler class.
     */
    public Route routeForClass(Class<? extends RouteHandler> handlerClass) {
        return routeMapping.routeForHandler(handlerClass);
    }

    /**
     * Get the path (URI) for the Route corresponding to a given RestHandler class.
     */
    public String routeURIForHandler(Class<? extends RouteHandler> handlerClass) {
        return routeMapping.routeForHandler(handlerClass).getRoutePath();
    }

    /**
     * Get the path (URI) for the Route corresponding to a given DataModel class.
     */
    public String routeURIForDataModel(Class<? extends DataModel> formModelClass) {
        return routeMapping.routeForFormDataModel(formModelClass).getRoutePath();
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Get the HTML template document for a given template class, or return null if there isn't a template with the
     * given name.
     */
    public List<Node> getTemplateDocForClass(Class<? extends DataModel> templateClass) {
        return templateLoader.getTemplateDocumentNodes(templateClass);
    }

    public long getResourcesLoadedEpochSeconds() {
        return resourcesLoadedEpochSeconds;
    }

    /**
     * Return custom Polymer element tagnames whose templates consist of only inline elements, not block elements (for
     * prettyprinting)
     */
    public HashSet<String> getCustomInlineElements() {
        return templateLoader.getCustomInlineElements();
    }

    /**
     * Register a custom element (e.g. a Polymer or X-Tags element) as an inline element for prettyprinting (otherwise
     * it will be prettyprinted as a block element, with newlines and indentation).
     */
    public void registerCustomInlineElement(String elementName) {
        templateLoader.registerCustomInlineElement(elementName);
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Returns a File reference to a static resource, if it exists within the static resource root. Returns null if the
     * file with this URI does not exist under the static resource root.
     */
    public File getStaticResource(String reqURI) {
        // Request URI must start with "/"
        if (!reqURI.startsWith("/")) {
            return null;
        }
        if (staticResourceRootDir == null || !staticResourceRootDir.exists()) {
            return null;
        }
        File currFileOrDir = staticResourceRootDir;
        int depth = 0;
        String[] parts = StringUtils.split(reqURI, "/");
        for (String subdirName : parts) {
            // Unescape %20 -> ' ' etc.
            // N.B. the unescape is performed only once here, between '/' characters (the URI is not
            // unescaped by the caller prior to passing the URI to this method), and the unescaped string
            // is passed directly to new File() below, after checking for "." and "..", so there is no
            // possibility of a double-encoding attack -- see https://www.owasp.org/index.php/Double_Encoding
            subdirName = WebUtils.unescapeURISegment(subdirName);
            if (!currFileOrDir.isDirectory()) {
                // Files don't have subdirectory
                return null;
            } else if (subdirName.isEmpty() || subdirName.equals(".")) {
                // Skip empty or "." path segments
            } else if (subdirName.equals("..")) {
                if (depth <= 0) {
                    // Explicitly disallow ".." above the static resource root
                    return null;
                } else {
                    // Go up one dir
                    --depth;
                    currFileOrDir = currFileOrDir.getParentFile();
                }
            } else {
                // Do down one dir
                File subDir = new File(currFileOrDir, subdirName);
                if (!subDir.exists() || subDir.isHidden()) {
                    // This subdirectory or file does not exist, or is hidden
                    return null;
                } else {
                    // Traverse into subdirectory
                    ++depth;
                    currFileOrDir = subDir;
                }
            }
        }
        if (!currFileOrDir.isFile()) {
            // At end of URI, ended up at a directory -- can't serve this as a static resource
            return null;
        }
        return currFileOrDir;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Set up classpath scanner for detecting handler classes, models and templates.
     */
    public SiteResources(String appPackageName) {
        // Locate static resources
        staticResourceRootDir = null;

        if (GribbitProperties.STATIC_RESOURCE_ROOT != null) {
            staticResourceRootDir = new File(GribbitProperties.STATIC_RESOURCE_ROOT);
            if (!staticResourceRootDir.exists()) {
                throw new RuntimeException("Static resource root dir specified in gribbit.properties does not exist: "
                        + GribbitProperties.STATIC_RESOURCE_ROOT);
            }
            if (!staticResourceRootDir.isDirectory()) {
                throw new RuntimeException("Static resource root specified in gribbit.properties is not a directory: "
                        + GribbitProperties.STATIC_RESOURCE_ROOT);
            }
        }

        routeMapping = new RouteMapping();
        dataModelLoader = new DataModelLoader();
        templateLoader = new TemplateLoader(this, dataModelLoader);

        // Set up classpath scanner
        classpathScanner = new FastClasspathScanner(//
                staticResourceRootDir == null //
                        ? new String[] { "gribbit", appPackageName } //
                        : new String[] { "gribbit", appPackageName, staticResourceRootDir.getPath() })
        //
                .matchSubclassesOf(RouteHandler.class, new SubclassMatchProcessor<RouteHandler>() {
                    @Override
                    public void processMatch(Class<? extends RouteHandler> matchingClass) {
                        routeMapping.registerRoute(matchingClass);
                    }
                })
                //
                .matchSubclassesOf(DataModel.class, new SubclassMatchProcessor<DataModel>() {
                    @Override
                    public void processMatch(Class<? extends DataModel> matchingClass) {
                        dataModelLoader.registerDataModel(matchingClass);
                    }
                })
                //
                .matchSubclassesOf(DBModel.TYPE, new SubclassMatchProcessor<DBModel<?>>() {
                    @Override
                    public void processMatch(Class<? extends DBModel<?>> matchingClass) {
                        Database.registerDBModel(matchingClass);
                    }
                })
                //
                .matchFilenamePattern(".*\\.html", new FileMatchProcessor() {
                    @Override
                    public void processMatch(String absolutePath, String relativePath, InputStream inputStream) {
                        try {
                            templateLoader.registerWebResource(absolutePath, relativePath,
                                    StringUtils.readWholeFile(inputStream));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

        // If this is the second or subsequent loading of site resources, directly load constant literal
        // values of static fields that contain inline templates, so that we can dynamically pick up these
        // changes if running in the debugger in Eclipse. (Eclipse doesn't hot-swap static initializers.)
        // Full hot code swap / dynamic class reloading is problematic, see 
        // http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html
        HashSet<String> staticFieldNames =
                GribbitServer.siteResources == null ? null : GribbitServer.siteResources.dataModelLoader
                        .getInlineTemplateStaticFieldNames();
        if (staticFieldNames != null) {
            classpathScanner.matchStaticFinalFieldNames(staticFieldNames, new StaticFinalFieldMatchProcessor() {
                @Override
                public void processMatch(String className, String fieldName, Object fieldConstantValue) {
                    dataModelLoader.registerTemplateStaticFieldValue(className, (String) fieldConstantValue);
                }
            });
        }

        // Scan classpath for handlers, models and templates
        classpathScanner.scan();

        templateLoader.initializeTemplates();

        resourcesLoadedEpochSeconds = ZonedDateTime.now().toEpochSecond();
    }

    /**
     * Return true if a file or directory on the classpath has been modified since the classpath scanner was run.
     */
    public boolean classpathContentsModifiedSinceScan() {
        return classpathScanner.classpathContentsModifiedSinceScan();
    }

}
