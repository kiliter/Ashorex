package com.shangan.nag.application;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 催办扫描调度器。
 *
 * <p>单实例定时任务，固定 60 秒唤醒一次，再按生效策略的扫描周期决定是否真正扫描， 这样管理员改扫描周期后无需重启。
 */
@Component
public class NagScanScheduler {

  private final NagScanner scanner;
  private final NagPolicyResolver policies;
  private final java.time.Clock clock;
  private final AtomicReference<java.time.Instant> lastScanAt = new AtomicReference<>();

  public NagScanScheduler(NagScanner scanner, NagPolicyResolver policies, java.time.Clock clock) {
    this.scanner = scanner;
    this.policies = policies;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT30S")
  public void tick() {
    int intervalMinutes = policies.global().scanIntervalMinutes();
    java.time.Instant now = clock.instant();
    java.time.Instant previous = lastScanAt.get();
    if (previous != null
        && java.time.Duration.between(previous, now).toMinutes() < intervalMinutes) {
      return;
    }
    lastScanAt.set(now);
    scanner.scanOnce();
  }

  /** 最近一次扫描时间，供后台概览展示。 */
  public java.time.Instant lastScanAt() {
    return lastScanAt.get();
  }
}
