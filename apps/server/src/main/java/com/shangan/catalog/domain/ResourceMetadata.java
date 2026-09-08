package com.shangan.catalog.domain;

import java.util.List;

/**
 * 来自外部媒体源的资源元数据，视频与书籍共用同一结构（见 ADR-0030）。
 *
 * <p>{@code sourceFingerprint} 由 Emby 的 Path 在内存中计算得出，Path 本身禁止入库、上页面或写日志。
 */
public record ResourceMetadata(
    String externalRef,
    String title,
    int sortIndex,
    Long durationMs,
    Integer pageCount,
    String sourceFingerprint,
    ResourceType resourceType) {

  /** 课程级元数据快照，用于整表重写三张只读投影。 */
  public record CourseMetadata(
      String externalRef,
      String title,
      String overview,
      Integer productionYear,
      List<String> genres,
      List<String> tags,
      List<Person> people) {

    public CourseMetadata {
      genres = genres == null ? List.of() : List.copyOf(genres);
      tags = tags == null ? List.of() : List.copyOf(tags);
      people = people == null ? List.of() : List.copyOf(people);
    }
  }

  /** Emby People 条目；视频里是讲师，书籍里是作者。 */
  public record Person(String name, String role) {}
}
