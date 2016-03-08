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

import java.lang.reflect.ParameterizedType;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.jsoup.nodes.Node;

import gribbit.model.DBModel;
import gribbit.model.DBModelStringKey;
import gribbit.model.DataModel;
import gribbit.model.TemplateModel;
import gribbit.model.util.FieldChecker;
import gribbit.route.Route;
import gribbit.route.RouteHandler;
import gribbit.route.RouteMapping;
import gribbit.server.GribbitServer;
import gribbit.util.thirdparty.UTF8;
import gribbit.util.thirdparty.UTF8.UTF8Exception;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubclassMatchProcessor;

public class SiteResources {

    private final FastClasspathScanner classpathScanner;

    private RouteMapping routeMapping = new RouteMapping();

    private TemplateModelLoader templateModelLoader = new TemplateModelLoader(this);

    private FieldChecker fieldChecker = new FieldChecker();

    private long resourcesLoadedEpochSeconds;

    public static int CLASSPATH_CHANGE_DETECTION_POLL_INTERVAL_MS = 5000;

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
        return routeMapping.routeForHandler(handlerClass).getRoutePath().getNormalizedPath();
    }

    /**
     * Get the path (URI) for the Route corresponding to a given DataModel class.
     */
    public String routeURIForDataModel(Class<? extends DataModel> formModelClass) {
        return routeMapping.routeForFormDataModel(formModelClass).getRoutePath().getNormalizedPath();
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Get the HTML template document for a given template class, or return null if there isn't a template with the
     * given name.
     */
    public List<Node> getTemplateForClass(Class<? extends TemplateModel> templateClass) {
        return templateModelLoader.getTemplateNodes(templateClass);
    }

    public long getResourcesLoadedEpochSeconds() {
        return resourcesLoadedEpochSeconds;
    }

    /**
     * Return custom Polymer element tagnames whose templates consist of only inline elements, not block elements
     * (for prettyprinting)
     */
    public HashSet<String> getCustomInlineElements() {
        return templateModelLoader.getCustomInlineElements();
    }

    /**
     * Register a custom element (e.g. a Polymer or X-Tags element) as an inline element for prettyprinting
     * (otherwise it will be prettyprinted as a block element, with newlines and indentation).
     */
    public void registerCustomInlineElement(String elementName) {
        templateModelLoader.registerCustomInlineElement(elementName);
    }

    /** Return the templates as HTML strings, for clientside rendering. */
    public HashMap<String, String> getTemplateNameToTemplateStr() {
        return templateModelLoader.getTemplateNameToTemplateStr();
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Check the fields of a DataModel or DBModel object against any field constraint annotations (e.g. Required or
     * MinLength).
     */
    public void checkFieldValuesAgainstConstraintAnnotations(Object object) {
        fieldChecker.checkFieldValuesAgainstConstraintAnnotations(object);
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Set up classpath scanner for detecting handler classes, models and templates.
     * 
     * @param basePackageName
     *            The base package name for classpath scanning. (Templates, handlers etc. should be in this
     *            package.)
     */
    public SiteResources(String basePackageName) {
        // If this is the second or subsequent loading of site resources, directly load constant literal
        // values of static fields that contain inline templates, so that we can dynamically pick up these
        // changes if running in the debugger in Eclipse. (Eclipse doesn't hot-swap static initializers.)
        // Full hot code swap / dynamic class reloading is problematic, see 
        // http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html
        HashSet<String> prevTemplateStaticFieldNames = GribbitServer.siteResources == null ? new HashSet<>()
                : GribbitServer.siteResources.templateModelLoader.getInlineTemplateStaticFieldNames();
        final HashMap<String, String> classAndFieldNameToLatestValue = new HashMap<>();

        // Hack to get the generic parameterized class type for DBModel, see Fast Classpath Scanner documentation 
        @SuppressWarnings("unchecked")
        final Class<DBModel<?>> DBModelClass = (Class<DBModel<?>>) ((ParameterizedType) DBModelStringKey.class
                .getGenericSuperclass()).getRawType();

        // Whitelist Gribbit for scanning, so that Gribbit's own handlers and templates can be picked up
        // TODO: Eliminate all custom templates and handlers from Gribbit?
        String gribbitServerPackageName = GribbitServer.class.getPackage().getName();
        String gribbitBasePackageName = gribbitServerPackageName.substring(0,
                gribbitServerPackageName.lastIndexOf('.'));

        // Set up classpath scanner
        String[] scanSpec = basePackageName == null ? new String[] { gribbitBasePackageName }
                : new String[] { gribbitBasePackageName, basePackageName };
        classpathScanner = new FastClasspathScanner(scanSpec)
                //
                .matchSubclassesOf(RouteHandler.class, new SubclassMatchProcessor<RouteHandler>() {
                    @Override
                    public void processMatch(Class<? extends RouteHandler> matchingClass) {
                        routeMapping.registerRoute(matchingClass);
                    }
                })
                //
                .matchStaticFinalFieldNames(prevTemplateStaticFieldNames, new StaticFinalFieldMatchProcessor() {
                    @Override
                    public void processMatch(String className, String fieldName, Object currFieldConstantValue) {
                        // Read (possibly-updated) static field value directly from classfile
                        classAndFieldNameToLatestValue.put(className + "." + fieldName,
                                (String) currFieldConstantValue);
                    }
                })
                //
                .matchSubclassesOf(DataModel.class, new SubclassMatchProcessor<DataModel>() {
                    @Override
                    public void processMatch(Class<? extends DataModel> matchingClass) {
                        // Check annotations match field types
                        fieldChecker.registerClassForConstraintChecking(matchingClass);
                    }
                })
                //
                .matchSubclassesOf(DBModelClass, new SubclassMatchProcessor<DBModel<?>>() {
                    @Override
                    public void processMatch(Class<? extends DBModel<?>> matchingClass) {
                        // Register DBModel classes with database
                        @SuppressWarnings("unchecked")
                        Class<DBModel<?>> dbModelClass = (Class<DBModel<?>>) matchingClass;
                        Database.registerDBModel(dbModelClass);
                    }
                })
                //
                .matchSubclassesOf(TemplateModel.class, new SubclassMatchProcessor<TemplateModel>() {
                    @Override
                    public void processMatch(Class<? extends TemplateModel> matchingClass) {
                        // If class has a field "public static final String _template", use the latest value read 
                        // from the classfile, so that changes to template strings are supported (to support
                        // hot template updates when debugging in Eclipse).
                        //
                        // N.B. Class-based MatchProcessors are called by FastClasspathScanner after all classfiles
                        // have been read, so by the time this code is called, classAndFieldNameToLatestValue has
                        // been populated with the latest values of all static template fields of all classes.
                        String latestStaticFieldTemplateStr = classAndFieldNameToLatestValue
                                .get(matchingClass.getName() + "."
                                        + TemplateModelLoader.TEMPLATE_MODEL_INLINE_TEMPLATE_FIELD_NAME);

                        // Load and parse template corresponding to each TemplateModel class
                        Class<? extends TemplateModel> templateClass = (Class<? extends TemplateModel>) matchingClass;
                        templateModelLoader.loadTemplate(templateClass, latestStaticFieldTemplateStr);

                        // We don't register TemplateModel classes with FieldChecker -- it is assumed that if a
                        // value is being rendered into a template, it should be valid (i.e. constraint annotations
                        // are ignored for TemplateModel fields, they are only used in fields of subclasses of
                        // DataModel and DBModel).
                    }
                })
                //
                .matchFilenamePathLeaf("head-content.html", new FileMatchContentsProcessor() {
                    @Override
                    public void processMatch(String relativePath, byte[] fileContents) {
                        // Load header files named "head-content.html" from anywhere in the classpath
                        try {
                            // Log.info("Loading head content from: " + relativePath);
                            templateModelLoader.loadHeadContent(UTF8.utf8ToString(fileContents));
                        } catch (UTF8Exception e) {
                            throw new RuntimeException("File " + relativePath + " is not in UTF8 format");
                        }
                    }
                })
                //
                .matchFilenamePathLeaf("tail-content.html", new FileMatchContentsProcessor() {
                    @Override
                    public void processMatch(String relativePath, byte[] fileContents) {
                        // Load footer files named "tail-content.html" from anywhere in the classpath
                        try {
                            // Log.info("Loading tail content from: " + relativePath);
                            templateModelLoader.loadTailContent(UTF8.utf8ToString(fileContents));
                        } catch (UTF8Exception e) {
                            throw new RuntimeException("File " + relativePath + " is not in UTF8 format");
                        }
                    }
                });

        // FIXME: call field.setAccessible(true) on all fields when DataModel / TemplateModel classes are loaded,
        // so that we can handle cases where classes are not public but their fields are?

        // Scan classpath for handlers, models and templates
        classpathScanner.scan();

        templateModelLoader.initializeTemplates();

        resourcesLoadedEpochSeconds = ZonedDateTime.now().toEpochSecond();
    }

    /**
     * Return true if a file or directory on the classpath has been modified since the classpath scanner was run.
     */
    public boolean classpathContentsModifiedSinceScan() {
        return classpathScanner.classpathContentsModifiedSinceScan();
    }

}
