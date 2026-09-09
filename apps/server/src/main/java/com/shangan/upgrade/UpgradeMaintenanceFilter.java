package com.shangan.upgrade;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 维护期在认证、Session 写入前拒绝业务流量；旧 App 以网络失败保留待重放上报。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class UpgradeMaintenanceFilter extends OncePerRequestFilter {
  private final UpgradeService upgrades;

  public UpgradeMaintenanceFilter(UpgradeService upgrades) {
    this.upgrades = upgrades;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    // 只放行无 Session、无业务写入的进程健康与版本探测；后台轮询在开放后自动恢复。
    if (upgrades.maintenance()
        && !path.equals("/actuator/health")
        && !path.equals("/internal/upgrade-readiness")) {
      response.setStatus(503);
      response.setCharacterEncoding("UTF-8");
      response.setContentType("application/problem+json");
      response.setHeader("Retry-After", "30");
      response
          .getWriter()
          .write(
              "{\"type\":\"about:blank\",\"title\":\"服务升级中\",\"status\":503,\"detail\":\"服务正在升级，请稍后重试\",\"errorCode\":\"SERVER_UPGRADING\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
