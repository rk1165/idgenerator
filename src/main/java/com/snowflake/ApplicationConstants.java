package com.snowflake;

public class ApplicationConstants {

    public static final long EPOCH = 1609459200000L; // 2021-01-01 00:00:00 UTC

    public static final int NODE_ID_BITS = 10;
    public static final int SEQUENCE_BITS = 12;

    public static final long MAX_NODE_ID = (1L << NODE_ID_BITS) - 1;     // 1023
    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;   // 4095

    public static final int NODE_ID_SHIFT = SEQUENCE_BITS;                  // 12
    public static final int TIMESTAMP_SHIFT = NODE_ID_BITS + SEQUENCE_BITS; // 22

    public static final int MAX_BATCH_SIZE = 10_000;
}
