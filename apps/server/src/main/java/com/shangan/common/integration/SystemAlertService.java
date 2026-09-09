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

  /** 手动测试使用已保存的系统配置，不受自动异常开关限制、不写入故障去重状态。 */
  public TestNotificationResult testNotification() {
    var config = settings.current().bark();
    if (!config.configured()) {
      return new TestNotificationResult(false, "未配置", "请先保存系统 Bark 服务地址和设备 Key，再发送测试通知。");
    }
    boolean sent =
        client.send(
            config.baseUrl(),
            config.deviceKey(),
            config.timeoutSeconds(),
            "上岸系统测试通知",
            "这是一条系统 Bark 测试通知，用于确认系统异常通知的接收设备。");
    return sent
        ? new TestNotificationResult(true, "已发送", "Bark 已接受测试通知，请在系统通知接收设备上确认。")
        : new TestNotificationResult(false, "发送失败", "测试通知发送失败，请检查已保存的系统 Bark 配置、设备 Key 和网络连接。");
  }

  /** 稳定中文结果；不包含设备密钥、目的地或远端原始响应。 */
  public record TestNotificationResult(boolean ok, String status, String message) {}

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

  /** 新版本验收期间不发送外部通知。 */
  @org.springframework.beans.factory.annotation.Autowired
  private com.shangan.upgrade.UpgradeService upgrades;

  /** 备份脚本只写状态标志，服务端统一读取系统配置发送，脚本无需接触密钥。 */
  @Scheduled(fixedDelay = 60000)
  public void inspectBackup() {
    if (upgrades != null && upgrades.maintenance()) return;
    if (!Files.isRegularFile(backupState)) return;
    try {
      String state = Files.readString(backupState).trim();
      report("BACKUP", !"OK".equals(state), "数据库备份或完整性检查失败，请检查备份任务日志。");
    } catch (java.io.IOException ignored) {
      report("BACKUP", true, "无法读取备份执行状态，请检查备份目录权限。");
    }
  }
}
