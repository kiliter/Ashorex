package com.shangan.common.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * 管理后台的拒绝访问与未认证响应。
 *
 * <p>后台前端是 SPA，所有失败都必须是可被 fetch 解析的 Problem Details，而不是 302 跳登录页； 否则前端只能拿到一份登录页 HTML，无法判断到底发生了什么。
 *
 * <p>单独成类而不是写成内联 lambda，是为了让测试能直接驱动真实实现， 避免测试里复制一份判断逻辑后与生产代码漂移。
 */
final class AdminSecurityHandlers {

  private AdminSecurityHandlers() {}

  /**
   * 拒绝访问处理器。
   *
   * <p>{@link CsrfException} 继承自 {@code AccessDeniedException}，会和「角色不足」共用本处理器。
   * 两者的用户动作完全不同：令牌失效重试即可（登出、会话固化、会话过期都会清空令牌）， 角色不足只能换账号。因此必须返回不同的 errorCode，否则会把「安全令牌失效」
   * 误报成「当前账号没有后台管理权限」，把用户引向完全错误的排查方向。
   */
  static AccessDeniedHandler accessDeniedHandler() {
    return (request, response, exception) -> {
      if (exception instanceof CsrfException) {
        writeProblem(
            response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_CSRF_INVALID", "安全令牌已失效，请重新提交");
        return;
      }
      writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_FORBIDDEN", "当前账号没有后台管理权限");
    };
  }

  /** 未认证入口：会话失效或未登录时返回 401，由前端路由守卫导向登录视图。 */
  static AuthenticationEntryPoint authenticationEntryPoint() {
    return (request, response, exception) ->
        writeProblem(
            response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_SESSION_EXPIRED", "会话已失效，请重新登录");
  }

  /** 登录失败：用户名或密码错误，不透出账号是否存在。 */
  static org.springframework.security.web.authentication.AuthenticationFailureHandler
      loginFailureHandler() {
    return (request, response, exception) ->
        writeProblem(
            response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_LOGIN_FAILED", "用户名或密码错误");
  }

  /**
   * 后台错误统一走 Problem Details，字段与 App API 保持一致，便于前端复用解析逻辑。
   *
   * <p>只输出固定文案，绝不包含异常消息或堆栈：后台错误可能携带内部实现细节。
   */
  private static void writeProblem(
      HttpServletResponse response, int status, String errorCode, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json;charset=UTF-8");
    response
        .getWriter()
        .write(
            "{\"status\":%d,\"errorCode\":\"%s\",\"title\":\"%s\",\"detail\":\"%s\"}"
                .formatted(status, errorCode, detail, detail));
  }
}
