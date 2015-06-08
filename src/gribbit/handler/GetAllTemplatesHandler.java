package gribbit.handler;

import gribbit.handler.route.annotation.RoutePath;
import gribbit.response.JSONResponse;
import gribbit.route.RouteHandler;
import gribbit.server.GribbitServer;
import io.netty.handler.codec.http.HttpResponseStatus;

@RoutePath("/gribbit/templates")
public class GetAllTemplatesHandler extends RouteHandler {
    public JSONResponse get() {
        return new JSONResponse(HttpResponseStatus.OK, GribbitServer.siteResources.getTemplateNameToTemplateStr());
    }
}
