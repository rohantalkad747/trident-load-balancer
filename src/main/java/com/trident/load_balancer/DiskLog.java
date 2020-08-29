package com.trident.load_balancer;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.Builder;
import lombok.Data;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.Serializable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Builder
public class DiskLog<V extends Serializable> {

    private final SegmentFactory<V> segmentFactory;

    private final AtomicInteger activeSegIndex = new AtomicInteger();

    private final List<Segment<V>> segments = Lists.newArrayList();

    private final ScheduledExecutorService compaction = Executors.newSingleThreadScheduledExecutor();

    public DiskLog(SegmentFactory<V> segmentFactory, Duration compactionInterval) {
        this.segmentFactory = segmentFactory;
        long compactionIntervalMs = compactionInterval.get(ChronoUnit.MILLIS);
        compaction.scheduleAtFixedRate(
                this::performCompaction,
                compactionIntervalMs,
                compactionIntervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    Segment<V> nextSeg() {
        return segments.get(activeSegIndex.incrementAndGet());
    }

    /**
     * Appends the given key-value pair to the log synchronously.
     */
    public void append(String key, V val) {
        Segment<V> maybeWritableSegment = segments.get(activeSegIndex.get());
        boolean appended = tryAppendToAnExistingSegment(key, val, maybeWritableSegment);
        if (!appended) {
            appendToNewSegment(key, val);
        }
    }

    private boolean tryAppendToAnExistingSegment(String key, V val, Segment<V> maybeWritableSegment) {
        for (; activeSegIndex.get() < segments.size(); maybeWritableSegment = nextSeg()) {
            if (maybeWritableSegment.appendValue(key, val)) {
                return true;
            }
        }
        return false;
    }

    private void appendToNewSegment(String key, V val) {
        Segment<V> newSegment = segmentFactory.newInstance();
        segments.add(newSegment);
        activeSegIndex.incrementAndGet();
        newSegment.appendValue(key, val);
    }

    private void performCompaction() {
        Map<String, Record<V>> recordMap = loadRecords();
        mergeSegmentRecords(recordMap);
        deleteBackingSegments();
    }

    private void deleteBackingSegments() {
        for (Segment<V> segment : segments) {
            segment.deleteBackingFile();
        }
    }

    private void mergeSegmentRecords(Map<String, Record<V>> recordMap) {
        Segment<V> currentSegment = segmentFactory.newInstance();
        segments.clear();
        segments.add(currentSegment);
        for (Record<V> record : recordMap.values()) {
            if (!record.isTombstone() && !currentSegment.appendRecord(record)) {
                currentSegment = segmentFactory.newInstance();
                segments.add(currentSegment);
            }
        }
        activeSegIndex.set(segments.size() - 1);
    }

    @NonNull
    private Map<String, Record<V>> loadRecords()
    {
        Map<String, Record<V>> recordMap = Maps.newHashMap();
        for (Segment<V> segment : segments) {
            Iterator<Record<V>> records = segment.getRecords();
            while (records.hasNext()) {
                Record<V> curRecord = records.next();
                Record<V> maybePreviousRecord = recordMap.get(curRecord.getKey());
                if (isLatestRecord(curRecord, maybePreviousRecord)) {
                    recordMap.put(curRecord.getKey(), curRecord);
                }
            }
            segment.markNotWritable();
        }
        return recordMap;
    }

    private boolean isLatestRecord(Record<V> curRecord, Record<V> maybePreviousRecord) {
        return maybePreviousRecord == null || curRecord.getAppendTime() > maybePreviousRecord.getAppendTime();
    }

    @Data
    public static class Record<V> {
        private final String key;

        private int offset;

        private int length;

        private long appendTime;

        private V val;

        /**
         * @return true if this the k-v pair is to be deleted.
         */
        public boolean isTombstone() {
            return val == null;
        }
    }
}
