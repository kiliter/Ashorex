package com.shangan.focus.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用 SQLite 保存用户自己的考试名称和时长预置。 */
@Repository
public class JdbcMockExamPresetRepository implements MockExamPresetRepository {
  private final JdbcClient jdbc;

  public JdbcMockExamPresetRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<PresetRow> findAll(String userId) {
    return jdbc.sql(
            "select id,user_id,name,duration_seconds,sort_order from mock_exam_presets "
                + "where user_id=:userId order by sort_order,id")
        .param("userId", userId)
        .query(
            (rs, row) ->
                new PresetRow(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("name"),
                    rs.getLong("duration_seconds"),
                    rs.getInt("sort_order")))
        .list();
  }

  @Override
  public Optional<PresetRow> findOwned(String userId, String presetId) {
    return jdbc.sql(
            "select id,user_id,name,duration_seconds,sort_order from mock_exam_presets "
                + "where id=:id and user_id=:userId")
        .params(Map.of("id", presetId, "userId", userId))
        .query(
            (rs, row) ->
                new PresetRow(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("name"),
                    rs.getLong("duration_seconds"),
                    rs.getInt("sort_order")))
        .optional();
  }

  @Override
  public void insert(PresetRow preset, Instant now) {
    jdbc.sql(
            "insert into mock_exam_presets "
                + "(id,user_id,name,duration_seconds,sort_order,created_at,updated_at) "
                + "values (:id,:userId,:name,:duration,:sortOrder,:now,:now)")
        .param("id", preset.id())
        .param("userId", preset.userId())
        .param("name", preset.name())
        .param("duration", preset.durationSeconds())
        .param("sortOrder", preset.sortOrder())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void update(
      String userId,
      String presetId,
      String name,
      long durationSeconds,
      int sortOrder,
      Instant now) {
    jdbc.sql(
            "update mock_exam_presets set name=:name,duration_seconds=:duration,"
                + "sort_order=:sortOrder,updated_at=:now where id=:id and user_id=:userId")
        .param("name", name)
        .param("duration", durationSeconds)
        .param("sortOrder", sortOrder)
        .param("now", now.toEpochMilli())
        .param("id", presetId)
        .param("userId", userId)
        .update();
  }

  @Override
  public boolean delete(String userId, String presetId) {
    return jdbc.sql("delete from mock_exam_presets where id=:id and user_id=:userId")
            .params(Map.of("id", presetId, "userId", userId))
            .update()
        == 1;
  }
}
