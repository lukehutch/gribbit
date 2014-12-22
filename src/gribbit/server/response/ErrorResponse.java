package gribbit.server.response;

import io.netty.handler.codec.http.HttpResponseStatus;

public class ErrorResponse extends TextResponse {

    public ErrorResponse(HttpResponseStatus status, String content) {
        super(status, content);
    }

}
