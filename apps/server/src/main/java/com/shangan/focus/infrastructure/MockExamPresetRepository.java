package com.shangan.focus.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 模拟考试预置持久化边界，所有查询都显式带用户所有权。 */
public interface MockExamPresetRepository {
  List<PresetRow> findAll(String userId);

  Optional<PresetRow> findOwned(String userId, String presetId);

  void insert(PresetRow preset, Instant now);

  void update(
      String userId,
      String presetId,
      String name,
      long durationSeconds,
      int sortOrder,
      Instant now);

  boolean delete(String userId, String presetId);

  record PresetRow(String id, String userId, String name, long durationSeconds, int sortOrder) {}
}
