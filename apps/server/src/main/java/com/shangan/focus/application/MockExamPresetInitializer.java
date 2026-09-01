package com.shangan.focus.application;

import com.shangan.identity.application.NewUserInitializer;
import org.springframework.stereotype.Component;

/** 为以后新建的用户补齐系统约定的三个模拟考试预置。 */
@Component
public class MockExamPresetInitializer implements NewUserInitializer {
  private final MockExamPresetService presets;

  public MockExamPresetInitializer(MockExamPresetService presets) {
    this.presets = presets;
  }

  @Override
  public void initialize(String userId) {
    presets.createDefaults(userId);
  }
}
