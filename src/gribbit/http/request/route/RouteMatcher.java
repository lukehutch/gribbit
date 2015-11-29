package gribbit.http.request.route;

import gribbit.http.request.Request;
import gribbit.http.response.GeneralResponse;
import gribbit.http.response.exception.ResponseException;

public abstract class RouteMatcher {
    public abstract GeneralResponse match(Request request) throws ResponseException;
}
