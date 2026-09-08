package com.shangan.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

/**
 * 后台拒绝访问时的错误码区分。
 *
 * <p>回归背景：{@code CsrfException} 继承自 {@code AccessDeniedException}，两者共用同一个
 * AccessDeniedHandler。早期实现没有区分，导致「登出后再次登录」这类令牌失效场景被报成 「当前账号没有后台管理权限」——提示与真实原因完全无关，用户会去查权限而不是重试。
 *
 * <p>本测试直接驱动安全链上注册的真实处理器，不复制判断逻辑，避免与实现漂移。
 */
class AdminAccessDeniedHandlerTest {

  private String handleAndAssertForbidden(AccessDeniedException exception) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    AdminSecurityHandlers.accessDeniedHandler().handle(request, response, exception);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    assertThat(response.getContentType()).contains("application/problem+json");
    return response.getContentAsString();
  }

  @Test
  @DisplayName("缺少 CSRF 令牌报 ADMIN_CSRF_INVALID，不再误报成权限不足")
  void missingCsrfTokenIsReportedAsCsrfFailure() throws Exception {
    String body = handleAndAssertForbidden(new MissingCsrfTokenException("missing"));

    assertThat(body).contains("\"errorCode\":\"ADMIN_CSRF_INVALID\"");
    assertThat(body).contains("安全令牌已失效，请重新提交");
    assertThat(body).doesNotContain("ADMIN_FORBIDDEN");
    assertThat(body).doesNotContain("没有后台管理权限");
  }

  @Test
  @DisplayName("CSRF 令牌不匹配报 ADMIN_CSRF_INVALID")
  void invalidCsrfTokenIsReportedAsCsrfFailure() throws Exception {
    String body =
        handleAndAssertForbidden(
            new InvalidCsrfTokenException(
                new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected"), "actual"));

    assertThat(body).contains("\"errorCode\":\"ADMIN_CSRF_INVALID\"");
    assertThat(body).doesNotContain("ADMIN_FORBIDDEN");
  }

  @Test
  @DisplayName("角色不足仍报 ADMIN_FORBIDDEN，不被 CSRF 分支吞掉")
  void insufficientRoleStillReportsForbidden() throws Exception {
    String body = handleAndAssertForbidden(new AccessDeniedException("role"));

    assertThat(body).contains("\"errorCode\":\"ADMIN_FORBIDDEN\"");
    assertThat(body).contains("当前账号没有后台管理权限");
    assertThat(body).doesNotContain("ADMIN_CSRF_INVALID");
  }

  @Test
  @DisplayName("错误响应不携带异常消息与堆栈")
  void responseCarriesNoStackTrace() throws Exception {
    String body =
        handleAndAssertForbidden(
            new AccessDeniedException("role", new IllegalStateException("内部细节不应外泄")));

    assertThat(body).doesNotContain("IllegalStateException");
    assertThat(body).doesNotContain("内部细节不应外泄");
    assertThat(body).doesNotContain("at com.shangan");
  }

  @Test
  @DisplayName("未认证入口返回 401 与稳定错误码，而不是跳转登录页")
  void authenticationEntryPointReturnsJsonProblem() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    AdminSecurityHandlers.authenticationEntryPoint()
        .commence(
            request,
            response,
            new org.springframework.security.core.AuthenticationException("x") {});

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getContentAsString()).contains("\"errorCode\":\"ADMIN_SESSION_EXPIRED\"");
    assertThat(response.getRedirectedUrl()).isNull();
  }
}
