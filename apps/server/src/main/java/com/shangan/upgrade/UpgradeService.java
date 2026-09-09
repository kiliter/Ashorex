package com.shangan.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangan.common.api.BusinessException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 后台与独立升级器的文件协议；只投影安全字段，不执行 Docker 或返回部署凭据。 */
@Service
public class UpgradeService {
  private final Path root;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final String version;

  public UpgradeService(
      ObjectMapper mapper,
      Clock clock,
      @Value("${UPDATES_DIR:}") String directory,
      @Value("${SHANGAN_VERSION:dev}") String version) {
    this.mapper = mapper;
    this.clock = clock;
    this.root = directory.isBlank() ? null : Path.of(directory);
    this.version = version;
  }

  /** 未接入 updater 的本地/旧部署保持原有行为。 */
  public boolean maintenance() {
    return root != null && Files.exists(root.resolve("maintenance"));
  }

  /** 返回构建版本，维护探测只读且不访问业务数据。 */
  public String version() {
    return version;
  }

  /** 白名单投影避免把 operation.json 内的容器环境、路径和密钥返回后台。 */
  public Map<String, Object> status() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("enabled", root != null);
    result.put("currentVersion", version);
    result.put("maintenance", maintenance());
    result.put("available", available());
    result.put("config", config());
    var status = read("status");
    for (String key :
        List.of("phase", "message", "updatedAt", "latestVersion", "targetVersion", "notes")) {
      if (status.get(key) instanceof String text) result.put(key, text);
    }
    result.put(
        "busy",
        root != null
            && (Files.exists(root.resolve("request.json"))
                || Files.exists(root.resolve("operation.json"))
                || Files.exists(root.resolve("executing"))));
    return result;
  }

  /** 心跳有时间上限，升级器未启动时不接受无法执行的请求。 */
  private boolean available() {
    try {
      Object value = read("heartbeat").get("at");
      return value instanceof String text
          && Instant.parse(text).isAfter(clock.instant().minusSeconds(60));
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private Map<String, Object> config() {
    var saved = read("config");
    return Map.of(
        "automatic",
        Boolean.TRUE.equals(saved.get("automatic")),
        "time",
        saved.getOrDefault("time", "03:00"),
        "timezone",
        saved.getOrDefault("timezone", "Asia/Shanghai"));
  }

  /** 串行发布单一任务；只接受固定动作，禁止传入 URL、镜像、命令或文件路径。 */
  public synchronized void submit(String action) {
    if (!Set.of("CHECK", "DOWNLOAD", "APPLY").contains(action)) throw invalid("升级操作无效");
    requireReady();
    if (Files.exists(root.resolve("request.json"))
        || Files.exists(root.resolve("operation.json"))
        || Files.exists(root.resolve("executing"))) {
      throw new BusinessException(HttpStatus.CONFLICT, "UPGRADE_BUSY", "已有升级任务，请等待完成");
    }
    write("request", Map.of("action", action, "id", UUID.randomUUID().toString()));
  }

  /** 持久化定时策略；时间按明确的 IANA 时区计算，不依赖容器默认时区。 */
  public synchronized void configure(boolean automatic, String time, String timezone) {
    requireReady();
    if (time == null || !time.matches("([01]\\d|2[0-3]):[0-5]\\d")) throw invalid("时间格式应为 HH:mm");
    try {
      if (!ZoneId.getAvailableZoneIds().contains(timezone)) throw new IllegalArgumentException();
    } catch (RuntimeException error) {
      throw invalid("请选择有效的 IANA 时区");
    }
    write("config", Map.of("automatic", automatic, "time", time, "timezone", timezone));
  }

  private void requireReady() {
    if (root == null || !available())
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "UPDATER_UNAVAILABLE", "升级器尚未就绪，请检查部署配置");
  }

  private BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "UPGRADE_INVALID", message);
  }

  /** 文件替换由同目录临时文件完成，读取异常只返回脱敏错误。 */
  private Map<String, Object> read(String name) {
    if (root == null || !Files.exists(root.resolve(name + ".json"))) return Map.of();
    try {
      return mapper.readValue(
          Files.readString(root.resolve(name + ".json")),
          new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } catch (IOException error) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "UPGRADE_STATE_UNAVAILABLE", "无法读取升级状态");
    }
  }

  private void write(String name, Map<String, Object> value) {
    Path temporary = null;
    try {
      temporary = Files.createTempFile(root, ".upgrade-", ".tmp");
      Files.writeString(temporary, mapper.writeValueAsString(value));
      try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        file.force(true);
      }
      Files.move(
          temporary,
          root.resolve(name + ".json"),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      try (var directory = FileChannel.open(root, StandardOpenOption.READ)) {
        directory.force(true);
      }
    } catch (IOException error) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "UPGRADE_STATE_UNAVAILABLE", "无法保存升级任务");
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          /* 临时文件不会被执行。 */
        }
    }
  }
}
