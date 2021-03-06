// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.http.rest;

import org.apache.doris.catalog.Catalog;
import org.apache.doris.common.DdlException;
import org.apache.doris.http.ActionController;
import org.apache.doris.http.BaseRequest;
import org.apache.doris.http.BaseResponse;
import org.apache.doris.http.IllegalArgException;

import io.netty.handler.codec.http.HttpMethod;

/*
 * fe_host:fe_http_port/api/bootstrap
 * return:
 * {"status":"OK","msg":"Success"}
 * {"status":"FAILED","msg":"err info..."}
 */
public class BootstrapFinishAction extends RestBaseAction {
    public static final String HOST_PORTS = "host_ports";

    public BootstrapFinishAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, "/api/bootstrap", new BootstrapFinishAction(controller));
    }

    @Override
    public void execute(BaseRequest request, BaseResponse response) throws DdlException {
        boolean canRead = Catalog.getInstance().canRead();

        // to json response
        RestBaseResult result = null;
        if (canRead) {
            result = RestBaseResult.getOk();
        } else {
            result = new RestBaseResult("unfinished");
        }

        // send result
        response.setContentType("application/json");
        response.getContent().append(result.toJson());
        sendResult(request, response);
    }
}
