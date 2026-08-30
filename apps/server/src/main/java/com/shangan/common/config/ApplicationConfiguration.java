package com.shangan.common.config;

import java.time.Clock;
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
}
