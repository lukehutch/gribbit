package gribbit.route;

import gribbit.response.exception.BadRequestException;
import gribbit.response.exception.ResponseException;
import gribbit.util.URLUtils;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.QueryStringEncoder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class RequestURL {
    private final List<String> unescapedURLParts;
    private final String escapedNormalizedURL;
    private final Map<String, List<String>> unescapedQueryParams;

    /**
     * Parse the request URL, handle unescaping of path and query segments, and normalize ".." and "." path
     * elements.
     */
    public RequestURL(String requestURL) throws ResponseException {
        QueryStringDecoder decoder = new QueryStringDecoder(requestURL);
        String urlPath = decoder.path();
        this.unescapedQueryParams = decoder.parameters();

        if (urlPath.isEmpty()) {
            urlPath = "/";
        }
        if (!urlPath.startsWith("/")) {
            throw new BadRequestException("Requests must start with '/'");
        }
        // Unescape URL parts so URL can be normalized
        String[] parts = urlPath.equals("/") ? new String[] { "" } : urlPath.split("/");
        this.unescapedURLParts = new ArrayList<>(parts.length - 1);
        for (int i = 1, n = parts.length; i < n; i++) {
            String pathElt = URLUtils.unescapeURLSegment(parts[i]);
            if (!pathElt.isEmpty() && !pathElt.equals(".")) {
                if (pathElt.equals("..")) {
                    if (unescapedURLParts.size() == 0) {
                        throw new BadRequestException("Attempt to navigate above root");
                    }
                    unescapedURLParts.remove(unescapedURLParts.size() - 1);
                } else {
                    unescapedURLParts.add(pathElt);
                }
            }
        }
        // Re-escape normalized URL path segments, so that normalized URL can be matched against routes
        StringBuilder buf = new StringBuilder();
        for (String part : unescapedURLParts) {
            buf.append('/');
            buf.append(URLUtils.escapeURLSegment(part));
        }
        escapedNormalizedURL = buf.length() == 0 ? "/" : buf.toString();
    }

    /** Return the URL path, normalized to handle "..", ".", and empty path segments. */
    public String getNormalizedPath() {
        return escapedNormalizedURL;
    }

    /** Returns the query params, unescaped. */
    public Map<String, List<String>> getQueryParams() {
        return unescapedQueryParams;
    }

    /** Return the URL and query params, with all URL parts properly escaped. */
    @Override
    public String toString() {
        QueryStringEncoder encoder = new QueryStringEncoder(escapedNormalizedURL);
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
