package com.shangan.common.integration;

import com.shangan.common.api.BusinessException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 个人 Bark 只能访问部署者信任的 HTTPS 源站，普通用户不能扩展服务端网络权限。 */
@Component
public class BarkEndpointPolicy {
  private final Set<String> allowedOrigins;

  public BarkEndpointPolicy(
      @Value("${app.bark.allowed-origins:https://api.day.app}") String origins) {
    allowedOrigins =
        Arrays.stream(origins.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(this::origin)
            .collect(Collectors.toUnmodifiableSet());
  }

  /** 保存和实际发送时都检查，旧配置也不能绕过新访问边界。 */
  public String requirePersonalEndpoint(String value) {
    try {
      String normalized = value.trim().replaceAll("/+$", "");
      URI uri = URI.create(normalized);
      if (!allowedOrigins.contains(origin(normalized))
          || uri.getRawPath().contains("..")
          || uri.getRawPath().contains("%")) throw new IllegalArgumentException();
      return normalized;
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "BARK_SETTINGS_INVALID",
          "Bark 服务地址必须是管理员允许的 HTTPS 源站；自建服务请联系管理员配置 BARK_ALLOWED_ORIGINS");
    }
  }

  /** 以完整源站（协议、主机、端口）匹配，不接受后缀或子域名匹配。 */
  private String origin(String value) {
    URI uri = URI.create(value);
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException("Bark 可信源站必须为不含凭据的 HTTPS 地址");
    return "https://"
        + uri.getHost().toLowerCase(java.util.Locale.ROOT)
        + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
  }
}
