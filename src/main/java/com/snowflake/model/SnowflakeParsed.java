package com.snowflake.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class SnowflakeParsed {

    private long timestamp;
    private long nodeId;
    private long sequence;
    private Instant timestampInstant;

}
