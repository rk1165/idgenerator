package com.snowflake.service;

public interface NodeIdProvider {
    /**
     * Get the node ID assigned to this instance.
     * @return node ID (0-1023)
     * @throws IllegalStateException if node ID not acquired or lost
     */
    long getNodeId();
}