package com.shangan.common;

/** 为领域对象提供可替换、可测试的字符串 ID。 */
public interface IdGenerator {

  /** 生成下一个唯一 ID。 */
  String nextId();
}
