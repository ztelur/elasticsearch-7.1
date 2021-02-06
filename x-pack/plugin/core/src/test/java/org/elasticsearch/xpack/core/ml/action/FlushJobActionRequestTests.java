/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.ml.action;

import org.elasticsearch.Version;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.xpack.core.ml.AbstractBWCWireSerializationTestCase;
import org.elasticsearch.xpack.core.ml.action.FlushJobAction.Request;

public class FlushJobActionRequestTests extends AbstractBWCWireSerializationTestCase<Request> {

    @Override
    protected Request createTestInstance() {
        Request request = new Request(randomAlphaOfLengthBetween(1, 20));
        if (randomBoolean()) {
            request.setWaitForNormalization(randomBoolean());
        }
        if (randomBoolean()) {
            request.setCalcInterim(randomBoolean());
        }
        if (randomBoolean()) {
            request.setStart(Long.toString(randomNonNegativeLong()));
        }
        if (randomBoolean()) {
            request.setEnd(Long.toString(randomNonNegativeLong()));
        }
        if (randomBoolean()) {
            request.setAdvanceTime(Long.toString(randomNonNegativeLong()));
        }
        if (randomBoolean()) {
            request.setSkipTime(Long.toString(randomNonNegativeLong()));
        }
        return request;
    }

    @Override
    protected Writeable.Reader<Request> instanceReader() {
        return Request::new;
    }

    @Override
    protected Request mutateInstanceForVersion(Request instance, Version version) {
        if (version.before(Version.V_7_9_0)) {
            Request bwcRequest = new Request(instance.jobId);
            bwcRequest.setWaitForNormalization(true);
            bwcRequest.setCalcInterim(instance.getCalcInterim());
            bwcRequest.setStart(instance.getStart());
            bwcRequest.setEnd(instance.getEnd());
            bwcRequest.setAdvanceTime(instance.getAdvanceTime());
            bwcRequest.setSkipTime(instance.getSkipTime());
            return bwcRequest;
        }
        return instance;
    }
}
