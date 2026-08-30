package com.shangan.media.emby;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 固定的 Emby 服务端配置，禁止从客户端请求中覆盖主机或密钥。 */
@Component
public record EmbyProperties(String baseUrl, String apiKey, String userId) {

  public EmbyProperties(
      @Value("${app.emby.base-url:}") String baseUrl,
      @Value("${app.emby.api-key:}") String apiKey,
      @Value("${app.emby.user-id:}") String userId) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    this.apiKey = apiKey == null ? "" : apiKey;
    this.userId = userId == null ? "" : userId;
  }

  /** 仅判断所需配置是否齐全，不输出任何配置值。 */
  public boolean configured() {
    return !baseUrl.isBlank() && !apiKey.isBlank();
  }
}
