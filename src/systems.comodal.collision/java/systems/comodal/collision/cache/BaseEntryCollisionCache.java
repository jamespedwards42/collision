package systems.comodal.collision.cache;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

/**
 * @param <K> the type of keys used to map to values
 * @param <L> the type of loaded values before being mapped to type V
 * @param <V> the type of mapped values
 * @author James P. Edwards
 */
abstract class BaseEntryCollisionCache<K, L, V> extends AtomicLogCounters
    implements LoadingCollisionCache<K, L, V> {

  static final VarHandle COLLISIONS = MethodHandles.arrayElementVarHandle(Object[].class);

  final int maxCollisionsShift;
  final KeyVal<K, V>[][] hashTable;
  final int mask;
  final ToIntFunction<K> hashCoder;
  private final Function<K, L> loader;
  private final BiFunction<K, L, V> mapper;
  private final Function<K, V> loadAndMap;

  BaseEntryCollisionCache(
      final int maxCollisionsShift,
      final byte[] counters,
      final int initCount,
      final int pow2LogFactor,
      final KeyVal<K, V>[][] hashTable,
      final ToIntFunction<K> hashCoder,
      final Function<K, L> loader,
      final BiFunction<K, L, V> mapper) {
    super(counters, initCount, pow2LogFactor);
    this.maxCollisionsShift = maxCollisionsShift;
    this.hashTable = hashTable;
    this.mask = hashTable.length - 1;
    this.hashCoder = hashCoder;
    this.loader = loader;
    this.mapper = mapper;
    this.loadAndMap = key -> {
      final L loaded = loader.apply(key);
      return loaded == null ? null : mapper.apply(key, loaded);
    };
  }

  /**
   * CAS initialize an array for holding values at a given hash location.
   *
   * @param hash The hash table index.
   * @return The hash bucket array, referred to as collisions.
   */
  @SuppressWarnings("unchecked")
  final KeyVal<K, V>[] getCreateCollisions(final int hash) {
    KeyVal<K, V>[] collisions = hashTable[hash];
    if (collisions == null) {
      collisions = (KeyVal<K, V>[]) Array.newInstance(KeyVal.class, 1 << maxCollisionsShift);
      final Object witness = COLLISIONS.compareAndExchange(hashTable, hash, null, collisions);
      return witness == null ? collisions : (KeyVal<K, V>[]) witness;
    }
    return collisions;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final V getAggressive(final K key) {
    return getAggressive(key, loader, mapper);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final V getAggressive(final K key, final Function<K, L> loader) {
    return getAggressive(key, loader, mapper);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final V get(final K key) {
    return get(key, loadAndMap);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("unchecked")
  public final V get(final K key, final Function<K, V> loadAndMap) {
    final int hash = hashCoder.applyAsInt(key) & mask;
    final KeyVal<K, V>[] collisions = getCreateCollisions(hash);
    final int counterOffset = hash << maxCollisionsShift;
    for (int index = 0; ; ) {
      final KeyVal<K, V> collision = (KeyVal<K, V>) COLLISIONS.getOpaque(collisions, index);
      if (collision == null) {
        return checkDecayAndSwap(counterOffset, collisions, key, loadAndMap);
      }
      if (key.equals(collision.key)) {
        atomicIncrement(counterOffset + index);
        return collision.val;
      }
      if (++index == collisions.length) {
        return checkDecayAndProbSwap(counterOffset, collisions, key, loadAndMap);
      }
    }
  }

  /**
   * Checks for an existing entry synchronized behind the current collision hash bucket using
   * opaque memory access semantics.  If an entry does not exist, a value is loaded and the
   * behavior will be in line with the method {@link #decayAndSwap decayAndSwap}
   *
   * @param counterOffset beginning counter array index corresponding to collision values.
   * @param collisions values sitting in a hash bucket.
   * @param key used for table hash and entry equality.
   * @param loadAndMap loads a new value to cache if missing.
   * @return a value for the corresponding key.
   */
  abstract V checkDecayAndSwap(final int counterOffset, final KeyVal<K, V>[] collisions,
      final K key, final Function<K, V> loadAndMap);

  /**
   * Checks for an existing entry synchronized behind the current collision hash bucket using
   * opaque memory access semantics.  The minimum count for each entry is proactively tracked for
   * swapping.  If an entry does not exist, a value is loaded and the behavior will be in line with
   * the method {@link #decayAndSwap decayAndSwap}
   *
   * @param counterOffset beginning counter array index corresponding to collision values.
   * @param collisions values sitting in a hash bucket.
   * @param key used for table hash and entry equality.
   * @param loadAndMap loads a new value to cache if missing.
   * @return a value for the corresponding key.
   */
  abstract V checkDecayAndProbSwap(final int counterOffset, final KeyVal<K, V>[] collisions,
      final K key, final Function<K, V> loadAndMap);

  /**
   * Divides all counters for values within a hash bucket (collisions), swaps the val for the
   * least frequently used, and sets its counter to an initial val.  Also evicts the tail entry if
   * its count is zero.
   *
   * @param counterOffset beginning counter array index corresponding to collision values.
   * @param maxCounterIndex Max counter index for known non null collision values.
   * @param collisions values sitting in a hash bucket.
   * @param entry The value to put in place of the least frequently used value.
   */
  final void decayAndSwap(final int counterOffset, final int maxCounterIndex,
      final KeyVal[] collisions, final KeyVal<K, V> entry) {
    int counterIndex = counterOffset;
    int minCounterIndex = counterOffset;
    int minCount = MAX_COUNT;
    do {
      int count = ((int) COUNTERS.getOpaque(counters, counterIndex)) & MAX_COUNT;
      if (count == 0) {
        COLLISIONS.setOpaque(collisions, counterIndex - counterOffset, entry);
        COUNTERS.setOpaque(counters, counterIndex, initCount);
        while (++counterIndex < maxCounterIndex) {
          count = ((int) COUNTERS.getOpaque(counters, counterIndex)) & MAX_COUNT;
          if (count == 0) {
            continue;
          }
          COUNTERS.setOpaque(counters, counterIndex, (byte) (count >> 1));
        }
        return;
      }
      // Counter misses may occur between these two calls.
      COUNTERS.setOpaque(counters, counterIndex, (byte) (count >> 1));
      if (count < minCount) {
        minCount = count;
        minCounterIndex = counterIndex;
      }
    } while (++counterIndex < maxCounterIndex);
    COLLISIONS.setOpaque(collisions, minCounterIndex - counterOffset, entry);
    COUNTERS.setOpaque(counters, minCounterIndex, initCount);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("unchecked")
  public final V getIfPresent(final K key) {
    final int hash = hashCoder.applyAsInt(key) & mask;
    final KeyVal<K, V>[] collisions = getCreateCollisions(hash);
    int index = 0;
    do {
      final KeyVal<K, V> entry = (KeyVal<K, V>) COLLISIONS.getOpaque(collisions, index);
      if (entry == null) {
        return null;
      }
      if (key.equals(entry.key)) {
        atomicIncrement((hash << maxCollisionsShift) + index);
        return entry.val;
      }
    } while (++index < collisions.length);
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("unchecked")
  public final V replace(final K key, final V val) {
    final KeyVal<K, V>[] collisions = getCreateCollisions(hashCoder.applyAsInt(key) & mask);
    int index = 0;
    do {
      final KeyVal<K, V> entry = (KeyVal<K, V>) COLLISIONS.getOpaque(collisions, index);
      if (entry == null) {
        return null;
      }
      if (entry.val == val) {
        return val;
      }
      if (key.equals(entry.key)) {
        final KeyVal<K, V> witness = (KeyVal<K, V>) COLLISIONS
            .compareAndExchange(collisions, index, entry, new KeyVal(key, val));
        if (witness == entry) {
          return val;
        }
        if (key.equals(witness.key)) {
          return witness.val; // If another thread raced to PUT, let it win.
        }
      }
    } while (++index < collisions.length);
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clear() {
    IntStream.range(0, hashTable.length)
        .parallel()
        .forEach(i -> {
          final KeyVal[] collisions = hashTable[i];
          if (collisions == null) {
            return;
          }
          int index = 0;
          do {
            COLLISIONS.setOpaque(collisions, index++, null);
          } while (index < collisions.length);
        });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void nullBuckets() {
    IntStream.range(0, hashTable.length).parallel().forEach(i -> hashTable[i] = null);
  }

  @Override
  public String toString() {
    return "maxCollisions=" + (1 << maxCollisionsShift)
        + ", numCounters=" + counters.length
        + ", initCount=" + initCount
        + ", hashTableLength=" + hashTable.length;
  }
}
