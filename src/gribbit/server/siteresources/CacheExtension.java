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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.codec.digest.DigestUtils;

import gribbit.server.GribbitServer;
import gribbit.util.Base64Safe;
import gribbit.util.Log;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;

/**
 * Implement "cache extension" or "hash-caching" be rewriting URL references in HTML templates to include a hashcode
 * generated by hashing the linked local resource. Allows resources to be cached indefinitely in the browser,
 * eliminating round-trips. Note that the mapping from original URI to hashcode is held in RAM, so the URI keyspace
 * for all hashed resources (static file paths and any hashed route URIs) must fit comfortably in RAM.
 * 
 * (Similar to PageSpeed's feature, https://developers.google.com/speed/pagespeed/module/filter-cache-extend )
 */
public class CacheExtension {

    public static class HashInfo {
        private String origURI;
        private String hashKey;
        private long lastModifiedEpochSeconds;

        public HashInfo(String origURI, String hashKey, long lastModifiedEpochSeconds) {
            this.origURI = origURI;
            this.hashKey = hashKey;
            this.lastModifiedEpochSeconds = lastModifiedEpochSeconds;
        }

        public String getHashURI() {
            // This is created on demand to avoid permanently storing two strings in the map that both contain origURI,
            // origURI itself and the hash URI (which contains it as a substring).
            return "/_/" + hashKey + origURI;
        }

        public long getLastModifiedEpochSeconds() {
            return lastModifiedEpochSeconds;
        }
    }

    /** A mapping from orig URI to the most recent hash key and last modified timestamp. */
    private static ConcurrentHashMap<String, HashInfo> origURIToHashInfo = new ConcurrentHashMap<>();

    /** A concurrent set containing URIs that are currently enqueued to be hashed. */
    private static ConcurrentHashMap<String, Object> scheduledURIsToHash = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------------------------------------------------

    /** Add or update the mapping between orig URI and hash key. */
    private static void updateURIHashAndTimestamp(String origURI, String hashKey, long lastModifiedEpochSeconds) {
        if (lastModifiedEpochSeconds > 0) {
            // Update mapping from orig URI to hash key and last modified time.
            // If we end up beaten my another thread with a hash with a newer timestamp,
            // we need to put the newer mapping back into the map, looping until the
            // time in the map is the most recent time.
            HashInfo newHashInfo = new HashInfo(origURI, hashKey, lastModifiedEpochSeconds);
            for (;;) {
                HashInfo oldHashInfo = origURIToHashInfo.put(origURI, newHashInfo);
                if (oldHashInfo == null
                        || oldHashInfo.lastModifiedEpochSeconds <= newHashInfo.lastModifiedEpochSeconds) {
                    break;
                }
                // The previous HashInfo object had a newer timestamp, use it instead
                newHashInfo = oldHashInfo;
            }
        }
    }

    /**
     * Add a mapping from orig URI to hash URI, scheduling the URI resource to be hashed if it hasn't already been
     * hashed, or if the resource has been modified since last time it was hashed.
     */
    private static void scheduleHasher(String origURI, long lastModifiedEpochSeconds, Hasher hasher) {
        // Schedule the hashing task
        GribbitServer.vertx.executeBlocking(future -> {
            long startTime = System.currentTimeMillis();

            // Perform MD5 digest, then convert to URI-safe base 64 encoding, then to hash URI
            String hashKey = hasher.computeHashKey();

            if (hashKey != null) {
                // Save mapping between origURI and hash key
                updateURIHashAndTimestamp(origURI, hashKey, lastModifiedEpochSeconds);

                Log.fine("Hashing resource: " + origURI + " -> " + hashKey + " -- took "
                        + (System.currentTimeMillis() - startTime) + " msec");
            } else {
                // If hashing failed (e.g. for FileNotFound exception, or issue reading from ByteBuf)
                // then just leave the resource unhashed
            }
            // Remove origURI from set of URIs in the queue
            scheduledURIsToHash.remove(origURI);
            future.complete();
        }, res -> {
            if (res.failed()) {
                Log.error("Exception in hasher");
            }
        });
    }

    @FunctionalInterface
    private static interface Hasher {
        String computeHashKey();
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Create a hash URI (which allows the browser to cache this resource indefinitely) if the last modified
     * timestamp has increased, or if there is no hash URI yet for this resource. For a new hash URI to be created,
     * the passed object is scheduled to be hashed by a background thread.
     *
     * This method can be called by any route handler that stores or returns database objects. It should be called
     * both when storing objects and when returning them, since the hash URI cache is held in RAM and is empty when
     * the server starts, so it needs to be built as requests start to come in. IMPORTANT NOTE: If the database can
     * be written to by other database clients, then this method must also be called when those changes are
     * detected, otherwise web clients connected to this web server will continue to serve old linked resources.
     * 
     * This method should only be used when the total keyspace of URIs that map to database objects easily fits in
     * RAM, and when the objects that need to be hashed are not large (i.e. tens of MB is OK, hundreds of MB is
     * probably not, since there are several background worker threads and they all can be hashing objects in
     * parallel).
     */
    public static void updateHashURI(String origURI, ByteBuf content, long lastModifiedEpochSeconds) {
        // Can only hash absolute but local (i.e. domain-less) URIs that have not already been hashed
        if (origURI.startsWith("/") && !origURI.startsWith("//") && !origURI.startsWith("/_/")) {
            // Check to see if there is already a mapping to hash URI for this original URI
            HashInfo hashInfo = origURIToHashInfo.get(origURI);
            if (hashInfo == null || hashInfo.lastModifiedEpochSeconds < lastModifiedEpochSeconds) {
                // There is no hash URI yet for origURI, or there is already a hash URI corresponding to origURI,
                // but the modification time has increased since the cached version, so need to re-hash.
                // Check if another thread has already enqueued the URI for hashing.
                Object alreadyInQueue = scheduledURIsToHash.put(origURI, new Object());
                if (alreadyInQueue == null) {
                    content.retain();
                    // This URI is not currently queued for hashing by background workers, add it to the queue
                    scheduleHasher(origURI, lastModifiedEpochSeconds, new Hasher() {
                        @Override
                        public String computeHashKey() {
                            // Compute MD5 hash of the ByteBuf contents, then base64-encode the results
                            try {
                                String hash = Base64Safe
                                        .base64Encode(DigestUtils.md5(new ByteBufInputStream(content)));
                                content.release(); // TODO: does ByteBufInputStream call release()?
                                return hash;
                            } catch (IOException e) {
                                return null;
                            }
                        }
                    });
                }
            }
        }
    }

    /**
     * Create a hash URI (which allows the browser to cache this resource indefinitely) if the last modified
     * timestamp has increased, or if there is no hash URI yet for this resource. The passed hash key is assumed to
     * have been computed after the last modified timestamp, and should therefore reflect the hash of the current
     * object pointed to by the URI.
     *
     * This method can be called by any route handler that stores or returns database objects. It should be called
     * both when storing objects and when returning them, since the hash URI cache is held in RAM and is empty when
     * the server starts, so it needs to be built as requests start to come in. IMPORTANT NOTE: If the database can
     * be written to by other database clients, then this method must also be called when those changes are
     * detected, otherwise web clients connected to this web server will continue to serve old linked resources.
     * 
     * This method defers object hashing to the caller, so it can be used in cases where there is another machine on
     * the network somewhere that hashes objects in the database.
     */
    public static void updateHashURI(String origURI, String hashKey, long lastModifiedEpochSeconds,
            long currTimeEpochMillis, long maxAgeMillis) {
        // Can only hash absolute but local (i.e. domain-less) URIs that have not already been hashed
        if (origURI.startsWith("/") && !origURI.startsWith("//") && !origURI.startsWith("/_/")) {
            // Check to see if there is already a mapping to hash URI for this original URI
            HashInfo hashInfo = origURIToHashInfo.get(origURI);
            if (hashInfo == null || hashInfo.lastModifiedEpochSeconds < lastModifiedEpochSeconds) {
                // There is no hash URI yet for origURI, or there is already a hash URI corresponding to origURI,
                // but the modification time has increased since the cached version, so hashcode needs to be updated.
                // Since hashcode has been provided, we can directly update the hashcode.
                updateURIHashAndTimestamp(origURI, hashKey, lastModifiedEpochSeconds);
            }
        }
    }

    /**
     * Adds a hash URI mapping for a file, and updates the mapping when the file is modified. Called by
     * HttpRequestHandler every time a file is served.
     */
    public static void updateHashURI(String origURI, File file) {
        // Can only hash absolute but local (i.e. domain-less) URIs that have not already been hashed
        if (origURI.startsWith("/") && !origURI.startsWith("//") && !origURI.startsWith("/_/")) {
            // Check to see if there is already a mapping to hash URI for this original URI
            HashInfo hashInfo = origURIToHashInfo.get(origURI);
            long lastModifiedEpochSeconds = file.lastModified() / 1000;
            if (hashInfo == null || hashInfo.lastModifiedEpochSeconds < lastModifiedEpochSeconds) {
                // There is no hash URI yet for origURI, or there is already a hash URI corresponding to origURI,
                // but the modification time has increased since the cached version, so need to re-hash.
                // Check if another thread has already enqueued the URI for hashing.
                Object alreadyInQueue = scheduledURIsToHash.put(origURI, new Object());
                if (alreadyInQueue == null) {
                    // This URI is not currently queued for hashing by background workers, add it to the queue
                    scheduleHasher(origURI, lastModifiedEpochSeconds, new Hasher() {
                        @Override
                        public String computeHashKey() {
                            try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
                                // Compute MD5 hash of file contents, then base64-encode the results
                                return Base64Safe.base64Encode(DigestUtils.md5(inputStream));
                            } catch (IOException e) {
                                return null;
                            }
                        }
                    });
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Returns the URL path from a hash URL, i.e. returns "/path" given "/_/HASHCODE/url". If the path is not of
     * this form, the input is returned unchanged.
     * 
     * Called by HttpRequestHandler on all request URLs. (When handling HTTP requests that contain hash keys, the
     * hash key can be completely ignored, since the hash URL is only used to prevent the browser from fetching the
     * resource the second and subsequent times it wants to access it; the resource still has to be served the first
     * time.)
     */
    public static String getOrigURL(String hashURL) {
        if (hashURL.startsWith("/_/")) {
            int slashIdx = hashURL.indexOf('/', 3);
            if (slashIdx < 0) {
                // Malformed
                return hashURL;
            }
            return hashURL.substring(slashIdx);
        } else {
            // Not a hash URI, just return the original
            return hashURL;
        }
    }

    /**
     * Returns the hashcode from a hash URL path, i.e. returns "HASHCODE" given "/_/HASHCODE/url", or null if the
     * path is not of this form.
     */
    public static String getHashKey(String hashURL) {
        if (hashURL.startsWith("/_/")) {
            int slashIdx = hashURL.indexOf('/', 3);
            if (slashIdx < 0) {
                // Malformed
                return null;
            }
            return hashURL.substring(3, slashIdx);
        } else {
            return null;
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Get the info on the hash URI for a given request URI, or null if the resource is not hashed. This returns in
     * O(1). Called by the HTML template renderer for each URI encountered, so has to be fast.
     * 
     * If the URI is for a static file and the file hasn't been cached before, this will add the file to the hashing
     * queue and return null. Once the hashing of file contents has been completed, subsequent calls to this method
     * with the same request URI will return the URI containing the hashcode.
     * 
     * If the URI is mapped to a Route, and the Route implements the hash() method taking the same parameters as the
     * get() method, then the hash() method is expected to return a hashcode in O(1), presumably stored in the
     * database for the resource corresponding to the URI.
     * 
     * @return null, if there is no hash URI corresponding to origURI, otherwise returns the HashInfo object
     *         corresponding to origURI.
     */
    public static HashInfo getHashInfo(String origURI) {
        HashInfo hashInfo = origURIToHashInfo.get(origURI);
        if (hashInfo == null) {
            // No known hash key for this orig URI
            return null;
        } else {
            return hashInfo;
        }
    }
}
