package com.snowflake;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.snowflake.ApplicationConstants.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput) // We want to know: Operations per Millisecond
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1) // Warmup the JVM
@Measurement(iterations = 5, time = 1) // Actual measurement
@Fork(1)
public class SnowflakeBenchmark {

    private static final long NODE_ID = 1L;


    // --- INSTANCES ---
    private CasSnowflake casGenerator;
    private LockSnowflake lockGenerator;

    @Setup
    public void setup() {
        casGenerator = new CasSnowflake();
        lockGenerator = new LockSnowflake();
    }

    // --- BENCHMARK 1: The Atomic/CAS Approach (Old) ---
    @Benchmark
    @Threads(16) // Simulate HIGH contention (16 threads fighting)
    public void testCasStrategy(Blackhole blackhole) {
        blackhole.consume(casGenerator.nextId());
    }

    // --- BENCHMARK 2: The ReentrantLock Approach (New) ---
    @Benchmark
    @Threads(16) // Simulate HIGH contention (16 threads fighting)
    public void testLockStrategy(Blackhole blackhole) {
        blackhole.consume(lockGenerator.nextId());
    }

    // ==========================================
    // IMPLEMENTATION 1: CAS (Optimistic)
    // ==========================================
    static class CasSnowflake {
        private final AtomicLong state = new AtomicLong(0L);

        public long nextId() {
            while (true) {
                long currentTimestamp = System.currentTimeMillis();
                long oldState = state.get();
                long lastTimestamp = oldState >> SEQUENCE_BITS;
                long sequence = oldState & MAX_SEQUENCE;

                if (currentTimestamp < lastTimestamp) {
                    // For benchmark simplicity, we ignore clock drift handling
                    // as it doesn't happen during a short test run.
                    currentTimestamp = waitNextMillis(lastTimestamp);
                }

                long newTimestamp;
                long newSequence;

                if (currentTimestamp == lastTimestamp) {
                    newSequence = (sequence + 1) & MAX_SEQUENCE;
                    if (newSequence == 0) {
                        newTimestamp = waitNextMillis(lastTimestamp);
                    } else {
                        newTimestamp = currentTimestamp;
                    }
                } else {
                    newTimestamp = currentTimestamp;
                    newSequence = 0L;
                }

                long newState = (newTimestamp << SEQUENCE_BITS) | newSequence;

                if (state.compareAndSet(oldState, newState)) {
                    return ((newTimestamp - EPOCH) << TIMESTAMP_SHIFT)
                            | (NODE_ID << NODE_ID_SHIFT)
                            | newSequence;
                }
                // If failed, loop again (Spinning)
            }
        }

        private long waitNextMillis(long lastTs) {
            long ts = System.currentTimeMillis();
            while (ts <= lastTs) {
                ts = System.currentTimeMillis();
            }
            return ts;
        }
    }

    // ==========================================
    // IMPLEMENTATION 2: LOCK (Pessimistic)
    // ==========================================
    static class LockSnowflake {
        private final Lock lock = new ReentrantLock();
        private long lastTimestamp = -1L;
        private long sequence = 0L;

        public long nextId() {
            lock.lock();
            try {
                long currentTimestamp = System.currentTimeMillis();

                if (currentTimestamp == lastTimestamp) {
                    sequence = (sequence + 1) & MAX_SEQUENCE;
                    if (sequence == 0) {
                        currentTimestamp = waitNextMillis(lastTimestamp);
                    }
                } else {
                    sequence = 0L;
                }
                lastTimestamp = currentTimestamp;

                return ((currentTimestamp - EPOCH) << TIMESTAMP_SHIFT)
                        | (NODE_ID << NODE_ID_SHIFT)
                        | sequence;
            } finally {
                lock.unlock();
            }
        }

        private long waitNextMillis(long lastTs) {
            long ts = System.currentTimeMillis();
            while (ts <= lastTs) {
                ts = System.currentTimeMillis();
            }
            return ts;
        }
    }
}