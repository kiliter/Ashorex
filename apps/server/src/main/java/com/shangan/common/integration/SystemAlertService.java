package com.shangan.common.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 系统异常专用 Bark，按异常类型合并；不访问个人催办配置。 */
@Service
public class SystemAlertService {
  private final IntegrationSettingsProvider settings;
  private final Clock clock;
  private final BarkPushClient client;
  private final Path backupState;
  private final Map<String, Instant> attempted = new HashMap<>();
  private final java.util.Set<String> notified = new java.util.HashSet<>();

  public SystemAlertService(
      IntegrationSettingsProvider settings,
      Clock clock,
      BarkPushClient client,
      @Value("${DATA_DIR:./data}") String dataDir) {
    this.settings = settings;
    this.clock = clock;
    this.client = client;
    this.backupState = Path.of(dataDir, "backup-status");
  }

  /** 同一持续异常成功通知一次；发送失败十分钟后可重试，恢复后重新布防。 */
  public synchronized void report(String event, boolean failed, String message) {
    if (!failed) {
      notified.remove(event);
      attempted.remove(event);
      return;
    }
    var config = settings.current().bark();
    if (!config.enabled() || !config.configured() || notified.contains(event)) return;
    Instant now = clock.instant();
    if (attempted.containsKey(event)
        && now.isBefore(attempted.get(event).plus(Duration.ofMinutes(10)))) return;
    attempted.put(event, now);
    if (client.send(
        config.baseUrl(), config.deviceKey(), config.timeoutSeconds(), "上岸系统异常", message)) {
      notified.add(event);
    }
  }

  /** 备份脚本只写状态标志，服务端统一读取系统配置发送，脚本无需接触密钥。 */
  @Scheduled(fixedDelay = 60000)
  public void inspectBackup() {
    if (!Files.isRegularFile(backupState)) return;
    try {
      String state = Files.readString(backupState).trim();
      report("BACKUP", !"OK".equals(state), "数据库备份或完整性检查失败，请检查备份任务日志。");
    } catch (java.io.IOException ignored) {
      report("BACKUP", true, "无法读取备份执行状态，请检查备份目录权限。");
    }
  }
}
