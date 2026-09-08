package com.shangan.nag.application;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.BarkEndpointPolicy;
import com.shangan.nag.domain.BarkSettings;
import com.shangan.nag.infrastructure.BarkSettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 校验并保存当前用户自己的推送目的地；空密钥保留已保存值。 */
@Service
public class BarkSettingsService {
  private final BarkSettingsRepository repository;

  private final BarkEndpointPolicy endpoints;

  public BarkSettingsService(BarkSettingsRepository repository, BarkEndpointPolicy endpoints) {
    this.repository = repository;
    this.endpoints = endpoints;
  }

  public BarkSettings get(String userId) {
    return repository.find(userId);
  }

  @Transactional
  public BarkSettings save(String userId, String baseUrl, String deviceKey, boolean enabled) {
    var previous = get(userId);
    String url = baseUrl == null ? previous.baseUrl() : baseUrl.trim().replaceAll("/+$", "");
    String key = deviceKey == null || deviceKey.isBlank() ? previous.deviceKey() : deviceKey.trim();
    url = endpoints.requirePersonalEndpoint(url);
    if (key.length() > 512 || (enabled && key.isBlank()))
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "BARK_SETTINGS_INVALID", "启用 Bark 前请填写有效设备 Key（最多 512 字符）");
    var value = new BarkSettings(url, key, enabled);
    repository.save(userId, value);
    return value;
  }
}
