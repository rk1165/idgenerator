package com.snowflake.controller;

import com.snowflake.ApplicationConstants;
import com.snowflake.model.SnowflakeBatch;
import com.snowflake.model.SnowflakeId;
import com.snowflake.model.SnowflakeParsed;
import com.snowflake.service.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/snowflake")
@RequiredArgsConstructor
@Slf4j
public class SnowflakeController {

    private final SnowflakeIdGenerator generator;

    /**
     * Get an Id
     *
     * @return an id generated using snowflake algorithm
     * @throws UnknownHostException
     */
    @GetMapping("/next")
    public SnowflakeId nextId() throws UnknownHostException {
        return createSnowFlakeId();
    }

    /**
     * Generate a batch of Ids
     *
     * @param count the number of ids to generate
     * @return a batch of ids
     * @throws UnknownHostException
     */
    @GetMapping("/batch")
    public SnowflakeBatch batchIds(@RequestParam(defaultValue = "10") int count) throws UnknownHostException {
        if (count > ApplicationConstants.MAX_BATCH_SIZE) count = ApplicationConstants.MAX_BATCH_SIZE; // cap batch size

        SnowflakeId[] snowflakeIds = new SnowflakeId[count];
        for (int i = 0; i < count; i++) {
            snowflakeIds[i] = createSnowFlakeId();
        }
        return new SnowflakeBatch(snowflakeIds);
    }

    /**
     * Parse a given id to see its details
     *
     * @param id a snowflake id
     * @return a response which shows timestamp, nodeId and sequence parts separately of a snowflake id
     */
    @GetMapping("/{id}/parse")
    public SnowflakeParsed parseId(@PathVariable long id) {
        long timestamp = (id >> ApplicationConstants.TIMESTAMP_SHIFT) + ApplicationConstants.EPOCH;
        long nodeId = (id >> ApplicationConstants.NODE_ID_SHIFT) & 0x3FF;
        long sequence = id & 0xFFF;
        return new SnowflakeParsed(timestamp, nodeId, sequence, Instant.ofEpochMilli(timestamp));
    }

    private SnowflakeId createSnowFlakeId() throws UnknownHostException {
        long id = generator.nextId();
        long nodeId = generator.getNodeId();
        String hostName = InetAddress.getLocalHost().getHostName();
        log.debug("Generated new Id = {}", id);
        return new SnowflakeId(id, nodeId, hostName);
    }
}