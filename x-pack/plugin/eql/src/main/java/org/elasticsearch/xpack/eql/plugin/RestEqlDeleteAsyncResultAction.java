/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.eql.plugin;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.core.async.DeleteAsyncResultAction;
import org.elasticsearch.xpack.core.async.DeleteAsyncResultRequest;

import java.util.Collections;
import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.DELETE;

public class RestEqlDeleteAsyncResultAction extends BaseRestHandler {
    @Override
    public List<Route> routes() {
        return Collections.singletonList(new Route(DELETE, "/_eql/search/{id}"));
    }

    @Override
    public String getName() {
        return "eql_delete_async_result";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        DeleteAsyncResultRequest delete = new DeleteAsyncResultRequest(request.param("id"));
        return channel -> client.execute(DeleteAsyncResultAction.INSTANCE, delete, new RestToXContentListener<>(channel));
    }
}
