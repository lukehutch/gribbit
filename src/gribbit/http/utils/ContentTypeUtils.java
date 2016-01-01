package gribbit.http.utils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class ContentTypeUtils {

    public static boolean isCompressibleContentType(String contentType) {
        return contentType != null
                && (contentType.startsWith("text/") || contentType.startsWith("application/javascript")
                        || contentType.startsWith("application/json") || contentType.startsWith("application/xml")
                        || contentType.startsWith("image/svg+xml") || contentType
                            .startsWith("application/x-font-ttf"));
    }

    // -------------------------------------------------------------------------------------------------------------

    private static <T> HashMap<T, T> toMap(T[][] pairs) {
        HashMap<T, T> map = new HashMap<>();
        for (T[] pair : pairs) {
            map.put(pair[0], pair[1]);
        }
        return map;
    }

    public static final Map<String, String> EXTENSION_TO_MIMETYPE = toMap(new String[][] {
            // See https://github.com/h5bp/server-configs-nginx/blob/master/mime.types for more
            { "txt", "text/plain" }, //
            { "htm", "text/html" }, //
            { "html", "text/html" }, //
            { "js", "application/javascript" }, //
            { "json", "application/json" }, //
            { "css", "text/css" }, //
            { "xml", "application/xml" }, //
            { "ico", "image/x-icon" }, //
            { "png", "image/png" }, //
            { "webp", "image/webp" }, //
            { "gif", "image/gif" }, //
            { "mng", "image/mng" }, //
            { "jpg", "image/jpeg" }, //
            { "jpeg", "image/jpeg" }, //
            { "svg", "image/svg+xml" }, //
            { "svgz", "image/svg+xml" }, // Served as image/svg+xml with a "Content-Encoding: gzip" header
            { "gz", "application/x-gzip" }, //
            { "bz2", "application/x-bzip2" }, //
            { "zip", "application/zip" }, //
            { "pdf", "application/pdf" }, //
            { "ogg", "audio/ogg" }, //
            { "mp3", "audio/mpeg" }, //
            { "wav", "audio/x-wav" }, //
            { "csv", "text/comma-separated-values" }, //

            // Font types:
            { "ttf", "application/x-font-ttf" }, //
            { "ttc", "application/x-font-ttf" }, //
            { "otf", "application/x-font-opentype" }, //
            { "woff", "application/font-woff" }, //
            { "woff2", "application/font-woff2" }, //
            { "eot", "application/vnd.ms-fontobject" }, //
            { "sfnt", "application/font-sfnt" }, //
    });

    private static <T> HashSet<T> toSet(T[] elts) {
        HashSet<T> set = new HashSet<>();
        for (T elt : elts) {
            set.add(elt);
        }
        return set;
    }

    public static final HashSet<String> FONT_EXTENSION = toSet(new String[] { "ttf", "ttc", "otf", "woff",
            "woff2", "eot", "sfnt" });

}
