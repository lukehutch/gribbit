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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.commons.io.IOUtils;
import org.json.simple.JSONValue;

public class RequestBuilder {

    /**
     * Make a GET or POST request, handling up to 6 redirects, and return the response. If isBinaryResponse is true,
     * returns a byte[] array, otherwise returns the response as a String.
     */
    private static Object makeRequest(String url, String[] keyValuePairs, boolean isGET, boolean isBinaryResponse,
            int redirDepth) {
        if (redirDepth > 6) {
            throw new IllegalArgumentException("Too many redirects");
        }
        HttpURLConnection connection = null;
        try {
            // Add the URL query params if this is a GET request
            String reqURL = isGET ? url + "?" + WebUtils.buildQueryString(keyValuePairs) : url;
            connection = (HttpURLConnection) new URL(reqURL).openConnection();
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(isGET ? "GET" : "POST");
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/43.0.2357.125 Safari/537.36");
            if (!isGET) {
                // Send the body if this is a POST request
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setRequestProperty("charset", "utf-8");
                String params = WebUtils.buildQueryString(keyValuePairs);
                connection.setRequestProperty("Content-Length", Integer.toString(params.length()));
                try (DataOutputStream w = new DataOutputStream(connection.getOutputStream())) {
                    w.writeBytes(params);
                    w.flush();
                }
            }
            if (connection.getResponseCode() == HttpResponseStatus.FOUND.code()) {
                // Follow a redirect. For safety, the params are not passed on, and the method is forced to GET.
                return makeRequest(connection.getHeaderField("Location"), /* keyValuePairs = */null, /* isGET = */
                        true, isBinaryResponse, redirDepth + 1);
            } else if (connection.getResponseCode() == HttpResponseStatus.OK.code()) {
                // For 200 OK, return the text of the response
                if (isBinaryResponse) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream(32768);
                    IOUtils.copy(connection.getInputStream(), output);
                    return output.toByteArray();
                } else {
                    StringWriter writer = new StringWriter(1024);
                    IOUtils.copy(connection.getInputStream(), writer, "UTF-8");
                    return writer.toString();
                }
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
     * Make a GET or POST request, handling up to 6 redirects, and return the response. If isBinaryResponse is true,
     * returns a byte[] array, otherwise returns the response as a String.
     */
    private static Object makeRequest(String url, String[] keyValuePairs, boolean isGET, boolean isBinaryResponse) {
        return makeRequest(url, keyValuePairs, isGET, isBinaryResponse, 0);
    }

    /**
     * Send a POST request to a given URL with the given key-value POST parameters (with keys in the even indices
     * and values in the following odd indices), and parse the JSON result by mapping to a new object of the
     * requested response type.
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or JSON could not be mapped to the response type.
     */
    public static <T> T postToURLWithJSONResponse(Class<T> responseType, String url, String... keyValuePairs)
            throws IllegalArgumentException {
        String jsonStr = (String) makeRequest(url, keyValuePairs, /* isGET = */false, /* isBinaryResponse = */
                false);
        try {
            return JSONJackson.jsonToObject(jsonStr, responseType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * Send a POST request to a given URL with the given key-value POST parameters (with keys in the even indices
     * and values in the following odd indices). Result is a json-simple object, see
     * https://code.google.com/p/json-simple/wiki/DecodingExamples
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or JSON could not be parsed.
     */
    public static Object postToURLWithJSONResponse(String url, String... keyValuePairs)
            throws IllegalArgumentException {
        String jsonStr = (String) makeRequest(url, keyValuePairs, /* isGET = */false, /* isBinaryResponse = */
                false);
        try {
            return JSONValue.parse(jsonStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * Send a POST request to a given URL with the given key-value POST parameters (with keys in the even indices
     * and values in the following odd indices). Returns the result as a string.
     */
    public static String postToURLWithStringResponse(String url, String... keyValuePairs)
            throws IllegalArgumentException {
        return (String) makeRequest(url, keyValuePairs, /* isGET = */false, /* isBinaryResponse = */false);
    }

    /**
     * Send a POST request to a given URL with the given key-value POST parameters. Returns the result as a byte[]
     * array.
     */
    public static byte[] postToURLWithBinaryResponse(String url, String... keyValuePairs)
            throws IllegalArgumentException {
        return (byte[]) makeRequest(url, keyValuePairs, /* isGET = */false, /* isBinaryResponse = */true);
    }

    /**
     * Send a GET request to a given URL with the given key-value URL parameters (with keys in the even indices and
     * values in the following odd indices), and parse the JSON result by mapping to a new object of the requested
     * response type.
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or JSON could not be mapped to the response type.
     */
    public static <T> T getFromURLWithJSONResponse(Class<T> responseType, String url, String... keyValuePairs)
            throws IllegalArgumentException {
        String jsonStr = (String) makeRequest(url, keyValuePairs, /* isGET = */true, /* isBinaryResponse = */
                false);
        try {
            return JSONJackson.jsonToObject(jsonStr, responseType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * Send a GET request to a given URL with the given key-value URL parameters (with keys in the even indices and
     * values in the following odd indices). Result is a json-simple object, see
     * https://code.google.com/p/json-simple/wiki/DecodingExamples
     * 
     * @throws IllegalArgumentException
     *             if request could not be completed or JSON could not be parsed.
     */
    public static Object getFromURLWithJSONResponse(String url, String... keyValuePairs)
            throws IllegalArgumentException {
        String jsonStr = (String) makeRequest(url, keyValuePairs, /* isGET = */true, /* isBinaryResponse = */
                false);
        try {
            return JSONValue.parse(jsonStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * Send a POST request to a given URL with the given key-value URL parameters (with keys in the even indices and
     * values in the following odd indices). Returns the result as a string.
     */
    public static String getFromURLWithStringResponse(String url, String... keyValuePairs)
            throws IllegalArgumentException {
        return (String) makeRequest(url, keyValuePairs, /* isGET = */true, /* isBinaryResponse = */false);
    }

    /**
     * Send a GET request to a given URL with the given key-value URL parameters (with keys in the even indices and
     * values in the following odd indices). Returns the result as a byte[] array.
     */
    public static byte[] getFromURLWithBinaryResponse(String url, String... keyValuePairs)
            throws IllegalArgumentException {
        return (byte[]) makeRequest(url, keyValuePairs, /* isGET = */true, /* isBinaryResponse = */true);
    }
}
