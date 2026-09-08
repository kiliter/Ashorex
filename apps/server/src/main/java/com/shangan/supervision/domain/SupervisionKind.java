package com.shangan.supervision.domain;

/** 督学关系类型；每个学员必须且只能有一个未归档的 PRIMARY 督学人。 */
public enum SupervisionKind {
  PRIMARY,
  COLLABORATOR
}
