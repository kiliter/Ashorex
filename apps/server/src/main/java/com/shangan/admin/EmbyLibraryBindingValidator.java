package com.shangan.admin;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.media.emby.EmbyDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 把后台提交的媒体库 ID 和类型解析为当前 Emby 用户真实可见的安全绑定。 */
@Component
public class EmbyLibraryBindingValidator {

  /** 拒绝失效、越权、重复媒体库和未知类型，名称始终采用 Emby 当前返回值。 */
  public List<RuntimeIntegrationSettings.EmbyLibrary> validate(
      List<EmbyDtos.MediaLibrary> available, List<String> submitted) {
    Map<String, EmbyDtos.MediaLibrary> availableById = new LinkedHashMap<>();
    for (EmbyDtos.MediaLibrary library : available) {
      availableById.put(library.id(), library);
    }
    List<RuntimeIntegrationSettings.EmbyLibrary> result = new ArrayList<>();
    Set<String> selectedIds = new LinkedHashSet<>();
    for (String value : submitted == null ? List.<String>of() : submitted) {
      String[] parts = value == null ? new String[0] : value.split("\\|", 2);
      if (parts.length != 2) {
        throw invalidType();
      }
      EmbyDtos.MediaLibrary library = availableById.get(parts[0]);
      if (library == null || !selectedIds.add(parts[0])) {
        throw new BusinessException(
            HttpStatus.CONFLICT, "EMBY_LIBRARY_CHANGED", "媒体库列表已发生变化，请刷新配置页后重新选择");
      }
      RuntimeIntegrationSettings.EmbyLibraryType type;
      try {
        type = RuntimeIntegrationSettings.EmbyLibraryType.valueOf(parts[1]);
      } catch (IllegalArgumentException exception) {
        throw invalidType();
      }
      result.add(new RuntimeIntegrationSettings.EmbyLibrary(library.id(), library.name(), type));
    }
    return List.copyOf(result);
  }

  private BusinessException invalidType() {
    return new BusinessException(
        HttpStatus.BAD_REQUEST, "EMBY_LIBRARY_TYPE_INVALID", "媒体库类型必须是剧集、电影或混合");
  }
}
