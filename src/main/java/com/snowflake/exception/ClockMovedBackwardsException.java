package com.snowflake.exception;

public class ClockMovedBackwardsException extends RuntimeException {

    public ClockMovedBackwardsException(String message) {
        super(message);
    }
}
