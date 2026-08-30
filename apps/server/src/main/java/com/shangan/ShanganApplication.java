package com.shangan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 上岸 V1 模块化单体服务端入口。 */
@SpringBootApplication
public class ShanganApplication {

  private ShanganApplication() {}

  /** 启动 Spring Boot 应用。 */
  public static void main(String[] args) {
    SpringApplication.run(ShanganApplication.class, args);
  }
}
