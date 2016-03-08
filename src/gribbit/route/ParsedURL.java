package gribbit.route;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import gribbit.util.URLUtils;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.QueryStringEncoder;

public class ParsedURL {
    private final ArrayList<String> unescapedURLParts;
    private final Map<String, List<String>> unescapedQueryParams;
    private String escapedNormalizedURL;

    /**
     * Parse the request URL, handle unescaping of path and query segments, and normalize ".." and "." path
     * elements.
     */
    public ParsedURL(String requestURL) {
        QueryStringDecoder decoder = new QueryStringDecoder(requestURL);
        String urlPath = decoder.path();
        this.unescapedQueryParams = decoder.parameters();

        if (urlPath.isEmpty()) {
            urlPath = "/";
        }
        if (!urlPath.startsWith("/")) {
            // Request URLs should always start with "/"; if they don't, assume URL is relative to "/"
            urlPath = "/" + urlPath;
        }
        // Unescape URL parts so URL can be normalized
        String[] parts = urlPath.equals("/") ? new String[] { "" } : urlPath.split("/");
        this.unescapedURLParts = new ArrayList<>(parts.length - 1);
        for (int i = 1, n = parts.length; i < n; i++) {
            String pathElt = URLUtils.unescapeURLSegment(parts[i]);
            if (!pathElt.isEmpty() && !pathElt.equals(".")) {
                if (pathElt.equals("..")) {
                    if (unescapedURLParts.size() == 0) {
                        // Attempt to navigate above root -- ignore
                    } else {
                        unescapedURLParts.remove(unescapedURLParts.size() - 1);
                    }
                } else {
                    unescapedURLParts.add(pathElt);
                }
            }
        }
    }

    /** Returns the URL path, normalized to handle "..", ".", and empty path segments. */
    public String getNormalizedPath() {
        if (escapedNormalizedURL == null) {
            // Lazily re-escape normalized URL path segments
            StringBuilder buf = new StringBuilder();
            for (String part : unescapedURLParts) {
                buf.append('/');
                buf.append(URLUtils.escapeURLSegment(part));
            }
            escapedNormalizedURL = buf.length() == 0 ? "/" : buf.toString();
        }
        return escapedNormalizedURL;
    }

    /** Returns the query params, unescaped. */
    public Map<String, List<String>> getQueryParams() {
        return unescapedQueryParams;
    }

    /** Returns true if this URL starts with the given template URL. */
    public boolean startsWith(ParsedURL templateURL) {
        if (templateURL.unescapedURLParts.size() > this.unescapedURLParts.size()) {
            return false;
        }
        for (int i = 0; i < templateURL.unescapedURLParts.size(); i++) {
            if (!templateURL.unescapedURLParts.get(i).equals(this.unescapedURLParts.get(i))) {
                return false;
            }
        }
        return true;
    }

    public int getNumURLParts() {
        return this.unescapedURLParts.size();
    }

    public List<String> getUnescapedURLParts(int startIdx) {
        return unescapedURLParts.subList(startIdx, unescapedURLParts.size());
    }

    public List<String> getUnescapedURLParts() {
        return unescapedURLParts;
    }

    /** Return the URL and query params, with all URL parts properly escaped. */
    @Override
    public String toString() {
        QueryStringEncoder encoder = new QueryStringEncoder(getNormalizedPath());
        if (unescapedQueryParams != null) {
            for (Entry<String, List<String>> ent : unescapedQueryParams.entrySet()) {
                List<String> vals = ent.getValue();
                if (vals != null) {
                    for (String val : vals) {
                        encoder.addParam(ent.getKey(), val);
                    }
                }
            }
        }
        return encoder.toString();
    }

    public File getFile(File rootDir) {
        File curr = rootDir;
        for (int i = 0; i < unescapedURLParts.size(); i++) {
            curr = new File(curr, unescapedURLParts.get(i));
            if (!curr.exists()) {
                return null;
            }
        }
        if (!curr.isFile()) {
            return null;
        }
        return curr;
    }
}
