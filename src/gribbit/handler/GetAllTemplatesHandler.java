package gribbit.handler;

import io.netty.handler.codec.http.HttpResponseStatus;
import gribbit.handler.route.annotation.RouteOverride;
import gribbit.response.JSONResponse;
import gribbit.route.RouteHandlerAuthRequired;
import gribbit.server.GribbitServer;

@RouteOverride("/gribbit/templates")
/** This is an AuthRequired handler in case there is anything sensitive in the templates. */
public class GetAllTemplatesHandler extends RouteHandlerAuthRequired {
    public JSONResponse get() {
        return new JSONResponse(HttpResponseStatus.OK, GribbitServer.siteResources.getTemplateNameToTemplateStr());
    }
}
