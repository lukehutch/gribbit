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

import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import org.apache.commons.io.IOUtils;
import org.json.simple.JSONValue;

public class RequestBuilder {

    /** Make a connection to a given URL, and initiate a GET or POST request, returning the connection. */
    private static HttpURLConnection makeConnection(String url, String[] keyValuePairs, boolean isGET)
            throws MalformedURLException, IOException {
        String reqURL = isGET ? url + "?" + WebUtils.buildQueryString(keyValuePairs) : url;
        HttpURLConnection connection = (HttpURLConnection) new URL(reqURL).openConnection();
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(isGET ? "GET" : "POST");
        connection.setUseCaches(false);
        if (!isGET) {
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("charset", "utf-8");
            String params = WebUtils.buildQueryString(keyValuePairs);
            connection.setRequestProperty("Content-Length", Integer.toString(params.length()));
            try (DataOutputStream w = new DataOutputStream(connection.getOutputStream())) {
                w.writeBytes(params);
                w.flush();
            }
        }
        return connection;
    }

    private static String makeRequest(String url, String[] keyValuePairs, boolean isGET) {
        HttpURLConnection connection = null;
        try {
            connection = makeConnection(url, keyValuePairs, isGET);
            if (connection.getResponseCode() == HttpResponseStatus.OK.code()) {
                StringWriter writer = new StringWriter();
                IOUtils.copy(connection.getInputStream(), writer, "UTF-8");
                return writer.toString();
            } else {
                throw new IllegalArgumentException("Got non-OK HTTP response code: " + connection.getResponseCode());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Exception during " + (isGET ? "GET" : "POST") + " request: "
                    + e.getMessage(), e);
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception e) {
                }
            }
        }
    }

    /**
     * Send a GET request to a given URL with the given key-value URL parameters, expecting a 302 Found redirect
     * response. Returns the destination URL for the redirect.
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or the response code was not 302 Found.
     */
    public static String getRedirectDestination(String url, String... keyValuePairs) throws IllegalArgumentException {
        HttpURLConnection connection = null;
        try {
            connection = makeConnection(url, keyValuePairs, /* isGET = */true);
            if (connection.getResponseCode() == HttpResponseStatus.FOUND.code()) {
                return connection.getHeaderField("Location");
            } else {
                throw new IllegalArgumentException("Got non-FOUND HTTP response code: " + connection.getResponseCode());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Exception while trying to determine redirect destination: "
                    + e.getMessage(), e);
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception e) {
                }
            }
        }
    }

    /**
     * Send a POST request to a given URL with the given key-value POST parameters, and parse the JSON result by mapping
     * to a new object of the requested response type.
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or JSON could not be mapped to the response type.
     */
    public static <T> T postToURLWithJSONResponse(Class<T> responseType, String url, String... keyValuePairs)
            throws IllegalArgumentException {
        String jsonStr = makeRequest(url, keyValuePairs, /* isGET = */false);
        try {
            return JSONJackson.jsonToObject(jsonStr, responseType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * Send a GET request to a given URL with the given key-value URL parameters, and parse the JSON result by mapping
     * to a new object of the requested response type.
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or JSON could not be mapped to the response type.
     */
    public static <T> T getFromURLWithJSONResponse(Class<T> responseType, String url, String... keyValuePairs)
            throws IllegalArgumentException {
        String jsonStr = makeRequest(url, keyValuePairs, /* isGET = */true);
        try {
            return JSONJackson.jsonToObject(jsonStr, responseType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * Send a POST request to a given URL with the given key-value POST parameters. Result is a json-simple object, see
     * https://code.google.com/p/json-simple/wiki/DecodingExamples
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or JSON could not be parsed.
     */
    public static Object postToURLWithJSONResponse(String url, String... keyValuePairs) throws IllegalArgumentException {
        String jsonStr = makeRequest(url, keyValuePairs, /* isGET = */false);
        try {
            return JSONValue.parse(jsonStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * Send a GET request to a given URL with the given key-value URL parameters. Result is a json-simple object, see
     * https://code.google.com/p/json-simple/wiki/DecodingExamples
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or JSON could not be parsed.
     */
    public static Object getFromURLWithJSONResponse(String url, String... keyValuePairs)
            throws IllegalArgumentException {
        String jsonStr = makeRequest(url, keyValuePairs, /* isGET = */true);
        try {
            return JSONValue.parse(jsonStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse JSON response: " + e.getMessage(), e);
        }
    }

}
