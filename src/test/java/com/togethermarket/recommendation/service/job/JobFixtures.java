package com.togethermarket.recommendation.service.job;

import com.togethermarket.recommendation.service.HybridRecommendationService.RankedCandidate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

final class JobFixtures {
  static final Instant START = Instant.parse("2026-11-13T09:00:00Z");
  static final Duration TIMEOUT = Duration.ofSeconds(40);

  static final class MutableClock extends Clock {
    final AtomicReference<Instant> time = new AtomicReference<>(START);

    @Override
    public Instant instant() {
      return time.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    void advance(Duration duration) {
      time.updateAndGet(t -> t.plus(duration));
    }
  }

  static final class QueuedExecutor implements Executor {
    final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    void runNext() {
      tasks.remove().run();
    }
  }

  /** Atomic reference implementation FOR TESTS ONLY. Never use as production durable storage. */
  static final class MemoryStore implements RecommendationJobStore {
    final Map<Long, RecommendationJobState> states = new HashMap<>();
    final Map<Long, List<RankedCandidate>> results = new HashMap<>();
    int commits;
    boolean failWrite;
    boolean failCompletion;
    boolean loseCommitAcknowledgment;
    boolean failSweep;

    synchronized void add(long id) {
      states.put(id, RecommendationJobState.pending(id, START));
    }

    synchronized RecommendationJobState state(long id) {
      return states.get(id);
    }

    synchronized List<RankedCandidate> result(long id) {
      return results.get(id);
    }

    @Override
    public synchronized Optional<Lease> claim(Long id, UUID token, Instant now, Duration timeout) {
      var before = states.get(id);
      if (before == null) return Optional.empty();
      var after = before.claim(token, now, timeout);
      states.put(id, after);
      return after.owns(token) && before.claimToken() == null
          ? Optional.of(new Lease(id, token, after.deadline()))
          : Optional.empty();
    }

    @Override
    public synchronized boolean complete(Lease lease, List<RankedCandidate> result, Instant now) {
      if (failCompletion) throw new IllegalStateException("Simulated rolled-back insert");
      var before = states.get(lease.requestId());
      var after = before.complete(lease.token(), now);
      boolean completed = before.owns(lease.token()) && after.status().equals("READY");
      if (completed) {
        results.put(lease.requestId(), List.copyOf(result));
        commits++;
      }
      states.put(lease.requestId(), after);
      if (loseCommitAcknowledgment)
        throw new IllegalStateException("Simulated lost commit acknowledgment");
      return completed;
    }

    @Override
    public synchronized boolean fail(Lease lease, RecommendationFailureCode code, Instant now) {
      if (failWrite) throw new IllegalStateException("Simulated DB outage");
      var before = states.get(lease.requestId());
      var after = before.fail(lease.token(), code, now);
      states.put(lease.requestId(), after);
      return !before.equals(after);
    }

    @Override
    public synchronized int expire(Instant now, Duration timeout, int limit) {
      if (failSweep) throw new IllegalStateException("Simulated DB outage");
      var expired =
          states.values().stream()
              .filter(s -> !s.expire(now, timeout).equals(s))
              .sorted(
                  Comparator.comparing(RecommendationJobState::createdAt)
                      .thenComparing(RecommendationJobState::requestId))
              .limit(limit)
              .toList();
      expired.forEach(s -> states.put(s.requestId(), s.expire(now, timeout)));
      return expired.size();
    }
  }
}
