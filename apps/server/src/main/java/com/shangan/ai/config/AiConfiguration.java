package com.shangan.ai.config;

import com.shangan.ai.application.AiChatEngine;
import com.shangan.ai.application.ReadOnlyStudyTools;
import com.shangan.ai.application.RuntimeAiChatEngine;
import com.shangan.ai.infrastructure.McpToolAllowlist;
import com.shangan.common.integration.IntegrationSettingsProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册按请求读取运行时配置的 AI 引擎，避免在 Spring 启动时固定捕获外部服务地址和密钥。 */
@Configuration(proxyBeanMethods = false)
public class AiConfiguration {

  @Bean
  AiChatEngine aiChatEngine(
      IntegrationSettingsProvider settings, ReadOnlyStudyTools tools, McpToolAllowlist allowlist) {
    return new RuntimeAiChatEngine(settings, tools, allowlist);
  }
}
