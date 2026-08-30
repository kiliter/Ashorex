package com.shangan.media.emby;

/** Emby 外部模型的最小安全快照，不包含 API Key 或媒体直链。 */
public final class EmbyDtos {

  private EmbyDtos() {}

  /** 可同步为课程课时的媒体项。 */
  public record MediaItem(String id, String title, long durationMs, int indexNumber) {}
}
