/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.spi.discovery.zk.internal;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.apache.curator.utils.PathUtils;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteInterruptedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.events.EventType;
import org.apache.ignite.internal.IgniteFutureTimeoutCheckedException;
import org.apache.ignite.internal.events.DiscoveryCustomEvent;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteRunnable;
import org.apache.ignite.marshaller.jdk.JdkMarshaller;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.spi.discovery.DiscoveryDataBag;
import org.apache.ignite.spi.discovery.DiscoverySpiCustomMessage;
import org.apache.ignite.spi.discovery.DiscoverySpiDataExchange;
import org.apache.ignite.spi.discovery.DiscoverySpiListener;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.jetbrains.annotations.Nullable;

import static org.apache.zookeeper.CreateMode.EPHEMERAL_SEQUENTIAL;
import static org.apache.zookeeper.CreateMode.PERSISTENT;

/**
 * TODO ZK: check if compression makes sense.
 */
public class ZookeeperDiscoveryImpl {
    /** */
    private final JdkMarshaller marsh = new JdkMarshaller();

    /** */
    private final ZkPaths zkPaths;

    /** */
    private final IgniteLogger log;

    /** */
    private final ZookeeperClusterNode locNode;

    /** */
    private final DiscoverySpiListener lsnr;

    /** */
    private final DiscoverySpiDataExchange exchange;

    /** */
    private ZookeeperClient zkClient;

    /** */
    private final GridFutureAdapter<Void> joinFut = new GridFutureAdapter<>();

    /** */
    private final ZkWatcher watcher;

    /** */
    private final ZKChildrenCallback childrenCallback;

    /** */
    private final ZkDataCallback dataCallback;

    /** */
    private final ZkClusterNodes top = new ZkClusterNodes();

    /** */
    private long gridStartTime;

    /** */
    private long lastProcEvt = -1;

    /** */
    private boolean joined;

    /** */
    private ZkDiscoveryEventsData evts;

    /** */
    private boolean crd;

    /** */
    private Map<Long, ZkEventAckFuture> ackFuts = new ConcurrentHashMap<>();

    /**
     * @param log
     * @param basePath
     * @param clusterName
     * @param locNode
     * @param lsnr
     * @param exchange
     */
    public ZookeeperDiscoveryImpl(IgniteLogger log,
        String basePath,
        String clusterName,
        ZookeeperClusterNode locNode,
        DiscoverySpiListener lsnr,
        DiscoverySpiDataExchange exchange) {
        assert locNode.id() != null && locNode.isLocal() : locNode;

        if (F.isEmpty(clusterName))
            throw new IllegalArgumentException("Cluster name is empty.");

        PathUtils.validatePath(basePath);

        zkPaths = new ZkPaths(basePath, clusterName);

        this.log = log.getLogger(getClass());
        this.locNode = locNode;
        this.lsnr = lsnr;
        this.exchange = exchange;

        watcher = new ZkWatcher();
        childrenCallback = new ZKChildrenCallback();
        dataCallback = new ZkDataCallback();
    }

    IgniteLogger log() {
        return log;
    }

    public ClusterNode localNode() {
        return locNode;
    }

    /**
     * @param nodeId Node ID.
     * @return Node instance.
     */
    @Nullable public ClusterNode node(UUID nodeId) {
        assert nodeId != null;

        return top.nodesById.get(nodeId);
    }

    public boolean pingNode(UUID nodeId) {
        // TODO ZK
        return node(nodeId) != null;
    }

    /**
     * @return Remote nodes.
     */
    public Collection<ClusterNode> remoteNodes() {
        return top.remoteNodes();
    }

    public boolean knownNode(UUID nodeId) {
        try {
            List<String> children = zkClient.getChildren(zkPaths.aliveNodesDir);

            for (int i = 0; i < children.size(); i++) {
                UUID id = ZkPaths.aliveNodeId(children.get(i));

                if (nodeId.equals(id))
                    return true;
            }

            return false;
        }
        catch (ZookeeperClientFailedException e) {
            throw new IgniteException(e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IgniteInterruptedException(e);
        }
    }

    /**
     * @param msg Message.
     */
    public void sendCustomMessage(DiscoverySpiCustomMessage msg) {
        assert msg != null;

        byte[] msgBytes;

        try {
            msgBytes = marshal(msg);
        }
        catch (IgniteCheckedException e) {
            throw new IgniteSpiException("Failed to marshal custom message: " + msg, e);
        }

        try {
            zkClient.createIfNeeded(zkPaths.customEvtsDir + "/" + locNode.id() + '|', msgBytes, CreateMode.PERSISTENT_SEQUENTIAL);
        }
        catch (ZookeeperClientFailedException e) {
            throw new IgniteException(e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IgniteInterruptedException(e);
        }
    }

    /**
     * @return Cluster start time.
     */
    public long gridStartTime() {
        return gridStartTime;
    }

    /**
     * @param igniteInstanceName
     * @param connectString
     * @param sesTimeout
     * @throws InterruptedException If interrupted.
     */
    public void joinTopology(String igniteInstanceName, String connectString, int sesTimeout)
        throws InterruptedException
    {
        DiscoveryDataBag discoDataBag = new DiscoveryDataBag(locNode.id());

        exchange.collect(discoDataBag);

        ZkJoiningNodeData joinData = new ZkJoiningNodeData(locNode, discoDataBag.joiningNodeData());

        byte[] joinDataBytes;

        try {
            joinDataBytes = marshal(joinData);
        }
        catch (Exception e) {
            throw new IgniteSpiException("Failed to marshal joining node data", e);
        }

        try {
            zkClient = new ZookeeperClient(igniteInstanceName,
                log,
                connectString,
                sesTimeout,
                new ConnectionLossListener());
        }
        catch (Exception e) {
            throw new IgniteSpiException("Failed to create Zookeeper client", e);
        }

        initZkNodes();

        startJoin(joinDataBytes);

        for (;;) {
            try {
                joinFut.get(10_000);

                break;
            }
            catch (IgniteFutureTimeoutCheckedException e) {
                U.warn(log, "Waiting for local join event [nodeId=" + locNode.id() + ", name=" + igniteInstanceName + ']');
            }
            catch (Exception e) {
                throw new IgniteSpiException("Failed to join cluster", e);
            }
        }
    }

    /**
     * @throws InterruptedException If interrupted.
     */
    private void initZkNodes() throws InterruptedException {
        try {
            // TODO ZK: use multi.
            if (zkClient.exists(zkPaths.aliveNodesDir))
                return; // This path is created last, assume all others dirs are created.

            zkClient.createIfNeeded(zkPaths.basePath, null, PERSISTENT);

            zkClient.createIfNeeded(zkPaths.clusterDir, null, PERSISTENT);

            zkClient.createIfNeeded(zkPaths.evtsPath, null, PERSISTENT);

            zkClient.createIfNeeded(zkPaths.joinDataDir, null, PERSISTENT);

            zkClient.createIfNeeded(zkPaths.customEvtsDir, null, PERSISTENT);

            zkClient.createIfNeeded(zkPaths.aliveNodesDir, null, PERSISTENT);
        }
        catch (ZookeeperClientFailedException e) {
            throw new IgniteSpiException("Failed to initialize Zookeeper nodes", e);
        }
    }

    /**
     * @throws InterruptedException If interrupted.
     */
    private void startJoin(byte[] joinDataBytes) throws InterruptedException {
        try {
            zkClient.getDataAsync(zkPaths.evtsPath, watcher, dataCallback);

            // TODO ZK: handle max size.
            String path = zkClient.createIfNeeded(zkPaths.joinDataDir + "/" + locNode.id() + "|", joinDataBytes, EPHEMERAL_SEQUENTIAL);

            int seqNum = Integer.parseInt(path.substring(path.lastIndexOf('|') + 1));

            zkClient.createIfNeeded(zkPaths.aliveNodesDir + "/" + locNode.id() + "|" + seqNum + "|", null, EPHEMERAL_SEQUENTIAL);

            zkClient.getChildrenAsync(zkPaths.aliveNodesDir, null, new AsyncCallback.Children2Callback() {
                @Override public void processResult(int rc, String path, Object ctx, List<String> children, Stat stat) {
                    onConnected(rc, children);
                }
            });

            connStartLatch.countDown();
        }
        catch (ZookeeperClientFailedException e) {
            throw new IgniteSpiException("Failed to initialize Zookeeper nodes", e);
        }
    }

    /** TODO ZK */
    private final CountDownLatch connStartLatch = new CountDownLatch(1);

    /**
     * For testing only.
     *
     * @throws Exception If failed.
     */
    public void waitConnectStart() throws Exception {
        connStartLatch.await();
    }

    /**
     * @param rc Async callback result.
     * @param aliveNodes Alive nodes.
     */
    private void onConnected(int rc, List<String> aliveNodes) {
        assert !joined;

        checkIsCoordinator(rc, aliveNodes);
    }

    private void checkIsCoordinator(int rc, List<String> aliveNodes) {
        try {
            assert rc == 0 : rc;

            TreeMap<Integer, String> alives = new TreeMap<>();

            Integer locInternalId = null;

            for (String aliveNodePath : aliveNodes) {
                Integer internalId = ZkPaths.aliveInternalId(aliveNodePath);

                alives.put(internalId, aliveNodePath);

                if (locInternalId == null) {
                    UUID nodeId = ZkPaths.aliveNodeId(aliveNodePath);

                    if (locNode.id().equals(nodeId))
                        locInternalId = internalId;
                }
            }

            assert !alives.isEmpty();
            assert locInternalId != null;

            Map.Entry<Integer, String> crdE = alives.firstEntry();

            if (locInternalId.equals(crdE.getKey()))
                onBecomeCoordinator(locInternalId);
            else {
                assert alives.size() > 1;

                Map.Entry<Integer, String> prevE = alives.floorEntry(locInternalId - 1);

                assert prevE != null;

                final int crdInternalId = crdE.getKey();
                final int locInternalId0 = locInternalId;

                log.info("Discovery coordinator already exists, watch for previous node [" +
                    "locId=" + locNode.id() +
                    ", prevPath=" + prevE.getValue() + ']');

                zkClient.existsAsync(zkPaths.aliveNodesDir + "/" + prevE.getValue(), new Watcher() {
                    @Override public void process(WatchedEvent evt) {
                        if (evt.getType() == Event.EventType.NodeDeleted) {
                            try {
                                onPreviousNodeFail(crdInternalId, locInternalId0);
                            }
                            catch (Throwable e) {
                                onFatalError(e);
                            }
                        }
                    }
                }, new AsyncCallback.StatCallback() {
                    @Override public void processResult(int rc, String path, Object ctx, Stat stat) {
                        assert rc == 0 : rc;

                        if (stat == null) {
                            try {
                                onPreviousNodeFail(crdInternalId, locInternalId0);
                            }
                            catch (Throwable e) {
                                onFatalError(e);
                            }
                        }
                    }
                });
            }
        }
        catch (Throwable e) {
            onFatalError(e);
        }
    }

    private void onPreviousNodeFail(int crdInternalId, int locInternalId) throws Exception {
        if (locInternalId == crdInternalId + 1) {
            if (log.isInfoEnabled())
                log.info("Previous discovery coordinator failed [locId=" + locNode.id() + ']');

            onBecomeCoordinator(locInternalId);
        }
        else {
            if (log.isInfoEnabled())
                log.info("Previous node failed, check is node new coordinator [locId=" + locNode.id() + ']');

            zkClient.getChildrenAsync(zkPaths.aliveNodesDir, null, new AsyncCallback.Children2Callback() {
                @Override public void processResult(int rc, String path, Object ctx, List<String> children, Stat stat) {
                    checkIsCoordinator(rc, children);
                }
            });
        }
    }

    /**
     * @param locInternalId Local node's internal ID.
     * @throws Exception If failed.
     */
    private void onBecomeCoordinator(int locInternalId) throws Exception {
        byte[] evtsData = zkClient.getData(zkPaths.evtsPath);

        if (evtsData.length > 0)
            onEventsUpdate(evtsData);

        crd = true;

        if (joined) {
            if (log.isInfoEnabled())
                log.info("Node is new discovery coordinator [locId=" + locNode.id() + ']');

            assert locNode.order() > 0 : locNode;
            assert evts != null;
        }
        else {
            if (log.isInfoEnabled())
                log.info("Node is first cluster node [locId=" + locNode.id() + ']');

            newClusterStarted(locInternalId);
        }

        zkClient.getChildrenAsync(zkPaths.aliveNodesDir, watcher, childrenCallback);
        zkClient.getChildrenAsync(zkPaths.customEvtsDir, watcher, childrenCallback);
    }

    /**
     * @param aliveNodes ZK nodes representing alive cluster nodes.
     * @throws Exception If failed.
     */
    private void generateTopologyEvents(List<String> aliveNodes) throws Exception {
        assert crd;

        if (log.isInfoEnabled())
            log.info("Process alive nodes change: " + aliveNodes);

        TreeMap<Integer, String> alives = new TreeMap<>();

        TreeMap<Long, ZookeeperClusterNode> curTop = new TreeMap<>(top.nodesByOrder);

        int evtCnt = evts.evts.size();

        for (String child : aliveNodes) {
            Integer inernalId = ZkPaths.aliveInternalId(child);

            Object old = alives.put(inernalId, child);

            assert old == null;

            if (!top.nodesByInternalId.containsKey(inernalId))
                generateNodeJoin(curTop, inernalId, child);
        }

        for (Map.Entry<Integer, ZookeeperClusterNode> e : top.nodesByInternalId.entrySet()) {
            if (!alives.containsKey(e.getKey()))
                generateNodeFail(curTop, e.getValue());
        }

        if (evts.evts.size() > evtCnt) {
            long start = System.currentTimeMillis();

            zkClient.setData(zkPaths.evtsPath, marsh.marshal(evts), -1);

            long time = System.currentTimeMillis() - start;

            if (log.isInfoEnabled())
                log.info("Discovery coordinator saved new topology events [topVer=" + evts.topVer + ", saveTime=" + time + ']');

            onEventsUpdate(evts);
        }
    }

    /**
     * @param curTop Current topology.
     * @param failedNode Failed node.
     */
    private void generateNodeFail(TreeMap<Long, ZookeeperClusterNode> curTop, ZookeeperClusterNode failedNode) {
        Object rmvd = curTop.remove(failedNode.order());

        assert rmvd != null;

        evts.topVer++;
        evts.evtIdGen++;

        ZkDiscoveryEventData evtData = new ZkDiscoveryNodeFailEventData(evts.evtIdGen,
            evts.topVer,
            failedNode.internalId());

        evts.addEvent(evtData);

        if (log.isInfoEnabled()) {
            log.info("Generated NODE_FAILED event [topVer=" + evtData.topologyVersion() +
                ", nodeId=" + failedNode.id() + ']');
        }
    }

    private void generateNodeJoin(TreeMap<Long, ZookeeperClusterNode> curTop,
        int internalId,
        String aliveNodePath)
        throws Exception
    {
        UUID nodeId = ZkPaths.aliveNodeId(aliveNodePath);
        int joinSeq = ZkPaths.aliveJoinSequence(aliveNodePath);

        String joinDataPath = zkPaths.joinDataDir + '/' + nodeId.toString() + "|" + String.format("%010d", joinSeq);

        byte[] joinData;

        try {
            joinData = zkClient.getData(joinDataPath);
        }
        catch (KeeperException.NoNodeException e) {
            U.warn(log, "Failed to read joining node data, node left before join process finished: " + nodeId);

            return;
        }

        // TODO ZK: fail node if can not unmarshal.
        ZkJoiningNodeData joiningNodeData = unmarshal(joinData);

        ZookeeperClusterNode joinedNode = joiningNodeData.node();

        assert nodeId.equals(joinedNode.id()) : joiningNodeData.node();

        evts.topVer++;
        evts.evtIdGen++;

        joinedNode.order(evts.topVer);
        joinedNode.internalId(internalId);

        DiscoveryDataBag joiningNodeBag = new DiscoveryDataBag(nodeId);

        joiningNodeBag.joiningNodeData(joiningNodeData.discoveryData());

        exchange.onExchange(joiningNodeBag);

        DiscoveryDataBag collectBag = new DiscoveryDataBag(nodeId, new HashSet<Integer>());

        exchange.collect(collectBag);

        Map<Integer, Serializable> commonData = collectBag.commonData();

        ZkJoinEventDataForJoined dataForJoined = new ZkJoinEventDataForJoined(
            new ArrayList<>(curTop.values()),
            commonData);

        Object old = curTop.put(joinedNode.order(), joinedNode);

        assert old == null;

        ZkDiscoveryNodeJoinEventData evtData = new ZkDiscoveryNodeJoinEventData(
            evts.evtIdGen,
            evts.topVer,
            joinedNode.id(),
            joinedNode.internalId());

        evtData.joiningNodeData = joiningNodeData;

        evts.addEvent(evtData);

        String evtDataPath = zkPaths.evtsPath + "/" + evtData.eventId();

        long start = System.currentTimeMillis();

        zkClient.createIfNeeded(evtDataPath, joinData, PERSISTENT);
        zkClient.createIfNeeded(evtDataPath + "/joined", marshal(dataForJoined), PERSISTENT);

        long time = System.currentTimeMillis() - start;

        if (log.isInfoEnabled()) {
            log.info("Generated NODE_JOINED event [topVer=" + evtData.topologyVersion() +
                ", nodeId=" + joinedNode.id() +
                ", addDataTime=" + time + ']');
        }
    }

    /**
     * @param locInternalId Local node internal ID.
     * @throws Exception If failed.
     */
    @SuppressWarnings("unchecked")
    private void newClusterStarted(int locInternalId) throws Exception {
        cleanupPreviousClusterData();

        joined = true;

        gridStartTime = U.currentTimeMillis();

        evts = new ZkDiscoveryEventsData(gridStartTime, 1L, new TreeMap<Long, ZkDiscoveryEventData>());

        locNode.internalId(locInternalId);
        locNode.order(1);

        top.addNode(locNode);

        lsnr.onDiscovery(EventType.EVT_NODE_JOINED,
            1L,
            locNode,
            (Collection)top.nodesByOrder.values(),
            Collections.<Long, Collection<ClusterNode>>emptyMap(),
            null);

        joinFut.onDone();
    }

    private void cleanupPreviousClusterData() throws Exception {
        // TODO ZK: use multi.
        zkClient.setData(zkPaths.evtsPath, null, -1);

        List<String> evtChildren = zkClient.getChildren(zkPaths.evtsPath);

        for (String evtPath : evtChildren) {
            String evtDir = zkPaths.evtsPath + "/" + evtPath;

            removeChildren(evtDir);
        }

        zkClient.deleteAllIfExists(zkPaths.evtsPath, evtChildren, -1);

        for (String evtPath : zkClient.getChildren(zkPaths.customEvtsDir))
            zkClient.delete(zkPaths.customEvtsDir + "/" + evtPath, -1);
    }

    /**
     * @param path Path.
     * @throws Exception If failed.
     */
    private void removeChildren(String path) throws Exception {
        zkClient.deleteAllIfExists(path, zkClient.getChildren(path), -1);
    }

    void removeAckFuture(ZkEventAckFuture fut) {
        ackFuts.remove(fut.eventId());
    }

    ZkClusterNodes nodes() {
        return top;
    }

    ZookeeperClient zkClient() {
        return zkClient;
    }

    /**
     * @param customEvtNodes ZK nodes representing custom events to process.
     * @throws Exception If failed.
     */
    private void generateCustomEvents(List<String> customEvtNodes) throws Exception {
        assert crd;

        TreeMap<Integer, String> newEvts = null;

        for (int i = 0; i < customEvtNodes.size(); i++) {
            String evtPath = customEvtNodes.get(i);

            int evtSeq = ZkPaths.customEventSequence(evtPath);

            if (evtSeq > evts.procCustEvt) {
                if (newEvts == null)
                    newEvts = new TreeMap<>();

                newEvts.put(evtSeq, evtPath);
            }
        }

        if (newEvts != null) {
            for (Map.Entry<Integer, String> evtE : newEvts.entrySet()) {
                UUID sndNodeId = ZkPaths.customEventSendNodeId(evtE.getValue());

                ZookeeperClusterNode sndNode = top.nodesById.get(sndNodeId);

                String evtDataPath = zkPaths.customEvtsDir + "/" + evtE.getValue();

                if (sndNode != null) {
                    byte[] evtBytes = zkClient.getData(zkPaths.customEvtsDir + "/" + evtE.getValue());

                    DiscoverySpiCustomMessage msg;

                    try {
                        msg = unmarshal(evtBytes);

                        evts.evtIdGen++;

                        ZkDiscoveryCustomEventData evtData = new ZkDiscoveryCustomEventData(
                            evts.evtIdGen,
                            evts.topVer,
                            sndNodeId,
                            evtE.getValue());

                        evtData.msg = msg;

                        evts.addEvent(evtData);

                        if (log.isInfoEnabled())
                            log.info("Generated CUSTOM event [topVer=" + evtData.topologyVersion() + ", evt=" + msg + ']');
                    }
                    catch (IgniteCheckedException e) {
                        U.error(log, "Failed to unmarshal custom discovery message: " + e, e);
                    }
                }
                else {
                    U.warn(log, "Ignore custom event from unknown node: " + sndNodeId);

                    zkClient.deleteIfExists(evtDataPath, -1);
                }

                evts.procCustEvt = evtE.getKey();
            }

            long start = System.currentTimeMillis();

            zkClient.setData(zkPaths.evtsPath, marsh.marshal(evts), -1);

            long time = System.currentTimeMillis() - start;

            if (log.isInfoEnabled())
                log.info("Discovery coordinator saved new topology events [topVer=" + evts.topVer + ", saveTime=" + time + ']');

            onEventsUpdate(evts);
        }
    }

    /**
     * @param data Marshalled events.
     * @throws Exception If failed.
     */
    private void onEventsUpdate(byte[] data) throws Exception {
        if (data.length == 0)
            return;

        assert !crd;

        ZkDiscoveryEventsData evtsData = unmarshal(data);

        onEventsUpdate(evtsData);

        evts = evtsData;
    }

    /**
     * @param evtsData Events.
     * @throws Exception If failed.
     */
    private void onEventsUpdate(ZkDiscoveryEventsData evtsData) throws Exception {
        TreeMap<Long, ZkDiscoveryEventData> evts = evtsData.evts;

        for (Map.Entry<Long, ZkDiscoveryEventData> e : evts.tailMap(lastProcEvt, false).entrySet()) {
            ZkDiscoveryEventData evtData = e.getValue();

            if (!joined) {
                if (evtData.eventType() != EventType.EVT_NODE_JOINED)
                    continue;

                ZkDiscoveryNodeJoinEventData evtData0 = (ZkDiscoveryNodeJoinEventData)evtData;

                UUID joinedId = ((ZkDiscoveryNodeJoinEventData)evtData).nodeId;

                boolean locJoin = evtData.eventType() == EventType.EVT_NODE_JOINED &&
                    locNode.id().equals(joinedId);

                if (locJoin) {
                    if (log.isInfoEnabled())
                        log.info("Local join event data: " + evtData + ']');

                    String path = zkPaths.evtsPath + "/" + evtData.eventId() + "/joined";

                    ZkJoinEventDataForJoined dataForJoined = unmarshal(zkClient.getData(path));

                    gridStartTime = evtsData.gridStartTime;

                    locNode.internalId(evtData0.joinedInternalId);
                    locNode.order(evtData.topologyVersion());

                    DiscoveryDataBag dataBag = new DiscoveryDataBag(locNode.id());

                    dataBag.commonData(dataForJoined.discoveryData());

                    exchange.onExchange(dataBag);

                    List<ZookeeperClusterNode> allNodes = dataForJoined.topology();

                    for (ZookeeperClusterNode node : allNodes)
                        top.addNode(node);

                    top.addNode(locNode);

                    List<ClusterNode> topSnapshot = new ArrayList<>((Collection)top.nodesByOrder.values());

                    lsnr.onDiscovery(evtData.eventType(),
                        evtData.topologyVersion(),
                        locNode,
                        topSnapshot,
                        Collections.<Long, Collection<ClusterNode>>emptyMap(),
                        null);

                    joinFut.onDone();

                    joined = true;
                }
            }
            else {
                if (log.isInfoEnabled())
                    log.info("New discovery event data: " + evtData + ']');

                switch (evtData.eventType()) {
                    case EventType.EVT_NODE_JOINED: {
                        ZkDiscoveryNodeJoinEventData evtData0 = (ZkDiscoveryNodeJoinEventData)evtData;

                        ZkJoiningNodeData joiningData;

                        if (crd) {
                            assert evtData0.joiningNodeData != null;

                            joiningData = evtData0.joiningNodeData;
                        }
                        else {
                            String path = zkPaths.evtsPath + "/" + evtData.eventId();

                            joiningData = unmarshal(zkClient.getData(path));

                            DiscoveryDataBag dataBag = new DiscoveryDataBag(evtData0.nodeId);

                            dataBag.joiningNodeData(joiningData.discoveryData());

                            exchange.onExchange(dataBag);
                        }

                        notifyNodeJoin(evtData0, joiningData);

                        break;
                    }

                    case EventType.EVT_NODE_FAILED: {
                        notifyNodeFail((ZkDiscoveryNodeFailEventData)e.getValue());

                        break;
                    }

                    case DiscoveryCustomEvent.EVT_DISCOVERY_CUSTOM_EVT: {
                        ZkDiscoveryCustomEventData evtData0 = (ZkDiscoveryCustomEventData)evtData;

                        DiscoverySpiCustomMessage msg;

                        if (crd) {
                            assert evtData0.msg != null : evtData0;

                            msg = evtData0.msg;
                        }
                        else {
                            String path = zkPaths.customEvtsDir + "/" + evtData0.evtPath;

                            msg = unmarshal(zkClient.getData(path));
                        }

                        notifyCustomEvent(evtData0, msg);

                        break;
                    }

                    default:
                        assert false : "Invalid event: " + evtData;
                }
            }

            if (joined)
                lastProcEvt = e.getKey();
        }
    }

    /**
     * @param evtData Event data.
     * @param msg Custom message.
     */
    @SuppressWarnings("unchecked")
    private void notifyCustomEvent(ZkDiscoveryCustomEventData evtData, DiscoverySpiCustomMessage msg) {
        if (log.isInfoEnabled())
            log.info(" [topVer=" + evtData.topologyVersion() + ", msg=" + msg + ']');

        ZookeeperClusterNode sndNode = top.nodesById.get(evtData.sndNodeId);

        assert sndNode != null : evtData;

        List<ClusterNode> topSnapshot = new ArrayList<>((Collection)top.nodesByOrder.values());

        lsnr.onDiscovery(evtData.eventType(),
            evtData.topologyVersion(),
            sndNode,
            topSnapshot,
            Collections.<Long, Collection<ClusterNode>>emptyMap(),
            msg);

        if (crd) {
            ZkEventAckFuture fut = new ZkEventAckFuture(this,
                zkPaths.customEvtsDir + "/" + evtData.evtPath,
                evtData.eventId());

            ackFuts.put(evtData.eventId(), fut);
        }
        else {
            String ackPath = zkPaths.customEvtsDir + "/" + evtData.evtPath + "/" + locNode.internalId();

            zkClient.createAsync(ackPath, null, CreateMode.PERSISTENT, null);
        }
    }

    /**
     * @param evtData Event data.
     * @param joiningData Joining node data.
     */
    @SuppressWarnings("unchecked")
    private void notifyNodeJoin(ZkDiscoveryNodeJoinEventData evtData, ZkJoiningNodeData joiningData) {
        ZookeeperClusterNode joinedNode = joiningData.node();

        joinedNode.order(evtData.topologyVersion());
        joinedNode.internalId(evtData.joinedInternalId);

        top.addNode(joinedNode);

        List<ClusterNode> topSnapshot = new ArrayList<>((Collection)top.nodesByOrder.values());

        lsnr.onDiscovery(evtData.eventType(),
            evtData.topologyVersion(),
            joinedNode,
            topSnapshot,
            Collections.<Long, Collection<ClusterNode>>emptyMap(),
            null);
    }

    /**
     * @param evtData Event data.
     */
    @SuppressWarnings("unchecked")
    private void notifyNodeFail(ZkDiscoveryNodeFailEventData evtData) {
        ZookeeperClusterNode failedNode = top.removeNode(evtData.failedNodeInternalId());

        assert failedNode != null;

        List<ClusterNode> topSnapshot = new ArrayList<>((Collection)top.nodesByOrder.values());

        lsnr.onDiscovery(evtData.eventType(),
            evtData.topologyVersion(),
            failedNode,
            topSnapshot,
            Collections.<Long, Collection<ClusterNode>>emptyMap(),
            null);

        if (crd) {
            for (ZkEventAckFuture ackFut : ackFuts.values())
                ackFut.onNodeFail(failedNode);
        }
    }

    /**
     *
     */
    public void stop() {
        if (zkClient != null)
            zkClient.close();

        joinFut.onDone(new IgniteSpiException("Node stopped"));
    }

    /**
     * @param e Error.
     */
    private void onFatalError(Throwable e) {
        // TODO ZK
        U.error(log, "Failed to process discovery data. Stopping the node in order to prevent cluster wide instability.", e);

        joinFut.onDone(e);

        if (e instanceof Error)
            throw (Error)e;
    }

    /**
     * @param bytes Bytes.
     * @return Unmarshalled object.
     * @throws IgniteCheckedException If failed.
     */
    private <T> T unmarshal(byte[] bytes) throws IgniteCheckedException {
        assert bytes != null && bytes.length > 0;

        return marsh.unmarshal(bytes, null);
    }

    /**
     * @param obj Object.
     * @return Bytes.
     * @throws IgniteCheckedException If failed.
     */
    private byte[] marshal(Object obj) throws IgniteCheckedException {
        assert obj != null;

        return marsh.marshal(obj);
    }

    /**
     *
     */
    private class ConnectionLossListener implements IgniteRunnable {
        /** {@inheritDoc} */
        @Override public void run() {
            // TODO ZK, can be called from any thread.
            U.warn(log, "Zookeeper connection loss, local node is SEGMENTED");

            if (joined) {
                assert evts != null;

                lsnr.onDiscovery(EventType.EVT_NODE_SEGMENTED,
                    evts.topVer,
                    locNode,
                    Collections.<ClusterNode>emptyList(),
                    Collections.<Long, Collection<ClusterNode>>emptyMap(),
                    null);
            }
            else
                joinFut.onDone(new IgniteSpiException("Local node SEGMENTED"));
        }
    }

    /**
     *
     */
    private class ZkWatcher implements Watcher {
        /** {@inheritDoc} */
        @Override public void process(WatchedEvent evt) {
            if (evt.getType() == Event.EventType.NodeDataChanged) {
                if (evt.getPath().equals(zkPaths.evtsPath)) {
                    if (!crd)
                        zkClient.getDataAsync(evt.getPath(), this, dataCallback);
                }
                else
                    U.warn(log, "Received NodeDataChanged for unexpected path: " + evt.getPath());
            }
            else if (evt.getType() == Event.EventType.NodeChildrenChanged) {
                if (evt.getPath().equals(zkPaths.aliveNodesDir))
                    zkClient.getChildrenAsync(evt.getPath(), this, childrenCallback);
                else if (evt.getPath().equals(zkPaths.customEvtsDir))
                    zkClient.getChildrenAsync(evt.getPath(), this, childrenCallback);
                else
                    U.warn(log, "Received NodeChildrenChanged for unexpected path: " + evt.getPath());
            }
        }
    }

    /**
     *
     */
    private class ZKChildrenCallback implements AsyncCallback.Children2Callback {
        /** {@inheritDoc} */
        @Override public void processResult(int rc, String path, Object ctx, List<String> children, Stat stat) {
            try {
                assert rc == 0 : rc;

                if (path.equals(zkPaths.aliveNodesDir))
                    generateTopologyEvents(children);
                else if (path.equals(zkPaths.customEvtsDir))
                    generateCustomEvents(children);
                else
                    U.warn(log, "Children callback for unexpected path: " + path);
            }
            catch (Throwable e) {
                onFatalError(e);
            }
        }
    }

    /**
     *
     */
    private class ZkDataCallback implements AsyncCallback.DataCallback {
        /** {@inheritDoc} */
        @Override public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
            try {
                assert rc == 0 : rc;

                if (path.equals(zkPaths.evtsPath)) {
                    if (!crd)
                        onEventsUpdate(data);
                }
                else
                    U.warn(log, "Data callback for unknown path: " + path);
            }
            catch (Throwable e) {
                onFatalError(e);
            }
        }
    }
}
