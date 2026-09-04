package com.shangan.admin;

import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.AuthService;
import com.shangan.identity.application.UserDeletionService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 管理员创建、启用、禁用和删除普通用户；所有业务校验与事务均由身份应用服务负责。 */
@Controller
@Validated
public class UserAdminController {
  private final AuthService auth;
  private final UserDeletionService userDeletions;

  public UserAdminController(AuthService auth, UserDeletionService userDeletions) {
    this.auth = auth;
    this.userDeletions = userDeletions;
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

  /** 删除用户及其全部学习记录；不可恢复，需要输入用户名二次确认。 */
  @PostMapping("/admin/users/{userId}/delete")
  String delete(
      @PathVariable String userId,
      @RequestParam(defaultValue = "") String confirmUsername,
      RedirectAttributes redirectAttributes) {
    try {
      userDeletions.delete(userId, confirmUsername);
      redirectAttributes.addFlashAttribute("userActionSuccess", "用户及其全部学习记录已删除。");
    } catch (BusinessException exception) {
      // 后台是页面表单，业务错误必须回到列表页展示，不能让全局处理器返回 Problem Details JSON。
      redirectAttributes.addFlashAttribute("userActionError", exception.getMessage());
    }
    return "redirect:/admin/users";
  }
}
