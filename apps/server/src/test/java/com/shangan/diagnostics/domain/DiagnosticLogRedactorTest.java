package com.shangan.diagnostics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 服务端二次脱敏：Token、口令与查询串不得进入落盘副本。 */
class DiagnosticLogRedactorTest {

  @Test
  @DisplayName("去掉 Bearer、Authorization、Cookie、JWT、口令与查询串")
  void 脱敏常见凭据() {
    String raw =
        """
        Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.aaa.bbb
        Cookie: SESSION=abc
        GET /api/v1/me?access_token=xyz
        password=hunter2
        api_key=emby-secret
        """;
    String redacted = DiagnosticLogRedactor.redact(raw);
    assertThat(redacted)
        .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
        .doesNotContain("SESSION=abc")
        .doesNotContain("access_token=xyz")
        .doesNotContain("hunter2")
        .doesNotContain("emby-secret")
        .contains("[REDACTED]")
        .contains("GET /api/v1/me");
  }
}
