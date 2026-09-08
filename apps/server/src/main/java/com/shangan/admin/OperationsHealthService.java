package com.shangan.admin;

import com.shangan.common.integration.IntegrationSettingsProvider;
import com.shangan.media.emby.EmbyHealthService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 汇总管理后台所需的只读运行状态。
 *
 * <p>严格只返回路径、文件大小、依赖状态与配置布尔值，不返回任何密钥、Token、远端 URL 或第三方响应正文。 V2 的课程同步与催办扫描状态在对应模块落地后再接入（见实施计划 T17）。
 */
@Service
public class OperationsHealthService {

  private static final String SQLITE_PREFIX = "jdbc:sqlite:";

  private final String datasourceUrl;
  private final EmbyHealthService emby;
  private final IntegrationSettingsProvider settings;

  public OperationsHealthService(
      @Value("${spring.datasource.url}") String datasourceUrl,
      EmbyHealthService emby,
      IntegrationSettingsProvider settings) {
    this.datasourceUrl = datasourceUrl;
    this.emby = emby;
    this.settings = settings;
  }

  /** 读取数据库文件与依赖状态；文件大小读取失败时安全降级为 0。 */
  public Snapshot snapshot() {
    Path database = databasePath();
    // 只探测一次，同时取出「是否可用」与展示文案，避免页面每次刷新打两次 Emby。
    EmbyHealthService.Probe probe = emby.probe();
    return new Snapshot(
        fileSize(database),
        fileSize(Path.of(database + "-wal")),
        probe.status(),
        probe.ok(),
        settings.current().emby().configured(),
        probe.latencyMs());
  }

  private Path databasePath() {
    String configuredPath =
        datasourceUrl.startsWith(SQLITE_PREFIX)
            ? datasourceUrl.substring(SQLITE_PREFIX.length())
            : datasourceUrl;
    return Path.of(configuredPath).toAbsolutePath().normalize();
  }

  private long fileSize(Path path) {
    try {
      return Files.exists(path) ? Files.size(path) : 0;
    } catch (java.io.IOException exception) {
      return 0;
    }
  }

  /**
   * 后台健康快照，字段全部为可安全渲染的运行状态。
   *
   * <p>安全边界：这里刻意不包含数据库文件路径。绝对路径会暴露部署目录结构， 属于既不该出现在页面、也不该出现在 API 响应里的信息；页面只需要文件体积。
   *
   * <p>{@code embyOk} 与 {@code embyConfigured} 是两件事：未配置是中性状态， 已配置但探测失败才是故障。页面据此区分中性态与红色异常态，不靠解析
   * {@code embyStatus} 文案。
   *
   * <p>{@code embyLatencyMs} 是本次 System/Info 探测的往返耗时（原型 8-1 的「可用 · 128ms」）。 未配置时探测不会真正发出请求，此时该值为
   * 0，页面必须据 {@code embyConfigured} 判断而不是直接渲染 0ms。
   */
  public record Snapshot(
      long databaseSizeBytes,
      long walSizeBytes,
      String embyStatus,
      boolean embyOk,
      boolean embyConfigured,
      long embyLatencyMs) {}
}
