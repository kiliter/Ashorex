package com.shangan.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 为浏览器提供稳定的管理后台入口，避免访问根路径时落入 Spring Boot 404 页面。 */
@Controller
public class AdminEntryController {

  /** 根路径和简写后台路径统一进入健康页，未认证用户随后由安全链跳转登录页。 */
  @GetMapping({"/", "/admin"})
  String redirectToAdminHealth() {
    return "redirect:/admin/health";
  }
}
