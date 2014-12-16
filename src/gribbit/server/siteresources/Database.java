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
import gribbit.model.field.annotation.DBIndex;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.util.Log;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.bson.types.ObjectId;
import org.mongojack.DBCursor;
import org.mongojack.JacksonDBCollection;
import org.mongojack.MongoCollection;
import org.mongojack.WriteResult;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;

public class Database {

    public static MongoClient mongoClient = null;
    static {
        try {
            mongoClient = new MongoClient(/* TODO: host and port number can be specified here */);
        } catch (Exception e) {
            throw new RuntimeException("Could not connect to database server", e);
        }
    }

    public static void checkDatabaseIsConnected() {
        try {
            // Make sure we can connect to MongoDB server
            // This prints three different stacktraces to System.err if database is not running, is there a cleaner way to check if it's running?
            mongoClient.getDatabaseNames();

        } catch (Exception e) {
            Log.error("Could not connect to database server, exiting");
            System.exit(1);
        }
    }

    /** A mapping from class to the corresponding MongoDB collection. */
    private HashMap<Class<? extends DBModel>, JacksonDBCollection<?, ?>> dbModelClassToCollection = new HashMap<>();

    /** All registered collection names, used to make sure two classes don't map to the same collection name. */
    private HashMap<String, Class<? extends DBModel>> collectionNameToDBModelClass = new HashMap<>();

    /** The id field for each DBModel. */
    private HashMap<Class<?>, Field> dbModelClassToIdField = new HashMap<>();

    /** The set of names of indexed fields for each DBModel. */
    private HashMap<Class<?>, HashSet<String>> dbModelClassToIndexedFieldNames = new HashMap<>();

    // ------------------------------------------------------------------------------------------------------------------------------------

    /** Get the collection name for a DBModel. It is derived from the simpleName of the class, but that may be overridden using the @MongoCollection annotation. */
    private static String collectionNameForDBModelClass(Class<? extends DBModel> dbModelClass) {
        // Read database collection annotation -- see http://mongojack.org/tutorial.html
        MongoCollection mongoCollectionAnnotation = dbModelClass.getAnnotation(MongoCollection.class);
        String collectionName = mongoCollectionAnnotation == null ? null : mongoCollectionAnnotation.name();
        if (collectionName == null || collectionName.isEmpty()) {
            // No @MongoCollection annotation on DBModel class, default to using class name as collection name 
            collectionName = dbModelClass.getSimpleName();
            // Log.info(DBModel.class.getName() + " subclass " + dbClass.getName() + " does not specify collection name using @" + MongoCollection.class.getSimpleName()
            //         + " annotation; using \"" + collectionName + "\" as collection name");
        }
        return collectionName;
    }

    /**
     * Wrap a MongoDB collection in a JacksonDBCollection wrapper and return it. Can be called manually by utilities that want to access database collections without starting up
     * the web server.
     */
    @SuppressWarnings("unchecked")
    public static <T extends DBModel, K> JacksonDBCollection<T, K> collectionForDBModel(Class<? extends DBModel> dbModelClass) {
        // If site resources have already been loaded, use cached mapping from DBModel class to collection 
        JacksonDBCollection<T, K> coll = null;
        if (GribbitServer.siteResources != null && GribbitServer.siteResources.db != null) {
            coll = (JacksonDBCollection<T, K>) GribbitServer.siteResources.db.dbModelClassToCollection.get(dbModelClass);
        }

        if (coll == null) {
            // If site resources have not yet been loaded, manually look up collection for DBModel

            // Get collection name, either from classname or from @MongoCollection annotation
            String collectionName = collectionNameForDBModelClass(dbModelClass);
            if (GribbitServer.siteResources != null && GribbitServer.siteResources.db != null) {
                Class<? extends DBModel> oldDBModelClass = GribbitServer.siteResources.db.collectionNameToDBModelClass.put(collectionName, dbModelClass);
                if (oldDBModelClass != null) {
                    throw new RuntimeException("Two database collection classes registered with the same name \"" + collectionName + "\": " + oldDBModelClass.getName() + ", "
                            + dbModelClass.getName());
                }
            }

            // Get id field
            Field idField = getIdFieldForDBModel(dbModelClass);

            // Check type of id field
            Class<?> idType = idField.getType();
            if ((DBModelStringKey.class.isAssignableFrom(dbModelClass) && !idType.equals(String.class)) || //
                    (DBModelLongKey.class.isAssignableFrom(dbModelClass) && !idType.equals(Long.class)) || //
                    (DBModelObjectIdKey.class.isAssignableFrom(dbModelClass) && !idType.equals(ObjectId.class))) {
                throw new RuntimeException("Field " + idField.getName() + " with @Id annotation in class " + dbModelClass.getName() + " must be of type " + idType.getSimpleName());
            }

            // Get collection based on class name (or manually overridden collection name)
            try {
                coll = (JacksonDBCollection<T, K>) JacksonDBCollection.wrap(mongoClient.getDB(GribbitProperties.DB_NAME).getCollection(collectionName), dbModelClass,
                        idField.getType());
            } catch (Exception e) {
                throw new RuntimeException("Failure during JacksonDBCollection.wrap(), this can be caused by having methods with the prefix \"get\" or \"set\". " + "Fields of "
                        + DBModel.class.getName() + " subclasses must be public, an getters/setters are not allowed.", e);
            }

            if (GribbitServer.siteResources != null && GribbitServer.siteResources.db != null) {
                // Save mapping from DBModel class to indexed fields so they're fast to find in future
                if (GribbitServer.siteResources.db.dbModelClassToIndexedFieldNames == null) {
                    GribbitServer.siteResources.db.dbModelClassToIndexedFieldNames.put(dbModelClass, getIndexedFieldsSlow(coll));
                }
                // Cache mapping from DBModel class to collection
                GribbitServer.siteResources.db.dbModelClassToCollection.put(dbModelClass, coll);
            }
        }

        return coll;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /** Register a subclass of DBModel with MongoJack. */
    public void registerDBModel(Class<? extends DBModel> dbModelClass) {
        // Ignore the DBModel[ObjectId|String|Long]Key superclasses, but register everything else
        if (dbModelClass != DBModelObjectIdKey.class && dbModelClass != DBModelStringKey.class && dbModelClass != DBModelLongKey.class) {

            // Create JacksonDBCollection and map from class to collection
            JacksonDBCollection<? extends DBModel, ?> coll = collectionForDBModel(dbModelClass);
            dbModelClassToCollection.put(dbModelClass, coll);

            // Get the set of fields in this collection that are currently indexed 
            HashSet<String> indexedFields = getIndexedFieldsSlow(coll);
            dbModelClassToIndexedFieldNames.put(dbModelClass, indexedFields);

            // Ensure that required indices exist for the collection
            for (Field field : dbModelClass.getFields()) {
                String fieldName = field.getName();
                if (field.getAnnotation(DBIndex.class) != null) {
                    // Ensure (non-unique) index for fields annotated with @DBIndex
                    coll.ensureIndex(fieldName);
                    indexedFields.add(fieldName);
                }
            }

            // Log.info("Found " + DBModel.class.getSimpleName() + " class: " + matchingClass.getName());
        }
    }

    /** Get all subclasses of DBModel (not including DBModelStringKey, DBModelLongKey or DBModelObjectIdKey). */
    public Set<Class<? extends DBModel>> getAllDBModelClasses() {
        return dbModelClassToCollection.keySet();
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Get the indexed fields of a DBModel the slow way, by querying the database.
     */
    private static <T extends DBModel, K> HashSet<String> getIndexedFieldsSlow(JacksonDBCollection<T, K> coll) {
        HashSet<String> indexedFields = new HashSet<>();
        for (DBObject obj : coll.getIndexInfo()) {
            BasicDBObject key = (BasicDBObject) obj.get("key");
            if (key != null) {
                // Each index key can consist of multiple key fields, see
                // http://docs.mongodb.org/ecosystem/tutorial/getting-started-with-java-driver/#getting-a-list-of-indexes-on-a-collection
                indexedFields.addAll(key.keySet());
            }
        }
        return indexedFields;
    }

    /**
     * Find the id field in a DBModel the slow way, by iterating through all fields.
     */
    private static Field findIdFieldSlow(Class<? extends DBModel> dbModelClass) {
        // Id fields have a name "id" or "_id", or are annotated with @Id.
        // Annotating with @Id is the same as using @JsonProperty("_id").
        // Can use either @org.mongojack.Id or @javax.persistence.Id -- see http://mongojack.org/tutorial.html
        // TODO: double-check that this is the logic that MongoJack uses to identify id field
        // TODO: do we need to ensure @Id field is the first field in the class? Or does JacksonDBMapper sort fields and queries in lexicographic order, with _id first? See http://devblog.me/wtf-mongo
        Field idField = null;
        {
            Field id = null;
            try {
                id = dbModelClass.getField("_id");
                if (id != null && (id.getAnnotation(org.mongojack.Id.class) != null || id.getAnnotation(javax.persistence.Id.class) != null)) {
                    idField = id;
                }
            } catch (NoSuchFieldException | SecurityException e) {
            }
            try {
                id = dbModelClass.getField("id");
                if (id != null && (id.getAnnotation(org.mongojack.Id.class) != null || id.getAnnotation(javax.persistence.Id.class) != null)) {
                    if (idField != null) {
                        throw new RuntimeException("Class " + dbModelClass.getName() + " has two id fields: \"" + idField.getName() + "\" and \"" + id.getName() + "\"");
                    }
                    idField = id;
                }
            } catch (NoSuchFieldException | SecurityException e) {
            }
            for (Field field : dbModelClass.getFields()) {
                for (Annotation ann : field.getAnnotations()) {
                    if (ann.annotationType() == org.mongojack.Id.class || ann.annotationType() == javax.persistence.Id.class) {
                        if (idField != null && !idField.equals(field)) {
                            throw new RuntimeException("Class "
                                    + dbModelClass.getName()
                                    + " has two id fields: \""
                                    + idField.getName()
                                    + "\" and \""
                                    + field.getName()
                                    + "\""
                                    + (DBModelStringKey.class.isAssignableFrom(dbModelClass) || DBModelLongKey.class.isAssignableFrom(dbModelClass)
                                            || DBModelLongKey.class.isAssignableFrom(dbModelClass) ? //
                                    ". If you subclass DBModelStringKey, DBModelLongKey or DBModelObjectIdKey "
                                            + "rather than DBModel, you cannot add your own @Id annotation to another field, it already has a field named \"id\"."
                                            : ""));
                        }
                        idField = field;
                    }
                }
            }
        }
        return idField;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /** Find the id field for a given DBModel. */
    public static Field getIdFieldForDBModel(Class<? extends DBModel> dbModelClass) {
        Field idField = null;
        if (GribbitServer.siteResources != null && GribbitServer.siteResources.db != null) {
            idField = GribbitServer.siteResources.db.dbModelClassToIdField.get(dbModelClass);
            if (idField == null) {
                idField = findIdFieldSlow(dbModelClass);
            }
        } else {
            idField = findIdFieldSlow(dbModelClass);
        }
        if (idField == null) {
            throw new RuntimeException(DBModel.class.getSimpleName() + " subclass " + dbModelClass.getName()
                    + " needs an id field named \"id\" or \"_id\", or a field with @Id annotation");
        }
        // Cache DBModel to id field mapping for future use
        if (GribbitServer.siteResources != null && GribbitServer.siteResources.db != null) {
            GribbitServer.siteResources.db.dbModelClassToIdField.put(dbModelClass, idField);
        }
        return idField;
    }

    /** Find the id field value for a DBModel instance. */
    public static Object getId(DBModel object) {
        Field idField = getIdFieldForDBModel(object.getClass());
        try {
            return idField.get(object);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /** Find a database object by key. */
    @SuppressWarnings("unchecked")
    public static <T extends DBModel, K> T findById(Class<T> dbModelClass, K id) {
        return (T) collectionForDBModel(dbModelClass).findOneById(id);
    }

    /**
     * Save (upsert) this object into the database.
     */
    public static WriteResult<DBModel, Object> save(DBModel object) {
        JacksonDBCollection<DBModel, Object> coll = (JacksonDBCollection<DBModel, Object>) collectionForDBModel(object.getClass());
        return coll.save(object);
    }

    /** Find an object by id. */
    @SuppressWarnings("unchecked")
    public static <T extends DBModel> T findOneById(Class<T> type, Object id) {
        JacksonDBCollection<DBModel, Object> coll = (JacksonDBCollection<DBModel, Object>) collectionForDBModel(type);
        return (T) coll.findOneById(id);
    }

    /** Check that a field exists, that it is accessible, and that it is indexed in the database. */
    private static <T extends DBModel> void checkFieldIsIndexed(JacksonDBCollection<T, Object> coll, Class<T> dbModelClass, String fieldName) {
        Field field;
        try {
            field = dbModelClass.getField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field \"" + fieldName + "\" is not a field of class " + dbModelClass.getName());
        } catch (SecurityException e) {
            throw new RuntimeException("Field \"" + fieldName + "\" of class " + dbModelClass.getName() + " is not accessible", e);
        }
        HashSet<String> indexedFieldNames = null;
        if (GribbitServer.siteResources != null && GribbitServer.siteResources.db != null) {
            indexedFieldNames = GribbitServer.siteResources.db.dbModelClassToIndexedFieldNames.get(dbModelClass);
        }
        if (indexedFieldNames == null) {
            // If the server is not running, we check that the field is indexed on every query. This adds some overhead, but it's better to warn
            // the user immediately about the lack of an index than to go ahead and run the query without an index, which would be much slower.
            indexedFieldNames = getIndexedFieldsSlow(coll);
        }
        if (indexedFieldNames == null || !indexedFieldNames.contains(field.getName())) {
            throw new RuntimeException("Field \"" + fieldName + "\" in class " + dbModelClass.getName()
                    + " is not an indexed field, so querying it will run in O(N). Add an annotation @" + DBIndex.class.getName() + " to cause the field to be indexed");
        }
    }

    /** Find an item by an indexed field's value */
    public static <T extends DBModel> T findOneByIndexedField(Class<T> type, String fieldName, String fieldValue) {
        JacksonDBCollection<T, Object> coll = collectionForDBModel(type);
        checkFieldIsIndexed(coll, type, fieldName);
        DBCursor<T> cursor = null;
        try {
            cursor = coll.find(new BasicDBObject(fieldName, fieldValue));
            if (cursor.hasNext()) {
                return cursor.next();
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /** Find an item by an indexed field's value */
    public static <T extends DBModel> ArrayList<T> findAllByIndexedField(Class<T> type, String fieldName, String fieldValue) {
        JacksonDBCollection<T, Object> coll = collectionForDBModel(type);
        checkFieldIsIndexed(coll, type, fieldName);
        ArrayList<T> results = new ArrayList<T>();
        DBCursor<T> cursor = null;
        try {
            cursor = coll.find(new BasicDBObject(fieldName, fieldValue));
            while (cursor.hasNext()) {
                results.add(cursor.next());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return results;
    }

    /**
     * Remove this object from the database.
     * 
     * @return
     */
    public static WriteResult<DBModel, Object> remove(DBModel object) {
        JacksonDBCollection<DBModel, Object> coll = (JacksonDBCollection<DBModel, Object>) collectionForDBModel(object.getClass());
        return coll.removeById(getId(object));
    }

    /**
     * Remove this object from the database. Slightly faster than remove(), because it doesn't have to find the id field.
     * 
     * @return
     */
    public static WriteResult<DBModel, Object> removeById(Class<? extends DBModel> type, Object id) {
        JacksonDBCollection<DBModel, Object> coll = (JacksonDBCollection<DBModel, Object>) collectionForDBModel(type);
        return coll.removeById(id);
    }

    /**
     * Find all objects in the database of the given type. NOTE: this may create a burden on the server if the result set is large -- use only when you know the result set is
     * guaranteed to be small, otherwise you expose the server to an OOM attack.
     */
    public static <T extends DBModel> ArrayList<T> findAll(Class<T> type) {
        ArrayList<T> results = new ArrayList<>();
        @SuppressWarnings("unchecked")
        DBCursor<T> cursor = (DBCursor<T>) collectionForDBModel(type).find();
        while (cursor.hasNext()) {
            results.add(cursor.next());
        }
        return results;
    }
}
