package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 保护已执行的 V008 原始资源字节，防止空白清理破坏历史校验；不启动数据库。 */
class V008ChecksumTest {
  @Test
  void 已执行迁移的校验和必须保持不变() {
    assertThat(new V008__nag_cancel_and_admin_actions().getChecksum()).isEqualTo(1718239079);
  }
}
