/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ccr;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsRequest;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.admin.indices.forcemerge.ForceMergeResponse;
import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsAction;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.RestoreInProgress;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.snapshots.IndexShardSnapshotStatus;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.RepositoryMissingException;
import org.elasticsearch.snapshots.RestoreInfo;
import org.elasticsearch.snapshots.RestoreService;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotShardSizeInfo;
import org.elasticsearch.snapshots.SnapshotsInfoService;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.transport.TransportActionProxy;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.CcrIntegTestCase;
import org.elasticsearch.xpack.ccr.action.repositories.GetCcrRestoreFileChunkAction;
import org.elasticsearch.xpack.ccr.action.repositories.PutCcrRestoreSessionAction;
import org.elasticsearch.xpack.ccr.repository.CcrRepository;
import org.elasticsearch.xpack.ccr.repository.CcrRestoreSourceService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

public class CcrRepositoryIT extends CcrIntegTestCase {

    private final IndicesOptions indicesOptions = IndicesOptions.strictSingleIndexNoExpandForbidClosed();

    public void testThatRepositoryIsPutAndRemovedWhenRemoteClusterIsUpdated() throws Exception {
        String leaderClusterRepoName = CcrRepository.NAME_PREFIX + "leader_cluster";
        final RepositoriesService repositoriesService =
            getFollowerCluster().getDataOrMasterNodeInstances(RepositoriesService.class).iterator().next();
        try {
            Repository repository = repositoriesService.repository(leaderClusterRepoName);
            assertEquals(CcrRepository.TYPE, repository.getMetadata().type());
            assertEquals(leaderClusterRepoName, repository.getMetadata().name());
        } catch (RepositoryMissingException e) {
            fail("need repository");
        }

        ClusterUpdateSettingsRequest putSecondCluster = newSettingsRequest();
        String address = getFollowerCluster().getDataNodeInstance(TransportService.class).boundAddress().publishAddress().toString();
        putSecondCluster.persistentSettings(Settings.builder().put("cluster.remote.follower_cluster_copy.seeds", address));
        assertAcked(followerClient().admin().cluster().updateSettings(putSecondCluster).actionGet());

        String followerCopyRepoName = CcrRepository.NAME_PREFIX + "follower_cluster_copy";
        try {
            Repository repository = repositoriesService.repository(followerCopyRepoName);
            assertEquals(CcrRepository.TYPE, repository.getMetadata().type());
            assertEquals(followerCopyRepoName, repository.getMetadata().name());
        } catch (RepositoryMissingException e) {
            fail("need repository");
        }

        ClusterUpdateSettingsRequest deleteLeaderCluster = newSettingsRequest();
        deleteLeaderCluster.persistentSettings(Settings.builder().put("cluster.remote.leader_cluster.seeds", ""));
        assertAcked(followerClient().admin().cluster().updateSettings(deleteLeaderCluster).actionGet());

        expectThrows(RepositoryMissingException.class, () -> repositoriesService.repository(leaderClusterRepoName));

        ClusterUpdateSettingsRequest deleteSecondCluster = newSettingsRequest();
        deleteSecondCluster.persistentSettings(Settings.builder().put("cluster.remote.follower_cluster_copy.seeds", ""));
        assertAcked(followerClient().admin().cluster().updateSettings(deleteSecondCluster).actionGet());

        expectThrows(RepositoryMissingException.class, () -> repositoriesService.repository(followerCopyRepoName));

        ClusterUpdateSettingsRequest putLeaderRequest = newSettingsRequest();
        address = getLeaderCluster().getDataNodeInstance(TransportService.class).boundAddress().publishAddress().toString();
        putLeaderRequest.persistentSettings(Settings.builder().put("cluster.remote.leader_cluster.seeds", address));
        assertAcked(followerClient().admin().cluster().updateSettings(putLeaderRequest).actionGet());
    }

    public void testThatRepositoryRecoversEmptyIndexBasedOnLeaderSettings() throws IOException {
        String leaderClusterRepoName = CcrRepository.NAME_PREFIX + "leader_cluster";
        String leaderIndex = "index1";
        String followerIndex = "index2";

        final int numberOfPrimaryShards = randomIntBetween(1, 3);
        final String leaderIndexSettings = getIndexSettings(numberOfPrimaryShards, between(0, 1));
        assertAcked(leaderClient().admin().indices().prepareCreate(leaderIndex).setSource(leaderIndexSettings, XContentType.JSON));
        ensureLeaderGreen(leaderIndex);

        final RestoreService restoreService = getFollowerCluster().getCurrentMasterNodeInstance(RestoreService.class);
        final ClusterService clusterService = getFollowerCluster().getCurrentMasterNodeInstance(ClusterService.class);

        Settings.Builder settingsBuilder = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_PROVIDED_NAME, followerIndex)
            .put(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), true);
        RestoreSnapshotRequest restoreRequest = new RestoreSnapshotRequest(leaderClusterRepoName, CcrRepository.LATEST)
            .indices(leaderIndex).indicesOptions(indicesOptions).renamePattern("^(.*)$")
            .renameReplacement(followerIndex).masterNodeTimeout(TimeValue.MAX_VALUE)
            .indexSettings(settingsBuilder);

        PlainActionFuture<RestoreInfo> future = PlainActionFuture.newFuture();
        restoreService.restoreSnapshot(restoreRequest, waitForRestore(clusterService, future));
        RestoreInfo restoreInfo = future.actionGet();

        assertEquals(restoreInfo.totalShards(), restoreInfo.successfulShards());
        assertEquals(0, restoreInfo.failedShards());

        ClusterStateResponse leaderState = leaderClient()
            .admin()
            .cluster()
            .prepareState()
            .clear()
            .setMetadata(true)
            .setIndices(leaderIndex)
            .get();
        ClusterStateResponse followerState = followerClient()
            .admin()
            .cluster()
            .prepareState()
            .clear()
            .setMetadata(true)
            .setIndices(followerIndex)
            .get();

        IndexMetadata leaderMetadata = leaderState.getState().metadata().index(leaderIndex);
        IndexMetadata followerMetadata = followerState.getState().metadata().index(followerIndex);
        assertEquals(leaderMetadata.getNumberOfShards(), followerMetadata.getNumberOfShards());
        Map<String, String> ccrMetadata = followerMetadata.getCustomData(Ccr.CCR_CUSTOM_METADATA_KEY);
        assertEquals(leaderIndex, ccrMetadata.get(Ccr.CCR_CUSTOM_METADATA_LEADER_INDEX_NAME_KEY));
        assertEquals(leaderMetadata.getIndexUUID(), ccrMetadata.get(Ccr.CCR_CUSTOM_METADATA_LEADER_INDEX_UUID_KEY));
        assertEquals("leader_cluster", ccrMetadata.get(Ccr.CCR_CUSTOM_METADATA_REMOTE_CLUSTER_NAME_KEY));
        assertEquals(followerIndex, followerMetadata.getSettings().get(IndexMetadata.SETTING_INDEX_PROVIDED_NAME));

        // UUID is changed so that we can follow indexes on same cluster
        assertNotEquals(leaderMetadata.getIndexUUID(), followerMetadata.getIndexUUID());
    }

    public void testDocsAreRecovered() throws Exception {
        ClusterUpdateSettingsRequest settingsRequest = newSettingsRequest();
        String chunkSize = randomFrom("4KB", "128KB", "1MB");
        settingsRequest.persistentSettings(Settings.builder().put(CcrSettings.RECOVERY_CHUNK_SIZE.getKey(), chunkSize));
        assertAcked(followerClient().admin().cluster().updateSettings(settingsRequest).actionGet());

        String leaderClusterRepoName = CcrRepository.NAME_PREFIX + "leader_cluster";
        String leaderIndex = "index1";
        String followerIndex = "index2";

        final int numberOfPrimaryShards = randomIntBetween(1, 3);
        final String leaderIndexSettings = getIndexSettings(numberOfPrimaryShards, between(0, 1));
        assertAcked(leaderClient().admin().indices().prepareCreate(leaderIndex).setSource(leaderIndexSettings, XContentType.JSON));
        ensureLeaderGreen(leaderIndex);

        final RestoreService restoreService = getFollowerCluster().getCurrentMasterNodeInstance(RestoreService.class);
        final ClusterService clusterService = getFollowerCluster().getCurrentMasterNodeInstance(ClusterService.class);

        final int firstBatchNumDocs = randomIntBetween(1, 64);
        logger.info("Indexing [{}] docs as first batch", firstBatchNumDocs);
        for (int i = 0; i < firstBatchNumDocs; i++) {
            final String source = String.format(Locale.ROOT, "{\"f\":%d}", i);
            leaderClient().prepareIndex("index1", "doc", Integer.toString(i)).setSource(source, XContentType.JSON).get();
        }

        leaderClient().admin().indices().prepareFlush(leaderIndex).setForce(true).setWaitIfOngoing(true).get();

        Settings.Builder settingsBuilder = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_PROVIDED_NAME, followerIndex)
            .put(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), true);
        RestoreSnapshotRequest restoreRequest = new RestoreSnapshotRequest(leaderClusterRepoName, CcrRepository.LATEST)
            .indices(leaderIndex).indicesOptions(indicesOptions).renamePattern("^(.*)$")
            .renameReplacement(followerIndex).masterNodeTimeout(new TimeValue(1L, TimeUnit.HOURS))
            .indexSettings(settingsBuilder);

        PlainActionFuture<RestoreInfo> future = PlainActionFuture.newFuture();
        restoreService.restoreSnapshot(restoreRequest, waitForRestore(clusterService, future));
        RestoreInfo restoreInfo = future.actionGet();

        assertEquals(restoreInfo.totalShards(), restoreInfo.successfulShards());
        assertEquals(0, restoreInfo.failedShards());
        for (int i = 0; i < firstBatchNumDocs; ++i) {
            assertExpectedDocument(followerIndex, i);
        }

        settingsRequest = newSettingsRequest();
        ByteSizeValue defaultValue = CcrSettings.RECOVERY_CHUNK_SIZE.getDefault(Settings.EMPTY);
        settingsRequest.persistentSettings(Settings.builder().put(CcrSettings.RECOVERY_CHUNK_SIZE.getKey(), defaultValue));
        assertAcked(followerClient().admin().cluster().updateSettings(settingsRequest).actionGet());
    }

    public void testRateLimitingIsEmployed() throws Exception {
        boolean followerRateLimiting = randomBoolean();

        ClusterUpdateSettingsRequest settingsRequest = newSettingsRequest();
        settingsRequest.persistentSettings(Settings.builder().put(CcrSettings.RECOVERY_MAX_BYTES_PER_SECOND.getKey(), "10K"));
        if (followerRateLimiting) {
            assertAcked(followerClient().admin().cluster().updateSettings(settingsRequest).actionGet());
        } else {
            assertAcked(leaderClient().admin().cluster().updateSettings(settingsRequest).actionGet());
        }

        String leaderClusterRepoName = CcrRepository.NAME_PREFIX + "leader_cluster";
        String leaderIndex = "index1";
        String followerIndex = "index2";

        final int numberOfPrimaryShards = randomIntBetween(1, 3);
        final String leaderIndexSettings = getIndexSettings(numberOfPrimaryShards, between(0, 1));
        assertAcked(leaderClient().admin().indices().prepareCreate(leaderIndex).setSource(leaderIndexSettings, XContentType.JSON));
        ensureLeaderGreen(leaderIndex);

        final RestoreService restoreService = getFollowerCluster().getCurrentMasterNodeInstance(RestoreService.class);
        final ClusterService clusterService = getFollowerCluster().getCurrentMasterNodeInstance(ClusterService.class);

        List<CcrRepository> repositories = new ArrayList<>();
        List<CcrRestoreSourceService> restoreSources = new ArrayList<>();

        for (RepositoriesService repositoriesService : getFollowerCluster().getDataOrMasterNodeInstances(RepositoriesService.class)) {
            Repository repository = repositoriesService.repository(leaderClusterRepoName);
            repositories.add((CcrRepository) repository);
        }
        for (CcrRestoreSourceService restoreSource : getLeaderCluster().getDataOrMasterNodeInstances(CcrRestoreSourceService.class)) {
            restoreSources.add(restoreSource);
        }

        logger.info("--> indexing some data");
        for (int i = 0; i < 100; i++) {
            final String source = String.format(Locale.ROOT, "{\"f\":%d}", i);
            leaderClient().prepareIndex("index1", "doc", Integer.toString(i)).setSource(source, XContentType.JSON).get();
        }

        leaderClient().admin().indices().prepareFlush(leaderIndex).setForce(true).setWaitIfOngoing(true).get();

        Settings.Builder settingsBuilder = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_PROVIDED_NAME, followerIndex)
            .put(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), true);
        RestoreSnapshotRequest restoreRequest = new RestoreSnapshotRequest(leaderClusterRepoName, CcrRepository.LATEST)
            .indices(leaderIndex).indicesOptions(indicesOptions).renamePattern("^(.*)$")
            .renameReplacement(followerIndex).masterNodeTimeout(TimeValue.MAX_VALUE)
            .indexSettings(settingsBuilder);

        PlainActionFuture<RestoreInfo> future = PlainActionFuture.newFuture();
        restoreService.restoreSnapshot(restoreRequest, waitForRestore(clusterService, future));
        future.actionGet();

        if (followerRateLimiting) {
            assertTrue(repositories.stream().anyMatch(cr -> cr.getRestoreThrottleTimeInNanos() > 0));
        } else {
            assertTrue(restoreSources.stream().anyMatch(cr -> cr.getThrottleTime() > 0));
        }

        settingsRequest = newSettingsRequest();
        ByteSizeValue defaultValue = CcrSettings.RECOVERY_MAX_BYTES_PER_SECOND.getDefault(Settings.EMPTY);
        settingsRequest.persistentSettings(Settings.builder().put(CcrSettings.RECOVERY_MAX_BYTES_PER_SECOND.getKey(), defaultValue));
        if (followerRateLimiting) {
            assertAcked(followerClient().admin().cluster().updateSettings(settingsRequest).actionGet());
        } else {
            assertAcked(leaderClient().admin().cluster().updateSettings(settingsRequest).actionGet());
        }
    }

    public void testIndividualActionsTimeout() throws Exception {
        ClusterUpdateSettingsRequest settingsRequest = newSettingsRequest();
        TimeValue timeValue = TimeValue.timeValueMillis(100);
        settingsRequest.persistentSettings(Settings.builder().put(CcrSettings.INDICES_RECOVERY_ACTION_TIMEOUT_SETTING.getKey(), timeValue));
        assertAcked(followerClient().admin().cluster().updateSettings(settingsRequest).actionGet());

        String leaderClusterRepoName = CcrRepository.NAME_PREFIX + "leader_cluster";
        String leaderIndex = "index1";
        String followerIndex = "index2";

        final int numberOfPrimaryShards = randomIntBetween(1, 3);
        final String leaderIndexSettings = getIndexSettings(numberOfPrimaryShards, between(0, 1));
        assertAcked(leaderClient().admin().indices().prepareCreate(leaderIndex).setSource(leaderIndexSettings, XContentType.JSON));
        ensureLeaderGreen(leaderIndex);

        List<MockTransportService> transportServices = new ArrayList<>();

        for (TransportService transportService : getFollowerCluster().getDataOrMasterNodeInstances(TransportService.class)) {
            MockTransportService mockTransportService = (MockTransportService) transportService;
            transportServices.add(mockTransportService);
            mockTransportService.addSendBehavior((connection, requestId, action, request, options) -> {
                if (action.equals(GetCcrRestoreFileChunkAction.NAME) == false &&
                    action.equals(TransportActionProxy.getProxyAction(GetCcrRestoreFileChunkAction.NAME)) == false) {
                    connection.sendRequest(requestId, action, request, options);
                }
            });
        }

        logger.info("--> indexing some data");
        for (int i = 0; i < 100; i++) {
            final String source = String.format(Locale.ROOT, "{\"f\":%d}", i);
            leaderClient().prepareIndex("index1", "doc", Integer.toString(i)).setSource(source, XContentType.JSON).get();
        }

        leaderClient().admin().indices().prepareFlush(leaderIndex).setForce(true).setWaitIfOngoing(true).get();

        Settings.Builder settingsBuilder = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_PROVIDED_NAME, followerIndex)
            .put(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), true);
        RestoreSnapshotRequest restoreRequest = new RestoreSnapshotRequest(leaderClusterRepoName, CcrRepository.LATEST)
            .indices(leaderIndex).indicesOptions(indicesOptions).renamePattern("^(.*)$")
            .renameReplacement(followerIndex).masterNodeTimeout(new TimeValue(1L, TimeUnit.HOURS))
            .indexSettings(settingsBuilder);

        try {
            final RestoreService restoreService = getFollowerCluster().getCurrentMasterNodeInstance(RestoreService.class);
            final ClusterService clusterService = getFollowerCluster().getCurrentMasterNodeInstance(ClusterService.class);
            PlainActionFuture<RestoreInfo> future = PlainActionFuture.newFuture();
            restoreService.restoreSnapshot(restoreRequest, waitForRestore(clusterService, future));

            // Depending on when the timeout occurs this can fail in two ways. If it times-out when fetching
            // metadata this will throw an exception. If it times-out when restoring a shard, the shard will
            // be marked as failed. Either one is a success for the purpose of this test.
            try {
                RestoreInfo restoreInfo = future.actionGet();
                assertThat(restoreInfo.failedShards(), greaterThan(0));
                assertThat(restoreInfo.successfulShards(), lessThan(restoreInfo.totalShards()));
                assertEquals(numberOfPrimaryShards, restoreInfo.totalShards());
            } catch (Exception e) {
                assertThat(ExceptionsHelper.unwrapCause(e), instanceOf(ElasticsearchTimeoutException.class));
            }
        } finally {
            for (MockTransportService transportService : transportServices) {
                transportService.clearAllRules();
            }

            settingsRequest = new ClusterUpdateSettingsRequest();
            TimeValue defaultValue = CcrSettings.INDICES_RECOVERY_ACTION_TIMEOUT_SETTING.getDefault(Settings.EMPTY);
            settingsRequest.persistentSettings(Settings.builder().put(CcrSettings.INDICES_RECOVERY_ACTION_TIMEOUT_SETTING.getKey(),
                defaultValue));
            assertAcked(followerClient().admin().cluster().updateSettings(settingsRequest).actionGet());
            // This test sets individual action timeouts low to attempt to replicated timeouts. Although the
            // clear session action is not blocked, it is possible that it will still occasionally timeout.
            // By wiping the leader index here, we ensure we do not trigger the index commit hanging around
            // assertion because the commit is released when the index shard is closed.
            getLeaderCluster().wipeIndices(leaderIndex);
        }
    }

    private ClusterUpdateSettingsRequest newSettingsRequest() {
        return new ClusterUpdateSettingsRequest().masterNodeTimeout(TimeValue.MAX_VALUE);
    }

    public void testFollowerMappingIsUpdated() throws IOException {
        String leaderClusterRepoName = CcrRepository.NAME_PREFIX + "leader_cluster";
        String leaderIndex = "index1";
        String followerIndex = "index2";

        final int numberOfPrimaryShards = randomIntBetween(1, 3);
        final String leaderIndexSettings = getIndexSettings(numberOfPrimaryShards, between(0, 1));
        assertAcked(leaderClient().admin().indices().prepareCreate(leaderIndex).setSource(leaderIndexSettings, XContentType.JSON));
        ensureLeaderGreen(leaderIndex);

        final RestoreService restoreService = getFollowerCluster().getCurrentMasterNodeInstance(RestoreService.class);
        final ClusterService clusterService = getFollowerCluster().getCurrentMasterNodeInstance(ClusterService.class);

        Settings.Builder settingsBuilder = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_PROVIDED_NAME, followerIndex)
            .put(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), true);
        RestoreSnapshotRequest restoreRequest = new RestoreSnapshotRequest(leaderClusterRepoName, CcrRepository.LATEST)
            .indices(leaderIndex).indicesOptions(indicesOptions).renamePattern("^(.*)$")
            .renameReplacement(followerIndex).masterNodeTimeout(new TimeValue(1L, TimeUnit.HOURS))
            .indexSettings(settingsBuilder);


        List<MockTransportService> transportServices = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean updateSent = new AtomicBoolean(false);
        Runnable updateMappings = () -> {
            if (updateSent.compareAndSet(false, true)) {
                leaderClient()
                    .admin()
                    .indices()
                    .preparePutMapping(leaderIndex)
                    .setType("doc")
                    .setSource("{\"properties\":{\"k\":{\"type\":\"long\"}}}", XContentType.JSON)
                    .execute(ActionListener.wrap(latch::countDown));
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw ExceptionsHelper.convertToRuntime(e);
            }
        };

        for (TransportService transportService : getFollowerCluster().getDataOrMasterNodeInstances(TransportService.class)) {
            MockTransportService mockTransportService = (MockTransportService) transportService;
            transportServices.add(mockTransportService);
            mockTransportService.addSendBehavior((connection, requestId, action, request, options) -> {
                if (action.equals(PutCcrRestoreSessionAction.NAME)) {
                    updateMappings.run();
                    connection.sendRequest(requestId, action, request, options);
                } else {
                    connection.sendRequest(requestId, action, request, options);
                }
            });
        }

        try {
            PlainActionFuture<RestoreInfo> future = PlainActionFuture.newFuture();
            restoreService.restoreSnapshot(restoreRequest, waitForRestore(clusterService, future));
            RestoreInfo restoreInfo = future.actionGet();

            assertEquals(restoreInfo.totalShards(), restoreInfo.successfulShards());
            assertEquals(0, restoreInfo.failedShards());

            ClusterStateRequest clusterStateRequest = new ClusterStateRequest();
            clusterStateRequest.clear();
            clusterStateRequest.metadata(true);
            clusterStateRequest.indices(followerIndex);
            MappingMetadata mappingMetadata = followerClient().admin().indices().prepareGetMappings("index2").get().getMappings()
                .get("index2").get("doc");
            assertThat(XContentMapValues.extractValue("properties.k.type", mappingMetadata.sourceAsMap()), equalTo("long"));
        } finally {
            for (MockTransportService transportService : transportServices) {
                transportService.clearAllRules();
            }
        }
    }

    public void testCcrRepositoryFetchesSnapshotShardSizeFromIndexShardStoreStats() throws Exception {
        final String leaderIndex = "leader";
        final int numberOfShards = randomIntBetween(1, 2);
        assertAcked(leaderClient().admin().indices().prepareCreate(leaderIndex)
            .setSource(getIndexSettings(numberOfShards, 0, singletonMap(Store.INDEX_STORE_STATS_REFRESH_INTERVAL_SETTING.getKey(),
                TimeValue.ZERO.getStringRep())), XContentType.JSON));

        final int numDocs = scaledRandomIntBetween(0, 1_000);
        if (numDocs > 0) {
            final BulkRequestBuilder bulkRequest = leaderClient().prepareBulk(leaderIndex, "_doc");
            for (int i = 0; i < numDocs; i++) {
                bulkRequest.add(new IndexRequest(leaderIndex).id(Integer.toString(i)).source("field", i));
            }
            assertThat(bulkRequest.get().hasFailures(), is(false));
        }

        final ForceMergeResponse forceMergeResponse = leaderClient().admin().indices().prepareForceMerge(leaderIndex)
            .setMaxNumSegments(1)
            .setFlush(true)
            .get();
        assertThat(forceMergeResponse.getSuccessfulShards(), equalTo(numberOfShards));
        assertThat(forceMergeResponse.getFailedShards(), equalTo(0));
        ensureLeaderGreen(leaderIndex);

        final IndexStats indexStats = leaderClient().admin().indices().prepareStats(leaderIndex)
            .clear()
            .setStore(true)
            .get()
            .getIndex(leaderIndex);
        assertThat(indexStats.getIndexShards(), notNullValue());
        assertThat(indexStats.getIndexShards(), aMapWithSize(numberOfShards));

        final String leaderCluster = CcrRepository.NAME_PREFIX + "leader_cluster";
        final RepositoriesService repositoriesService = getFollowerCluster().getCurrentMasterNodeInstance(RepositoriesService.class);
        final Repository repository = repositoriesService.repository(leaderCluster);
        assertThat(repository.getMetadata().type(), equalTo(CcrRepository.TYPE));
        assertThat(repository.getMetadata().name(), equalTo(leaderCluster));

        for (int shardId = 0; shardId < numberOfShards; shardId++) {
            IndexShardSnapshotStatus.Copy indexShardSnapshotStatus = repository.getShardSnapshotStatus(
                new SnapshotId(CcrRepository.LATEST, CcrRepository.LATEST),
                new IndexId(indexStats.getIndex(), indexStats.getUuid()),
                new ShardId(new Index(indexStats.getIndex(), indexStats.getUuid()), shardId)).asCopy();

            assertThat(indexShardSnapshotStatus, notNullValue());
            assertThat(indexShardSnapshotStatus.getStage(), is(IndexShardSnapshotStatus.Stage.DONE));
            assertThat(indexShardSnapshotStatus.getTotalSize(),
                equalTo(indexStats.getIndexShards().get(shardId).getPrimary().getStore().getSizeInBytes()));
        }

        final CountDownLatch blockCcrRestore = new CountDownLatch(1);

        final List<MockTransportService> transportServices = new ArrayList<>();
        for (TransportService transportService : getFollowerCluster().getDataOrMasterNodeInstances(TransportService.class)) {
            final MockTransportService mockTransportService = (MockTransportService) transportService;
            mockTransportService.addSendBehavior((connection, requestId, action, request, options) -> {
                if (action.equals(PutCcrRestoreSessionAction.NAME)) {
                    try {
                        blockCcrRestore.await();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                }
                connection.sendRequest(requestId, action, request, options);
            });
            transportServices.add(mockTransportService);
        }

        try {
            final String followerIndex = "follower";
            final RestoreService restoreService = getFollowerCluster().getCurrentMasterNodeInstance(RestoreService.class);
            final ClusterService clusterService = getFollowerCluster().getCurrentMasterNodeInstance(ClusterService.class);

            final PlainActionFuture<IndexRoutingTable> waitForRestoreInProgress = PlainActionFuture.newFuture();
            final ClusterStateListener listener = event -> {
                RestoreInProgress restoreInProgress = event.state().custom(RestoreInProgress.TYPE, RestoreInProgress.EMPTY);
                if (restoreInProgress != null
                    && restoreInProgress.isEmpty() == false
                    && event.state().routingTable().hasIndex(followerIndex)) {
                    waitForRestoreInProgress.onResponse(event.state().routingTable().index(followerIndex));
                }
            };
            clusterService.addListener(listener);

            final RestoreSnapshotRequest restoreRequest = new RestoreSnapshotRequest(leaderCluster, CcrRepository.LATEST)
                .indices(leaderIndex).indicesOptions(indicesOptions).renamePattern("^(.*)$")
                .renameReplacement(followerIndex)
                .masterNodeTimeout(TimeValue.MAX_VALUE)
                .indexSettings(Settings.builder()
                    .put(IndexMetadata.SETTING_INDEX_PROVIDED_NAME, followerIndex)
                    .put(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), true));
            restoreService.restoreSnapshot(restoreRequest, PlainActionFuture.newFuture());

            final IndexRoutingTable indexRoutingTable = waitForRestoreInProgress.get(30L, TimeUnit.SECONDS);
            clusterService.removeListener(listener);

            final SnapshotsInfoService snapshotsInfoService = getFollowerCluster().getCurrentMasterNodeInstance(SnapshotsInfoService.class);
            assertBusy(() -> {
                SnapshotShardSizeInfo snapshotShardSizeInfo = snapshotsInfoService.snapshotShardSizes();
                for (int shardId = 0; shardId < numberOfShards; shardId++) {
                    Long snapshotShardSize = snapshotShardSizeInfo.getShardSize(indexRoutingTable.shard(shardId).primaryShard());
                    assertThat(snapshotShardSize,
                        equalTo(indexStats.getIndexShards().get(shardId).getPrimary().getStore().getSizeInBytes()));
                }
            }, 60L, TimeUnit.SECONDS);

            blockCcrRestore.countDown();
            ensureFollowerGreen(followerIndex);

            assertAcked(followerClient().admin().indices().prepareDelete(followerIndex).setMasterNodeTimeout(TimeValue.MAX_VALUE));
        } finally {
            transportServices.forEach(MockTransportService::clearAllRules);
        }
    }

    public void testCcrRepositoryFailsToFetchSnapshotShardSizes() throws Exception {
        final String leaderIndex = "leader";
        final int numberOfShards = randomIntBetween(1, 2);
        assertAcked(leaderClient().admin().indices().prepareCreate(leaderIndex)
            .setSource(getIndexSettings(numberOfShards, 0, singletonMap(Store.INDEX_STORE_STATS_REFRESH_INTERVAL_SETTING.getKey(),
                TimeValue.ZERO.getStringRep())), XContentType.JSON));

        final IndexMetadata indexMetadata = leaderClient().admin().cluster().prepareState().setIndices(leaderIndex)
            .clear().setMetadata(true).get().getState().metadata().index(leaderIndex);

        final AtomicInteger indicesStatsRequestsCount = new AtomicInteger(0);
        final CountDownLatch blockCcrRestore = new CountDownLatch(1);

        final List<MockTransportService> transportServices = new ArrayList<>();
        for (TransportService transportService : getFollowerCluster().getDataOrMasterNodeInstances(TransportService.class)) {
            final MockTransportService mockTransportService = (MockTransportService) transportService;
            mockTransportService.addSendBehavior((connection, requestId, action, request, options) -> {
                if (action.equals(PutCcrRestoreSessionAction.NAME)) {
                    try {
                        blockCcrRestore.await();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                }
                connection.sendRequest(requestId, action, request, options);
            });
            transportServices.add(mockTransportService);
        }
        for (TransportService transportService : getLeaderCluster().getDataOrMasterNodeInstances(TransportService.class)) {
            final MockTransportService mockTransportService = (MockTransportService) transportService;
            mockTransportService.addRequestHandlingBehavior(IndicesStatsAction.NAME, (handler, request, channel, task) -> {
                if (request instanceof IndicesStatsRequest) {
                    IndicesStatsRequest indicesStatsRequest = (IndicesStatsRequest) request;
                    if (Arrays.equals(indicesStatsRequest.indices(), new String[]{leaderIndex})
                        && indicesStatsRequest.store()
                        && indicesStatsRequest.search() == false
                        && indicesStatsRequest.fieldData() == false
                    ) {
                        indicesStatsRequestsCount.incrementAndGet();
                        channel.sendResponse(new ElasticsearchException("simulated"));
                        return;
                    }
                }
                handler.messageReceived(request, channel, task);
            });
            transportServices.add(mockTransportService);
        }

        final String followerIndex = "follower";
        try {
            final String leaderCluster = CcrRepository.NAME_PREFIX + "leader_cluster";
            final RepositoriesService repositoriesService = getFollowerCluster().getCurrentMasterNodeInstance(RepositoriesService.class);
            final Repository repository = repositoriesService.repository(leaderCluster);
            assertThat(repository.getMetadata().type(), equalTo(CcrRepository.TYPE));
            assertThat(repository.getMetadata().name(), equalTo(leaderCluster));

            for (int i = 0; i < numberOfShards; i++) {
                final Index index = indexMetadata.getIndex();
                final int shardId = i;
                ElasticsearchException exception = expectThrows(ElasticsearchException.class,
                    () -> repository.getShardSnapshotStatus(
                        new SnapshotId(CcrRepository.LATEST, CcrRepository.LATEST),
                        new IndexId(index.getName(), index.getUUID()),
                        new ShardId(index, shardId)));
                assertThat(exception.getMessage(), equalTo("simulated"));
            }
            assertThat(indicesStatsRequestsCount.getAndSet(0), equalTo(numberOfShards));

            final RestoreService restoreService = getFollowerCluster().getCurrentMasterNodeInstance(RestoreService.class);
            final ClusterService clusterService = getFollowerCluster().getCurrentMasterNodeInstance(ClusterService.class);

            final PlainActionFuture<IndexRoutingTable> waitForRestoreInProgress = PlainActionFuture.newFuture();
            final ClusterStateListener listener = event -> {
                RestoreInProgress restoreInProgress = event.state().custom(RestoreInProgress.TYPE, RestoreInProgress.EMPTY);
                if (restoreInProgress != null
                    && restoreInProgress.isEmpty() == false
                    && event.state().routingTable().hasIndex(followerIndex)) {
                    waitForRestoreInProgress.onResponse(event.state().routingTable().index(followerIndex));
                }
            };
            clusterService.addListener(listener);

            final RestoreSnapshotRequest restoreRequest = new RestoreSnapshotRequest(leaderCluster, CcrRepository.LATEST)
                .indices(leaderIndex).indicesOptions(indicesOptions).renamePattern("^(.*)$")
                .renameReplacement(followerIndex)
                .masterNodeTimeout(TimeValue.MAX_VALUE)
                .indexSettings(Settings.builder()
                    .put(IndexMetadata.SETTING_INDEX_PROVIDED_NAME, followerIndex)
                    .put(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), true));
            restoreService.restoreSnapshot(restoreRequest, PlainActionFuture.newFuture());

            final IndexRoutingTable indexRoutingTable = waitForRestoreInProgress.get(30L, TimeUnit.SECONDS);
            clusterService.removeListener(listener);

            final SnapshotsInfoService snapshotsInfoService = getFollowerCluster().getCurrentMasterNodeInstance(SnapshotsInfoService.class);
            assertBusy(() -> {
                SnapshotShardSizeInfo snapshotShardSizeInfo = snapshotsInfoService.snapshotShardSizes();
                for (int shardId = 0; shardId < numberOfShards; shardId++) {
                    final ShardRouting primary = indexRoutingTable.shard(shardId).primaryShard();
                    assertThat(snapshotShardSizeInfo.getShardSize(primary), equalTo(ShardRouting.UNAVAILABLE_EXPECTED_SHARD_SIZE));
                    final long randomSize = randomNonNegativeLong();
                    assertThat(snapshotShardSizeInfo.getShardSize(primary, randomSize), equalTo(randomSize));
                }
            }, 60L, TimeUnit.SECONDS);
        } finally {
            transportServices.forEach(MockTransportService::clearAllRules);
        }

        assertThat(indicesStatsRequestsCount.get(), equalTo(numberOfShards));
        blockCcrRestore.countDown();

        followerClient().admin().cluster().prepareReroute().get();
        ensureFollowerGreen(followerIndex);

        assertAcked(followerClient().admin().indices().prepareDelete(followerIndex).setMasterNodeTimeout(TimeValue.MAX_VALUE));
    }

    private void assertExpectedDocument(String followerIndex, final int value) {
        final GetResponse getResponse = followerClient().prepareGet(followerIndex, "doc", Integer.toString(value)).get();
        assertTrue("Doc with id [" + value + "] is missing", getResponse.isExists());
        if (sourceEnabled) {
            assertTrue((getResponse.getSource().containsKey("f")));
            assertThat(getResponse.getSource().get("f"), equalTo(value));
        }
    }

}
