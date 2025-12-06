package com.snowflake.service;

import com.snowflake.exception.ClockMovedBackwardsException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

import static com.snowflake.ApplicationConstants.*;

@Slf4j
@Data
@Component
public class SnowflakeIdGeneratorCAS {

    private final long nodeId;
    private final AtomicLong state = new AtomicLong(0L); // packs [lastTimestamp | sequence]

    public SnowflakeIdGeneratorCAS(NodeIdProvider nodeIdProvider) {
        this.nodeId = nodeIdProvider.getNodeId();
    }

    /**
     * Generates and returns the next snowflake id
     *
     * @return snowflake id
     */
    public long nextId() {
        while (true) {
            long currentTimestamp = System.currentTimeMillis();
            long oldState = state.get();
            long lastTimestamp = oldState >> SEQUENCE_BITS; // if we shift right and drop the sequence bits we have last timeStamp
            long sequence = oldState & MAX_SEQUENCE;        // extract the sequence
            log.debug("CurrentTimeStamp={} oldState={} lastTimeStamp={} sequence={}", currentTimestamp, oldState, lastTimestamp, sequence);

            // Clock moved backwards - critical error
            // currentTimeStamp is always supposed to be after the lastTimeStamp because lastTimeStamp is the
            // timestamp when previous id generation took place. Suppose because of a clock drift the currentTimeStamp
            // becomes earlier than the lastTimeStamp during which we have already generated the id.
            if (currentTimestamp < lastTimestamp) {
                long drift = lastTimestamp - currentTimestamp;
                // fail fast
                throw new ClockMovedBackwardsException("Clock moved backwards by " + drift + "ms. Not generating IDs.");
            }

            long newTimestamp;
            long newSequence;

            if (currentTimestamp == lastTimestamp) {
                // When we are generating id in the same millisecond, then only the sequence bit will be increasing
                newSequence = (sequence + 1) & MAX_SEQUENCE;    // (sequence + 1 ) % 4096
                if (newSequence == 0) {
                    // Sequence Exhaustion (>4096 IDs in 1ms), busy wait for next millis
                    newTimestamp = waitNextMillis(lastTimestamp);
                } else {
                    // Imagine the generation happening very fast like in a single millisecond multiple ids getting generated
                    // in that case newTimeStamp will still remain currentTimeStamp only
                    newTimestamp = currentTimestamp;
                }
            } else {
                // While generating the id at very high rate and in the loop suddenly if the currentTimeStamp changed from the
                // lastTimeStamp, we reset the newTimeStamp to currentTimeStamp and start the sequence from 0
                newTimestamp = currentTimestamp;
                newSequence = 0L;
            }

            long newState = (newTimestamp << SEQUENCE_BITS) | newSequence;  // pack the new timestamp and new sequence

            // Suppose there are multiple threads generating the ids, it's possible that two threads can generate the same
            // sequence, so we compare the states in memory using CAS to ensure atomic updates
            // If it fails, that means another thread successfully created an id and updated the state, so we retry
            // else we return the id
            if (state.compareAndSet(oldState, newState)) {
                // A raw unix timestamp is ~44bits and doesn't fit in Snowflake's 41-bit timestamp field.
                // By storing (timeStamp-EPOCH) we keep it within 41 bits
                return ((newTimestamp - EPOCH) << TIMESTAMP_SHIFT)
                        | (nodeId << NODE_ID_SHIFT)
                        | newSequence;
            } else {
                // CAS failed, retry
                log.debug("CAS failed");
            }
        }

    }

    private long waitNextMillis(long lastTs) {
        log.info("Generated more than 4096 Ids in a millisecond. Busy Waiting");
        long ts = System.currentTimeMillis();
        while (ts <= lastTs) {
            ts = System.currentTimeMillis();
        }
        return ts;
    }
}