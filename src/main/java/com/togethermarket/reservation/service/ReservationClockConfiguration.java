package com.togethermarket.reservation.service;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class ReservationClockConfiguration {
  @Bean
  Clock reservationClock() {
    return Clock.systemUTC();
  }
}
