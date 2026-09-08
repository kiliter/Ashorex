package com.shangan.archive.domain;

/** 支持「归档 → 彻底删除」两阶段的实体类型（见 ADR-0029）。 */
public enum ArchivableEntityType {
  COURSE,
  LEARNING_RESOURCE,
  USER,
  SUPERVISION
}
