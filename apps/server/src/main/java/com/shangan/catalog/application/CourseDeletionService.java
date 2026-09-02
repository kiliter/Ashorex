package com.shangan.catalog.application;

import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.CourseDeletionGraph;
import com.shangan.catalog.infrastructure.CourseDeletionRepository;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 校验归档状态、锁定影响预览，并在一个短事务中物理删除课程完整关联图。 */
@Service
public class CourseDeletionService {

  private static final Logger log = LoggerFactory.getLogger(CourseDeletionService.class);
  private static final int MAX_BATCH_SIZE = 50;

  private final CourseRepository courses;
  private final CourseDeletionRepository deletions;
  private final CourseAttachmentCleaner attachmentCleaner;
  private final CourseDeletionDerivedDataRefresher derivedDataRefresher;

  public CourseDeletionService(
      CourseRepository courses,
      CourseDeletionRepository deletions,
      CourseAttachmentCleaner attachmentCleaner,
      CourseDeletionDerivedDataRefresher derivedDataRefresher) {
    this.courses = courses;
    this.deletions = deletions;
    this.attachmentCleaner = attachmentCleaner;
    this.derivedDataRefresher = derivedDataRefresher;
  }

  /** 返回二次确认需要的实时统计和防止陈旧确认的校验令牌。 */
  @Transactional(readOnly = true)
  public Preview preview(List<String> submittedCourseIds) {
    List<String> courseIds = validateArchivedCourses(submittedCourseIds);
    CourseDeletionGraph.Impact impact = deletions.inspect(courseIds);
    if (impact.courseCount() != courseIds.size()) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "COURSE_DELETE_PREVIEW_STALE", "课程状态已变化，请刷新后重试");
    }
    return new Preview(courseIds, impact, token(courseIds, impact));
  }

  /** 重新计算影响并比对管理员确认的预览，匹配后才执行整批物理删除。 */
  @Transactional
  public int delete(List<String> submittedCourseIds, String confirmedToken) {
    List<String> courseIds = validateArchivedCourses(submittedCourseIds);
    CourseDeletionGraph.Impact currentImpact = deletions.inspect(courseIds);
    String currentToken = token(courseIds, currentImpact);
    if (confirmedToken == null
        || !MessageDigest.isEqual(
            currentToken.getBytes(StandardCharsets.UTF_8),
            confirmedToken.getBytes(StandardCharsets.UTF_8))) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "COURSE_DELETE_PREVIEW_STALE", "删除影响已变化，请重新查看并确认");
    }
    CourseDeletionGraph.DeletionResult result = deletions.deleteGraph(courseIds);
    afterCommit(result);
    return courseIds.size();
  }

  /** 同时兼容 V027 已写 removed_at 的历史课程，使它们能够从归档区真正删除。 */
  private List<String> validateArchivedCourses(List<String> submittedCourseIds) {
    List<String> courseIds = normalizedCourseIds(submittedCourseIds);
    for (String courseId : courseIds) {
      Course course =
          courses
              .findCourse(courseId)
              .orElseThrow(
                  () ->
                      new BusinessException(
                          HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "所选课程不存在，请刷新后重试"));
      if (course.enabled()) {
        throw new BusinessException(HttpStatus.CONFLICT, "COURSE_NOT_ARCHIVED", "只能删除已归档课程，请刷新后重试");
      }
    }
    return courseIds;
  }

  private List<String> normalizedCourseIds(List<String> submittedCourseIds) {
    LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
    if (submittedCourseIds != null) {
      for (String courseId : submittedCourseIds) {
        if (courseId != null && !courseId.isBlank()) uniqueIds.add(courseId.trim());
      }
    }
    if (uniqueIds.isEmpty()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "COURSE_SELECTION_REQUIRED", "请至少选择一门已归档课程");
    }
    if (uniqueIds.size() > MAX_BATCH_SIZE) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "COURSE_SELECTION_LIMIT", "一次最多删除 50 门课程");
    }
    return List.copyOf(uniqueIds);
  }

  /** 令牌只锁定安全 ID 和各类数量，不携带课程名称、正文或外部媒体信息。 */
  private String token(List<String> courseIds, CourseDeletionGraph.Impact impact) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(String.join("\n", courseIds).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(impact.toString().getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("运行环境缺少 SHA-256", exception);
    }
  }

  /** 文件清理和报表重建只能在数据库提交成功后运行，失败不得回滚已完成的物理删除。 */
  private void afterCommit(CourseDeletionGraph.DeletionResult result) {
    Runnable action =
        () -> {
          try {
            attachmentCleaner.delete(result.attachmentPaths());
          } catch (RuntimeException exception) {
            log.error("课程删除后的附件清理失败，fileCount={}", result.attachmentPaths().size());
          }
          try {
            derivedDataRefresher.refresh(result.affectedDays());
          } catch (RuntimeException exception) {
            log.error("课程删除后的派生数据刷新失败，dayCount={}", result.affectedDays().size());
          }
        };
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }

  public record Preview(List<String> courseIds, CourseDeletionGraph.Impact impact, String token) {
    public Preview {
      courseIds = List.copyOf(courseIds);
    }
  }
}
