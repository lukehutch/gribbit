package gribbit.http.request.handler;

import gribbit.http.request.Request;
import gribbit.http.response.Response;
import gribbit.response.exception.RequestHandlingException;

public interface HttpErrorHandler<E extends RequestHandlingException> {
    public Response generateResponse(Request request, E e);
}
