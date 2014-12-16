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
import gribbit.model.field.annotation.DBIndexUnique;
import gribbit.server.GribbitServer;
import gribbit.server.config.GribbitProperties;
import gribbit.util.Log;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

import org.bson.types.ObjectId;
import org.mongojack.DBCursor;
import org.mongojack.JacksonDBCollection;
import org.mongojack.MongoCollection;
import org.mongojack.WriteResult;

import com.mongodb.BasicDBObject;
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

    /** All registered collection names. */
    private HashMap<String, Class<? extends DBModel>> collectionNameToDBModelClass = new HashMap<>();

    /** The id field for each DBModel. */
    private HashMap<Class<?>, Field> dbModelClassToIdField = new HashMap<>();

    // ------------------------------------------------------------------------------------------------------------------------------------

    /** Register a subclass of DBModel with MongoJack. */
    public void registerDBModel(Class<? extends DBModel> dbModelClass) {
        // Ignore the DBModel[ObjectId|String|Long]Key superclasses, but register everything else
        if (dbModelClass != DBModelObjectIdKey.class && dbModelClass != DBModelStringKey.class && dbModelClass != DBModelLongKey.class) {
            // Read database collection annotation -- see http://mongojack.org/tutorial.html
            MongoCollection mongoCollectionAnnotation = dbModelClass.getAnnotation(MongoCollection.class);
            String collectionName = mongoCollectionAnnotation == null ? null : mongoCollectionAnnotation.name();
            if (collectionName == null || collectionName.isEmpty()) {
                // No @MongoCollection annotation on DBModel class, default to using class name as collection name 
                collectionName = dbModelClass.getSimpleName();
                // Log.info(DBModel.class.getName() + " subclass " + dbClass.getName() + " does not specify collection name using @" + MongoCollection.class.getSimpleName()
                //         + " annotation; using \"" + collectionName + "\" as collection name");
            }
            Class<? extends DBModel> oldDBModelClass = collectionNameToDBModelClass.put(collectionName, dbModelClass);
            if (oldDBModelClass != null) {
                throw new RuntimeException("Two database collection classes registered with the same name \"" + collectionName + "\": " + oldDBModelClass.getName() + ", "
                        + dbModelClass.getName());
            }

            // Get id field
            Field idField = findIdFieldSlow(dbModelClass);
            if (idField == null) {
                throw new RuntimeException("Need a field named \"id\" or \"_id\", or with @Id annotation, in class " + dbModelClass.getName());
            }

            // Make sure type of @Id-annotated field is correct for model type (int-keyed, string-keyed or ObjectId-keyed)
            Class<?> idType = DBModelStringKey.class.isAssignableFrom(dbModelClass) ? String.class : DBModelLongKey.class.isAssignableFrom(dbModelClass) ? Long.class
                    : DBModelObjectIdKey.class.isAssignableFrom(dbModelClass) ? ObjectId.class : null;
            if (idType == null) {
                throw new RuntimeException("Class " + dbModelClass.getName() + " needs to be a subclass of " + DBModelStringKey.class.getSimpleName() + ", "
                        + DBModelLongKey.class.getSimpleName() + " or " + DBModelLongKey.class.getSimpleName());
            }
            if (idField.getType() != idType) {
                throw new RuntimeException("Field " + idField.getName() + " with @Id annotation in class " + dbModelClass.getName() + " must be of type " + idType.getSimpleName());
            }

            // Save mapping from DBModel class to id field so it's easy to find in future 
            dbModelClassToIdField.put(dbModelClass, idField);

            // Create JacksonDBCollection and map from class to collection
            JacksonDBCollection<? extends DBModel, ?> dbColl = JacksonDBCollection.wrap(mongoClient.getDB(GribbitProperties.DB_NAME).getCollection(collectionName), dbModelClass,
                    idType);
            dbModelClassToCollection.put(dbModelClass, dbColl);

            // Ensure that required indices exist for the collection
            for (Field field : dbModelClass.getFields()) {
                if (field.getAnnotation(DBIndexUnique.class) != null) {
                    // Ensure unique index
                    dbColl.ensureIndex(new BasicDBObject(field.getName(), 1), null, /* unique = */true);
                } else if (field.getAnnotation(DBIndex.class) != null) {
                    // Ensure non-unique index
                    dbColl.ensureIndex(field.getName());
                }
            }

            // Log.info("Found " + DBModel.class.getSimpleName() + " class: " + matchingClass.getName());
        }
    }

    /** Get all subclasses of DBModel (not including DBModelStringKey, DBModelLongKey or DBModelObjectIdKey). */
    public Set<Class<? extends DBModel>> getAllDBModelClasses() {
        return dbModelClassToCollection.keySet();
    }

    /**
     * Find the id field in a DBModel the slow way, by iterating through all fields. Once all SiteResources have been loaded, dbModelClassToIdField is used instead when
     * getIdFieldForDBModel() is called.
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
                if (id != null) {
                    idField = id;
                }
            } catch (NoSuchFieldException | SecurityException e) {
            }
            try {
                id = dbModelClass.getField("id");
                if (id != null) {
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
                            throw new RuntimeException("Class " + dbModelClass.getName() + " has two id fields: \"" + idField.getName() + "\" and \"" + field.getName()
                                    + "\". If you subclass DBModelStringKey, DBModelLongKey or DBModelObjectIdKey "
                                    + "rather than DBModel, you cannot add your own @Id annotation to another field, it already has a field named \"id\".");
                        }
                        idField = field;
                    }
                }
            }
        }
        return idField;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /** Find the database collection for a given DBModel class. */
    public <T extends DBModel, K> JacksonDBCollection<T, K> dbCollectionForDBModel(Class<T> klass) {
        @SuppressWarnings("unchecked")
        JacksonDBCollection<T, K> coll = (JacksonDBCollection<T, K>) dbModelClassToCollection.get(klass);
        if (coll == null) {
            throw new RuntimeException("Class " + klass.getName() + " not registered as a DBModel class");
        }
        return coll;
    }

    /** Find a database collection by object type (there is a 1-1 mapping between object types and collections). */
    public static <T extends DBModel, K> JacksonDBCollection<T, K> coll(Class<T> klass) {
        return GribbitServer.siteResources.dbCollectionForDBModel(klass);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /** Find the id field for a given DBModel. */
    public Field idFieldForDBModel(Class<? extends DBModel> klass) {
        Field field = dbModelClassToIdField.get(klass);
        if (field == null) {
            throw new RuntimeException("Class " + klass.getName() + " not registered as a DBModel class");
        }
        return field;
    }

    /** Find the id field for a given DBModel. */
    public static Field getIdFieldForDBModel(Class<? extends DBModel> klass) {
        return GribbitServer.siteResources == null ? findIdFieldSlow(klass) : GribbitServer.siteResources.idFieldForDBModel(klass);
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
    public static <T extends DBModel, K> T findById(Class<T> klass, K id) {
        return coll(klass).findOneById(id);
    }

    /**
     * Save (upsert) this object into the database.
     */
    public static WriteResult<DBModel, Object> save(DBModel object) {
        @SuppressWarnings("unchecked")
        JacksonDBCollection<DBModel, Object> coll = (JacksonDBCollection<DBModel, Object>) coll(object.getClass());
        return coll.save(object);
    }

    /** Find an object by id. */
    @SuppressWarnings("unchecked")
    public static <T extends DBModel> T findOneById(Class<T> type, Object id) {
        JacksonDBCollection<DBModel, Object> coll = (JacksonDBCollection<DBModel, Object>) coll(type);
        return (T) coll.findOneById(id);
    }

    /** Remove this object from the database. 
     * @return */
    public static WriteResult<DBModel, Object> remove(DBModel object) {
        @SuppressWarnings("unchecked")
        JacksonDBCollection<DBModel, Object> coll = (JacksonDBCollection<DBModel, Object>) coll(object.getClass());
        return coll.removeById(getId(object));
    }

    /** Remove this object from the database. Slightly faster than remove(), because it doesn't have to find the id field. 
     * @return */
    public static WriteResult<DBModel, Object> removeById(Class<? extends DBModel> type, Object id) {
        @SuppressWarnings("unchecked")
        JacksonDBCollection<DBModel, Object> coll = (JacksonDBCollection<DBModel, Object>) coll(type);
        return coll.removeById(id);
    }

    /**
     * Find all objects in the database of the given type. NOTE: this may create a burden on the server if the result set is large -- use only when you know the result set is
     * guaranteed to be small, otherwise you expose the server to an OOM attack.
     */
    public static <T extends DBModel> ArrayList<T> findAll(Class<T> type) {
        ArrayList<T> results = new ArrayList<>();
        DBCursor<T> cursor = coll(type).find();
        while (cursor.hasNext()) {
            results.add(cursor.next());
        }
        return results;
    }
}
