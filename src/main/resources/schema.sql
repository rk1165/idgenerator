CREATE TABLE IF NOT EXISTS snowflake_node_registry
(
    node_id        INT PRIMARY KEY,       -- 0-1023 (the Snowflake node ID)
    instance_id    VARCHAR(255) NOT NULL, -- unique identifier for this instance (hostname/container-id/UUID)
    last_heartbeat TIMESTAMP(3) NOT NULL, -- last time this instance checked in
    acquired_at    TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3)

) ENGINE=InnoDB;

