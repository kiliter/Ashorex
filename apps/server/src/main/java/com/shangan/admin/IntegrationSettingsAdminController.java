package com.shangan.admin;

import com.shangan.ai.content.application.OpenRouterModelCatalogService;
import com.shangan.common.api.BusinessException;
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

/** 管理员查看并保存 Emby、ASR、LLM 和模型目录运行时配置。 */
@Controller
public class IntegrationSettingsAdminController {

  private final RuntimeIntegrationSettingsService settings;
  private final OpenRouterModelCatalogService modelCatalog;

  public IntegrationSettingsAdminController(
      RuntimeIntegrationSettingsService settings, OpenRouterModelCatalogService modelCatalog) {
    this.settings = settings;
    this.modelCatalog = modelCatalog;
  }

  @GetMapping("/admin/settings/integrations")
  String show(
      @RequestParam(defaultValue = "false") boolean saved,
      @RequestParam(defaultValue = "false") boolean refreshed,
      @RequestParam(defaultValue = "") String refreshError,
      Model model,
      HttpServletResponse response) {
    preventCaching(response);
    RuntimeIntegrationSettings current = settings.current();
    String effectiveRefreshError = refreshError;
    if (modelCatalog.count() == 0 && !current.openRouter().apiKey().isBlank()) {
      try {
        modelCatalog.refresh();
      } catch (BusinessException exception) {
        effectiveRefreshError = exception.getMessage();
      }
    }
    model.addAttribute("settingsForm", IntegrationSettingsForm.from(current));
    model.addAttribute("currentSettings", current);
    model.addAttribute("fieldErrors", Map.of());
    model.addAttribute("saved", saved);
    model.addAttribute("refreshed", refreshed);
    model.addAttribute("refreshError", effectiveRefreshError);
    model.addAttribute("catalogModels", modelCatalog.search(""));
    model.addAttribute("catalogCount", modelCatalog.count());
    return "admin/integration-settings";
  }

  /** 手动刷新固定 OpenRouter 目录；失败时旧缓存继续可用。 */
  @PostMapping("/admin/settings/models/refresh")
  String refreshModels() {
    try {
      modelCatalog.refresh();
      return "redirect:/admin/settings/integrations?refreshed=true";
    } catch (BusinessException exception) {
      return "redirect:/admin/settings/integrations?refreshError="
          + java.net.URLEncoder.encode(
              exception.getMessage(), java.nio.charset.StandardCharsets.UTF_8);
    }
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
      model.addAttribute("refreshed", false);
      model.addAttribute("refreshError", "");
      model.addAttribute("catalogModels", modelCatalog.search(""));
      model.addAttribute("catalogCount", modelCatalog.count());
      return "admin/integration-settings";
    }
  }

  private void preventCaching(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");
    response.setHeader(HttpHeaders.PRAGMA, "no-cache");
  }
}
