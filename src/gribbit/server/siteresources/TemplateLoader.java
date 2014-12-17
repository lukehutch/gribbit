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
import gribbit.model.DBModelLongKey;
import gribbit.model.DBModelObjectIdKey;
import gribbit.model.DBModelStringKey;
import gribbit.model.DataModel;
import gribbit.util.Log;
import gribbit.util.StringUtils;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class TemplateLoader {
    private SiteResources siteResources;

    private Vulcanizer vulcanizer;

    private HashMap<String, Class<? extends DataModel>> classNameToDataModel = new HashMap<>();

    private ArrayList<String> headContent = new ArrayList<>(), tailContent = new ArrayList<>();

    private HashMap<Class<? extends DataModel>, Document> templateClassToDocument = new HashMap<>();

    private static final String DATAMODEL_INLINE_TEMPLATE_FIELD_NAME = "_template";

    /** Pattern for template parameters, of the form "${name}" */
    public static final Pattern TEMPLATE_PARAM_PATTERN = Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9_]*)\\}");

    // ------------------------------------------------------------------------------------------------------------------------------------

    TemplateLoader(SiteResources siteResources, File polymerModuleRootDir) {
        this.siteResources = siteResources;
        this.vulcanizer = new Vulcanizer(siteResources, polymerModuleRootDir);
    }

    ArrayList<String> getHeadContent() {
        return headContent;
    }

    ArrayList<String> getTailContent() {
        return tailContent;
    }

    byte[] getVulcanizedHTMLBytes() {
        return vulcanizer.vulcanizedHTMLBytes;
    }

    byte[] getVulcanizedJSBytes() {
        return vulcanizer.vulcanizedJSBytes;
    }

    /** Return custom Polymer element tagnames whose templates consist of only inline elements, not block elements (for prettyprinting) */
    HashSet<String> getCustomInlineElements() {
        return vulcanizer.customInlineElements;
    }

    /** Return the named template, or null if it doesn't exist. */
    Document getTemplateDocument(Class<? extends DataModel> templateClass) {
        return templateClassToDocument.get(templateClass);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    void initializeTemplates() {
        // Vulcanize HTML/CSS/JS resources into one CSS+HTML file and one JS file, in topological sort order of dependencies 
        vulcanizer.vulcanize();

        // The set of all template names is the intersection between html file names and DataModel class names
        HashSet<String> templateNames = new HashSet<>(vulcanizer.templateNameToDocument.keySet());
        templateNames.retainAll(classNameToDataModel.keySet());

        for (String templateName : templateNames) {
            Class<? extends DataModel> templateClass = classNameToDataModel.get(templateName);
            Document templateDoc = vulcanizer.templateNameToDocument.get(templateName);

            // Cross-check parameter names between HTML templates and DataModel subclasses 
            DataModel.crossCheckDataModelAndView(siteResources, templateName, templateClass, templateDoc);

            // Create a mapping from DataModel class to the HTML doc that holds the template contents
            templateClassToDocument.put(templateClass, templateDoc);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    void gotDataModel(Class<? extends DataModel> dataModelClass) {
        if (dataModelClass != DBModel.class && dataModelClass != DBModelObjectIdKey.class && dataModelClass != DBModelStringKey.class && dataModelClass != DBModelLongKey.class) {

            // Make sure that the field types make sense given any constraint annotations on the fields
            DataModel.checkFieldTypesAgainstAnnotations(dataModelClass);

            // Store a mapping between class name and DataModel subclass
            Class<? extends DataModel> oldVal = classNameToDataModel.put(dataModelClass.getSimpleName(), dataModelClass);
            if (oldVal != null) {
                // TODO: don't use just leafnames for data models and templates; use whole package name so that names don't have to be unique across the whole project
                throw new RuntimeException("Class name \"" + dataModelClass.getSimpleName() + "\" is not unique (two different classes extending " + DataModel.class.getName()
                        + " have the same class name)");
            }

            // If the DataModel contains a "_template" field, load the inline template as if it were in an HTML file of the same name as the class
            Field templateField;
            try {
                templateField = dataModelClass.getField(DATAMODEL_INLINE_TEMPLATE_FIELD_NAME);
                if (templateField != null) {
                    int modifiers = templateField.getModifiers();
                    if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && templateField.getType().equals(String.class)) {
                        // Make it look like we loaded the inline template from a correspondingly-named HTML file
                        String relativePath = dataModelClass.getName().replace('.', '/') + ".html";
                        String templateStr = (String) templateField.get(null);
                        vulcanizer.addResource("/" + relativePath, templateStr);
                    }
                }
            } catch (NoSuchFieldException e) {
                // Ignore
            } catch (SecurityException | IllegalAccessException | IllegalArgumentException | NullPointerException e) {
                Log.warning("Could not read field " + DATAMODEL_INLINE_TEMPLATE_FIELD_NAME + " in class " + dataModelClass + ": " + e);
            }
        }
    }

    void gotWebResource(String absolutePath, String relativePath, InputStream inputStream) {
        try {
            if (absolutePath.endsWith("/head-content.html")) {
                // Load header HTML content from the classpath, and run it through Jsoup to clean it
                headContent.addAll(StringUtils.splitAsListOfString(Jsoup.parseBodyFragment(StringUtils.readWholeFile(inputStream)).body().html(), "\n"));

            } else if (absolutePath.endsWith("/tail-content.html")) {
                // Load footer HTML content from the classpath, and run it through Jsoup to clean it
                tailContent.addAll(StringUtils.splitAsListOfString(Jsoup.parseBodyFragment(StringUtils.readWholeFile(inputStream)).body().html(), "\n"));

            } else {
                // Load HTML/CSS/JS resource from the classpath
                vulcanizer.addResource("/" + relativePath, StringUtils.readWholeFile(inputStream));
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not read web resource " + absolutePath, e);
        }
        // Log.info("Found web resource: " + relativePath);
    }
}
