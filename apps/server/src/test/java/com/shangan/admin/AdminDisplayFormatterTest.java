package com.shangan.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证管理后台日期和耗时使用统一、紧凑的展示格式。 */
class AdminDisplayFormatterTest {

  @Test
  void formatsUtcInstantAsStandardChinaDateTime() {
    assertThat(AdminDisplayFormatter.dateTime(Instant.parse("2026-08-31T15:04:33.507Z")))
        .isEqualTo("2026-08-31 23:04:33");
    assertThat(AdminDisplayFormatter.dateTime(null)).isEqualTo("—");
  }

  @Test
  void abbreviatesDurationWithMillisecondsSecondsMinutesAndHours() {
    assertThat(AdminDisplayFormatter.duration(null)).isEqualTo("—");
    assertThat(AdminDisplayFormatter.duration(507L)).isEqualTo("507 ms");
    assertThat(AdminDisplayFormatter.duration(1_500L)).isEqualTo("1.5 s");
    assertThat(AdminDisplayFormatter.duration(65_000L)).isEqualTo("1 m 5 s");
    assertThat(AdminDisplayFormatter.duration(3_720_000L)).isEqualTo("1 h 2 m");
  }
}
