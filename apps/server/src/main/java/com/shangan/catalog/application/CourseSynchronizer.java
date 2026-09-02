package com.shangan.catalog.application;

/** 批量建课调用既有课程同步的窄接口，避免批量编排依赖同步服务的其他管理能力。 */
@FunctionalInterface
public interface CourseSynchronizer {

  void syncCourse(String courseId);
}
