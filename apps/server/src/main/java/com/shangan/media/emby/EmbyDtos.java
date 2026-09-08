package com.shangan.media.emby;

/** Emby 外部模型的最小安全快照，不包含 API Key 或媒体直链。 */
public final class EmbyDtos {

  private EmbyDtos() {}

  /** 管理后台可选择的用户可见视频媒体库，不包含服务器物理路径。 */
  public record MediaLibrary(String id, String name, String collectionType) {}

  /** 管理后台联想和批量建课使用的安全来源元数据，不包含主机、密钥或物理路径。 */
  public record MediaSource(
      String id,
      String name,
      String itemType,
      String collectionType,
      String parentId,
      boolean hasPrimaryImage) {
    /** 兼容无封面信息的协议 Fake 与既有调用。 */
    public MediaSource(
        String id, String name, String itemType, String collectionType, String parentId) {
      this(id, name, itemType, collectionType, parentId, false);
    }
  }

  /** 受大小限制的封面快照，不携带远端响应头或地址。 */
  public record Cover(byte[] bytes, String contentType) {}

  /**
   * 课程来源的完整元数据快照。
   *
   * <p>流派、标签、人物是移动端的唯一筛选维度来源（见 ADR-0027）；本记录不含物理路径。
   */
  public record SourceMetadata(
      String id,
      String name,
      String overview,
      Integer productionYear,
      java.util.List<String> genres,
      java.util.List<String> tags,
      java.util.List<PersonRef> people) {

    public SourceMetadata {
      genres = genres == null ? java.util.List.of() : java.util.List.copyOf(genres);
      tags = tags == null ? java.util.List.of() : java.util.List.copyOf(tags);
      people = people == null ? java.util.List.of() : java.util.List.copyOf(people);
    }
  }

  /** Emby People 条目；视频里是讲师，书籍里是作者。 */
  public record PersonRef(String name, String role) {}

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
