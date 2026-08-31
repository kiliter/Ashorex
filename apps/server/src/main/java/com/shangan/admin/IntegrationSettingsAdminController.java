package com.shangan.admin;

import com.shangan.common.integration.IntegrationSettingsValidationException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 管理员查看并保存 Emby 运行时配置。 */
@Controller
public class IntegrationSettingsAdminController {

  private final RuntimeIntegrationSettingsService settings;

  public IntegrationSettingsAdminController(RuntimeIntegrationSettingsService settings) {
    this.settings = settings;
  }

  @GetMapping("/admin/settings/integrations")
  String show(
      @RequestParam(defaultValue = "false") boolean saved,
      Model model,
      HttpServletResponse response) {
    preventCaching(response);
    RuntimeIntegrationSettings current = settings.current();
    model.addAttribute("settingsForm", IntegrationSettingsForm.from(current));
    model.addAttribute("currentSettings", current);
    model.addAttribute("fieldErrors", Map.of());
    model.addAttribute("saved", saved);
    return "admin/integration-settings";
  }

  @PostMapping("/admin/settings/integrations")
  String save(
      @ModelAttribute IntegrationSettingsForm settingsForm,
      Model model,
      HttpServletResponse response) {
    preventCaching(response);
    try {
      settings.save(settingsForm.toSettings());
      return "redirect:/admin/settings/integrations?saved=true";
    } catch (IntegrationSettingsValidationException exception) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      model.addAttribute("settingsForm", settingsForm);
      model.addAttribute("currentSettings", settings.current());
      model.addAttribute("fieldErrors", exception.fieldErrors());
      model.addAttribute("saved", false);
      return "admin/integration-settings";
    }
  }

  private void preventCaching(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");
    response.setHeader(HttpHeaders.PRAGMA, "no-cache");
  }
}
