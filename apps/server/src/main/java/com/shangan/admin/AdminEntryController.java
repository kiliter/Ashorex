package com.shangan.admin;

import com.shangan.identity.domain.UserRole;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 后台入口与会话查询。
 *
 * <p>客户端路由的转发在 {@link AdminSpaRoutingConfiguration} 中集中声明。
 */
@Controller
public class AdminEntryController {

  /** 根路径进入后台。 */
  @GetMapping("/")
  String root() {
    return "redirect:/admin/";
  }

  /**
   * 当前后台会话信息。
   *
   * <p>同时承担「下发 CSRF Cookie」的职责：Spring Security 在响应本请求时写入 XSRF-TOKEN， 前端启动时先调它，之后的写操作才有令牌可回填。
   */
  @GetMapping(value = "/admin/api/session", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  SessionResponse session(Authentication authentication, CsrfToken csrfToken) {
    // Spring Security 7 的 CSRF 令牌是延迟加载的：必须真正读取一次 token 值，
    // 过滤器才会把它写进 XSRF-TOKEN Cookie，否则前端登录时会因缺令牌被 403。
    csrfToken.getToken();
    if (authentication == null || !authentication.isAuthenticated()) {
      return new SessionResponse(false, null, List.of());
    }
    List<String> roles =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .toList();
    return new SessionResponse(
        roles.contains(UserRole.ADMIN.name()), authentication.getName(), roles);
  }

  /** 后台会话状态。 */
  public record SessionResponse(boolean authenticated, String username, List<String> roles) {}
}
