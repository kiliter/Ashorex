package com.shangan.catalog.infrastructure;

import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.MediaItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用 JdbcClient 保存课程快照，远端同步不会覆盖本地管理字段。 */
@Repository
public class JdbcCourseRepository implements CourseRepository {

  private final JdbcClient jdbc;

  public JdbcCourseRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Course> findCourse(String courseId) {
    return jdbc.sql("select * from courses where id = :id")
        .param("id", courseId)
        .query(this::mapCourse)
        .optional();
  }

  @Override
  public List<Course> findAllCourses(boolean enabledOnly) {
    String sql =
        "select * from courses "
            + (enabledOnly ? "where enabled = 1 " : "")
            + "order by sort_order, name";
    return jdbc.sql(sql).query(this::mapCourse).list();
  }

  @Override
  public List<MediaItem> findMediaItems(String courseId, boolean enabledOnly) {
    String sql =
        "select * from media_items where course_id = :courseId "
            + (enabledOnly ? "and enabled = 1 and available = 1 " : "")
            + "order by sort_order, title";
    return jdbc.sql(sql).param("courseId", courseId).query(this::mapMediaItem).list();
  }

  @Override
  public Optional<MediaItem> findMediaItem(String mediaItemId) {
    return jdbc.sql("select * from media_items where id = :id")
        .param("id", mediaItemId)
        .query(this::mapMediaItem)
        .optional();
  }

  @Override
  public void insertCourse(Course course, Instant now) {
    jdbc.sql(
            """
            insert into courses (
              id, name, description, emby_parent_item_id, enabled, sort_order,
              last_synced_at, last_sync_error, created_at, updated_at
            ) values (
              :id, :name, :description, :parentId, :enabled, :sortOrder,
              null, null, :now, :now
            )
            """)
        .params(
            Map.of(
                "id", course.id(),
                "name", course.name(),
                "description", course.description(),
                "parentId", course.embyParentItemId(),
                "enabled", course.enabled() ? 1 : 0,
                "sortOrder", course.sortOrder(),
                "now", now.toEpochMilli()))
        .update();
  }

  @Override
  public void insertMediaItem(MediaItem item, Instant now) {
    jdbc.sql(
            """
            insert into media_items (
              id, course_id, emby_item_id, emby_item_type, source_fingerprint,
              title, duration_ms, enabled, sort_order, available, created_at, updated_at
            ) values (
              :id, :courseId, :embyId, :embyItemType, :sourceFingerprint,
              :title, :durationMs, :enabled, :sortOrder, 1, :now, :now
            )
            """)
        .param("id", item.id())
        .param("courseId", item.courseId())
        .param("embyId", item.embyItemId())
        .param("embyItemType", item.embyItemType())
        .param("sourceFingerprint", item.sourceFingerprint())
        .param("title", item.title())
        .param("durationMs", item.durationMs())
        .param("enabled", item.enabled() ? 1 : 0)
        .param("sortOrder", item.sortOrder())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void updateMediaItemFromRemote(MediaItem item, Instant now) {
    jdbc.sql(
            """
            update media_items set
              emby_item_id = :embyId,
              emby_item_type = :embyItemType,
              source_fingerprint = :sourceFingerprint,
              title = :title,
              duration_ms = :durationMs,
              available = 1,
              updated_at = :now
            where id = :id
            """)
        .param("id", item.id())
        .param("embyId", item.embyItemId())
        .param("embyItemType", item.embyItemType())
        .param("sourceFingerprint", item.sourceFingerprint())
        .param("title", item.title())
        .param("durationMs", item.durationMs())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void insertMediaItemSourceMapping(
      String id,
      String mediaItemId,
      String oldEmbyItemId,
      String newEmbyItemId,
      String matchType,
      Instant now) {
    jdbc.sql(
            """
            insert into media_item_source_mappings (
              id, media_item_id, old_emby_item_id, new_emby_item_id, match_type, mapped_at
            ) values (
              :id, :mediaItemId, :oldEmbyItemId, :newEmbyItemId, :matchType, :mappedAt
            )
            """)
        .params(
            Map.of(
                "id", id,
                "mediaItemId", mediaItemId,
                "oldEmbyItemId", oldEmbyItemId,
                "newEmbyItemId", newEmbyItemId,
                "matchType", matchType,
                "mappedAt", now.toEpochMilli()))
        .update();
  }

  @Override
  public void markUnavailableExceptMediaIds(
      String courseId, List<String> availableMediaItemIds, Instant now) {
    jdbc.sql("update media_items set available = 0, updated_at = :now where course_id = :courseId")
        .params(Map.of("now", now.toEpochMilli(), "courseId", courseId))
        .update();
    for (String mediaItemId : availableMediaItemIds) {
      jdbc.sql(
              "update media_items set available = 1, updated_at = :now "
                  + "where course_id = :courseId and id = :mediaItemId")
          .params(
              Map.of(
                  "now", now.toEpochMilli(),
                  "courseId", courseId,
                  "mediaItemId", mediaItemId))
          .update();
    }
  }

  @Override
  public void updateCourseSource(String courseId, String embyParentItemId, Instant now) {
    jdbc.sql("update courses set emby_parent_item_id = :parentId, updated_at = :now where id = :id")
        .params(Map.of("parentId", embyParentItemId, "now", now.toEpochMilli(), "id", courseId))
        .update();
  }

  @Override
  public void updateCourseSyncResult(String courseId, Instant syncedAt, String error) {
    jdbc.sql(
            "update courses set last_synced_at = :syncedAt, last_sync_error = :error, "
                + "updated_at = :syncedAt where id = :courseId")
        .param("syncedAt", syncedAt.toEpochMilli())
        .param("error", error)
        .param("courseId", courseId)
        .update();
  }

  @Override
  public void updateMediaControls(String mediaItemId, boolean enabled, int sortOrder) {
    jdbc.sql("update media_items set enabled = :enabled, sort_order = :sortOrder where id = :id")
        .params(Map.of("enabled", enabled ? 1 : 0, "sortOrder", sortOrder, "id", mediaItemId))
        .update();
  }

  private Course mapCourse(ResultSet rs, int row) throws SQLException {
    return new Course(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("emby_parent_item_id"),
        rs.getInt("enabled") == 1,
        rs.getInt("sort_order"),
        instantOrNull(rs, "last_synced_at"),
        rs.getString("last_sync_error"));
  }

  private MediaItem mapMediaItem(ResultSet rs, int row) throws SQLException {
    return new MediaItem(
        rs.getString("id"),
        rs.getString("course_id"),
        rs.getString("emby_item_id"),
        rs.getString("emby_item_type"),
        rs.getString("source_fingerprint"),
        rs.getString("title"),
        rs.getLong("duration_ms"),
        rs.getInt("enabled") == 1,
        rs.getInt("sort_order"),
        rs.getInt("available") == 1);
  }

  private Instant instantOrNull(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : Instant.ofEpochMilli(rs.getLong(column));
  }
}
