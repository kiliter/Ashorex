package com.shangan.admin;

import com.shangan.common.integration.IntegrationSettingsValidationException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import com.shangan.media.emby.EmbyHealthService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运行配置：只保留 Emby 与 Server 酱，以及 feature 开关。 */
@RestController
@RequestMapping("/admin/api")
public class SettingsAdminController {

  private final RuntimeIntegrationSettingsService settings;
  private final EmbyHealthService embyHealth;

  public SettingsAdminController(
      RuntimeIntegrationSettingsService settings, EmbyHealthService embyHealth) {
    this.settings = settings;
    this.embyHealth = embyHealth;
  }

  @GetMapping("/settings")
  SettingsResponse settings() {
    RuntimeIntegrationSettings current = settings.current();
    RuntimeIntegrationSettings.Emby emby = current.emby();
    RuntimeIntegrationSettings.ServerChan serverChan = current.serverChan();
    // 密钥只回显「是否已配置」，绝不把明文下发到浏览器。
    return new SettingsResponse(
        new EmbyView(
            emby.baseUrl(),
            emby.userId(),
            emby.timeoutSeconds(),
            emby.apiKey() != null && !emby.apiKey().isBlank()),
        new ServerChanView(
            serverChan.sendKey() != null && !serverChan.sendKey().isBlank(),
            serverChan.timeoutSeconds(),
            serverChan.nagEnabled(),
            serverChan.dailyDigestEnabled()),
        new FeaturesView(
            current.features().documentResources(), current.features().maxDocumentSizeMb()),
        embyHealth.status());
  }

  @PostMapping("/settings")
  ResponseEntity<Void> save(@RequestBody SettingsRequest request) {
    RuntimeIntegrationSettings current = settings.current();
    // 密钥留空表示保持原值，避免前端需要持有明文。
    String apiKey =
        request.embyApiKey() == null || request.embyApiKey().isBlank()
            ? current.emby().apiKey()
            : request.embyApiKey().trim();
    String sendKey =
        request.serverChanSendKey() == null || request.serverChanSendKey().isBlank()
            ? current.serverChan().sendKey()
            : request.serverChanSendKey().trim();
    settings.save(
        new RuntimeIntegrationSettings(
            new RuntimeIntegrationSettings.Emby(
                request.embyBaseUrl(), apiKey, request.embyUserId(), request.embyTimeoutSeconds()),
            current.embyLibraries(),
            new RuntimeIntegrationSettings.ServerChan(
                sendKey,
                request.serverChanTimeoutSeconds(),
                request.serverChanNagEnabled(),
                request.serverChanDailyDigestEnabled()),
            new RuntimeIntegrationSettings.Features(
                request.documentResources(), request.maxDocumentSizeMb()),
            0L));
    return ResponseEntity.noContent().build();
  }

  /**
   * Emby 连通性测试。
   *
   * <p>职责：对当前已保存的运行配置执行一次短超时只读探测，把结论回给管理后台的「测试连接」按钮。
   *
   * <p>边界：只读，不修改任何配置；未配置时返回 {@code ok=false} 与「未配置」提示而不是错误状态码， 因此前端始终能拿到 200 与可展示文案。响应中不含 Base
   * URL、API Key、Emby 原始路径与异常堆栈。
   */
  @PostMapping("/settings/test-emby")
  TestEmbyResponse testEmby() {
    EmbyHealthService.Probe probe = embyHealth.probe();
    return new TestEmbyResponse(probe.ok(), probe.status(), probe.message(), probe.latencyMs());
  }

  /** 配置校验失败时返回字段级错误，前端逐项高亮。 */
  @ExceptionHandler(IntegrationSettingsValidationException.class)
  ProblemDetail onValidationFailure(IntegrationSettingsValidationException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, String.join("；", exception.fieldErrors().values()));
    problem.setTitle("运行配置校验失败");
    problem.setProperty("errorCode", "SETTINGS_INVALID");
    problem.setProperty("fieldErrors", exception.fieldErrors());
    return problem;
  }

  /** 运行配置响应。 */
  public record SettingsResponse(
      EmbyView emby, ServerChanView serverChan, FeaturesView features, Object embyStatus) {}

  /** Emby 配置视图；`apiKeyConfigured` 代替明文密钥。 */
  public record EmbyView(
      String baseUrl, String userId, int timeoutSeconds, boolean apiKeyConfigured) {}

  /**
   * Emby 连通性测试结果。
   *
   * @param ok 是否连通成功；未配置也返回 false
   * @param status 稳定中文状态：未配置 / 可用 / 不可用，可直接驱动状态点
   * @param message 面向管理员的可操作中文文案，已脱敏
   * @param latencyMs 探测耗时毫秒；未配置时为 0
   */
  public record TestEmbyResponse(boolean ok, String status, String message, long latencyMs) {}

  /** Server 酱配置视图。 */
  public record ServerChanView(
      boolean sendKeyConfigured,
      int timeoutSeconds,
      boolean nagEnabled,
      boolean dailyDigestEnabled) {}

  /** 功能开关视图。 */
  public record FeaturesView(boolean documentResources, int maxDocumentSizeMb) {}

  /** 保存请求；两个密钥留空表示不修改。 */
  public record SettingsRequest(
      String embyBaseUrl,
      String embyApiKey,
      String embyUserId,
      int embyTimeoutSeconds,
      String serverChanSendKey,
      int serverChanTimeoutSeconds,
      boolean serverChanNagEnabled,
      boolean serverChanDailyDigestEnabled,
      boolean documentResources,
      int maxDocumentSizeMb) {

    /** 保留给未来的批量校验扩展点，当前仅用于文档化字段集合。 */
    public Map<String, Object> asMap() {
      return Map.of("embyBaseUrl", embyBaseUrl, "embyTimeoutSeconds", embyTimeoutSeconds);
    }
  }
}
