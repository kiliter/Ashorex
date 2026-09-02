package com.shangan.media.emby;

import java.util.List;

/** 课程模块访问 Emby 的只读端口。 */
public interface EmbyGateway {
  /** 兼容只关心课时列表的既有 Fake；生产适配器会覆盖并读取真实媒体库。 */
  default List<EmbyDtos.MediaLibrary> listMediaLibraries() {
    return List.of();
  }

  List<EmbyDtos.MediaItem> listChildren(String parentItemId);
}
