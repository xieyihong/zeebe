/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.logstreams.impl.log;

import static io.camunda.zeebe.logstreams.impl.serializer.DataFrameDescriptor.FRAME_ALIGNMENT;

import io.camunda.zeebe.logstreams.impl.serializer.DataFrameDescriptor;
import io.camunda.zeebe.logstreams.log.LogAppendEntry;
import io.camunda.zeebe.logstreams.log.LogStreamBatchWriter;
import io.camunda.zeebe.scheduler.ActorCondition;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sequencer is a multiple-producer, single-consumer queue of {@link LogAppendEntry}. It buffers
 * a fixed amount of entries and rejects writes when the queue is full. The consumer may read at its
 * own pace by repeatedly calling {@link Sequencer#tryRead()} or register for notifications when new
 * entries are written by calling {@link Sequencer#registerConsumer(ActorCondition)}.
 *
 * <p>The sequencer assigns all entries a position and makes that position available to its
 * consumer. The sequencer does not copy or serialize entries, it only keeps a reference to them
 * until they are handed off to the consumer.
 */
public final class Sequencer implements LogStreamBatchWriter, Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(Sequencer.class);
  private final int partitionId;
  private final int maxFragmentSize;

  private volatile long position;
  private volatile boolean isClosed = false;
  private volatile ActorCondition consumer;
  private final Queue<SequencedBatch> queue = new ArrayBlockingQueue<>(128);
  private final ReentrantLock lock = new ReentrantLock();
  private final SequencerMetrics metrics;

  public Sequencer(final int partitionId, final long initialPosition, final int maxFragmentSize) {
    LOG.trace("Starting new sequencer at position {}", initialPosition);
    this.position = initialPosition;
    this.partitionId = partitionId;
    this.maxFragmentSize = maxFragmentSize;
    this.metrics = new SequencerMetrics(partitionId);
  }

  /**
   * @param eventCount the potential event count we want to check
   * @param batchSize the potential batch Size (in bytes) we want to check
   * @return True if the serialized batch would fit within {@link Sequencer#maxFragmentSize}, false
   *     otherwise.
   */
  @Override
  public boolean canWriteEvents(final int eventCount, final int batchSize) {
    final int framedMessageLength =
        batchSize
            + eventCount * (DataFrameDescriptor.HEADER_LENGTH + FRAME_ALIGNMENT)
            + FRAME_ALIGNMENT;
    return framedMessageLength <= maxFragmentSize;
  }

  /**
   * @param appendEntry the entry to write
   * @param sourcePosition a back-pointer to the record whose processing created this entry
   * @return -1 if write was rejected, the position of the entry if write was successful.
   */
  @Override
  public long tryWrite(final LogAppendEntry appendEntry, final long sourcePosition) {
    if (isClosed) {
      LOG.warn("Rejecting write of {}, sequencer is closed", appendEntry);
      return -1;
    }
    lock.lock();
    try {
      final var currentPosition = position;
      final var isEnqueued =
          queue.offer(new SequencedBatch(currentPosition, sourcePosition, List.of(appendEntry)));
      if (isEnqueued) {
        if (consumer != null) {
          consumer.signal();
        }
        metrics.observeBatchSize(1);

        position = currentPosition + 1;
        return currentPosition;
      } else {
        LOG.trace("Rejecting write of {}, sequencer queue is full", appendEntry);
        return -1;
      }
    } finally {
      metrics.setQueueSize(queue.size());
      lock.unlock();
    }
  }

  /**
   * @param appendEntries a set of entries to append; these will be appended in the order in which
   *     the collection is iterated.
   * @param sourcePosition a back-pointer to the record whose processing created these entries
   * @return -1 if write was rejected, 0 if batch was empty, the highest position of the batch if
   *     write was successful.
   */
  @Override
  public long tryWrite(
      final Iterable<? extends LogAppendEntry> appendEntries, final long sourcePosition) {
    if (isClosed) {
      LOG.warn("Rejecting write of {}, sequencer is closed", appendEntries);
      return -1;
    }

    final var entries = new ArrayList<LogAppendEntry>();
    for (final var entry : appendEntries) {
      entries.add(entry);
    }
    final var batchSize = entries.size();
    if (batchSize == 0) {
      return 0;
    }

    lock.lock();
    try {
      final var firstPosition = position;
      final var isEnqueued =
          queue.offer(new SequencedBatch(firstPosition, sourcePosition, entries));
      if (isEnqueued) {
        if (consumer != null) {
          consumer.signal();
        }
        metrics.observeBatchSize(batchSize);

        final var nextPosition = firstPosition + batchSize;
        position = nextPosition;
        return nextPosition - 1;
      } else {
        LOG.trace("Rejecting write of {}, sequencer queue is full", entries);
        if (consumer != null) {
          consumer.signal();
        }
        return -1;
      }
    } finally {
      metrics.setQueueSize(queue.size());
      lock.unlock();
    }
  }

  /**
   * Tries to read a {@link SequencedBatch} from the queue.
   *
   * @return A {@link SequencedBatch} or null if none is available.
   */
  public SequencedBatch tryRead() {
    return queue.poll();
  }

  public SequencedBatch peek() {
    return queue.peek();
  }

  /**
   * Closes the sequencer. After closing, writes are rejected but reads are still allowed to drain
   * the queue. Closing the sequencer is not atomic so some writes may occur shortly after closing.
   */
  @Override
  public void close() {
    LOG.info("Closing sequencer for writing");
    isClosed = true;
  }

  /**
   * @return true if the sequencer is closed for writing.
   */
  public boolean isClosed() {
    return isClosed;
  }

  public void registerConsumer(final ActorCondition consumer) {
    this.consumer = consumer;
    consumer.signal();
  }

  public record SequencedBatch(
      long firstPosition, long sourcePosition, List<LogAppendEntry> entries) {}
}
