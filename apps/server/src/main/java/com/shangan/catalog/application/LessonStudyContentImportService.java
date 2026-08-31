package com.shangan.catalog.application;

import com.shangan.catalog.domain.LessonStudyContent;
import com.shangan.catalog.domain.MediaItem;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.catalog.infrastructure.LessonStudyContentRepository;
import com.shangan.catalog.infrastructure.LessonStudyContentZipParser;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 先在事务外完整校验上传包，再用一个短事务原子覆盖课程学习内容。 */
@Service
public class LessonStudyContentImportService {

  private final CourseRepository courses;
  private final LessonStudyContentRepository contents;
  private final LessonStudyContentZipParser parser;
  private final IdGenerator ids;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public LessonStudyContentImportService(
      CourseRepository courses,
      LessonStudyContentRepository contents,
      LessonStudyContentZipParser parser,
      IdGenerator ids,
      Clock clock,
      TransactionTemplate transactions) {
    this.courses = courses;
    this.contents = contents;
    this.parser = parser;
    this.ids = ids;
    this.clock = clock;
    this.transactions = transactions;
  }

  /**
   * 导入一门课程的完整 ZIP。
   *
   * @param courseId 当前后台页面对应的课程 ID
   * @param zipBytes 上传的 ZIP 字节
   * @return 实际导入的课时数量
   */
  public ImportResult importZip(String courseId, byte[] zipBytes) {
    LessonStudyContentZipParser.ParsedPackage parsed = parser.parse(zipBytes);
    Map<String, MediaItem> lessonsByEmbyId = validateCourseLessons(courseId, parsed);
    Instant now = clock.instant();
    List<LessonStudyContent> values = new ArrayList<>(parsed.lessons().size());
    for (LessonStudyContentZipParser.ParsedLesson lesson : parsed.lessons()) {
      MediaItem mediaItem = lessonsByEmbyId.get(lesson.embyItemId());
      values.add(
          new LessonStudyContent(
              ids.nextId(), mediaItem.id(), lesson.fullText(), lesson.summaryMarkdown(), now, now));
    }
    transactions.executeWithoutResult(status -> contents.upsertAll(values));
    return new ImportResult(values.size());
  }

  /** 查询一门课程各课时的内容更新时间，供后台列表显示导入状态。 */
  @Transactional(readOnly = true)
  public Map<String, Instant> contentUpdatedAtByLessonId(String courseId) {
    return contents.findUpdatedAtByCourseId(courseId);
  }

  /** 按本地课时 ID 读取内容，供受保护的 App API 使用。 */
  @Transactional(readOnly = true)
  public Optional<LessonStudyContent> findByLessonId(String lessonId) {
    return contents.findByMediaItemId(lessonId);
  }

  /** 返回已维护学习内容的课时总数，供后台运行状态页展示。 */
  @Transactional(readOnly = true)
  public long contentCount() {
    return contents.count();
  }

  /** 校验课程存在，并确保 manifest 中所有 Emby Item ID 都精确属于当前课程。 */
  private Map<String, MediaItem> validateCourseLessons(
      String courseId, LessonStudyContentZipParser.ParsedPackage parsed) {
    courses
        .findCourse(courseId)
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在"));
    Map<String, MediaItem> lessonsByEmbyId = new LinkedHashMap<>();
    for (MediaItem lesson : courses.findMediaItems(courseId, false)) {
      lessonsByEmbyId.put(lesson.embyItemId(), lesson);
    }
    for (LessonStudyContentZipParser.ParsedLesson lesson : parsed.lessons()) {
      if (!lessonsByEmbyId.containsKey(lesson.embyItemId())) {
        throw new BusinessException(
            HttpStatus.BAD_REQUEST,
            "STUDY_CONTENT_IMPORT_INVALID",
            "Emby Item ID " + lesson.embyItemId() + " 不属于当前课程");
      }
    }
    return lessonsByEmbyId;
  }

  /** 后台导入成功结果。 */
  public record ImportResult(int importedCount) {}
}
