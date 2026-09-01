package com.shangan.identity.application;

/** 新用户创建后的显式初始化扩展点，避免身份模块直接访问其他业务表。 */
public interface NewUserInitializer {

  /** 在用户创建事务内补齐该业务模块的默认数据；实现必须幂等。 */
  void initialize(String userId);
}
