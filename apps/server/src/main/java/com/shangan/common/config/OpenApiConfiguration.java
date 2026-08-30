package com.shangan.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 固定 V1 OpenAPI 元信息，便于生成可审查、可冻结的中文接口合同。 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
  @Bean
  OpenAPI shanganOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("上岸 V1 API")
                .description("iOS 客户端使用的只读与学习业务接口；所有业务路由统一使用 /api/v1 前缀。")
                .version("0.1.0-rc1"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
  }

  /** CurrentUser 由 Bearer Token 解析，不是客户端可提交的查询参数。 */
  @Bean
  ParameterCustomizer hideResolvedCurrentUser() {
    return (parameter, methodParameter) ->
        methodParameter.getParameterType().equals(com.shangan.common.auth.CurrentUser.class)
            ? null
            : parameter;
  }

  /** 除公开认证与签名播放票据外，所有 API 操作都标注 Bearer JWT 安全要求。 */
  @Bean
  OperationCustomizer bearerSecurityRequirement() {
    return (operation, handlerMethod) -> {
      Class<?> controller = handlerMethod.getBeanType();
      String method = handlerMethod.getMethod().getName();
      boolean publicAuthentication =
          controller.equals(com.shangan.identity.api.AuthController.class)
              && java.util.Set.of("login", "refresh", "logout").contains(method);
      boolean signedPlayback =
          controller.equals(com.shangan.learning.api.PlaybackProxyController.class);
      if (!publicAuthentication && !signedPlayback) {
        operation.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
      }
      return operation;
    };
  }
}
