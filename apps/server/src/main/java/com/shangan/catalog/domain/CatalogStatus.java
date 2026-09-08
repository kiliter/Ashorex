package com.shangan.catalog.domain;

/** 课程与学习资源的生命周期状态；彻底删除只能在归档区执行（见 ADR-0029）。 */
public enum CatalogStatus {
  ACTIVE,
  ARCHIVED
}
