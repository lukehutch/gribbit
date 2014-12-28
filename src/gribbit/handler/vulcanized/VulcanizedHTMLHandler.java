/**
 * This file is part of the Gribbit Web Framework.
 * 
 *     https://github.com/lukehutch/gribbit
 * 
 * @author Luke Hutchison
 * 
 * --
 * 
 * @license Apache 2.0 
 * 
 * Copyright 2015 Luke Hutchison
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gribbit.handler.vulcanized;

import gribbit.handler.route.annotation.RouteOverride;
import gribbit.response.ByteBufResponse;
import gribbit.response.NotModifiedResponse;
import gribbit.response.Response;
import gribbit.route.AuthNotRequiredRoute;
import gribbit.server.GribbitServer;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

@RouteOverride("/vulcanized-html")
public interface VulcanizedHTMLHandler extends AuthNotRequiredRoute {
    public default Response get() throws Exception {
        long resourcesLoadedEpochSeconds = GribbitServer.siteResources.getResourcesLoadedEpochSeconds();
        if (!getRequest().cachedVersionIsOlderThan(resourcesLoadedEpochSeconds)) {
            // Classpath elements have not changed since last fetched by browser -- return Not Modified 
            return new NotModifiedResponse(resourcesLoadedEpochSeconds);
        } else {
            // Classpath elements have changed since the last version fetched by the browser --
            // return the latest version of the vulcanized resources from GribbitServer.siteResources
            ByteBuf vulcanizedHTML = GribbitServer.siteResources.getVulcanizedHTML();
            // Have to increase refcount each time the resource is served
            vulcanizedHTML.retain();
            ByteBufResponse response =
                    new ByteBufResponse(HttpResponseStatus.OK, "text/html;charset=utf-8", vulcanizedHTML);
            response.setLastModifiedEpochSeconds(resourcesLoadedEpochSeconds);
            return response;
        }
    }
}
