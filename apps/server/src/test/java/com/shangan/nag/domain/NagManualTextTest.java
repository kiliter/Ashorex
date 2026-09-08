package com.shangan.nag.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 手动标题的空值兼容与长度边界；按 Unicode 字符计数。 */
class NagManualTextTest {
  @Test
  void 空值回退且拒绝超长标题() {
    assertThat(Nag.manualText("  ", 80)).isNull();
    assertThat(Nag.manualText(" 标题 ", 80)).isEqualTo("标题");
    assertThat(Nag.manualText("学".repeat(80), 80)).hasSize(80);
    assertThatThrownBy(() -> Nag.manualText("学".repeat(81), 80)).hasMessageContaining("80");
    assertThatThrownBy(() -> Nag.manualText("学".repeat(1001), 1000)).hasMessageContaining("1000");
  }
}
