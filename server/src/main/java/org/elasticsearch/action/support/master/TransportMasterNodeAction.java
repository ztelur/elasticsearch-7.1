/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.support.master;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.MasterNodeChangePredicate;
import org.elasticsearch.cluster.NotMasterException;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.coordination.FailedToCommitClusterStateException;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.discovery.MasterNotDiscoveredException;
import org.elasticsearch.node.NodeClosedException;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.ConnectTransportException;
import org.elasticsearch.transport.RemoteTransportException;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.function.Predicate;

/**
 * A base class for operations that needs to be performed on the master node.
 * 专门处理需要在 master node 上执行的操作
 */
public abstract class TransportMasterNodeAction<Request extends MasterNodeRequest<Request>, Response extends ActionResponse>
    extends HandledTransportAction<Request, Response> {

    private static final Logger logger = LogManager.getLogger(TransportMasterNodeAction.class);

    protected final ThreadPool threadPool;
    protected final TransportService transportService;
    protected final ClusterService clusterService;
    protected final IndexNameExpressionResolver indexNameExpressionResolver;

    private final String executor;

    protected TransportMasterNodeAction(String actionName, TransportService transportService,
                                        ClusterService clusterService, ThreadPool threadPool, ActionFilters actionFilters,
                                        Writeable.Reader<Request> request, IndexNameExpressionResolver indexNameExpressionResolver) {
        this(actionName, true, transportService, clusterService, threadPool, actionFilters, request, indexNameExpressionResolver);
    }

    protected TransportMasterNodeAction(String actionName, boolean canTripCircuitBreaker,
                                        TransportService transportService, ClusterService clusterService, ThreadPool threadPool,
                                        ActionFilters actionFilters, Writeable.Reader<Request> request,
                                        IndexNameExpressionResolver indexNameExpressionResolver) {
        super(actionName, canTripCircuitBreaker, transportService, actionFilters, request);
        this.transportService = transportService;
        this.clusterService = clusterService;
        this.threadPool = threadPool;
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        this.executor = executor();
    }

    protected abstract String executor();

    protected abstract Response read(StreamInput in) throws IOException;

    protected abstract void masterOperation(Request request, ClusterState state, ActionListener<Response> listener) throws Exception;

    /**
     * Override this operation if access to the task parameter is needed
     */
    protected void masterOperation(Task task, Request request, ClusterState state, ActionListener<Response> listener) throws Exception {
        masterOperation(request, state, listener);
    }

    protected boolean localExecute(Request request) {
        return false;
    }

    protected abstract ClusterBlockException checkBlock(Request request, ClusterState state);

    @Override
    protected void doExecute(Task task, final Request request, ActionListener<Response> listener) {
        // 根据当前集群状态进行处理
        ClusterState state = clusterService.state();
        logger.trace("starting processing request [{}] with cluster state version [{}]", request, state.version());
        if (task != null) {
            request.setParentTask(clusterService.localNode().getId(), task.getId());
        }
        new AsyncSingleAction(task, request, listener).doStart(state);
    }

    /**
     * 单独异步 Action
     */
    class AsyncSingleAction {

        private final ActionListener<Response> listener;
        private final Request request;
        private ClusterStateObserver observer;
        private final long startTime;
        private final Task task;

        AsyncSingleAction(Task task, Request request, ActionListener<Response> listener) {
            this.task = task;
            this.request = request;
            this.listener = listener;
            this.startTime = threadPool.relativeTimeInMillis();
        }

        protected void doStart(ClusterState clusterState) {
            try {
                final DiscoveryNodes nodes = clusterState.nodes();
                // 如果当前节点是选取的 Master，或者需要本地执行
                if (nodes.isLocalNodeElectedMaster() || localExecute(request)) {
                    // check for block, if blocked, retry, else, execute locally
                    // 检查 block，如果被 blocked了，则重试，否则直接本地执行
                    final ClusterBlockException blockException = checkBlock(request, clusterState);
                    if (blockException != null) {
                        // excetion 是否可以重试
                        if (!blockException.retryable()) {
                            listener.onFailure(blockException);
                        } else {
                            logger.debug("can't execute due to a cluster block, retrying", blockException);
                            // 重试
                            retry(clusterState, blockException, newState -> {
                                try {
                                    ClusterBlockException newException = checkBlock(request, newState);
                                    return (newException == null || !newException.retryable());
                                } catch (Exception e) {
                                    // accept state as block will be rechecked by doStart() and listener.onFailure() then called
                                    logger.trace("exception occurred during cluster block checking, accepting state", e);
                                    return true;
                                }
                            });
                        }
                    } else {
                        ActionListener<Response> delegate = ActionListener.delegateResponse(listener, (delegatedListener, t) -> {
                            if (t instanceof FailedToCommitClusterStateException || t instanceof NotMasterException) {
                                logger.debug(() -> new ParameterizedMessage("master could not publish cluster state or " +
                                    "stepped down before publishing action [{}], scheduling a retry", actionName), t);
                                retryOnMasterChange(clusterState, t);
                            } else {
                                delegatedListener.onFailure(t);
                            }
                        });
                        // 本地线程池执行
                        threadPool.executor(executor)
                            .execute(ActionRunnable.wrap(delegate, l -> masterOperation(task, request, clusterState, l)));
                    }
                } else {
                    // 选取 master 节点执行
                    if (nodes.getMasterNode() == null) {
                        logger.debug("no known master node, scheduling a retry");
                        // 当 master 节点发生变化后重试
                        retryOnMasterChange(clusterState, null);
                    } else {
                        // 获取 master node
                        DiscoveryNode masterNode = nodes.getMasterNode();
                        final String actionName = getMasterActionName(masterNode);
                        // 通过 transportService 发送消息，通知 master 创建 index
                        transportService.sendRequest(masterNode, actionName, request,
                            new ActionListenerResponseHandler<Response>(listener, TransportMasterNodeAction.this::read) {
                                @Override
                                public void handleException(final TransportException exp) {
                                    Throwable cause = exp.unwrapCause();
                                    if (cause instanceof ConnectTransportException ||
                                        (exp instanceof RemoteTransportException && cause instanceof NodeClosedException)) {
                                        // we want to retry here a bit to see if a new master is elected
                                        logger.debug("connection exception while trying to forward request with action name [{}] to " +
                                                "master node [{}], scheduling a retry. Error: [{}]",
                                            actionName, nodes.getMasterNode(), exp.getDetailedMessage());
                                        retryOnMasterChange(clusterState, cause);
                                    } else {
                                        listener.onFailure(exp);
                                    }
                                }
                        });
                    }
                }
            } catch (Exception e) {
                listener.onFailure(e);
            }
        }

        private void retryOnMasterChange(ClusterState state, Throwable failure) {
            retry(state, failure, MasterNodeChangePredicate.build(state));
        }

        private void retry(ClusterState state, final Throwable failure, final Predicate<ClusterState> statePredicate) {
            if (observer == null) {
                final long remainingTimeoutMS = request.masterNodeTimeout().millis() - (threadPool.relativeTimeInMillis() - startTime);
                if (remainingTimeoutMS <= 0) {
                    logger.debug(() -> new ParameterizedMessage("timed out before retrying [{}] after failure", actionName), failure);
                    listener.onFailure(new MasterNotDiscoveredException(failure));
                    return;
                }
                this.observer = new ClusterStateObserver(
                        state, clusterService, TimeValue.timeValueMillis(remainingTimeoutMS), logger, threadPool.getThreadContext());
            }
            observer.waitForNextChange(
                new ClusterStateObserver.Listener() {
                    @Override
                    public void onNewClusterState(ClusterState state) {
                        doStart(state);
                    }

                    @Override
                    public void onClusterServiceClose() {
                        listener.onFailure(new NodeClosedException(clusterService.localNode()));
                    }

                    @Override
                    public void onTimeout(TimeValue timeout) {
                        logger.debug(() -> new ParameterizedMessage("timed out while retrying [{}] after failure (timeout [{}])",
                            actionName, timeout), failure);
                        listener.onFailure(new MasterNotDiscoveredException(failure));
                    }
                }, statePredicate
            );
        }
    }

    /**
     * Allows to conditionally return a different master node action name in the case an action gets renamed.
     * This mainly for backwards compatibility should be used rarely
     */
    protected String getMasterActionName(DiscoveryNode node) {
        return actionName;
    }
}
