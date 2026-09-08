package com.shangan.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 提供所有业务模块共享的基础依赖。 */
@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {

  /** 统一提供 UTC 时钟。领域和应用代码必须注入该 Bean，不得直接读取系统时间。 */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * 供 Emby 响应解析与配置 JSON 序列化使用的 Jackson 2 ObjectMapper。
   *
   * <p>Spring Boot 4 的 Web 层默认使用 Jackson 3，容器中不再自动注册 Jackson 2 的 ObjectMapper， 因此这里显式提供一个。
   */
  @Bean
  @ConditionalOnMissingBean(ObjectMapper.class)
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
