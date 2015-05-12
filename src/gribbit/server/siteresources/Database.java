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
import gribbit.server.config.GribbitProperties;
import gribbit.util.Log;
import gribbit.util.Reflection;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import org.mongojack.DBCursor;
import org.mongojack.JacksonDBCollection;
import org.mongojack.MongoCollection;
import org.mongojack.WriteResult;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;

public class Database {

    /** A mapping from DBModel subclass to the corresponding MongoDB collection. */
    @SuppressWarnings("rawtypes")
    private static ConcurrentHashMap<Class<? extends DBModel>, JacksonDBCollection<? extends DBModel<?>, ?>> //
    dbModelClassToCollection = new ConcurrentHashMap<>();

    /** All registered collection names, used to make sure two classes don't map to the same collection name. */
    private static ConcurrentHashMap<String, Boolean> usedCollectionNames = new ConcurrentHashMap<>();

    /** The id field for each DBModel. */
    @SuppressWarnings("rawtypes")
    private static ConcurrentHashMap<Class<? extends DBModel>, Class<?>> dbModelClassToIdType = new ConcurrentHashMap<>();

    /** The set of names of indexed fields for each DBModel. */
    @SuppressWarnings("rawtypes")
    private static ConcurrentHashMap<Class<? extends DBModel>, HashSet<String>> dbModelClassToIndexedFieldNames = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------------------------------------------------------------------------

    public static MongoClient mongoClient = null;
    static {
        try {
            mongoClient = new MongoClient(/* TODO: host and port number can be specified here */);
        } catch (Exception e) {
            throw new RuntimeException("Could not connect to database server", e);
        }
    }

    /** Only use to ensure database connection has started up properly. */
    public static class DatabaseStartup extends DBModelObjectIdKey {
    }

    public static void checkDatabaseIsConnected() {
        try {
            // Make sure we can connect to MongoDB server
            // This prints three different stacktraces to System.err if database is not running, is there
            // a cleaner way to check if it's running?
            long start = System.currentTimeMillis();
            Database.collectionForDBModel(DatabaseStartup.class);
            Log.info("Brought up database connection in " + (System.currentTimeMillis() - start) + " msec");

        } catch (Exception e) {
            Log.exception("Could not connect to database server, exiting", e);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------

    /**
     * For subclasses of DBModel<K>, get the concrete type of K. This method for fetching type param information is
     * required because dbModelClass.getField("id").getType() is simply Object.class due to type erasure.
     */
    private static <T extends DBModel<K>, K> Class<K> getIdFieldType(Class<T> dbModelClass) {
        if (dbModelClass == DBModel.class) {
            throw new RuntimeException("Must subclass DBModel, can't use it directly");
        }

        // Follow superclass hierarchy upwards until we hit DBModel<K>, then get the actual type of K 
        Class<?> parameterType = null;
        for (Type currType = dbModelClass; currType != null;) {
            Class<?> currClass = null;
            if (currType instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) currType;
                Type[] args = parameterizedType.getActualTypeArguments();
                if (args.length > 0) {
                    // Hit a parameterized type with at least one arg, store the type of the first arg
                    parameterType = (Class<?>) args[0];
                }
                currClass = (Class<?>) parameterizedType.getRawType();
                if (currClass.equals(DBModel.class)) {
                    // Once we hit DBModel, stop
                    break;
                }
            } else if (currType instanceof Class) {
                currClass = (Class<?>) currType;
            } else {
                // Should not happen
                throw new RuntimeException("Could not find id type for class " + dbModelClass.getName());
            }
            currType = currClass.getGenericSuperclass();
        }

        if (parameterType == null) {
            throw new RuntimeException(
                    "For subclasses of DBModel<K>, the type K must be an actual type, not a type parameter"
                            + dbModelClass.getName());
        }

        @SuppressWarnings("unchecked")
        Class<K> t = (Class<K>) parameterType;
        return t;
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Register a subclass of DBModel with MongoJack. Not threadsafe (and cannot be run while readers are calling
     * collectionForDBModel()), so should be called on all DBModel classes in a single pass on startup, before any other
     * threads start running database queries.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <T extends DBModel<K>, U extends DBModel, K> void registerDBModel(Class<U> dbModelClass) {
        // Ignore the DBModel[ObjectId|String|Long]Key superclasses, but register everything else
        if (dbModelClass != DBModelObjectIdKey.class && dbModelClass != DBModelStringKey.class
                && dbModelClass != DBModelLongKey.class) {
            // Don't double-register classes
            JacksonDBCollection<T, K> coll = (JacksonDBCollection<T, K>) dbModelClassToCollection.get(dbModelClass);
            if (coll == null) {
                Log.fine("Registering database model: " + dbModelClass.getName());

                // Try instantiating dbModelClass with default constructor to make sure there will be no problems
                // instantiating it later 
                try {
                    Reflection.instantiateWithDefaultConstructor(dbModelClass);
                } catch (Exception e) {
                    throw new RuntimeException("Could not instantiate " + DBModel.class.getSimpleName() + " subclass "
                            + dbModelClass.getName()
                            + " -- it needs to be public, it needs a zero-argument constructor if there "
                            + "are any other non-default constructors defined, and the class must be "
                            + "static if it is an inner class");
                }

                // Get collection name, either from classname or from @MongoCollection annotation -- see
                // http://mongojack.org/tutorial.html
                MongoCollection mongoCollectionAnnotation = dbModelClass.getAnnotation(MongoCollection.class);
                String collectionName = mongoCollectionAnnotation == null ? null : mongoCollectionAnnotation.name();
                if (collectionName == null || collectionName.isEmpty()) {
                    // There is no @MongoCollection annotation on this DBModel class instance,
                    // therefore default to using the class' (unqualified) name as the collection name 
                    collectionName = dbModelClass.getSimpleName();
                    // Log.info(DBModel.class.getName() + " subclass " + dbClass.getName() + " does not specify
                    // collection name using @" + MongoCollection.class.getSimpleName()
                    //         + " annotation; using \"" + collectionName + "\" as collection name");
                }

                if (usedCollectionNames.put(collectionName, Boolean.TRUE) != null) {
                    throw new RuntimeException("Two " + DBModel.class.getName()
                            + " subclasses are mapped to the same collection name \"" + collectionName + "\"");
                }

                // Get concrete type of id field by reflection
                Class<K> idType = getIdFieldType(dbModelClass);

                try {
                    // Get database collection based on collection name, and wrap it with a Jackson mapper to/from
                    // the DBModel class, indexed using the id field type
                    coll = (JacksonDBCollection<T, K>) JacksonDBCollection.wrap(
                            mongoClient.getDB(GribbitProperties.DB_NAME).getCollection(collectionName), dbModelClass,
                            idType);
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Failure during JacksonDBCollection.wrap(), this can be caused by having methods "
                                    + "with the prefix \"get\" or \"set\". " + "Fields of " + DBModel.class.getName()
                                    + " subclasses must be public, and getters/setters are not allowed.", e);
                }

                // Save mapping from DBModel class to collection
                dbModelClassToCollection.put(dbModelClass, coll);

                // Get the concrete type K of the id field for this DBModel<K> subclass
                dbModelClassToIdType.put(dbModelClass, (Class<?>) idType);

                // Get the set of fields in this collection that are currently indexed 
                HashSet<String> indexedFields = new HashSet<>();
                for (DBObject obj : coll.getIndexInfo()) {
                    BasicDBObject key = (BasicDBObject) obj.get("key");
                    if (key != null) {
                        // Each index key can consist of multiple key fields, see http://goo.gl/xiYYT0
                        indexedFields.addAll(key.keySet());
                    }
                }

                // Check field annotations
                for (Field field : dbModelClass.getFields()) {
                    String fieldName = field.getName();

                    // Ensure that an index exists for fields annotated with DBIndex
                    if (field.getAnnotation(DBIndex.class) != null) {
                        if (!indexedFields.contains(fieldName)) {
                            coll.ensureIndex(fieldName);
                            indexedFields.add(fieldName);
                        }
                    }

                    // There can be only one primary key field, and it is already annotated in DBModel,
                    // so fields in subclasses can't be annotated with @Id.
                    if ((field.getAnnotation(org.mongojack.Id.class) != null //
                            || field.getAnnotation(javax.persistence.Id.class) != null)
                            && !fieldName.equals("id")) {
                        throw new RuntimeException("Class " + dbModelClass.getName()
                                + " has an @Id annotation on a field other than the id field");
                    } else if (fieldName.equals("_id")) {
                        // The name "_id" is what the "id" field is mapped to in the database
                        throw new RuntimeException("Illegal field name \"_id\" in class " + dbModelClass.getName());
                    }
                }

                // Save the set of indexed fields
                dbModelClassToIndexedFieldNames.put(dbModelClass, indexedFields);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Wrap a MongoDB collection in a JacksonDBCollection wrapper and return it. Can be called manually by utilities
     * that want to access database collections without starting up the web server.
     */
    @SuppressWarnings({ "unchecked" })
    public static <T extends DBModel<K>, K> JacksonDBCollection<T, K> collectionForDBModel(Class<T> dbModelClass) {
        // Look up cached mapping from DBModel class to collection 
        JacksonDBCollection<T, K> coll = (JacksonDBCollection<T, K>) dbModelClassToCollection.get(dbModelClass);
        if (coll == null) {
            // If collection has not yet been mapped, register the model and then get the mapping
            registerDBModel(dbModelClass);
            coll = (JacksonDBCollection<T, K>) dbModelClassToCollection.get(dbModelClass);
        }
        return coll;
    }

    /** Find a database object by key. */
    public static <T extends DBModel<K>, K> T findOneById(Class<T> dbModelClass, K id) {
        JacksonDBCollection<T, K> coll = collectionForDBModel(dbModelClass);
        return coll.findOneById(id);
    }

    /** Check that a field exists, that it is accessible, and that it is indexed in the database. */
    private static <T extends DBModel<K>, K> void checkFieldIsIndexed(JacksonDBCollection<T, K> coll,
            Class<T> dbModelClass, String fieldName) {
        try {
            // (We don't actually do anything with the field, we just try getting it to ensure it exists)
            dbModelClass.getField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field \"" + fieldName + "\" is not a field of class " + dbModelClass.getName());
        } catch (SecurityException e) {
            throw new RuntimeException("Field \"" + fieldName + "\" of class " + dbModelClass.getName()
                    + " is not accessible", e);
        }
        HashSet<String> indexedFieldNames = dbModelClassToIndexedFieldNames.get(dbModelClass);
        if (indexedFieldNames == null) {
            throw new RuntimeException("Can't find indexed field list");
        } else if (!indexedFieldNames.contains(fieldName)) {
            throw new RuntimeException("Field \"" + fieldName + "\" in class " + dbModelClass.getName()
                    + " is not an indexed field, so querying it will run in O(N). Add an annotation @"
                    + DBIndex.class.getName() + " to cause the field to be indexed");
        }
    }

    /** Find an item by an indexed field's value */
    public static <T extends DBModel<K>, K> T findOneByIndexedField(Class<T> dbModelClass, String fieldName,
            String fieldValue) {
        JacksonDBCollection<T, K> coll = collectionForDBModel(dbModelClass);
        checkFieldIsIndexed(coll, dbModelClass, fieldName);
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
    public static <T extends DBModel<K>, K> ArrayList<T> findAllByIndexedField(Class<T> type, String fieldName,
            String fieldValue) {
        JacksonDBCollection<T, K> coll = collectionForDBModel(type);
        checkFieldIsIndexed(coll, type, fieldName);
        ArrayList<T> results = new ArrayList<>();
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
     * Save (upsert) this object into the database.
     */
    @SuppressWarnings("unchecked")
    public static <T extends DBModel<K>, K> WriteResult<T, K> save(T object) {
        return collectionForDBModel(object.getClass()).save(object);
    }

    /**
     * Remove this object from the database.
     */
    @SuppressWarnings("unchecked")
    public static <T extends DBModel<K>, K> WriteResult<T, K> remove(T object) {
        return collectionForDBModel(object.getClass()).removeById(object.id);
    }

    /**
     * Remove this object from the database. Slightly faster than remove(), because it doesn't have to find the id
     * field.
     * 
     * @return
     */
    public static <T extends DBModel<K>, K> WriteResult<T, K> removeById(Class<T> dbModelClass, K id) {
        return collectionForDBModel(dbModelClass).removeById(id);
    }

    /**
     * Find all objects in the database of the given type. NOTE: the entire result set is stored in an ArrayList and
     * returned -- only when you know the result set is guaranteed to be small, otherwise you expose the server to an
     * OOM attack.
     */
    public static <T extends DBModel<K>, K> ArrayList<T> findAll(Class<T> dbModelClass) {
        ArrayList<T> results = new ArrayList<>();
        JacksonDBCollection<T, K> coll = collectionForDBModel(dbModelClass);
        DBCursor<T> cursor = (DBCursor<T>) coll.find();
        while (cursor.hasNext()) {
            results.add(cursor.next());
        }
        return results;
    }
}
