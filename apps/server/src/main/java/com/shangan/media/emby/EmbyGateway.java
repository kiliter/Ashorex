package com.shangan.media.emby;

import java.util.List;

/** 课程模块访问 Emby 的只读端口。 */
public interface EmbyGateway {
  /** 兼容只关心课时列表的既有 Fake；生产适配器会覆盖并读取真实媒体库。 */
  default List<EmbyDtos.MediaLibrary> listMediaLibraries() {
    return List.of();
  }

  /** 按名称或 Item ID 联想配置用户可见的媒体库、Series 和 Folder。 */
  default List<EmbyDtos.MediaSource> searchSources(String query) {
    return listMediaLibraries().stream()
        .map(
            library ->
                new EmbyDtos.MediaSource(
                    library.id(), library.name(), "CollectionFolder", library.collectionType(), ""))
        .toList();
  }

  /** 重新读取一个来源的安全元数据，用于提交前权限和存在性校验。 */
  default EmbyDtos.MediaSource getSource(String itemId) {
    return searchSources(itemId).stream()
        .filter(source -> source.id().equals(itemId))
        .findFirst()
        .orElseThrow(
            () ->
                new com.shangan.common.api.BusinessException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "EMBY_PARENT_NOT_FOUND",
                    "Emby 媒体来源不存在或当前用户无权访问"));
  }

  /**
   * 读取来源的完整元数据（流派、标签、人物、年份、简介）。
   *
   * <p>默认实现只回填名称，便于纯逻辑测试的 Fake 保持简单；生产适配器会覆盖。
   */
  default EmbyDtos.SourceMetadata getSourceMetadata(String itemId) {
    EmbyDtos.MediaSource source = getSource(itemId);
    return new EmbyDtos.SourceMetadata(
        source.id(), source.name(), "", null, List.of(), List.of(), List.of());
  }

  /** 读取小尺寸封面，生产实现限制响应大小与图片类型。 */
  default EmbyDtos.Cover readCover(String itemId) {
    throw new com.shangan.common.api.BusinessException(
        org.springframework.http.HttpStatus.NOT_FOUND, "EMBY_COVER_NOT_FOUND", "暂无封面");
  }

  List<EmbyDtos.MediaItem> listChildren(String parentItemId);
}
