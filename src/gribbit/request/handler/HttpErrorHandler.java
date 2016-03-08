package gribbit.request.handler;

import gribbit.response.Response;
import gribbit.response.exception.ResponseException;
import io.vertx.core.http.HttpServerRequest;

public interface HttpErrorHandler {
    public <E extends ResponseException> Response generateResponse(HttpServerRequest request, E e);
}
