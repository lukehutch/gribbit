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
import gribbit.route.RouteInfo;
import gribbit.route.RouteMapping;
import gribbit.server.GribbitServer;
import gribbit.util.StringUtils;
import gribbit.util.WebUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.File;
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
import com.lukehutch.fastclasspathscanner.matchprocessor.SubinterfaceMatchProcessor;

public class SiteResources {

    // Location of static resources on filesystem
    private File staticResourceRootDir;
    private File polymerModuleRootDir;

    private final FastClasspathScanner classpathScanner;

    private RouteMapping routeMapping;

    private TemplateLoader templateLoader;

    private ByteBuf vulcanizedHTML, vulcanizedJS;

    private DataModelLoader dataModelLoader;

    private long resourcesLoadedEpochSeconds;

    // -----------------------------------------------------------------------------------------------------

    public ArrayList<RouteInfo> getAllRoutes() {
        return routeMapping.getAllRoutes();
    }

    public RouteInfo getInternalServerErrorRoute() {
        return routeMapping.getInternalServerErrorRoute();
    }

    public RouteInfo getBadRequestRoute() {
        return routeMapping.getBadRequestRoute();
    }

    public RouteInfo getNotFoundRoute() {
        return routeMapping.getNotFoundRoute();
    }

    public RouteInfo getUnauthorizedRoute() {
        return routeMapping.getUnauthorizedRoute();
    }

    public RouteInfo getEmailNotValidatedRoute() {
        return routeMapping.getEmailNotValidatedRoute();
    }

    /**
     * Get the Route corresponding to a given RestHandler class.
     */
    public RouteInfo routeForClass(Class<? extends RouteHandler> handlerClass) {
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

    public ByteBuf getVulcanizedHTML() {
        return vulcanizedHTML;
    }

    public ByteBuf getVulcanizedJS() {
        return vulcanizedJS;
    }

    /**
     * Get the HTML template document for a given template class, or return null if there isn't a template with the
     * given name.
     */
    public List<Node> getTemplateDocForClass(Class<? extends DataModel> templateClass) {
        return templateLoader.getTemplateDocument(templateClass);
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
    public SiteResources(String appPackageName, String staticResourceRootPath) {
        // Locate static resources
        staticResourceRootDir = null;
        String polymerModulePath = "polymer" + File.separator + "bower_components";
        for (File pathElement : FastClasspathScanner.getUniqueClasspathElements()) {
            if (pathElement.isDirectory()) {
                File resourceDir = new File(pathElement, staticResourceRootPath);
                if (resourceDir.exists()) {
                    if (staticResourceRootDir != null) {
                        throw new RuntimeException("Found two matches for static resource root \""
                                + staticResourceRootPath + "\" on classpath: " + staticResourceRootDir.getPath()
                                + " , " + resourceDir.getPath());
                    }
                    staticResourceRootDir = resourceDir;
                }
                File polymerDir = new File(pathElement.getPath() + File.separator + polymerModulePath);
                if (polymerDir.exists()) {
                    if (polymerModuleRootDir != null) {
                        throw new RuntimeException("Found two matches for Polymer module root \"" + polymerModulePath
                                + "\" on classpath: " + polymerModuleRootDir.getPath() + " , " + polymerDir.getPath());
                    }
                    polymerModuleRootDir = polymerDir;
                }
            }
        }
        if (staticResourceRootDir == null) {
            throw new RuntimeException("Could not find static resource path \"" + staticResourceRootPath
                    + "\" in a directory on the classpath (N.B. cannot be in zipfiles or jarfiles "
                    + "on the classpath)");
        }
        if (polymerModuleRootDir == null) {
            throw new RuntimeException("Could not find Polymer module root \"" + polymerModulePath
                    + "\" in a directory on the classpath (N.B. cannot be in zipfiles or jarfiles "
                    + "on the classpath)");
        }

        routeMapping = new RouteMapping();
        dataModelLoader = new DataModelLoader();
        templateLoader = new TemplateLoader(this, polymerModuleRootDir, dataModelLoader);

        // Set up classpath scanner
        classpathScanner = new FastClasspathScanner(//
                new String[] { "gribbit", appPackageName, staticResourceRootPath, "org/polymerproject" })
        //
                .matchSubinterfacesOf(RouteHandler.class, new SubinterfaceMatchProcessor<RouteHandler>() {
                    @Override
                    public void processMatch(Class<? extends RouteHandler> matchingInterface) {
                        routeMapping.registerRoute(matchingInterface);
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
                .matchFilenamePattern(".*\\.(html|js|css)", new FileMatchProcessor() {
                    @Override
                    public void processMatch(String absolutePath, String relativePath, InputStream inputStream) {
                        templateLoader.registerWebResource(absolutePath, relativePath, inputStream);
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

        byte[] vulcanizedHTMLBytes = templateLoader.getVulcanizedHTMLBytes();
        vulcanizedHTML = Unpooled.wrappedBuffer(vulcanizedHTMLBytes == null ? new byte[0] : vulcanizedHTMLBytes);
        byte[] vulcanizedJSBytes = templateLoader.getVulcanizedHTMLBytes();
        vulcanizedJS = Unpooled.wrappedBuffer(vulcanizedJSBytes == null ? new byte[0] : vulcanizedJSBytes);

        resourcesLoadedEpochSeconds = ZonedDateTime.now().toEpochSecond();
    }

    /**
     * Return true if a file or directory on the classpath has been modified since the classpath scanner was run.
     */
    public boolean classpathContentsModifiedSinceScan() {
        return classpathScanner.classpathContentsModifiedSinceScan();
    }

}
