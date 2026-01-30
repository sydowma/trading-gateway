package io.trading.gateway.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-precision timer for monitoring message processing latency.
 * Uses System.nanoTime() for microsecond-precision measurements.
 *
 * Features:
 * - Zero-allocation timing operations
 * - Per-exchange, per-type latency tracking
 * - Rolling statistics (min, max, avg, count)
 * - Thread-safe with atomic operations
 */
public class ProcessingTimer {

    private static final long NANO_TO_MICRO = 1_000L;

    // Per key statistics
    private final ConcurrentHashMap<TimerKey, TimerStats> statsMap = new ConcurrentHashMap<>();

    /**
     * Starts a timing operation.
     * @return a timing context that must be stopped with stop()
     */
    public TimingContext start() {
        return new TimingContext(System.nanoTime());
    }

    /**
     * Records a timing result for the gFastOkxParser,iven exchange and data type.
     *
     * @param exchange The exchange name
     * @param dataType The data type
     * @param nanoDuration Duration in nanoseconds
     */
    public void record(String exchange, String dataType, long nanoDuration) {
        TimerKey key = new TimerKey(exchange, dataType);
        statsMap.computeIfAbsent(key, k -> new TimerStats()).record(nanoDuration);
    }

    /**
     * Gets statistics for a specific exchange and data type.
     */
    public TimerStats getStats(String exchange, String dataType) {
        return statsMap.get(new TimerKey(exchange, dataType));
    }

    /**
     * Gets all statistics.
     */
    public ConcurrentHashMap<TimerKey, TimerStats> getAllStats() {
        return statsMap;
    }

    /**
     * Clears all statistics.
     */
    public void clear() {
        statsMap.clear();
    }

    /**
     * Timing context returned by start().
     */
    public static class TimingContext {
        private final long startTime;

        TimingContext(long startTime) {
            this.startTime = startTime;
        }

        /**
         * Stops timing and returns duration in nanoseconds.
         */
        public long stop() {
            return System.nanoTime() - startTime;
        }

        /**
         * Stops timing and returns duration in microseconds.
         */
        public long stopMicros() {
            return (System.nanoTime() - startTime) / NANO_TO_MICRO;
        }
    }

    /**
     * Timer key for grouping statistics.
     */
    public static class TimerKey {
        private final String exchange;
        private final String dataType;

        public TimerKey(String exchange, String dataType) {
            this.exchange = exchange;
            this.dataType = dataType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TimerKey timerKey = (TimerKey) o;
            return exchange.equals(timerKey.exchange) && dataType.equals(timerKey.dataType);
        }

        @Override
        public int hashCode() {
            return 31 * exchange.hashCode() + dataType.hashCode();
        }

        @Override
        public String toString() {
            return exchange + "/" + dataType;
        }

        public String getExchange() {
            return exchange;
        }

        public String getDataType() {
            return dataType;
        }
    }

    /**
     * Statistics for a timer key.
     */
    public static class TimerStats {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalNanos = new AtomicLong(0);
        private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxNanos = new AtomicLong(0);

        void record(long nanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(nanos);
            updateMin(nanos);
            updateMax(nanos);
        }

        private void updateMin(long nanos) {
            while (true) {
                long current = minNanos.get();
                if (nanos >= current) break;
                if (minNanos.compareAndSet(current, nanos)) break;
            }
        }

        private void updateMax(long nanos) {
            while (true) {
                long current = maxNanos.get();
                if (nanos <= current) break;
                if (maxNanos.compareAndSet(current, nanos)) break;
            }
        }

        public long getCount() {
            return count.get();
        }

        public long getTotalMicros() {
            return totalNanos.get() / NANO_TO_MICRO;
        }

        public double getAvgMicros() {
            long c = count.get();
            return c > 0 ? (double) totalNanos.get() / c / NANO_TO_MICRO : 0.0;
        }

        public long getMinMicros() {
            long m = minNanos.get();
            return m == Long.MAX_VALUE ? 0 : m / NANO_TO_MICRO;
        }

        public long getMaxMicros() {
            return maxNanos.get() / NANO_TO_MICRO;
        }

        public long getTotalNanos() {
            return totalNanos.get();
        }

        public double getAvgNanos() {
            long c = count.get();
            return c > 0 ? (double) totalNanos.get() / c : 0.0;
        }

        public long getMinNanos() {
            long m = minNanos.get();
            return m == Long.MAX_VALUE ? 0 : m;
        }

        public long getMaxNanos() {
            return maxNanos.get();
        }
    }
}
