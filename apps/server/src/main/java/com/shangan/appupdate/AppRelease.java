package com.shangan.appupdate;

import com.fasterxml.jackson.databind.JsonNode;
import com.shangan.common.api.BusinessException;
import org.springframework.http.HttpStatus;

/** 公共移动端版本投影：仅版本号用于升级判断，缺少附件时明确不可下载。 */
public record AppRelease(
    String version,
    String notes,
    String publishedAt,
    String platform,
    boolean downloadable,
    long size,
    String sha256,
    String downloadPath,
    String pagePath) {
  /** 只接受当前支持的两个平台，不能将输入作为任意文件路径。 */
  static String platform(String value) {
    if (!"android".equals(value) && !"ios".equals(value))
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "APP_UPDATE_PLATFORM_INVALID", "不支持的安装包平台");
    return value;
  }

  /** 正式版本限定三段非负整数，排除预发布、路径与查询串。 */
  static String tag(String value) {
    if (value == null
        || !value.matches("v(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})"))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "APP_UPDATE_VERSION_INVALID", "安装包版本无效");
    return value;
  }

  /** 根据固定命名和摘要判断附件就绪，不从服务端镜像推断移动端产物。 */
  static AppRelease parse(JsonNode json, String platform) {
    platform(platform);
    String tag = tag(json.path("tag_name").asText());
    if (json.path("draft").asBoolean() || json.path("prerelease").asBoolean())
      throw new BusinessException(HttpStatus.NOT_FOUND, "APP_UPDATE_NOT_FOUND", "暂无正式移动端版本");
    String version = tag.substring(1);
    String name = filename(version, platform);
    long size = 0;
    String digest = "";
    for (JsonNode asset : json.path("assets")) {
      if (name.equals(asset.path("name").asText())
          && asset.path("size").asLong() > 0
          && asset.path("digest").asText().matches("sha256:[a-fA-F0-9]{64}")
          && assetUrl(tag, platform).equals(asset.path("browser_download_url").asText())) {
        size = asset.path("size").asLong();
        digest = asset.path("digest").asText().substring(7).toLowerCase(java.util.Locale.ROOT);
      }
    }
    return new AppRelease(
        version,
        json.path("body").asText(""),
        json.path("published_at").asText(),
        platform,
        size > 0,
        size,
        digest,
        "/api/v1/app-updates/releases/" + tag + "/assets/" + platform,
        "/downloads/app/" + tag + "/ios");
  }

  static String filename(String version, String platform) {
    return "Ashorex-" + version + "-" + platform + ("ios".equals(platform) ? ".ipa" : ".apk");
  }

  static String assetUrl(String tag, String platform) {
    return "https://github.com/kiliter/Ashorex/releases/download/"
        + tag
        + "/"
        + filename(tag.substring(1), platform);
  }
}
