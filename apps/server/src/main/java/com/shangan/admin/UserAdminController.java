package com.shangan.admin;

import com.shangan.identity.application.AuthService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 管理员创建、启用和禁用普通用户；所有业务校验与事务均由身份应用服务负责。 */
@Controller
@Validated
public class UserAdminController {
  private final AuthService auth;

  public UserAdminController(AuthService auth) {
    this.auth = auth;
  }

  @GetMapping("/admin/users")
  String users(Model model) {
    model.addAttribute("users", auth.listManagedUsers());
    return "admin/users";
  }

  @PostMapping("/admin/users")
  String create(
      @RequestParam @NotBlank String username,
      @RequestParam @NotBlank String displayName,
      @RequestParam @NotBlank String password) {
    auth.createManagedUser(username, displayName, password);
    return "redirect:/admin/users";
  }

  @PostMapping("/admin/users/{userId}/enabled")
  String setEnabled(
      @PathVariable String userId, @RequestParam(defaultValue = "false") boolean enabled) {
    auth.setManagedUserEnabled(userId, enabled);
    return "redirect:/admin/users";
  }
}
