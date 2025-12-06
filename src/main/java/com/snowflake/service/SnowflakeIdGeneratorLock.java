package com.snowflake.service;

import com.snowflake.exception.ClockMovedBackwardsException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.snowflake.ApplicationConstants.*;

@Slf4j
@Component
@Data
@Primary
public class SnowflakeIdGeneratorLock {

    private final long nodeId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    // Use ReentrantLock. It is friendly to Virtual Threads (doesn't pin the carrier).
    private final Lock lock = new ReentrantLock();

    public SnowflakeIdGeneratorLock(NodeIdProvider nodeIdProvider) {
        this.nodeId = nodeIdProvider.getNodeId();
    }

    public long nextId() {
        lock.lock();
        try {
            long currentTimestamp = System.currentTimeMillis();

            // Clock moved backwards check
            if (currentTimestamp < lastTimestamp) {
                long drift = lastTimestamp - currentTimestamp;
                throw new ClockMovedBackwardsException("Clock moved backwards by " + drift + "ms. Not generating IDs.");
            }

            if (currentTimestamp == lastTimestamp) {
                // Same millisecond: increment sequence
                sequence = (sequence + 1) & MAX_SEQUENCE;

                // Sequence Exhaustion: Wait for next millisecond
                if (sequence == 0) {
                    currentTimestamp = waitNextMillis(lastTimestamp);
                }
            } else {
                // New millisecond: reset sequence
                sequence = 0L;
            }

            // Update state
            lastTimestamp = currentTimestamp;

            // Generate ID
            return ((currentTimestamp - EPOCH) << TIMESTAMP_SHIFT)
                    | (nodeId << NODE_ID_SHIFT)
                    | sequence;

        } finally {
            lock.unlock();
        }
    }

    private long waitNextMillis(long lastTs) {
        long ts = System.currentTimeMillis();
        while (ts <= lastTs) {
            // Hint to the scheduler that we are busy-waiting
            // (Optional, but good for busy loops)
            Thread.onSpinWait();
            ts = System.currentTimeMillis();
        }
        return ts;
    }
}