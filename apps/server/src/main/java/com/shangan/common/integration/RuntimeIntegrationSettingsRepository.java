package com.shangan.common.integration;

import java.util.Optional;

/** 运行时外部服务配置的单行持久化边界。 */
public interface RuntimeIntegrationSettingsRepository {
  Optional<RuntimeIntegrationSettings> find();

  void replace(RuntimeIntegrationSettings settings);
}
