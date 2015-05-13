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

import gribbit.response.exception.BadRequestException;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.commons.io.IOUtils;

public class RequestBuilder {

    /** Build a string of escaped URL query param key-value pairs. */
    private static String buildParams(String... keyValuePairs) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (buf.length() > 0) {
                buf.append("&");
            }
            WebUtils.escapeQueryParamKeyVal(keyValuePairs[i], i < keyValuePairs.length - 1 ? keyValuePairs[i + 1] : "",
                    buf);
        }
        return buf.toString();
    }

    /**
     * Get the response from an HttpURLConnection, parse it as JSON, and map it to the requested response type.
     */
    private static <T> T getJSONResponse(Class<T> responseType, HttpURLConnection connection) throws IOException,
            IllegalArgumentException {
        if (connection.getResponseCode() == HttpResponseStatus.OK.code()) {
            try {
                StringWriter writer = new StringWriter();
                IOUtils.copy(connection.getInputStream(), writer, "UTF-8");
                String jsonStr = writer.toString();

                return JacksonJSON.jsonToObject(jsonStr, responseType);

            } catch (Exception e) {
                throw new IllegalArgumentException("Could not parse JSON response", e);
            }

        } else {
            throw new IllegalArgumentException("Got response code " + connection.getResponseCode());
        }
    }

    /**
     * Send a POST request to a given URL with the given key-value POST parameters, and parse the JSON result by mapping
     * to a new object of the requested response type.
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or JSON could not be mapped to the response type.
     * @throws BadRequestException
     *             if response code is not OK (200).
     */
    public static <T> T postToURLWithJSONResponse(Class<T> responseType, String url, String... keyValuePairs)
            throws BadRequestException {
        HttpURLConnection connection = null;
        try {
            String params = buildParams(keyValuePairs);

            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("charset", "utf-8");
            connection.setRequestProperty("Content-Length", Integer.toString(params.length()));
            connection.setUseCaches(false);

            try (DataOutputStream w = new DataOutputStream(connection.getOutputStream())) {
                w.writeBytes(params);
                w.flush();
            }

            return getJSONResponse(responseType, connection);

        } catch (IOException e) {
            throw new IllegalArgumentException("IOException during POST request", e);

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Send a GET request to a given URL with the given key-value URL parameters, and parse the JSON result by mapping
     * to a new object of the requested response type.
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or JSON could not be mapped to the response type.
     * @throws BadRequestException
     *             if response code is not OK (200).
     */
    public static <T> T getFromURLWithJSONResponse(Class<T> responseType, String url, String... keyValuePairs)
            throws BadRequestException {
        HttpURLConnection connection = null;
        try {
            StringBuilder buf = new StringBuilder(url);
            buf.append('?');
            for (int i = 0; i < keyValuePairs.length; i += 2) {
                if (buf.length() > 0) {
                    buf.append("&");
                }
                WebUtils.escapeQueryParamKeyVal(keyValuePairs[i], i < keyValuePairs.length - 1 ? keyValuePairs[i + 1]
                        : "", buf);
            }
            String urlWithParams = buf.toString();

            connection = (HttpURLConnection) new URL(urlWithParams).openConnection();
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);

            return getJSONResponse(responseType, connection);

        } catch (IOException e) {
            throw new IllegalArgumentException("IOException during POST request", e);

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
