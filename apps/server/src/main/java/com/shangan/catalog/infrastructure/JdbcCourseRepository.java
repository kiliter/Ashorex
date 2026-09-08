package com.shangan.catalog.infrastructure;

import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceMetadata;
import com.shangan.catalog.domain.ResourceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 课程域的 SQLite 持久化实现。元数据投影每次同步整表重写。 */
@Repository
public class JdbcCourseRepository implements CourseRepository {

  private static final String SELECT_COURSE =
      """
      SELECT id, external_source, external_ref, title, overview, production_year,
             sort_order, status, source_missing, last_synced_at, last_sync_error, archived_at
        FROM courses
      """;

  private static final String SELECT_RESOURCE =
      """
      SELECT id, course_id, resource_type, title, sort_index, duration_ms, page_count,
             external_ref, source_fingerprint, available, status, archived_at
        FROM learning_resources
      """;

  private final JdbcClient jdbcClient;

  public JdbcCourseRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public List<Course> findAll() {
    return jdbcClient
        .sql(SELECT_COURSE + " ORDER BY sort_order, title")
        .query(this::mapCourse)
        .list();
  }

  @Override
  public List<Course> findVisible() {
    return jdbcClient
        .sql(
            SELECT_COURSE
                + " WHERE status = 'ACTIVE' AND source_missing = 0 ORDER BY sort_order, title")
        .query(this::mapCourse)
        .list();
  }

  @Override
  public Optional<Course> findById(String id) {
    return jdbcClient
        .sql(SELECT_COURSE + " WHERE id = :id")
        .param("id", id)
        .query(this::mapCourse)
        .optional();
  }

  @Override
  public Optional<Course> findByExternalRef(String externalSource, String externalRef) {
    return jdbcClient
        .sql(SELECT_COURSE + " WHERE external_source = :source AND external_ref = :ref")
        .param("source", externalSource)
        .param("ref", externalRef)
        .query(this::mapCourse)
        .optional();
  }

  @Override
  public void insertCourse(Course course, Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO courses (
                id, external_source, external_ref, title, overview, production_year,
                sort_order, status, source_missing, last_synced_at, last_sync_error,
                archived_at, created_at, updated_at
            ) VALUES (
                :id, :source, :ref, :title, :overview, :year,
                :sortOrder, :status, :sourceMissing, :lastSyncedAt, :lastSyncError,
                :archivedAt, :now, :now
            )
            """)
        .param("id", course.id())
        .param("source", course.externalSource())
        .param("ref", course.externalRef())
        .param("title", course.title())
        .param("overview", course.overview())
        .param("year", course.productionYear())
        .param("sortOrder", course.sortOrder())
        .param("status", course.status().name())
        .param("sourceMissing", course.sourceMissing() ? 1 : 0)
        .param("lastSyncedAt", millis(course.lastSyncedAt()))
        .param("lastSyncError", course.lastSyncError())
        .param("archivedAt", millis(course.archivedAt()))
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void updateSyncedFields(
      String courseId,
      String title,
      String overview,
      Integer productionYear,
      boolean sourceMissing,
      String lastSyncError,
      Instant lastSyncedAt) {
    jdbcClient
        .sql(
            """
            UPDATE courses
               SET title = :title,
                   overview = :overview,
                   production_year = :year,
                   source_missing = :sourceMissing,
                   last_sync_error = :error,
                   last_synced_at = :syncedAt,
                   updated_at = :syncedAt
             WHERE id = :id
            """)
        .param("title", title)
        .param("overview", overview)
        .param("year", productionYear)
        .param("sourceMissing", sourceMissing ? 1 : 0)
        .param("error", lastSyncError)
        .param("syncedAt", lastSyncedAt.toEpochMilli())
        .param("id", courseId)
        .update();
  }

  /** 与课时迁移处于同一事务，保证后续同步使用已确认的新父节点。 */
  @Override
  public void updateCourseExternalRef(String courseId, String externalRef, Instant now) {
    jdbcClient
        .sql("UPDATE courses SET external_ref = :ref, updated_at = :now WHERE id = :id")
        .param("ref", externalRef)
        .param("now", now.toEpochMilli())
        .param("id", courseId)
        .update();
  }

  @Override
  public void updateSortOrder(String courseId, int sortOrder, Instant now) {
    jdbcClient
        .sql("UPDATE courses SET sort_order = :sortOrder, updated_at = :now WHERE id = :id")
        .param("sortOrder", sortOrder)
        .param("now", now.toEpochMilli())
        .param("id", courseId)
        .update();
  }

  @Override
  public void updateStatus(String courseId, boolean archived, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE courses
               SET status = :status, archived_at = :archivedAt, updated_at = :now
             WHERE id = :id
            """)
        .param("status", archived ? CatalogStatus.ARCHIVED.name() : CatalogStatus.ACTIVE.name())
        .param("archivedAt", archived ? now.toEpochMilli() : null)
        .param("now", now.toEpochMilli())
        .param("id", courseId)
        .update();
  }

  @Override
  public void markSourceMissing(String courseId, boolean sourceMissing, String error, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE courses
               SET source_missing = :missing, last_sync_error = :error, updated_at = :now
             WHERE id = :id
            """)
        .param("missing", sourceMissing ? 1 : 0)
        .param("error", error)
        .param("now", now.toEpochMilli())
        .param("id", courseId)
        .update();
  }

  @Override
  public List<LearningResource> findResourcesByCourse(String courseId) {
    return jdbcClient
        .sql(SELECT_RESOURCE + " WHERE course_id = :courseId ORDER BY sort_index, title")
        .param("courseId", courseId)
        .query(this::mapResource)
        .list();
  }

  @Override
  public List<LearningResource> findVisibleResourcesByCourse(String courseId) {
    return jdbcClient
        .sql(
            SELECT_RESOURCE
                + """
                 WHERE course_id = :courseId
                   AND status = 'ACTIVE'
                   AND available = 1
                 ORDER BY sort_index, title
                """)
        .param("courseId", courseId)
        .query(this::mapResource)
        .list();
  }

  @Override
  public Optional<LearningResource> findResourceById(String id) {
    return jdbcClient
        .sql(SELECT_RESOURCE + " WHERE id = :id")
        .param("id", id)
        .query(this::mapResource)
        .optional();
  }

  @Override
  public void insertResource(LearningResource resource, Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO learning_resources (
                id, course_id, resource_type, title, sort_index, duration_ms, page_count,
                external_ref, source_fingerprint, available, status, archived_at,
                created_at, updated_at
            ) VALUES (
                :id, :courseId, :type, :title, :sortIndex, :durationMs, :pageCount,
                :ref, :fingerprint, :available, :status, :archivedAt, :now, :now
            )
            """)
        .param("id", resource.id())
        .param("courseId", resource.courseId())
        .param("type", resource.resourceType().name())
        .param("title", resource.title())
        .param("sortIndex", resource.sortIndex())
        .param("durationMs", resource.durationMs())
        .param("pageCount", resource.pageCount())
        .param("ref", resource.externalRef())
        .param("fingerprint", resource.sourceFingerprint())
        .param("available", resource.available() ? 1 : 0)
        .param("status", resource.status().name())
        .param("archivedAt", millis(resource.archivedAt()))
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void updateResourceExternalRef(String resourceId, String externalRef, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE learning_resources
               SET external_ref = :ref, available = 1, updated_at = :now
             WHERE id = :id
            """)
        .param("ref", externalRef)
        .param("now", now.toEpochMilli())
        .param("id", resourceId)
        .update();
  }

  @Override
  public void updateResourceSyncedFields(
      String resourceId,
      String title,
      int sortIndex,
      Long durationMs,
      Integer pageCount,
      String sourceFingerprint,
      Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE learning_resources
               SET title = :title,
                   sort_index = :sortIndex,
                   duration_ms = :durationMs,
                   page_count = COALESCE(:pageCount, page_count),
                   source_fingerprint = COALESCE(:fingerprint, source_fingerprint),
                   updated_at = :now
             WHERE id = :id
            """)
        .param("title", title)
        .param("sortIndex", sortIndex)
        .param("durationMs", durationMs)
        .param("pageCount", pageCount)
        .param("fingerprint", sourceFingerprint)
        .param("now", now.toEpochMilli())
        .param("id", resourceId)
        .update();
  }

  @Override
  public void updateResourceAvailability(String resourceId, boolean available, Instant now) {
    jdbcClient
        .sql(
            "UPDATE learning_resources SET available = :available, updated_at = :now WHERE id = :id")
        .param("available", available ? 1 : 0)
        .param("now", now.toEpochMilli())
        .param("id", resourceId)
        .update();
  }

  @Override
  public void updateResourceStatus(String resourceId, boolean archived, Instant now) {
    jdbcClient
        .sql(
            """
            UPDATE learning_resources
               SET status = :status, archived_at = :archivedAt, updated_at = :now
             WHERE id = :id
            """)
        .param("status", archived ? CatalogStatus.ARCHIVED.name() : CatalogStatus.ACTIVE.name())
        .param("archivedAt", archived ? now.toEpochMilli() : null)
        .param("now", now.toEpochMilli())
        .param("id", resourceId)
        .update();
  }

  @Override
  public boolean updateResourcePageCountIfAbsent(String resourceId, int pageCount, Instant now) {
    int updated =
        jdbcClient
            .sql(
                """
                UPDATE learning_resources
                   SET page_count = :pageCount, updated_at = :now
                 WHERE id = :id AND page_count IS NULL
                """)
            .param("pageCount", pageCount)
            .param("now", now.toEpochMilli())
            .param("id", resourceId)
            .update();
    return updated > 0;
  }

  @Override
  public void replaceTaxonomy(String courseId, ResourceMetadata.CourseMetadata metadata) {
    jdbcClient
        .sql("DELETE FROM course_genres WHERE course_id = :courseId")
        .param("courseId", courseId)
        .update();
    jdbcClient
        .sql("DELETE FROM course_tags WHERE course_id = :courseId")
        .param("courseId", courseId)
        .update();
    jdbcClient
        .sql("DELETE FROM course_people WHERE course_id = :courseId")
        .param("courseId", courseId)
        .update();
    int order = 0;
    for (String genre : metadata.genres()) {
      jdbcClient
          .sql(
              """
              INSERT INTO course_genres (course_id, genre, sort_order)
              VALUES (:courseId, :genre, :sortOrder)
              ON CONFLICT(course_id, genre) DO NOTHING
              """)
          .param("courseId", courseId)
          .param("genre", genre)
          .param("sortOrder", order++)
          .update();
    }
    order = 0;
    for (String tag : metadata.tags()) {
      jdbcClient
          .sql(
              """
              INSERT INTO course_tags (course_id, tag, sort_order)
              VALUES (:courseId, :tag, :sortOrder)
              ON CONFLICT(course_id, tag) DO NOTHING
              """)
          .param("courseId", courseId)
          .param("tag", tag)
          .param("sortOrder", order++)
          .update();
    }
    order = 0;
    for (ResourceMetadata.Person person : metadata.people()) {
      jdbcClient
          .sql(
              """
              INSERT INTO course_people (course_id, person_name, role, sort_order)
              VALUES (:courseId, :name, :role, :sortOrder)
              ON CONFLICT(course_id, person_name) DO NOTHING
              """)
          .param("courseId", courseId)
          .param("name", person.name())
          .param("role", person.role() == null ? "" : person.role())
          .param("sortOrder", order++)
          .update();
    }
  }

  @Override
  public List<String> genresOf(String courseId) {
    return jdbcClient
        .sql("SELECT genre FROM course_genres WHERE course_id = :courseId ORDER BY sort_order")
        .param("courseId", courseId)
        .query(String.class)
        .list();
  }

  @Override
  public List<String> tagsOf(String courseId) {
    return jdbcClient
        .sql("SELECT tag FROM course_tags WHERE course_id = :courseId ORDER BY sort_order")
        .param("courseId", courseId)
        .query(String.class)
        .list();
  }

  @Override
  public List<ResourceMetadata.Person> peopleOf(String courseId) {
    return jdbcClient
        .sql(
            """
            SELECT person_name, role
              FROM course_people
             WHERE course_id = :courseId
             ORDER BY sort_order
            """)
        .param("courseId", courseId)
        .query(
            (row, rowNumber) ->
                new ResourceMetadata.Person(row.getString("person_name"), row.getString("role")))
        .list();
  }

  @Override
  public void insertSourceMapping(
      String id,
      String resourceId,
      String oldExternalRef,
      String newExternalRef,
      String matchedBy,
      String confirmedBy,
      Instant now) {
    jdbcClient
        .sql(
            """
            INSERT INTO resource_source_mappings (
                id, resource_id, old_external_ref, new_external_ref,
                matched_by, confirmed_by, created_at
            ) VALUES (:id, :resourceId, :oldRef, :newRef, :matchedBy, :confirmedBy, :now)
            """)
        .param("id", id)
        .param("resourceId", resourceId)
        .param("oldRef", oldExternalRef)
        .param("newRef", newExternalRef)
        .param("matchedBy", matchedBy)
        .param("confirmedBy", confirmedBy == null ? "" : confirmedBy)
        .param("now", now.toEpochMilli())
        .update();
  }

  private Long millis(Instant instant) {
    return instant == null ? null : instant.toEpochMilli();
  }

  private Course mapCourse(ResultSet row, int rowNumber) throws SQLException {
    return new Course(
        row.getString("id"),
        row.getString("external_source"),
        row.getString("external_ref"),
        row.getString("title"),
        row.getString("overview"),
        nullableInt(row, "production_year"),
        row.getInt("sort_order"),
        CatalogStatus.valueOf(row.getString("status")),
        row.getInt("source_missing") == 1,
        nullableInstant(row, "last_synced_at"),
        row.getString("last_sync_error"),
        nullableInstant(row, "archived_at"));
  }

  private LearningResource mapResource(ResultSet row, int rowNumber) throws SQLException {
    return new LearningResource(
        row.getString("id"),
        row.getString("course_id"),
        ResourceType.valueOf(row.getString("resource_type")),
        row.getString("title"),
        row.getInt("sort_index"),
        nullableLong(row, "duration_ms"),
        nullableInt(row, "page_count"),
        row.getString("external_ref"),
        row.getString("source_fingerprint"),
        row.getInt("available") == 1,
        CatalogStatus.valueOf(row.getString("status")),
        nullableInstant(row, "archived_at"));
  }

  private Instant nullableInstant(ResultSet row, String column) throws SQLException {
    long value = row.getLong(column);
    return row.wasNull() ? null : Instant.ofEpochMilli(value);
  }

  private Long nullableLong(ResultSet row, String column) throws SQLException {
    long value = row.getLong(column);
    return row.wasNull() ? null : value;
  }

  private Integer nullableInt(ResultSet row, String column) throws SQLException {
    int value = row.getInt(column);
    return row.wasNull() ? null : value;
  }
}
