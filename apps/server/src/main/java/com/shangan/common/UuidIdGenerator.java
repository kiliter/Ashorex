package com.shangan.common;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** 使用随机 UUID 生成符合 V1 数据规则的字符串 ID。 */
@Component
public final class UuidIdGenerator implements IdGenerator {

  @Override
  public String nextId() {
    return UUID.randomUUID().toString();
  }
}
