package com.shangan.common.integration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 不解析普通用户输入的任意域名，精确源站匹配杜绝绕过可信主机边界。 */
class BarkEndpointPolicyTest {
  private final BarkEndpointPolicy policy = new BarkEndpointPolicy("https://api.day.app");

  @Test
  void 默认只允许官方源站并拒绝地址混淆() {
    assertThat(policy.requirePersonalEndpoint("https://api.day.app/"))
        .isEqualTo("https://api.day.app");
    for (String address :
        new String[] {
          "https://127.0.0.1",
          "https://[::1]",
          "https://169.254.169.254",
          "https://internal.example",
          "https://api.day.app.evil.example",
          "https://evil@api.day.app",
          "https://api.day.app:8443",
          "https://api.day.app?redirect=internal",
          "http://api.day.app",
          "https://api.day.app/../admin"
        }) {
      assertThatThrownBy(() -> policy.requirePersonalEndpoint(address))
          .hasMessageContaining("管理员允许");
    }
  }

  @Test
  void 仅部署者明确配置的自建源站可用于个人推送() {
    var custom = new BarkEndpointPolicy("https://api.day.app,https://bark.example.org:9443");
    assertThat(custom.requirePersonalEndpoint("https://bark.example.org:9443/service"))
        .isEqualTo("https://bark.example.org:9443/service");
    assertThatThrownBy(() -> custom.requirePersonalEndpoint("https://bark.example.org"))
        .hasMessageContaining("管理员允许");
  }
}
