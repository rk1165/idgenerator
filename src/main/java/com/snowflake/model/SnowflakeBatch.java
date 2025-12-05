package com.snowflake.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SnowflakeBatch {

    private SnowflakeId[] ids;
}
