package gribbit.http.response;

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * A text-based error response.
 */
public class ErrorResponse extends TextResponse {
    public ErrorResponse(HttpResponseStatus status, String content) {
        super(status, content);
    }
}
