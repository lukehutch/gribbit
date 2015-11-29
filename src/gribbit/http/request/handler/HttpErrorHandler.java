package gribbit.http.request.handler;

import gribbit.http.request.Request;
import gribbit.http.response.GeneralResponse;
import gribbit.http.response.exception.ResponseException;

public interface HttpErrorHandler<E extends ResponseException> {
    public GeneralResponse generateResponse(Request request, E e);
}
