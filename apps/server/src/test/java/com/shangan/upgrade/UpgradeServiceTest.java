package com.shangan.upgrade;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangan.common.api.BusinessException;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 只验证升级文件协议，不启动 SQLite/Flyway 或容器。 */
class UpgradeServiceTest {
  @TempDir Path root;
  final Clock clock = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);

  UpgradeService service() {
    return new UpgradeService(new ObjectMapper(), clock, root.toString(), "2.2.0");
  }

  @Test
  void 未连接升级器不接受任务() {
    assertThatThrownBy(() -> service().submit("APPLY"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("尚未就绪");
    assertThat(Files.exists(root.resolve("request.json"))).isFalse();
  }

  @Test
  void 只投影安全字段且重复任务被拒绝() throws Exception {
    Files.writeString(root.resolve("heartbeat.json"), "{\"at\":\"2026-09-09T00:00:00Z\"}");
    Files.writeString(
        root.resolve("status.json"),
        "{\"phase\":\"READY\",\"Env\":\"secret\",\"currentVersion\":\"伪造版本\"}");
    var service = service();
    assertThat(service.status())
        .containsEntry("currentVersion", "2.2.0")
        .containsEntry("phase", "READY")
        .doesNotContainKey("Env");
    service.submit("APPLY");
    assertThat(Files.readString(root.resolve("request.json"))).contains("APPLY");
    assertThatThrownBy(() -> service.submit("APPLY"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已有升级任务");
    assertThatThrownBy(() -> service.submit("rm -rf")).isInstanceOf(BusinessException.class);
  }

  @Test
  void 时区时间校验及维护状态() throws Exception {
    Files.writeString(root.resolve("heartbeat.json"), "{\"at\":\"2026-09-09T00:00:00Z\"}");
    var service = service();
    assertThatThrownBy(() -> service.configure(true, "25:00", "Asia/Shanghai"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.configure(true, "03:00", "wrong"))
        .isInstanceOf(BusinessException.class);
    service.configure(true, "03:00", "Asia/Shanghai");
    assertThat(Files.readString(root.resolve("config.json"))).contains("Asia/Shanghai");
    assertThat(service.maintenance()).isFalse();
    Files.createFile(root.resolve("maintenance"));
    assertThat(service.maintenance()).isTrue();
  }
}
