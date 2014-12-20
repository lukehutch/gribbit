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
package gribbit.model;

import gribbit.server.siteresources.Database;

import org.bson.types.ObjectId;
import org.mongojack.Id;
import org.mongojack.WriteResult;

public abstract class DBModelObjectIdKey extends DBModel {

    @Id
    public ObjectId id;

    public DBModelObjectIdKey() {
    }

    /**
     * Save (upsert) this object into the database.
     */
    public WriteResult<DBModel, Object> save() {
        if (id == null) {
            // Create new ObjectId if this object was not previously retrieved from the database
            id = new ObjectId();
        }

        // Check that values in the object fields satisfy any constraint annotations
        try {
            checkFieldValuesAgainstConstraints();
        } catch (Exception e) {
            throw new RuntimeException("Object cannot be saved, constraint annotations not satisified: "
                    + e.getMessage());
        }

        return Database.save(this);
    }

    /** Remove this object from the database. */
    public WriteResult<DBModel, Object> remove() {
        if (id == null) {
            throw new RuntimeException(
                    "id is null, so object cannot be removed (object was not previously "
                            + "saved in or retrieved from database)");
        }
        return Database.removeById(getClass(), id);
    }

    public ObjectId getId() {
        return id;
    }
}
