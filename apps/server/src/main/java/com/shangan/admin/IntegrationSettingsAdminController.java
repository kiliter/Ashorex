package com.shangan.admin;

import com.shangan.ai.content.application.OpenRouterModelCatalogService;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.IntegrationSettingsValidationException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
  private final IntegrationConnectionTestService connectionTest;

  public IntegrationSettingsAdminController(
      RuntimeIntegrationSettingsService settings,
      OpenRouterModelCatalogService modelCatalog,
      IntegrationConnectionTestService connectionTest) {
    this.settings = settings;
    this.modelCatalog = modelCatalog;
    this.connectionTest = connectionTest;
  }

  /** 使用已保存配置和内置“你好”MP3 验证 ASR 接口，并返回局部通知所需结果。 */
  @PostMapping("/admin/settings/integrations/test/asr")
  ResponseEntity<IntegrationTestResponse> testAsr() {
    try {
      String result = connectionTest.testAsr();
      return ResponseEntity.ok(new IntegrationTestResponse(true, "ASR 测试成功，识别结果：" + result));
    } catch (BusinessException exception) {
      return ResponseEntity.status(exception.status())
          .body(new IntegrationTestResponse(false, exception.getMessage()));
    }
  }

  /** 使用最小提示词验证已保存的 LLM Chat Completions 配置，并返回局部通知所需结果。 */
  @PostMapping("/admin/settings/integrations/test/llm")
  ResponseEntity<IntegrationTestResponse> testLlm() {
    try {
      String result = connectionTest.testLlm();
      return ResponseEntity.ok(new IntegrationTestResponse(true, "LLM 测试成功，返回结果：" + result));
    } catch (BusinessException exception) {
      return ResponseEntity.status(exception.status())
          .body(new IntegrationTestResponse(false, exception.getMessage()));
    }
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
  ResponseEntity<ModelRefreshResponse> refreshModels() {
    try {
      modelCatalog.refresh();
      long count = modelCatalog.count();
      return ResponseEntity.ok(
          new ModelRefreshResponse(true, "模型目录已刷新，共 " + count + " 个模型", count));
    } catch (BusinessException exception) {
      return ResponseEntity.status(exception.status())
          .body(new ModelRefreshResponse(false, exception.getMessage(), modelCatalog.count()));
    }
  }

  /** 异步保存整份运行时配置；校验失败时返回字段错误，不重新渲染配置页面。 */
  @PostMapping("/admin/settings/integrations")
  ResponseEntity<IntegrationSaveResponse> save(
      @ModelAttribute IntegrationSettingsForm settingsForm) {
    try {
      settings.save(settingsForm.toSettings());
      return ResponseEntity.ok(new IntegrationSaveResponse(true, "配置已保存并立即生效", Map.of()));
    } catch (IntegrationSettingsValidationException exception) {
      return ResponseEntity.badRequest()
          .body(
              new IntegrationSaveResponse(
                  false, "配置未保存：" + exception.getMessage(), exception.fieldErrors()));
    } catch (DataAccessException exception) {
      // 管理台异步操作始终返回 JSON，数据库异常不能再转发到 Whitelabel /error。
      return ResponseEntity.internalServerError()
          .body(new IntegrationSaveResponse(false, "配置保存失败，请查看服务端日志", Map.of()));
    }
  }

  private void preventCaching(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");
    response.setHeader(HttpHeaders.PRAGMA, "no-cache");
  }

  /** 管理后台异步测试接口的最小响应，避免重新渲染整个配置页面。 */
  private record IntegrationTestResponse(boolean success, String message) {}

  /** 配置异步保存结果；字段错误用于页面原位标记并定位对应配置组。 */
  private record IntegrationSaveResponse(
      boolean success, String message, Map<String, String> fieldErrors) {}

  /** 模型目录异步刷新结果；只更新页面计数和右上角通知。 */
  private record ModelRefreshResponse(boolean success, String message, long catalogCount) {}
}
