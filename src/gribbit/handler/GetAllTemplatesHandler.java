package gribbit.handler;

import gribbit.auth.RoleNames;
import gribbit.handler.route.annotation.Roles;
import gribbit.handler.route.annotation.RoutePath;
import gribbit.response.JSONResponse;
import gribbit.route.RouteHandler;
import gribbit.server.GribbitServer;
import io.netty.handler.codec.http.HttpResponseStatus;

@Roles({RoleNames.ADMIN})
@RoutePath("/gribbit/templates")
public class GetAllTemplatesHandler extends RouteHandler {
    public JSONResponse get() {
        return new JSONResponse(HttpResponseStatus.OK, GribbitServer.siteResources.getTemplateNameToTemplateStr());
    }
}
