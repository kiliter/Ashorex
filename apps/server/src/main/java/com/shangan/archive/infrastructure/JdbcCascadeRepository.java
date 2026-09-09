package com.shangan.archive.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 级联删除的 SQLite 实现；删除顺序与 Spec 11.4 严格一致。 */
@Repository
public class JdbcCascadeRepository implements CascadeRepository {

  private final JdbcClient jdbcClient;

  public JdbcCascadeRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Map<String, Integer> countCourseRows(String courseId) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put(
        "todo_attachments",
        count(
            """
        SELECT count(*) FROM todo_attachments a
          JOIN todos t ON t.id = a.todo_id
          JOIN learning_resources r ON r.id = t.resource_id
         WHERE r.course_id = :courseId
        """,
            courseId));
    counts.put(
        "todo_progress_events",
        count(
            """
        SELECT count(*) FROM todo_progress_events e
          JOIN todos t ON t.id = e.todo_id
          JOIN learning_resources r ON r.id = t.resource_id
         WHERE r.course_id = :courseId
        """,
            courseId));
    counts.put(
        "todo_deletions",
        count(
            """
        SELECT count(*) FROM todo_deletions d
          JOIN learning_resources r ON r.id = d.resource_id
         WHERE r.course_id = :courseId
        """,
            courseId));
    counts.put(
        "todos",
        count(
            """
        SELECT count(*) FROM todos t
          JOIN learning_resources r ON r.id = t.resource_id
         WHERE r.course_id = :courseId
        """,
            courseId));
    counts.put(
        "lesson_watch_states",
        count(
            """
        SELECT count(*) FROM lesson_watch_states s
          JOIN learning_resources r ON r.id = s.resource_id
         WHERE r.course_id = :courseId
        """,
            courseId));
    counts.put(
        "course_genres",
        count("SELECT count(*) FROM course_genres WHERE course_id = :courseId", courseId));
    counts.put(
        "course_tags",
        count("SELECT count(*) FROM course_tags WHERE course_id = :courseId", courseId));
    counts.put(
        "course_people",
        count("SELECT count(*) FROM course_people WHERE course_id = :courseId", courseId));
    counts.put(
        "resource_source_mappings",
        count(
            """
        SELECT count(*) FROM resource_source_mappings m
          JOIN learning_resources r ON r.id = m.resource_id
         WHERE r.course_id = :courseId
        """,
            courseId));
    counts.put(
        "learning_resources",
        count("SELECT count(*) FROM learning_resources WHERE course_id = :courseId", courseId));
    counts.put("courses", 1);
    return counts;
  }

  @Override
  public Map<String, Integer> countUserRows(String userId) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put(
        "todo_attachments",
        countByUser("SELECT count(*) FROM todo_attachments WHERE user_id = :userId", userId));
    counts.put(
        "todo_progress_events",
        countByUser("SELECT count(*) FROM todo_progress_events WHERE user_id = :userId", userId));
    counts.put("todos", countByUser("SELECT count(*) FROM todos WHERE user_id = :userId", userId));
    counts.put(
        "todo_deletions",
        countByUser("SELECT count(*) FROM todo_deletions WHERE user_id = :userId", userId));
    counts.put(
        "lesson_watch_states",
        countByUser("SELECT count(*) FROM lesson_watch_states WHERE user_id = :userId", userId));
    counts.put(
        "exam_goals",
        countByUser("SELECT count(*) FROM exam_goals WHERE user_id = :userId", userId));
    counts.put(
        "nag_deliveries",
        countByUser(
            """
        SELECT count(*) FROM nag_deliveries d
          JOIN nags n ON n.id = d.nag_id
         WHERE n.user_id = :userId
        """,
            userId));
    counts.put("nags", countByUser("SELECT count(*) FROM nags WHERE user_id = :userId", userId));
    counts.put(
        "nag_policies",
        countByUser("SELECT count(*) FROM nag_policies WHERE user_id = :userId", userId));
    counts.put(
        "supervisions",
        countByUser(
            """
        SELECT count(*) FROM supervisions
         WHERE learner_user_id = :userId OR supervisor_user_id = :userId
        """,
            userId));
    counts.put(
        "course_addition_receipts",
        countByUser(
            "SELECT count(*) FROM course_addition_receipts WHERE user_id = :userId", userId));
    counts.put(
        "user_presence",
        countByUser("SELECT count(*) FROM user_presence WHERE user_id = :userId", userId));
    // 个人推送密钥也列入用户删除预览与审计，不能只依赖隐式级联。
    counts.put(
        "user_bark_settings",
        countByUser("SELECT count(*) FROM user_bark_settings WHERE user_id = :userId", userId));
    counts.put(
        "refresh_tokens",
        countByUser("SELECT count(*) FROM refresh_tokens WHERE user_id = :userId", userId));
    counts.put(
        "user_roles",
        countByUser("SELECT count(*) FROM user_roles WHERE user_id = :userId", userId));
    counts.put("users", 1);
    return counts;
  }

  @Override
  public List<String> courseAttachmentPaths(String courseId) {
    return jdbcClient
        .sql(
            """
            SELECT a.storage_path FROM todo_attachments a
              JOIN todos t ON t.id = a.todo_id
              JOIN learning_resources r ON r.id = t.resource_id
             WHERE r.course_id = :courseId
            """)
        .param("courseId", courseId)
        .query(String.class)
        .list();
  }

  @Override
  public List<String> userAttachmentPaths(String userId) {
    return jdbcClient
        .sql("SELECT storage_path FROM todo_attachments WHERE user_id = :userId")
        .param("userId", userId)
        .query(String.class)
        .list();
  }

  @Override
  public ImpactSummary courseImpact(String courseId) {
    Integer users =
        jdbcClient
            .sql(
                """
                SELECT count(DISTINCT s.user_id) FROM lesson_watch_states s
                  JOIN learning_resources r ON r.id = s.resource_id
                 WHERE r.course_id = :courseId
                """)
            .param("courseId", courseId)
            .query(Integer.class)
            .single();
    Long watched =
        jdbcClient
            .sql(
                """
                SELECT COALESCE(sum(s.total_watched_ms), 0) FROM lesson_watch_states s
                  JOIN learning_resources r ON r.id = s.resource_id
                 WHERE r.course_id = :courseId
                """)
            .param("courseId", courseId)
            .query(Long.class)
            .single();
    Long bytes =
        jdbcClient
            .sql(
                """
                SELECT COALESCE(sum(a.size_bytes), 0) FROM todo_attachments a
                  JOIN todos t ON t.id = a.todo_id
                  JOIN learning_resources r ON r.id = t.resource_id
                 WHERE r.course_id = :courseId
                """)
            .param("courseId", courseId)
            .query(Long.class)
            .single();
    return new ImpactSummary(
        users == null ? 0 : users, watched == null ? 0 : watched, bytes == null ? 0 : bytes);
  }

  @Override
  public Map<String, Integer> deleteCourseCascade(String courseId) {
    Map<String, Integer> deleted = new LinkedHashMap<>();
    deleted.put(
        "todo_attachments",
        update(
            """
        DELETE FROM todo_attachments
         WHERE todo_id IN (SELECT t.id FROM todos t
                             JOIN learning_resources r ON r.id = t.resource_id
                            WHERE r.course_id = :courseId)
        """,
            courseId));
    deleted.put(
        "todo_progress_events",
        update(
            """
        DELETE FROM todo_progress_events
         WHERE todo_id IN (SELECT t.id FROM todos t
                             JOIN learning_resources r ON r.id = t.resource_id
                            WHERE r.course_id = :courseId)
        """,
            courseId));
    deleted.put(
        "todo_deletions",
        update(
            """
        DELETE FROM todo_deletions
         WHERE resource_id IN (SELECT id FROM learning_resources WHERE course_id = :courseId)
        """,
            courseId));
    deleted.put(
        "todos",
        update(
            """
        DELETE FROM todos
         WHERE resource_id IN (SELECT id FROM learning_resources WHERE course_id = :courseId)
        """,
            courseId));
    deleted.put(
        "lesson_watch_states",
        update(
            """
        DELETE FROM lesson_watch_states
         WHERE resource_id IN (SELECT id FROM learning_resources WHERE course_id = :courseId)
        """,
            courseId));
    deleted.put(
        "course_genres", update("DELETE FROM course_genres WHERE course_id = :courseId", courseId));
    deleted.put(
        "course_tags", update("DELETE FROM course_tags WHERE course_id = :courseId", courseId));
    deleted.put(
        "course_people", update("DELETE FROM course_people WHERE course_id = :courseId", courseId));
    deleted.put(
        "resource_source_mappings",
        update(
            """
        DELETE FROM resource_source_mappings
         WHERE resource_id IN (SELECT id FROM learning_resources WHERE course_id = :courseId)
        """,
            courseId));
    deleted.put(
        "learning_resources",
        update("DELETE FROM learning_resources WHERE course_id = :courseId", courseId));
    deleted.put("courses", update("DELETE FROM courses WHERE id = :courseId", courseId));
    return deleted;
  }

  @Override
  public Map<String, Integer> deleteUserCascade(String userId) {
    Map<String, Integer> deleted = new LinkedHashMap<>();
    deleted.put(
        "todo_attachments",
        updateByUser("DELETE FROM todo_attachments WHERE user_id = :userId", userId));
    deleted.put(
        "todo_progress_events",
        updateByUser("DELETE FROM todo_progress_events WHERE user_id = :userId", userId));
    deleted.put("todos", updateByUser("DELETE FROM todos WHERE user_id = :userId", userId));
    deleted.put(
        "todo_deletions",
        updateByUser("DELETE FROM todo_deletions WHERE user_id = :userId", userId));
    deleted.put(
        "lesson_watch_states",
        updateByUser("DELETE FROM lesson_watch_states WHERE user_id = :userId", userId));
    deleted.put(
        "exam_goals", updateByUser("DELETE FROM exam_goals WHERE user_id = :userId", userId));
    deleted.put(
        "nag_deliveries",
        updateByUser(
            "DELETE FROM nag_deliveries WHERE nag_id IN (SELECT id FROM nags WHERE user_id = :userId)",
            userId));
    deleted.put("nags", updateByUser("DELETE FROM nags WHERE user_id = :userId", userId));
    deleted.put(
        "nag_policies", updateByUser("DELETE FROM nag_policies WHERE user_id = :userId", userId));
    deleted.put(
        "supervisions",
        updateByUser(
            "DELETE FROM supervisions WHERE learner_user_id = :userId OR supervisor_user_id = :userId",
            userId));
    deleted.put(
        "course_addition_receipts",
        updateByUser("DELETE FROM course_addition_receipts WHERE user_id = :userId", userId));
    deleted.put(
        "user_presence", updateByUser("DELETE FROM user_presence WHERE user_id = :userId", userId));
    deleted.put(
        "user_bark_settings",
        updateByUser("DELETE FROM user_bark_settings WHERE user_id = :userId", userId));
    deleted.put(
        "refresh_tokens",
        updateByUser("DELETE FROM refresh_tokens WHERE user_id = :userId", userId));
    deleted.put(
        "user_roles", updateByUser("DELETE FROM user_roles WHERE user_id = :userId", userId));
    deleted.put("users", updateByUser("DELETE FROM users WHERE id = :userId", userId));
    return deleted;
  }

  @Override
  public OrphanReport scanOrphans() {
    int orphanTodos =
        plain(
            "SELECT count(*) FROM todos t LEFT JOIN users u ON u.id = t.user_id WHERE u.id IS NULL");
    int orphanEvents =
        plain(
            """
            SELECT count(*) FROM todo_progress_events e
              LEFT JOIN todos t ON t.id = e.todo_id
             WHERE t.id IS NULL
            """);
    int orphanAttachments =
        plain(
            """
            SELECT count(*) FROM todo_attachments a
              LEFT JOIN todos t ON t.id = a.todo_id
             WHERE t.id IS NULL
            """);
    int orphanWatchStates =
        plain(
            """
            SELECT count(*) FROM lesson_watch_states s
              LEFT JOIN learning_resources r ON r.id = s.resource_id
             WHERE r.id IS NULL
            """);
    int orphanNags =
        plain(
            "SELECT count(*) FROM nags n LEFT JOIN users u ON u.id = n.user_id WHERE u.id IS NULL");
    return new OrphanReport(
        orphanTodos, orphanEvents, orphanAttachments, orphanWatchStates, orphanNags);
  }

  @Override
  public int countExpiredArchives(long archivedBefore) {
    int courses =
        jdbcClient
            .sql(
                """
                SELECT count(*) FROM courses
                 WHERE status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at < :before
                """)
            .param("before", archivedBefore)
            .query(Integer.class)
            .single();
    int users =
        jdbcClient
            .sql(
                """
                SELECT count(*) FROM users
                 WHERE status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at < :before
                """)
            .param("before", archivedBefore)
            .query(Integer.class)
            .single();
    return courses + users;
  }

  private int count(String sql, String courseId) {
    Integer value = jdbcClient.sql(sql).param("courseId", courseId).query(Integer.class).single();
    return value == null ? 0 : value;
  }

  private int countByUser(String sql, String userId) {
    Integer value = jdbcClient.sql(sql).param("userId", userId).query(Integer.class).single();
    return value == null ? 0 : value;
  }

  private int plain(String sql) {
    Integer value = jdbcClient.sql(sql).query(Integer.class).single();
    return value == null ? 0 : value;
  }

  private int update(String sql, String courseId) {
    return jdbcClient.sql(sql).param("courseId", courseId).update();
  }

  private int updateByUser(String sql, String userId) {
    return jdbcClient.sql(sql).param("userId", userId).update();
  }
}
