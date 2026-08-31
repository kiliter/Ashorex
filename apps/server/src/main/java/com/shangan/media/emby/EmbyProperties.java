package com.shangan.media.emby;

import com.shangan.common.integration.IntegrationSettingsProvider;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 从服务端运行时配置提供者读取固定 Emby 目标，禁止普通客户端按请求覆盖。 */
@Component
public class EmbyProperties {
  private final IntegrationSettingsProvider settings;

  @Autowired
  public EmbyProperties(IntegrationSettingsProvider settings) {
    this.settings = settings;
  }

  /** 为不启动 Spring 的媒体契约测试提供固定快照，生产环境使用运行时 Provider 构造器。 */
  public EmbyProperties(String baseUrl, String apiKey, String userId) {
    this(
        () ->
            new RuntimeIntegrationSettings(
                new RuntimeIntegrationSettings.Emby(baseUrl, apiKey, userId), 0));
  }

  /** 取得单次 Emby 操作应使用的不可变配置快照。 */
  public RuntimeIntegrationSettings.Emby current() {
    return settings.current().emby();
  }
}
