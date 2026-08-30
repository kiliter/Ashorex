package com.shangan.media.emby;

import java.util.List;

/** 课程模块访问 Emby 的只读端口。 */
public interface EmbyGateway {
  List<EmbyDtos.MediaItem> listChildren(String parentItemId);
}
