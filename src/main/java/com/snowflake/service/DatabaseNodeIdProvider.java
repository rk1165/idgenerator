package com.snowflake.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.snowflake.ApplicationConstants.MAX_NODE_ID;

@Component
@Slf4j
public class DatabaseNodeIdProvider implements NodeIdProvider, DisposableBean {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration NODE_EXPIRY_THRESHOLD = Duration.ofSeconds(30);

    private final JdbcTemplate jdbcTemplate;
    private final String instanceId;
    private final ScheduledExecutorService heartbeatExecutor;

    private volatile Long acquiredNodeId = null;
    private volatile boolean running = true;

    public DatabaseNodeIdProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.instanceId = generateInstanceId();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "snowflake-heartbeat")
        );
    }

    /**
     * Generate a unique instance identifier.
     * Combines hostname + process ID + random UUID for uniqueness across restarts.
     */
    private String generateInstanceId() {
        String hostname = getHostname();
        long pid = ProcessHandle.current().pid();
        String random = UUID.randomUUID().toString().substring(0, 8);
        return String.format("%s-%d-%s", hostname, pid, random);
    }

    private String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    /**
     * Acquire a node ID from the registry.
     * This must be called before the Snowflake generator starts.
     */
    @PostConstruct
    public void acquireNodeId() {
        log.info("Attempting to acquire Snowflake node ID for instance: {}", instanceId);

        // Step 1: Try to acquire an expired node (previous owner died)
        Long nodeId = tryAcquireExpiredNode();

        // Step 2: If no expired nodes, try to acquire an unused node
        if (nodeId == null) {
            nodeId = tryAcquireUnusedNode();
        }

        if (nodeId == null) {
            logNodeStatus();
            throw new IllegalStateException(
                    "Failed to acquire Snowflake node ID. All 1024 node IDs are in use. " +
                            "Consider scaling down or increasing NODE_EXPIRY_THRESHOLD."
            );
        }

        this.acquiredNodeId = nodeId;
        log.info("Successfully acquired Snowflake node ID: {} for instance: {}", nodeId, instanceId);

        startHeartbeat();
    }

    /**
     * Try to acquire a node ID whose previous owner has stopped sending heartbeats.
     * This finds the lowest node_id where last_heartbeat is older than the expiry threshold.
     *
     * @return the acquired node ID, or null if no expired nodes found
     */
    private Long tryAcquireExpiredNode() {
        Timestamp expiryThreshold = Timestamp.from(
                Instant.now().minus(NODE_EXPIRY_THRESHOLD)
        );

        log.info("Looking for expired nodes (heartbeat before {})", expiryThreshold);

        // Find the node id which received last_heartbeat 30 seconds before Now and was previously in use
        // then update its instance_id
        int updated = jdbcTemplate.update(
                """
                        UPDATE snowflake_node_registry
                        SET instance_id = ?, 
                            last_heartbeat = NOW(3),
                            acquired_at = NOW(3)
                        WHERE node_id = (
                            SELECT node_id FROM (
                                SELECT node_id FROM snowflake_node_registry
                                WHERE last_heartbeat < ?
                                  AND instance_id != ''
                                ORDER BY node_id
                                LIMIT 1
                            ) AS subquery
                        )
                        """,
                instanceId,
                expiryThreshold
        );

        if (updated > 0) {
            Long nodeId = jdbcTemplate.queryForObject(
                    "SELECT node_id FROM snowflake_node_registry WHERE instance_id = ?",
                    Long.class,
                    instanceId
            );
            log.info("Acquired expired node ID: {} (previous owner stopped heartbeat)", nodeId);
            return nodeId;
        }

        log.warn("No expired nodes found");
        return null;
    }

    /**
     * Try to acquire an unused node ID (instance_id is empty).
     * These are nodes that have never been used or were explicitly released - like if instance got shut down
     *
     * @return the acquired node ID, or null if no unused nodes found
     */
    private Long tryAcquireUnusedNode() {
        log.info("Looking for unused nodes (empty instance_id or non-existent)");

        // Iterate through node IDs to find an unused one
        for (int nodeId = 0; nodeId <= MAX_NODE_ID; nodeId++) {
            // First, try to UPDATE an existing unused row
            int updated = jdbcTemplate.update(
                    """
                            UPDATE snowflake_node_registry
                            SET instance_id = ?,
                                last_heartbeat = NOW(3),
                                acquired_at = NOW(3)
                            WHERE node_id = ?
                              AND instance_id = ''
                            """,
                    instanceId,
                    nodeId
            );

            if (updated > 0) {
                log.info("Acquired existing unused node ID: {}", nodeId);
                return (long) nodeId;
            }

            // If UPDATE didn't match, try to INSERT (row might not exist)
            try {
                int inserted = jdbcTemplate.update(
                        """
                                INSERT INTO snowflake_node_registry (node_id, instance_id, last_heartbeat, acquired_at)
                                VALUES (?, ?, NOW(3), NOW(3))
                                """,
                        nodeId,
                        instanceId
                );

                if (inserted > 0) {
                    log.info("Acquired new node ID (inserted): {}", nodeId);
                    return (long) nodeId;
                }
            } catch (DuplicateKeyException e) {
                // Another instance grabbed this node_id between our UPDATE and INSERT
                // This is fine, just try the next node_id
                log.warn("Node ID {} was claimed by another instance, trying next", nodeId);
            }
        }

        log.warn("No unused nodes found");
        return null;
    }

    /**
     * Log the current state of node allocation for debugging.
     */
    private void logNodeStatus() {
        try {
            Timestamp expiryThreshold = Timestamp.from(
                    Instant.now().minus(NODE_EXPIRY_THRESHOLD)
            );

            Integer activeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM snowflake_node_registry WHERE instance_id != '' AND last_heartbeat >= ?",
                    Integer.class,
                    expiryThreshold
            );

            Integer expiredCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM snowflake_node_registry WHERE instance_id != '' AND last_heartbeat < ?",
                    Integer.class,
                    expiryThreshold
            );

            Integer unusedCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM snowflake_node_registry WHERE instance_id = ''",
                    Integer.class
            );

            log.error("Node status - Active: {}, Expired: {}, Unused: {}",
                    activeCount, expiredCount, unusedCount);
        } catch (Exception e) {
            log.error("Failed to get node status", e);
        }
    }

    /**
     * Start background heartbeat to keep the node ID alive.
     */
    private void startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeat,
                HEARTBEAT_INTERVAL.toMillis(),
                HEARTBEAT_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Send heartbeat to indicate this instance is still alive.
     */
    private void sendHeartbeat() {
        if (!running || acquiredNodeId == null) {
            return;
        }
        log.debug("Sending heartbeat to node ID={} by instanceId={}", acquiredNodeId, instanceId);
        try {
            int updated = jdbcTemplate.update(
                    """
                            UPDATE snowflake_node_registry
                            SET last_heartbeat = NOW(3)
                            WHERE node_id = ? AND instance_id = ?
                            """,
                    acquiredNodeId,
                    instanceId
            );

            if (updated == 0) {
                // Someone else took our node ID! This is a critical error.
                log.error("CRITICAL: Lost ownership of node ID {}! Another instance may have taken it. " +
                        "Stopping ID generation to prevent duplicates.", acquiredNodeId);
                running = false;
                throw new IllegalStateException("Lost Snowflake node ID ownership");
            }

            log.debug("Heartbeat sent for node ID: {}", acquiredNodeId);

        } catch (DataAccessException e) {
            log.error("Failed to send heartbeat for node ID: {}", acquiredNodeId, e);
            // Don't stop immediately - transient DB issues shouldn't kill the service
            // But if heartbeats fail for too long, another instance could steal the ID
        }
    }

    /**
     * Get the acquired node ID for use by the Snowflake generator.
     */
    @Override
    public long getNodeId() {
        if (acquiredNodeId == null) {
            throw new IllegalStateException("Node ID not yet acquired. Call acquireNodeId() first.");
        }
        if (!running) {
            throw new IllegalStateException("Node ID provider is not running. Possibly lost ownership.");
        }
        return acquiredNodeId;
    }

    /**
     * Release the node ID on graceful shutdown.
     */
    @Override
    @PreDestroy
    public void destroy() {
        log.info("Releasing Snowflake node ID: {}", acquiredNodeId);
        running = false;

        heartbeatExecutor.shutdown();
        try {
            heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (acquiredNodeId != null) {
            try {
                // Mark as expired so another instance can immediately reuse it
                jdbcTemplate.update(
                        """
                                UPDATE snowflake_node_registry
                                SET last_heartbeat = '2025-01-01 00:00:00', instance_id = ''
                                WHERE node_id = ? AND instance_id = ?
                                """,
                        acquiredNodeId,
                        instanceId
                );
                log.info("Successfully released node ID: {}", acquiredNodeId);
            } catch (DataAccessException e) {
                log.warn("Failed to release node ID: {}", acquiredNodeId, e);
            }
        }
    }
}