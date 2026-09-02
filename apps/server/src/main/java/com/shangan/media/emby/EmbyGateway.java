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

  List<EmbyDtos.MediaItem> listChildren(String parentItemId);
}
