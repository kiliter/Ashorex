package com.shangan.common.integration;

import java.util.Optional;

/** 运行时集成配置的持久化边界；配置固定单行。 */
public interface RuntimeIntegrationSettingsRepository {

  /** 读取当前配置行；首次启动时为空。 */
  Optional<RuntimeIntegrationSettings> find();

  /** 整行覆盖写入当前配置。 */
  void replace(RuntimeIntegrationSettings value);
}
