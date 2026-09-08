package com.shangan.common.integration;

import java.util.List;

/**
 * 运行时外部集成配置的不可变快照。
 *
 * <p>V2 只保留两个外部依赖：Emby（唯一媒体源）与 Server 酱（催办推送渠道），另含 feature 开关。ASR、LLM、OpenRouter 与内容定时补全已随 AI
 * 内容生产域一并移除。
 */
public record RuntimeIntegrationSettings(
    Emby emby,
    List<EmbyLibrary> embyLibraries,
    ServerChan serverChan,
    Features features,
    long updatedAt,
    Bark bark) {

  public RuntimeIntegrationSettings {
    bark = bark == null ? Bark.defaults() : bark;
    embyLibraries = embyLibraries == null ? List.of() : List.copyOf(embyLibraries);
    emby = emby == null ? Emby.defaults() : emby;
    serverChan = serverChan == null ? ServerChan.defaults() : serverChan;
    features = features == null ? Features.defaults() : features;
  }

  /** 兼容旧调用；新增配置默认关闭。 */
  public RuntimeIntegrationSettings(
      Emby emby,
      List<EmbyLibrary> libraries,
      ServerChan serverChan,
      Features features,
      long updatedAt) {
    this(emby, libraries, serverChan, features, updatedAt, Bark.defaults());
  }

  /** 系统 Bark 仅发送运维异常，与个人催办完全独立。 */
  public record Bark(String baseUrl, String deviceKey, boolean enabled, int timeoutSeconds) {
    public boolean configured() {
      return present(baseUrl) && present(deviceKey);
    }

    public static Bark defaults() {
      return new Bark("https://api.day.app", "", false, 8);
    }
  }

  /** 便于只关心 Emby 的调用方与协议测试构造快照。 */
  public RuntimeIntegrationSettings(Emby emby, long updatedAt) {
    this(emby, List.of(), ServerChan.defaults(), Features.defaults(), updatedAt);
  }

  /** 返回全部默认值，用于首次启动或配置行缺失时兜底。 */
  public static RuntimeIntegrationSettings defaults() {
    return new RuntimeIntegrationSettings(
        Emby.defaults(), List.of(), ServerChan.defaults(), Features.defaults(), 0L);
  }

  /** Emby 固定源站配置；用户 ID 可为空，但地址与密钥必须同时存在才视为已配置。 */
  public record Emby(String baseUrl, String apiKey, String userId, int timeoutSeconds) {

    public Emby(String baseUrl, String apiKey, String userId) {
      this(baseUrl, apiKey, userId, 10);
    }

    public boolean configured() {
      return present(baseUrl) && present(apiKey);
    }

    public static Emby defaults() {
      return new Emby("", "", "", 10);
    }
  }

  /** 管理员允许作为课程来源的一个顶层媒体库。 */
  public record EmbyLibrary(String id, String name, EmbyLibraryType contentType) {}

  /**
   * 媒体库可提供的内容类型。
   *
   * <p>{@code BOOK} 对应 Emby 书籍库，是材料（DOCUMENT）资源的来源；按 ADR-0030 在 V2 只落枚举值，不参与同步。
   */
  public enum EmbyLibraryType {
    SERIES,
    MOVIE,
    MIXED,
    BOOK
  }

  /** Server 酱推送配置；催办为唯一启用用途，每日汇总在 V2 不实现。 */
  public record ServerChan(
      String sendKey, int timeoutSeconds, boolean nagEnabled, boolean dailyDigestEnabled) {

    public boolean configured() {
      return present(sendKey);
    }

    public static ServerChan defaults() {
      return new ServerChan("", 8, true, false);
    }
  }

  /** 功能开关；材料资源在 V2 默认关闭，见 ADR-0030。 */
  public record Features(boolean documentResources, int maxDocumentSizeMb) {

    public static Features defaults() {
      return new Features(false, 200);
    }
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
