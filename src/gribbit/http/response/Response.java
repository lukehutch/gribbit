package gribbit.http.response;

import gribbit.http.request.Request;
import io.netty.handler.codec.http.HttpResponseStatus;


public abstract class Response implements AutoCloseable {
    protected final Request request;
    protected final HttpResponseStatus status;
    protected boolean keepAlive;

    public Response(Request request, HttpResponseStatus status) {
        this.request = request;
        this.status = status;

        // Close connection after serving response if response status is Bad Request or Internal Server Error.
        // TODO: Do we need to close connection on error? (e.g. does it help mitigate DoS attacks?)
        this.keepAlive = request.isKeepAlive() && (status != HttpResponseStatus.BAD_REQUEST //
                || this.status != HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    public abstract void close();

    @Override
    protected void finalize() throws Throwable {
        close();
    }
}
