package io.orch8.sdk.worker;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Cooperative cancellation signal handed to handlers. Set on lease loss, local
 * timeout or forced shutdown. The handler thread is also interrupted.
 */
public final class CancellationToken {
  private final CountDownLatch latch = new CountDownLatch(1);
  private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
  private volatile CancellationReason reason;

  CancellationToken() {}

  public boolean isCancelled() {
    return reason != null;
  }

  /** The reason, or {@code null} while not cancelled. */
  public CancellationReason reason() {
    return reason;
  }

  /** Throws {@link TaskCancelledException} when cancelled. */
  public void throwIfCancelled() {
    CancellationReason r = reason;
    if (r != null) {
      throw new TaskCancelledException(r);
    }
  }

  /**
   * Sleeps up to {@code millis}, returning early on cancellation or interrupt.
   *
   * @return {@code true} if the full duration elapsed, {@code false} if cancelled
   */
  public boolean sleep(long millis) {
    try {
      return !latch.await(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public boolean sleep(Duration duration) {
    return sleep(duration.toMillis());
  }

  /** Runs {@code callback} once on cancellation (immediately if already cancelled). */
  public void onCancel(Runnable callback) {
    callbacks.add(callback);
    if (isCancelled() && callbacks.remove(callback)) {
      callback.run();
    }
  }

  synchronized boolean cancel(CancellationReason r) {
    if (reason != null) {
      return false;
    }
    reason = r;
    latch.countDown();
    for (Runnable cb : callbacks) {
      if (callbacks.remove(cb)) {
        try {
          cb.run();
        } catch (RuntimeException ignored) {
          // callbacks must not break cancellation
        }
      }
    }
    return true;
  }
}
