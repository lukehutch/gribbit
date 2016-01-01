package gribbit.http.request.handler;

import gribbit.http.request.Request;
import gribbit.http.response.Response;
import gribbit.http.response.exception.ResponseException;

public interface HttpErrorHandler<E extends ResponseException> {
    public Response generateResponse(Request request, E e);
}
