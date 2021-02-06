/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.logstash.rest;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.logstash.action.GetPipelineAction;
import org.elasticsearch.xpack.logstash.action.GetPipelineRequest;
import org.elasticsearch.xpack.logstash.action.GetPipelineResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class RestGetPipelineAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "logstash_get_pipeline";
    }

    @Override
    public List<Route> routes() {
        return org.elasticsearch.common.collect.List.of(
            new Route(Method.GET, "/_logstash/pipeline"),
            new Route(Method.GET, "/_logstash/pipeline/{id}")
        );
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        final List<String> ids = Arrays.asList(request.paramAsStringArray("id", Strings.EMPTY_ARRAY));
        return restChannel -> client.execute(
            GetPipelineAction.INSTANCE,
            new GetPipelineRequest(ids),
            new RestToXContentListener<GetPipelineResponse>(restChannel) {
                @Override
                protected RestStatus getStatus(GetPipelineResponse response) {
                    if (response.pipelines().isEmpty() && ids.isEmpty() == false) {
                        return RestStatus.NOT_FOUND;
                    }
                    return RestStatus.OK;
                }
            }
        );
    }
}
