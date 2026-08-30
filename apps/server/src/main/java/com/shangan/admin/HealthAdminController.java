package com.shangan.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** 展示不含秘密值的服务运行状态。 */
@Controller
public class HealthAdminController {
  private final OperationsHealthService health;

  public HealthAdminController(OperationsHealthService health) {
    this.health = health;
  }

  @GetMapping("/admin/health")
  String health(Model model) {
    model.addAttribute("health", health.snapshot());
    return "admin/health";
  }
}
