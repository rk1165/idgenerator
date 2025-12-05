package com.snowflake.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SnowflakeId {

    private long id;
    private long nodeId;
    private String host;

}
