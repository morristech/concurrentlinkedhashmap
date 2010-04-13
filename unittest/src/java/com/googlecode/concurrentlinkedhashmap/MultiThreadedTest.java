package com.googlecode.concurrentlinkedhashmap;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap.Builder;
import static com.googlecode.concurrentlinkedhashmap.ConcurrentTestHarness.timeTasks;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A unit-test to assert basic concurrency characteristics by validating the
 * internal state after load.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class MultiThreadedTest extends BaseTest {
  private Queue<String> failures;
  private List<Integer> keys;
  private int timeout = 180;
  private int nThreads;

  public MultiThreadedTest() {
    super(Integer.valueOf(System.getProperty("multiThreaded.maximumCapacity")));
  }

  @BeforeClass(alwaysRun = true)
  public void beforeMultiThreaded() {
    int iterations = Integer.valueOf(System.getProperty("multiThreaded.iterations"));
    nThreads = Integer.valueOf(System.getProperty("multiThreaded.nThreads"));
    timeout = Integer.valueOf(System.getProperty("multiThreaded.timeout"));
    failures = new ConcurrentLinkedQueue<String>();
    keys = new ArrayList<Integer>();
    Random random = new Random();
    for (int i = 0; i < iterations; i++) {
      keys.add(random.nextInt(iterations / 100));
    }
  }

  /**
   * Tests that the cache is in the correct test after a read-write load.
   */
  @Test(groups = "development")
  public void concurrent() throws InterruptedException {
    debug(" * concurrent: START");
    final List<List<Integer>> sets = shuffle(nThreads, keys);
    ThreadPoolExecutor es =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                               new LinkedBlockingQueue<Runnable>());
    final ConcurrentLinkedHashMap<Integer, Integer> cache =
        new Builder<Integer, Integer>()
            .maximumWeightedCapacity(capacity)
            .concurrencyLevel(nThreads)
            .build();
    Future<Long> future = es.submit(new Callable<Long>() {
      @Override
      public Long call() throws Exception {
        return timeTasks(nThreads, new Thrasher(cache, sets));
      }
    });
    try {
      long timeNS = future.get(timeout, TimeUnit.SECONDS);
      debug("\nExecuted in %d second(s)", TimeUnit.NANOSECONDS.toSeconds(timeNS));
      validator.state(cache);
    } catch (ExecutionException e) {
      fail("Exception during test: " + e.toString(), e);
    } catch (TimeoutException e) {
      for (StackTraceElement[] trace : Thread.getAllStackTraces().values()) {
        for (StackTraceElement element : trace) {
          System.out.println("\tat " + element);
        }
        if (trace.length > 0) {
          System.out.println("------");
        }
      }
      es.shutdownNow();
      while (es.getPoolSize() > 0) { /* spin until terminated */ }

      // Print the state of the cache
      debug("Cached Elements: %s", cache.toString());
      debug("List Forward:\n%s", listForwardToString(cache));
      debug("List Backward:\n%s", listBackwardsToString(cache));

      // Print the recorded failures
      for (String failure : failures) {
        debug(failure);
      }
      fail("Spun forever", e);
    }
    debug("concurrent: END");
  }

  /**
   * Based on the passed in working set, creates N shuffled variants.
   *
   * @param samples the number of variants to create
   * @param workingSet the base working set to build from
   */
  private <T> List<List<T>> shuffle(int samples, Collection<T> workingSet) {
    List<List<T>> sets = new ArrayList<List<T>>(samples);
    for (int i = 0; i < samples; i++) {
      List<T> set = new ArrayList<T>(workingSet);
      Collections.shuffle(set);
      sets.add(set);
    }
    return sets;
  }

  /**
   * Executes operations against the cache to simulate random load.
   */
  private final class Thrasher implements Runnable {
    private static final int OPERATIONS = 19;

    private final ConcurrentLinkedHashMap<Integer, Integer> cache;
    private final List<List<Integer>> sets;
    private final AtomicInteger index;

    public Thrasher(ConcurrentLinkedHashMap<Integer, Integer> cache, List<List<Integer>> sets) {
      this.index = new AtomicInteger();
      this.cache = cache;
      this.sets = sets;
    }

    public void run() {
      int id = index.getAndIncrement();
      debug("#%d: STARTING", id);
      for (Integer key : sets.get(id)) {
        try {
          execute(key);
        } catch (RuntimeException e) {
          String error =
              String.format("Failed: key %s on operation %d for node %s", key, key % OPERATIONS,
                            nodeToString(findNode(key, cache)));
          failures.add(error);
          throw e;
        } catch (Throwable thr) {
          String error =
              String.format("Halted: key %s on operation %d for node %s", key, key % OPERATIONS,
                            nodeToString(findNode(key, cache)));
          failures.add(error);
        }
      }
      debug("#%d: ENDING", id);
    }

    /** Hashes the key to a public operation. */
    private void execute(int key) {
      switch (key % OPERATIONS) {
        case 0:
          cache.containsKey(key);
          break;
        case 1:
          cache.containsValue(key);
          break;
        case 2:
          cache.isEmpty();
          break;
        case 3:
          if (cache.size() < 0) {
            throw new IllegalStateException();
          }
          if (cache.weightedSize() < 0) {
            throw new IllegalStateException();
          }
          break;
        case 4:
          cache.get(key);
          break;
        case 5:
          cache.put(key, key);
          break;
        case 6:
          cache.putIfAbsent(key, key);
          break;
        case 7:
          cache.remove(key);
          break;
        case 8:
          cache.remove(key, key);
          break;
        case 9:
          cache.replace(key, key);
          break;
        case 10:
          cache.replace(key, key, key);
          break;
        case 11:
          cache.clear();
          break;
        case 12:
          if ((key % 2) == 0) {
            for (Integer i : cache.keySet()) {
              if (i == null) {
                throw new IllegalArgumentException();
              }
            }
          } else {
            for (Integer i : cache.keySet().toArray(new Integer[cache.size()])) {
              // spin
            }
          }
          break;
        case 13:
          if ((key % 2) == 0) {
            for (Integer i : cache.values()) {
              if (i == null) {
                throw new IllegalArgumentException();
              }
            }
          } else {
            for (Integer i : cache.values().toArray(new Integer[cache.size()])) {
              // spin
            }
          }
          break;
        case 14:
          if ((key % 2) == 0) {
            for (Entry<Integer, Integer> entry : cache.entrySet()) {
              if ((entry == null) || (entry.getKey() == null) || (entry.getValue() == null)) {
                throw new IllegalArgumentException(String.valueOf(entry));
              }
            }
          } else {
            for (Entry<?, ?> entry : cache.entrySet().toArray(new Entry[cache.size()])) {
              // spin
            }
          }
          break;
        case 15:
          cache.hashCode();
          break;
        case 16:
          cache.equals(cache);
          break;
        case 17:
          cache.toString();
          break;
        case 18:
          cache.setCapacity(cache.capacity());
          break;
        default:
          throw new IllegalArgumentException();
      }
    }
  }
}