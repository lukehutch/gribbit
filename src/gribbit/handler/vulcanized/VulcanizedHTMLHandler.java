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

import gribbit.handler.error.NotModified;
import gribbit.handler.route.annotation.RouteOverride;
import gribbit.server.GribbitServer;
import gribbit.server.RestHandler;
import io.netty.buffer.Unpooled;

@RouteOverride("/vulcanized-html")
public class VulcanizedHTMLHandler extends RestHandler.AuthNotRequired {
    public void get() throws Exception {
        long resourcesLoadedEpochSecond = GribbitServer.siteResources.getResourcesLoadedEpochSecond();
        if (req.cachedVersionIsOlderThan(resourcesLoadedEpochSecond)) {
            // Return the latest vulcanized content
            res.setContentUnsafe(Unpooled.wrappedBuffer(GribbitServer.siteResources.getVulcanizedHTMLBytes()),
                    "text/html;charset=utf-8");
        } else {
            // Classpath elements have not changed since last fetched by browser.
            // Call the Not Modified handler. 
            GribbitServer.siteResources.routeForHandler(NotModified.class).callHandler(req, res);
        }
        // Have to set this header even on Not Modified, or the resource will no longer be cached.
        res.setLastModifiedEpochSecond(resourcesLoadedEpochSecond);
    }
}
