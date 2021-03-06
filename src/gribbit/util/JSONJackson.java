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
package gribbit.util;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import gribbit.server.config.GribbitProperties;

@SuppressWarnings("deprecation")
public class JSONJackson {

    // TODO: maybe try using https://github.com/jdereg/json-io instead for parsing JSON?

    private static JsonFactory factory = new JsonFactory();

    static ObjectMapper mapper = new ObjectMapper(factory);
    static {
        mapper.setVisibilityChecker(mapper.getSerializationConfig().getDefaultVisibilityChecker() //
                .withFieldVisibility(JsonAutoDetect.Visibility.PUBLIC_ONLY) //
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE) //
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE) //
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));

        if (GribbitProperties.PRETTY_PRINT_JSON) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
        // mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public static <T> T jsonToObject(String jsonString, Class<T> klass)
            throws JsonParseException, JsonMappingException, IOException {
        return mapper.readValue(jsonString, klass);
    }

    public static <T> T jsonToObject(InputStream inputStream, Class<T> klass)
            throws JsonParseException, JsonMappingException, IOException {
        return mapper.readValue(inputStream, klass);
    }

    //    // Superceded by JSON.toJSON(), which respects @Private annotations etc. 
    //    public static <T> String objectToJson(Object object) throws JsonProcessingException {
    //        return mapper.writeValueAsString(object);
    //    }

}
