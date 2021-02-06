/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.ingest.PutPipelineAction;
import org.elasticsearch.action.ingest.PutPipelineRequest;
import org.elasticsearch.action.ingest.SimulateDocumentBaseResult;
import org.elasticsearch.action.ingest.SimulatePipelineAction;
import org.elasticsearch.action.ingest.SimulatePipelineRequest;
import org.elasticsearch.action.ingest.SimulatePipelineResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.license.License.OperationMode;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.AvgAggregationBuilder;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.xpack.core.TestXPackTransportClient;
import org.elasticsearch.xpack.core.XPackField;
import org.elasticsearch.xpack.core.ml.MlConfigIndex;
import org.elasticsearch.xpack.core.ml.action.CloseJobAction;
import org.elasticsearch.xpack.core.ml.action.DeleteDatafeedAction;
import org.elasticsearch.xpack.core.ml.action.DeleteJobAction;
import org.elasticsearch.xpack.core.ml.action.GetDatafeedsStatsAction;
import org.elasticsearch.xpack.core.ml.action.GetJobsStatsAction;
import org.elasticsearch.xpack.core.ml.action.InternalInferModelAction;
import org.elasticsearch.xpack.core.ml.action.NodeAcknowledgedResponse;
import org.elasticsearch.xpack.core.ml.action.OpenJobAction;
import org.elasticsearch.xpack.core.ml.action.PutDatafeedAction;
import org.elasticsearch.xpack.core.ml.action.PutJobAction;
import org.elasticsearch.xpack.core.ml.action.PutTrainedModelAction;
import org.elasticsearch.xpack.core.ml.action.StartDatafeedAction;
import org.elasticsearch.xpack.core.ml.action.StopDatafeedAction;
import org.elasticsearch.xpack.core.ml.client.MachineLearningClient;
import org.elasticsearch.xpack.core.ml.datafeed.DatafeedState;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelConfig;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelDefinition;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelInput;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.RegressionConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.RegressionConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TargetType;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.tree.Tree;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.tree.TreeNode;
import org.elasticsearch.xpack.core.ml.job.config.JobState;
import org.elasticsearch.xpack.ml.LocalStateMachineLearning;
import org.elasticsearch.xpack.ml.inference.aggs.InferencePipelineAggregationBuilder;
import org.elasticsearch.xpack.ml.inference.loadingservice.ModelLoadingService;
import org.elasticsearch.xpack.ml.support.BaseMlIntegTestCase;
import org.junit.Before;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class MachineLearningLicensingIT extends BaseMlIntegTestCase {

    @Before
    public void resetLicensing() {
        enableLicensing();

        ensureStableCluster(1);
        ensureYellow();
    }

    public void testMachineLearningPutJobActionRestricted() {
        String jobId = "testmachinelearningputjobactionrestricted";
        // Pick a license that does not allow machine learning
        License.OperationMode mode = randomInvalidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(false);
        // test that license restricted apis do not work
        Settings settings = internalCluster().transportClient().settings();
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutJobAction.Response> listener = PlainActionFuture. newFuture();
            new MachineLearningClient(client).putJob(new PutJobAction.Request(createJob(jobId)), listener);
            listener.actionGet();
            fail("put job action should not be enabled!");
        } catch (ElasticsearchSecurityException e) {
            assertThat(e.status(), is(RestStatus.FORBIDDEN));
            assertThat(e.getMessage(), containsString("non-compliant"));
            assertThat(e.getMetadata(LicenseUtils.EXPIRED_FEATURE_METADATA), hasItem(XPackField.MACHINE_LEARNING));
        }

        // Pick a license that does allow machine learning
        mode = randomValidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(true);
        // test that license restricted apis do now work
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutJobAction.Response> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putJob(new PutJobAction.Request(createJob(jobId)), listener);
            PutJobAction.Response response = listener.actionGet();
            assertNotNull(response);
        }
    }

    public void testMachineLearningOpenJobActionRestricted() throws Exception {
        String jobId = "testmachinelearningopenjobactionrestricted";
        assertMLAllowed(true);
        // test that license restricted apis do now work
        Settings settings = internalCluster().transportClient().settings();
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutJobAction.Response> putJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putJob(new PutJobAction.Request(createJob(jobId)), putJobListener);
            PutJobAction.Response response = putJobListener.actionGet();
            assertNotNull(response);
        }

        // Pick a license that does not allow machine learning
        License.OperationMode mode = randomInvalidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(false);
        // test that license restricted apis do not work
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<NodeAcknowledgedResponse> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).openJob(new OpenJobAction.Request(jobId), listener);
            listener.actionGet();
            fail("open job action should not be enabled!");
        } catch (ElasticsearchSecurityException e) {
            assertThat(e.status(), is(RestStatus.FORBIDDEN));
            assertThat(e.getMessage(), containsString("non-compliant"));
            assertThat(e.getMetadata(LicenseUtils.EXPIRED_FEATURE_METADATA), hasItem(XPackField.MACHINE_LEARNING));
        }

        // Pick a license that does allow machine learning
        mode = randomValidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(true);

        // now that the license is invalid, the job should get closed:
        assertBusy(() -> {
            JobState jobState = getJobStats(jobId).getState();
            assertEquals(JobState.CLOSED, jobState);
        });

        // test that license restricted apis do now work
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<NodeAcknowledgedResponse> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).openJob(new OpenJobAction.Request(jobId), listener);
            AcknowledgedResponse response = listener.actionGet();
            assertNotNull(response);
        }
    }

    public void testMachineLearningPutDatafeedActionRestricted() {
        String jobId = "testmachinelearningputdatafeedactionrestricted";
        String datafeedId = jobId + "-datafeed";
        assertMLAllowed(true);
        // test that license restricted apis do now work
        Settings settings = internalCluster().transportClient().settings();
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutJobAction.Response> putJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putJob(new PutJobAction.Request(createJob(jobId)), putJobListener);
            PutJobAction.Response putJobResponse = putJobListener.actionGet();
            assertNotNull(putJobResponse);
        }

        // Pick a license that does not allow machine learning
        License.OperationMode mode = randomInvalidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(false);
        // test that license restricted apis do not work
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutDatafeedAction.Response> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putDatafeed(
                    new PutDatafeedAction.Request(createDatafeed(datafeedId, jobId, Collections.singletonList(jobId))), listener);
            listener.actionGet();
            fail("put datafeed action should not be enabled!");
        } catch (ElasticsearchSecurityException e) {
            assertThat(e.status(), is(RestStatus.FORBIDDEN));
            assertThat(e.getMessage(), containsString("non-compliant"));
            assertThat(e.getMetadata(LicenseUtils.EXPIRED_FEATURE_METADATA), hasItem(XPackField.MACHINE_LEARNING));
        }

        // Pick a license that does allow machine learning
        mode = randomValidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(true);
        // test that license restricted apis do now work
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutDatafeedAction.Response> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putDatafeed(
                    new PutDatafeedAction.Request(createDatafeed(datafeedId, jobId, Collections.singletonList(jobId))), listener);
            PutDatafeedAction.Response response = listener.actionGet();
            assertNotNull(response);
        }
    }

    public void testAutoCloseJobWithDatafeed() throws Exception {
        String jobId = "testautoclosejobwithdatafeed";
        String datafeedId = jobId + "-datafeed";
        assertMLAllowed(true);
        String datafeedIndex = jobId + "-data";
        prepareCreate(datafeedIndex).addMapping("type", "{\"type\":{\"properties\":{\"time\":{\"type\":\"date\"}}}}",
            XContentType.JSON).get();
        Settings settings = internalCluster().transportClient().settings();
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            // put job
            PlainActionFuture<PutJobAction.Response> putJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putJob(new PutJobAction.Request(createJob(jobId)), putJobListener);
            PutJobAction.Response putJobResponse = putJobListener.actionGet();
            assertNotNull(putJobResponse);
            // put datafeed
            PlainActionFuture<PutDatafeedAction.Response> putDatafeedListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putDatafeed(
                    new PutDatafeedAction.Request(createDatafeed(datafeedId, jobId,
                            Collections.singletonList(datafeedIndex))), putDatafeedListener);
            PutDatafeedAction.Response putDatafeedResponse = putDatafeedListener.actionGet();
            assertNotNull(putDatafeedResponse);
            // open job
            PlainActionFuture<NodeAcknowledgedResponse> openJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).openJob(new OpenJobAction.Request(jobId), openJobListener);
            AcknowledgedResponse openJobResponse = openJobListener.actionGet();
            assertNotNull(openJobResponse);
            // start datafeed
            PlainActionFuture<NodeAcknowledgedResponse> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).startDatafeed(new StartDatafeedAction.Request(datafeedId, 0L), listener);
            listener.actionGet();
        }

        if (randomBoolean()) {
            enableLicensing(randomInvalidLicenseType());
        } else {
            disableLicensing();
        }
        assertMLAllowed(false);

        client().admin().indices().prepareRefresh(MlConfigIndex.indexName()).get();

        // now that the license is invalid, the job should be closed and datafeed stopped:
        assertBusy(() -> {
            JobState jobState = getJobStats(jobId).getState();
            assertEquals(JobState.CLOSED, jobState);

            DatafeedState datafeedState = getDatafeedStats(datafeedId).getDatafeedState();
            assertEquals(DatafeedState.STOPPED, datafeedState);

            ClusterState state = client().admin().cluster().prepareState().get().getState();
            PersistentTasksCustomMetadata tasks = state.metadata().custom(PersistentTasksCustomMetadata.TYPE);
            assertEquals(0, tasks.taskMap().size());
        });

        enableLicensing(randomValidLicenseType());
        assertMLAllowed(true);

        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            // open job
            PlainActionFuture<NodeAcknowledgedResponse> openJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).openJob(new OpenJobAction.Request(jobId), openJobListener);
            AcknowledgedResponse openJobResponse = openJobListener.actionGet();
            assertNotNull(openJobResponse);
            // start datafeed
            PlainActionFuture<NodeAcknowledgedResponse> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).startDatafeed(new StartDatafeedAction.Request(datafeedId, 0L), listener);
            listener.actionGet();
        }

        assertBusy(() -> {
            JobState jobState = getJobStats(jobId).getState();
            assertEquals(JobState.OPENED, jobState);

            DatafeedState datafeedState = getDatafeedStats(datafeedId).getDatafeedState();
            assertEquals(DatafeedState.STARTED, datafeedState);

            ClusterState state = client().admin().cluster().prepareState().get().getState();
            PersistentTasksCustomMetadata tasks = state.metadata().custom(PersistentTasksCustomMetadata.TYPE);
            assertEquals(2, tasks.taskMap().size());
        });

        if (randomBoolean()) {
            enableLicensing(randomInvalidLicenseType());
        } else {
            disableLicensing();
        }
        assertMLAllowed(false);

        // now that the license is invalid, the job should be closed and datafeed stopped:
        assertBusy(() -> {
            JobState jobState = getJobStats(jobId).getState();
            assertEquals(JobState.CLOSED, jobState);

            DatafeedState datafeedState = getDatafeedStats(datafeedId).getDatafeedState();
            assertEquals(DatafeedState.STOPPED, datafeedState);

            ClusterState state = client().admin().cluster().prepareState().get().getState();
            PersistentTasksCustomMetadata tasks = state.metadata().custom(PersistentTasksCustomMetadata.TYPE);
            assertEquals(0, tasks.taskMap().size());
        });
    }

    public void testMachineLearningStartDatafeedActionRestricted() throws Exception {
        String jobId = "testmachinelearningstartdatafeedactionrestricted";
        String datafeedId = jobId + "-datafeed";
        assertMLAllowed(true);
        String datafeedIndex = jobId + "-data";
        prepareCreate(datafeedIndex).addMapping("type", "{\"type\":{\"properties\":{\"time\":{\"type\":\"date\"}}}}",
            XContentType.JSON).get();
        // test that license restricted apis do now work
        Settings settings = internalCluster().transportClient().settings();
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutJobAction.Response> putJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putJob(new PutJobAction.Request(createJob(jobId)), putJobListener);
            PutJobAction.Response putJobResponse = putJobListener.actionGet();
            assertNotNull(putJobResponse);
            PlainActionFuture<PutDatafeedAction.Response> putDatafeedListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putDatafeed(
                    new PutDatafeedAction.Request(createDatafeed(datafeedId, jobId,
                            Collections.singletonList(datafeedIndex))), putDatafeedListener);
            PutDatafeedAction.Response putDatafeedResponse = putDatafeedListener.actionGet();
            assertNotNull(putDatafeedResponse);
            PlainActionFuture<NodeAcknowledgedResponse> openJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).openJob(new OpenJobAction.Request(jobId), openJobListener);
            AcknowledgedResponse openJobResponse = openJobListener.actionGet();
            assertNotNull(openJobResponse);
        }

        // Pick a license that does not allow machine learning
        License.OperationMode mode = randomInvalidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(false);

        // now that the license is invalid, the job should get closed:
        assertBusy(() -> {
            JobState jobState = getJobStats(jobId).getState();
            assertEquals(JobState.CLOSED, jobState);
            ClusterState state = client().admin().cluster().prepareState().get().getState();
            PersistentTasksCustomMetadata tasks = state.metadata().custom(PersistentTasksCustomMetadata.TYPE);
            assertEquals(0, tasks.taskMap().size());
        });

        // test that license restricted apis do not work
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<NodeAcknowledgedResponse> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).startDatafeed(new StartDatafeedAction.Request(datafeedId, 0L), listener);
            listener.actionGet();
            fail("start datafeed action should not be enabled!");
        } catch (ElasticsearchSecurityException e) {
            assertThat(e.status(), is(RestStatus.FORBIDDEN));
            assertThat(e.getMessage(), containsString("non-compliant"));
            assertThat(e.getMetadata(LicenseUtils.EXPIRED_FEATURE_METADATA), hasItem(XPackField.MACHINE_LEARNING));
        }

        // Pick a license that does allow machine learning
        mode = randomValidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(true);
        // test that license restricted apis do now work
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            // re-open job now that the license is valid again
            PlainActionFuture<NodeAcknowledgedResponse> openJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).openJob(new OpenJobAction.Request(jobId), openJobListener);
            AcknowledgedResponse openJobResponse = openJobListener.actionGet();
            assertNotNull(openJobResponse);

            PlainActionFuture<NodeAcknowledgedResponse> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).startDatafeed(new StartDatafeedAction.Request(datafeedId, 0L), listener);
            AcknowledgedResponse response = listener.actionGet();
            assertNotNull(response);
        }
    }

    public void testMachineLearningStopDatafeedActionNotRestricted() throws Exception {
        String jobId = "testmachinelearningstopdatafeedactionnotrestricted";
        String datafeedId = jobId + "-datafeed";
        assertMLAllowed(true);
        String datafeedIndex = jobId + "-data";
        prepareCreate(datafeedIndex).addMapping("type", "{\"type\":{\"properties\":{\"time\":{\"type\":\"date\"}}}}",
            XContentType.JSON).get();
        // test that license restricted apis do now work
        Settings settings = internalCluster().transportClient().settings();
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutJobAction.Response> putJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putJob(new PutJobAction.Request(createJob(jobId)), putJobListener);
            PutJobAction.Response putJobResponse = putJobListener.actionGet();
            assertNotNull(putJobResponse);
            PlainActionFuture<PutDatafeedAction.Response> putDatafeedListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putDatafeed(
                    new PutDatafeedAction.Request(createDatafeed(datafeedId, jobId,
                            Collections.singletonList(datafeedIndex))), putDatafeedListener);
            PutDatafeedAction.Response putDatafeedResponse = putDatafeedListener.actionGet();
            assertNotNull(putDatafeedResponse);
            PlainActionFuture<NodeAcknowledgedResponse> openJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).openJob(new OpenJobAction.Request(jobId), openJobListener);
            AcknowledgedResponse openJobResponse = openJobListener.actionGet();
            assertNotNull(openJobResponse);
            PlainActionFuture<NodeAcknowledgedResponse> startDatafeedListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).startDatafeed(
                new StartDatafeedAction.Request(datafeedId, 0L), startDatafeedListener);
            AcknowledgedResponse startDatafeedResponse = startDatafeedListener.actionGet();
            assertNotNull(startDatafeedResponse);
        }

        boolean invalidLicense = randomBoolean();
        if (invalidLicense) {
            enableLicensing(randomInvalidLicenseType());
        } else {
            enableLicensing(randomValidLicenseType());
        }

        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<StopDatafeedAction.Response> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).stopDatafeed(new StopDatafeedAction.Request(datafeedId), listener);
            if (invalidLicense) {
                // the stop datafeed due to invalid license happens async, so check if the datafeed turns into stopped state:
                assertBusy(() -> {
                    GetDatafeedsStatsAction.Response response =
                            new MachineLearningClient(client)
                                    .getDatafeedsStats(new GetDatafeedsStatsAction.Request(datafeedId)).actionGet();
                    assertEquals(DatafeedState.STOPPED, response.getResponse().results().get(0).getDatafeedState());
                });
            } else {
                listener.actionGet();
            }

            if (invalidLicense) {
                // the close due to invalid license happens async, so check if the job turns into closed state:
                assertBusy(() -> {
                    GetJobsStatsAction.Response response =
                            new MachineLearningClient(client).getJobsStats(new GetJobsStatsAction.Request(jobId)).actionGet();
                    assertEquals(JobState.CLOSED, response.getResponse().results().get(0).getState());
                });
            }
        }
    }

    public void testMachineLearningCloseJobActionNotRestricted() throws Exception {
        String jobId = "testmachinelearningclosejobactionnotrestricted";
        assertMLAllowed(true);
        // test that license restricted apis do now work
        Settings settings = internalCluster().transportClient().settings();
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutJobAction.Response> putJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putJob(new PutJobAction.Request(createJob(jobId)), putJobListener);
            PutJobAction.Response putJobResponse = putJobListener.actionGet();
            assertNotNull(putJobResponse);
            PlainActionFuture<NodeAcknowledgedResponse> openJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).openJob(new OpenJobAction.Request(jobId), openJobListener);
            AcknowledgedResponse openJobResponse = openJobListener.actionGet();
            assertNotNull(openJobResponse);
        }

        boolean invalidLicense = randomBoolean();
        if (invalidLicense) {
            enableLicensing(randomInvalidLicenseType());
        } else {
            enableLicensing(randomValidLicenseType());
        }

        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<CloseJobAction.Response> listener = PlainActionFuture.newFuture();
            CloseJobAction.Request request = new CloseJobAction.Request(jobId);
            request.setCloseTimeout(TimeValue.timeValueSeconds(20));
            if (invalidLicense) {
                // the close due to invalid license happens async, so check if the job turns into closed state:
                assertBusy(() -> {
                    GetJobsStatsAction.Response response =
                            new MachineLearningClient(client).getJobsStats(new GetJobsStatsAction.Request(jobId)).actionGet();
                    assertEquals(JobState.CLOSED, response.getResponse().results().get(0).getState());
                });
            } else {
                new MachineLearningClient(client).closeJob(request, listener);
                listener.actionGet();
            }
        }
    }

    public void testMachineLearningDeleteJobActionNotRestricted() {
        String jobId = "testmachinelearningclosejobactionnotrestricted";
        assertMLAllowed(true);
        // test that license restricted apis do now work
        Settings settings = internalCluster().transportClient().settings();
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutJobAction.Response> putJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putJob(new PutJobAction.Request(createJob(jobId)), putJobListener);
            PutJobAction.Response putJobResponse = putJobListener.actionGet();
            assertNotNull(putJobResponse);
        }

        // Pick a random license
        License.OperationMode mode = randomLicenseType();
        enableLicensing(mode);

        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<AcknowledgedResponse> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).deleteJob(new DeleteJobAction.Request(jobId), listener);
            listener.actionGet();
        }
    }

    public void testMachineLearningDeleteDatafeedActionNotRestricted() {
        String jobId = "testmachinelearningdeletedatafeedactionnotrestricted";
        String datafeedId = jobId + "-datafeed";
        assertMLAllowed(true);
        // test that license restricted apis do now work
        Settings settings = internalCluster().transportClient().settings();
        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<PutJobAction.Response> putJobListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putJob(new PutJobAction.Request(createJob(jobId)), putJobListener);
            PutJobAction.Response putJobResponse = putJobListener.actionGet();
            assertNotNull(putJobResponse);
            PlainActionFuture<PutDatafeedAction.Response> putDatafeedListener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).putDatafeed(
                    new PutDatafeedAction.Request(createDatafeed(datafeedId, jobId,
                            Collections.singletonList(jobId))), putDatafeedListener);
            PutDatafeedAction.Response putDatafeedResponse = putDatafeedListener.actionGet();
            assertNotNull(putDatafeedResponse);
        }

        // Pick a random license
        License.OperationMode mode = randomLicenseType();
        enableLicensing(mode);

        try (TransportClient client = new TestXPackTransportClient(settings, LocalStateMachineLearning.class)) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            PlainActionFuture<AcknowledgedResponse> listener = PlainActionFuture.newFuture();
            new MachineLearningClient(client).deleteDatafeed(new DeleteDatafeedAction.Request(datafeedId), listener);
            listener.actionGet();
        }
    }

    public void testMachineLearningCreateInferenceProcessorRestricted() {
        String modelId = "modelprocessorlicensetest";
        assertMLAllowed(true);
        putInferenceModel(modelId);

        String pipeline = "{" +
            "    \"processors\": [\n" +
            "      {\n" +
            "        \"inference\": {\n" +
            "          \"target_field\": \"regression_value\",\n" +
            "          \"model_id\": \"modelprocessorlicensetest\",\n" +
            "          \"inference_config\": {\"regression\": {}},\n" +
            "          \"field_map\": {}\n" +
            "        }\n" +
            "      }]}\n";
        // Creating a pipeline should work
        PlainActionFuture<AcknowledgedResponse> putPipelineListener = PlainActionFuture.newFuture();
        client().execute(PutPipelineAction.INSTANCE,
            new PutPipelineRequest("test_infer_license_pipeline",
                new BytesArray(pipeline.getBytes(StandardCharsets.UTF_8)),
                XContentType.JSON),
            putPipelineListener);
        AcknowledgedResponse putPipelineResponse = putPipelineListener.actionGet();
        assertTrue(putPipelineResponse.isAcknowledged());

        client().prepareIndex("infer_license_test", MapperService.SINGLE_MAPPING_NAME)
            .setPipeline("test_infer_license_pipeline")
            .setSource("{}", XContentType.JSON)
            .execute()
            .actionGet();

        String simulateSource = "{\n" +
            "  \"pipeline\": \n" +
            pipeline +
            "  ,\n" +
            "  \"docs\": [\n" +
            "    {\"_source\": {\n" +
            "      \"col1\": \"female\",\n" +
            "      \"col2\": \"M\",\n" +
            "      \"col3\": \"none\",\n" +
            "      \"col4\": 10\n" +
            "    }}]\n" +
            "}";
        PlainActionFuture<SimulatePipelineResponse> simulatePipelineListener = PlainActionFuture.newFuture();
        client().execute(SimulatePipelineAction.INSTANCE,
            new SimulatePipelineRequest(new BytesArray(simulateSource.getBytes(StandardCharsets.UTF_8)), XContentType.JSON),
            simulatePipelineListener);

        assertThat(simulatePipelineListener.actionGet().getResults(), is(not(empty())));

        // Pick a license that does not allow machine learning
        License.OperationMode mode = randomInvalidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(false);

        // Inference against the previous pipeline should still work
        try {
            client().prepareIndex("infer_license_test", MapperService.SINGLE_MAPPING_NAME)
                .setPipeline("test_infer_license_pipeline")
                .setSource("{}", XContentType.JSON)
                .execute()
                .actionGet();
        } catch (ElasticsearchSecurityException ex) {
            fail(ex.getMessage());
        }

        // Creating a new pipeline with an inference processor should work
        putPipelineListener = PlainActionFuture.newFuture();
        client().execute(PutPipelineAction.INSTANCE,
            new PutPipelineRequest("test_infer_license_pipeline_again",
                new BytesArray(pipeline.getBytes(StandardCharsets.UTF_8)),
                XContentType.JSON),
            putPipelineListener);
        putPipelineResponse = putPipelineListener.actionGet();
        assertTrue(putPipelineResponse.isAcknowledged());

        // Inference against the new pipeline should fail since it has never previously succeeded
        ElasticsearchSecurityException e = expectThrows(ElasticsearchSecurityException.class, () -> {
            client().prepareIndex("infer_license_test", MapperService.SINGLE_MAPPING_NAME)
                .setPipeline("test_infer_license_pipeline_again")
                .setSource("{}", XContentType.JSON)
                .execute()
                .actionGet();
        });
        assertThat(e.status(), is(RestStatus.FORBIDDEN));
        assertThat(e.getMessage(), containsString("non-compliant"));
        assertThat(e.getMetadata(LicenseUtils.EXPIRED_FEATURE_METADATA), hasItem(XPackField.MACHINE_LEARNING));

        // Simulating the pipeline should fail
        SimulateDocumentBaseResult simulateResponse = (SimulateDocumentBaseResult)client().execute(SimulatePipelineAction.INSTANCE,
            new SimulatePipelineRequest(new BytesArray(simulateSource.getBytes(StandardCharsets.UTF_8)), XContentType.JSON))
            .actionGet()
            .getResults()
            .get(0);
        assertThat(simulateResponse.getFailure(), is(not(nullValue())));
        assertThat((simulateResponse.getFailure()).getCause(), is(instanceOf(ElasticsearchSecurityException.class)));

        // Pick a license that does allow machine learning
        mode = randomValidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(true);
        // test that license restricted apis do now work
        PlainActionFuture<AcknowledgedResponse> putPipelineListenerNewLicense = PlainActionFuture.newFuture();
        client().execute(PutPipelineAction.INSTANCE,
            new PutPipelineRequest("test_infer_license_pipeline",
                new BytesArray(pipeline.getBytes(StandardCharsets.UTF_8)),
                XContentType.JSON),
            putPipelineListenerNewLicense);
        AcknowledgedResponse putPipelineResponseNewLicense = putPipelineListenerNewLicense.actionGet();
        assertTrue(putPipelineResponseNewLicense.isAcknowledged());

        PlainActionFuture<SimulatePipelineResponse> simulatePipelineListenerNewLicense = PlainActionFuture.newFuture();
        client().execute(SimulatePipelineAction.INSTANCE,
            new SimulatePipelineRequest(new BytesArray(simulateSource.getBytes(StandardCharsets.UTF_8)), XContentType.JSON),
            simulatePipelineListenerNewLicense);

        assertThat(simulatePipelineListenerNewLicense.actionGet().getResults(), is(not(empty())));

        //both ingest pipelines should work

        client().prepareIndex("infer_license_test", MapperService.SINGLE_MAPPING_NAME)
            .setPipeline("test_infer_license_pipeline")
            .setSource("{}", XContentType.JSON)
            .execute()
            .actionGet();
        client().prepareIndex("infer_license_test", MapperService.SINGLE_MAPPING_NAME)
            .setPipeline("test_infer_license_pipeline_again")
            .setSource("{}", XContentType.JSON)
            .execute()
            .actionGet();
    }

    public void testMachineLearningInferModelRestricted() {
        String modelId = "modelinfermodellicensetest";
        assertMLAllowed(true);
        putInferenceModel(modelId);


        PlainActionFuture<InternalInferModelAction.Response> inferModelSuccess = PlainActionFuture.newFuture();
        client().execute(InternalInferModelAction.INSTANCE, new InternalInferModelAction.Request(
            modelId,
            Collections.singletonList(Collections.emptyMap()),
            RegressionConfigUpdate.EMPTY_PARAMS,
            false
        ), inferModelSuccess);
        InternalInferModelAction.Response response = inferModelSuccess.actionGet();
        assertThat(response.getInferenceResults(), is(not(empty())));
        assertThat(response.isLicensed(), is(true));

        // Pick a license that does not allow machine learning
        License.OperationMode mode = randomInvalidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(false);

        // inferring against a model should now fail
        ElasticsearchSecurityException e = expectThrows(ElasticsearchSecurityException.class, () -> {
            client().execute(InternalInferModelAction.INSTANCE, new InternalInferModelAction.Request(
                modelId,
                Collections.singletonList(Collections.emptyMap()),
                RegressionConfigUpdate.EMPTY_PARAMS,
                false
            )).actionGet();
        });
        assertThat(e.status(), is(RestStatus.FORBIDDEN));
        assertThat(e.getMessage(), containsString("non-compliant"));
        assertThat(e.getMetadata(LicenseUtils.EXPIRED_FEATURE_METADATA), hasItem(XPackField.MACHINE_LEARNING));

        // Inferring with previously Licensed == true should pass, but indicate license issues
        inferModelSuccess = PlainActionFuture.newFuture();
        client().execute(InternalInferModelAction.INSTANCE, new InternalInferModelAction.Request(
            modelId,
            Collections.singletonList(Collections.emptyMap()),
            RegressionConfigUpdate.EMPTY_PARAMS,
            true
        ), inferModelSuccess);
        response = inferModelSuccess.actionGet();
        assertThat(response.getInferenceResults(), is(not(empty())));
        assertThat(response.isLicensed(), is(false));

        // Pick a license that does allow machine learning
        mode = randomValidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(true);

        PlainActionFuture<InternalInferModelAction.Response> listener = PlainActionFuture.newFuture();
        client().execute(InternalInferModelAction.INSTANCE, new InternalInferModelAction.Request(
            modelId,
            Collections.singletonList(Collections.emptyMap()),
            RegressionConfigUpdate.EMPTY_PARAMS,
            false
        ), listener);
        assertThat(listener.actionGet().getInferenceResults(), is(not(empty())));
    }

    public void testInferenceAggRestricted() {
        String modelId = "inference-agg-restricted";
        assertMLAllowed(true);
        putInferenceModel(modelId);

        // index some data
        String index = "inference-agg-licence-test";
        client().admin().indices().prepareCreate(index)
            .addMapping("type", "feature1", "type=double", "feature2", "type=keyword").get();
        client().prepareBulk()
            .add(new IndexRequest(index).source("feature1", "10.0", "feature2", "foo"))
            .add(new IndexRequest(index).source("feature1", "20.0", "feature2", "foo"))
            .add(new IndexRequest(index).source("feature1", "20.0", "feature2", "bar"))
            .add(new IndexRequest(index).source("feature1", "20.0", "feature2", "bar"))
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            .get();

        TermsAggregationBuilder termsAgg = new TermsAggregationBuilder("foobar").field("feature2");
        AvgAggregationBuilder avgAgg = new AvgAggregationBuilder("avg_feature1").field("feature1");
        termsAgg.subAggregation(avgAgg);

        XPackLicenseState licenseState = internalCluster().getInstance(XPackLicenseState.class);
        ModelLoadingService modelLoading = internalCluster().getInstance(ModelLoadingService.class);

        Map<String, String> bucketPaths = new HashMap<>();
        bucketPaths.put("feature1", "avg_feature1");
        InferencePipelineAggregationBuilder inferenceAgg =
            new InferencePipelineAggregationBuilder("infer_agg", new SetOnce<>(modelLoading), licenseState, bucketPaths);
        inferenceAgg.setModelId(modelId);

        termsAgg.subAggregation(inferenceAgg);

        SearchRequest search = new SearchRequest(index);
        search.source().aggregation(termsAgg);
        client().search(search).actionGet();

        // Pick a license that does not allow machine learning
        License.OperationMode mode = randomInvalidLicenseType();
        enableLicensing(mode);
        assertMLAllowed(false);

        // inferring against a model should now fail
        SearchRequest invalidSearch = new SearchRequest(index);
        invalidSearch.source().aggregation(termsAgg);
        ElasticsearchSecurityException e = expectThrows(ElasticsearchSecurityException.class,
            () -> client().search(invalidSearch).actionGet());

        assertThat(e.status(), is(RestStatus.FORBIDDEN));
        assertThat(e.getMessage(), containsString("current license is non-compliant for [ml]"));
        assertThat(e.getMetadata(LicenseUtils.EXPIRED_FEATURE_METADATA), hasItem(XPackField.MACHINE_LEARNING));
    }

    private void putInferenceModel(String modelId) {
        TrainedModelConfig config = TrainedModelConfig.builder()
            .setParsedDefinition(
            new TrainedModelDefinition.Builder()
            .setTrainedModel(
            Tree.builder()
                .setTargetType(TargetType.REGRESSION)
                .setFeatureNames(Collections.singletonList("feature1"))
                .setNodes(TreeNode.builder(0).setLeafValue(1.0))
                .build())
            .setPreProcessors(Collections.emptyList()))
            .setModelId(modelId)
            .setDescription("test model for classification")
            .setInput(new TrainedModelInput(Collections.singletonList("feature1")))
            .setInferenceConfig(RegressionConfig.EMPTY_PARAMS)
            .build();
        client().execute(PutTrainedModelAction.INSTANCE, new PutTrainedModelAction.Request(config)).actionGet();
    }

    private static OperationMode randomInvalidLicenseType() {
        return randomFrom(License.OperationMode.GOLD, License.OperationMode.STANDARD, License.OperationMode.BASIC);
    }

    private static OperationMode randomValidLicenseType() {
        return randomFrom(License.OperationMode.TRIAL, License.OperationMode.PLATINUM, OperationMode.ENTERPRISE);
    }

    private static OperationMode randomLicenseType() {
        return randomFrom(License.OperationMode.values());
    }

    private static void assertMLAllowed(boolean expected) {
        for (XPackLicenseState licenseState : internalCluster().getInstances(XPackLicenseState.class)) {
            assertEquals(licenseState.checkFeature(XPackLicenseState.Feature.MACHINE_LEARNING), expected);
        }
    }

    public static void disableLicensing() {
        disableLicensing(randomValidLicenseType());
    }

    public static void disableLicensing(License.OperationMode operationMode) {
        for (XPackLicenseState licenseState : internalCluster().getInstances(XPackLicenseState.class)) {
            licenseState.update(operationMode, false, null);
        }
    }

    public static void enableLicensing() {
        enableLicensing(randomValidLicenseType());
    }

    public static void enableLicensing(License.OperationMode operationMode) {
        for (XPackLicenseState licenseState : internalCluster().getInstances(XPackLicenseState.class)) {
            licenseState.update(operationMode, true, null);
        }
    }
}
