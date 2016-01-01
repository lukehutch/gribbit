package gribbit.http.route;

import gribbit.http.request.Request;
import gribbit.http.response.Response;
import gribbit.http.response.exception.ResponseException;

public abstract class RouteMatcher {
    public abstract Response match(Request request) throws ResponseException;
}
