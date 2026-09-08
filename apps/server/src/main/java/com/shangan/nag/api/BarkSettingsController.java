package com.shangan.nag.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.nag.application.BarkSettingsService;
import com.shangan.nag.domain.BarkSettings;
import org.springframework.web.bind.annotation.*;

/** 只允许登录用户读取和修改自己的 Bark 配置；绝不回显设备密钥。 */
@RestController
@RequestMapping("/api/v1/me/bark")
public class BarkSettingsController {
  private final BarkSettingsService service;

  public BarkSettingsController(BarkSettingsService service) {
    this.service = service;
  }

  @GetMapping
  public View get(CurrentUser user) {
    return view(service.get(user.userId()));
  }

  @PutMapping
  public View save(CurrentUser user, @RequestBody Request request) {
    return view(
        service.save(user.userId(), request.baseUrl(), request.deviceKey(), request.enabled()));
  }

  private View view(BarkSettings settings) {
    return new View(settings.baseUrl(), settings.configured(), settings.enabled());
  }

  public record View(String baseUrl, boolean deviceKeyConfigured, boolean enabled) {}

  public record Request(String baseUrl, String deviceKey, boolean enabled) {}
}
