package com.togethermarket.reservation.service;

import com.togethermarket.reservation.repository.ReservationRepository;
import java.time.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(
    name = "reservation.expiry-scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReservationExpiryScheduler {
  private static final Logger log = LoggerFactory.getLogger(ReservationExpiryScheduler.class);
  private final ReservationRepository reservations;
  private final ReservationExpiryWorker worker;
  private final Clock clock;
  private final int batchSize;

  public ReservationExpiryScheduler(
      ReservationRepository reservations,
      ReservationExpiryWorker worker,
      @Qualifier("reservationClock") Clock clock,
      @Value("${reservation.expiry-scheduler.batch-size:100}") int batchSize) {
    if (batchSize < 1 || batchSize > 1000)
      throw new IllegalArgumentException("expiry batch size must be 1..1000");
    this.reservations = reservations;
    this.worker = worker;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  @Scheduled(
      initialDelayString = "${reservation.expiry-scheduler.delay:PT30S}",
      fixedDelayString = "${reservation.expiry-scheduler.delay:PT30S}")
  public void sweep() {
    var now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
    for (Long id : reservations.findDueIds(now, PageRequest.of(0, batchSize)))
      try {
        worker.expire(id);
      } catch (TransientDataAccessException ex) {
        log.warn("Reservation expiry deferred due to a concurrent transaction: {}", id);
      }
  }
}
