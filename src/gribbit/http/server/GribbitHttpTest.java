package gribbit.http.server;

import gribbit.http.request.Request;
import gribbit.http.request.handler.HttpRequestHandler;
import gribbit.http.response.FileResponse;
import gribbit.http.response.GeneralResponse;
import gribbit.http.response.HTMLResponse;
import gribbit.http.response.exception.ResponseException;

public class GribbitHttpTest {

    public static void main(String[] args) {
        new GribbitHttpServer().addHttpRequestHandler(new HttpRequestHandler() {
            @Override
            public GeneralResponse handle(Request request) throws ResponseException {
                if (!request.getURL().endsWith(".jpg")) {
                    return new HTMLResponse(request, "<html><body><p>Hello -- " + request.getURL()
                            + "</p><img src=\"/test.jpg\">");
                } else {
                    return null;
                }
            }
        }).addHttpRequestHandler(new HttpRequestHandler() {
            @Override
            public GeneralResponse handle(Request request) throws ResponseException {
                return new FileResponse(request, "/home/luke/Photos/2014-02-15 Olive.jpg");
            }
        }).start();
    }

}
