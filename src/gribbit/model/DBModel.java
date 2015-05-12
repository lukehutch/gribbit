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

import gribbit.server.siteresources.DataModelLoader;
import gribbit.server.siteresources.Database;

import org.mongojack.Id;
import org.mongojack.WriteResult;

public abstract class DBModel<K> extends DataModel {
    @Id
    public K id;

    public DBModel() {
    }

    public DBModel(K id) {
        this.id = id;
    }

    /**
     * Save (upsert) this object into the database.
     */
    public WriteResult<DBModel<K>, K> save() {
        if (id == null) {
            throw new RuntimeException("id cannot be null");
        }

        // Check that values in the object fields satisfy any constraint annotations
        try {
            DataModelLoader.checkFieldValuesAgainstConstraints(this);
        } catch (Exception e) {
            throw new RuntimeException("Object cannot be saved, constraint annotations not satisified: "
                    + e.getMessage());
        }

        return Database.save(this);
    }

    /**
     * Remove this object from the database.
     */
    public WriteResult<DBModel<K>, K> remove() {
        if (id == null) {
            throw new RuntimeException("id is null, so object cannot be removed (object was not previously "
                    + "saved in or retrieved from database)");
        }
        @SuppressWarnings("unchecked")
        WriteResult<DBModel<K>, K> result = Database.removeById(getClass(), id);
        return result;
    }
}
