package com.togethermarket.reservation.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.togethermarket.reservation.repository.ReservationRepository;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Pageable;

class ReservationExpirySchedulerTest {
  @Test
  void lockedReservationDoesNotPreventOtherExpirations() {
    var reservations = mock(ReservationRepository.class);
    var worker = mock(ReservationExpiryWorker.class);
    var clock = Clock.fixed(Instant.parse("2026-11-20T01:00:00Z"), ZoneOffset.UTC);
    when(reservations.findDueIds(any(), any())).thenReturn(List.of(1L, 2L));
    when(worker.expire(1L)).thenThrow(new CannotAcquireLockException("busy"));
    new ReservationExpiryScheduler(reservations, worker, clock, 100).sweep();
    verify(worker).expire(2L);
    verify(reservations)
        .findDueIds(
            eq(LocalDateTime.of(2026, 11, 20, 10, 0)),
            argThat((Pageable p) -> p.getPageSize() == 100));
  }

  @Test
  void configuredSchedulerRegistersItsTaskAndCanBeDisabled() {
    var context =
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(ReservationExpiryScheduler.class)
            .withPropertyValues("reservation.expiry-scheduler.delay=PT1H")
            .withBean(ReservationRepository.class, () -> mock(ReservationRepository.class))
            .withBean(ReservationExpiryWorker.class, () -> mock(ReservationExpiryWorker.class))
            .withBean(Clock.class, Clock::systemUTC);
    context.run(
        c -> {
          org.junit.jupiter.api.Assertions.assertNull(c.getStartupFailure());
          org.junit.jupiter.api.Assertions.assertEquals(
              1,
              c.getBean(org.springframework.scheduling.config.ScheduledTaskHolder.class)
                  .getScheduledTasks()
                  .size());
        });
    context
        .withPropertyValues("reservation.expiry-scheduler.enabled=false")
        .run(
            c ->
                org.junit.jupiter.api.Assertions.assertTrue(
                    c.getBeansOfType(ReservationExpiryScheduler.class).isEmpty()));
  }
}
