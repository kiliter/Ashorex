package com.shangan.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 提供管理后台登录页面；表单提交由 Spring Security 处理。 */
@Controller
public class AdminLoginController {

  @GetMapping("/admin/login")
  String login() {
    return "admin/login";
  }
}
