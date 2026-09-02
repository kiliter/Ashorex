package com.shangan.media.emby;

/** Emby 外部模型的最小安全快照，不包含 API Key 或媒体直链。 */
public final class EmbyDtos {

  private EmbyDtos() {}

  /** 管理后台可选择的用户可见视频媒体库，不包含服务器物理路径。 */
  public record MediaLibrary(String id, String name, String collectionType) {}

  /** 管理后台联想和批量建课使用的安全来源元数据，不包含主机、密钥或物理路径。 */
  public record MediaSource(
      String id, String name, String itemType, String collectionType, String parentId) {}

  /** 可同步为课程课时的媒体项。 */
  public record MediaItem(
      String id,
      String title,
      long durationMs,
      int indexNumber,
      String itemType,
      String sourceFingerprint) {

    /** 兼容原有纯逻辑测试；旧构造方式没有来源指纹。 */
    public MediaItem(String id, String title, long durationMs, int indexNumber) {
      this(id, title, durationMs, indexNumber, "Video", null);
    }
  }
}
