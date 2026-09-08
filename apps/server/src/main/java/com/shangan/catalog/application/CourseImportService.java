package com.shangan.catalog.application;

import com.shangan.catalog.domain.Course;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 绑定范围内的课程发现与逐项导入；整批由客户端串行推进，单门失败互不影响。 */
@Service
public class CourseImportService {
  private final EmbyGateway emby;
  private final CourseRepository courses;
  private final CourseSyncService sync;
  private final EmbyCatalogReader reader;
  private final RuntimeIntegrationSettingsService settings;
  private final TransactionTemplate transaction;

  /** 注入显式事务边界，远端请求全部发生在事务之外。 */
  public CourseImportService(
      EmbyGateway emby,
      CourseRepository courses,
      CourseSyncService sync,
      EmbyCatalogReader reader,
      RuntimeIntegrationSettingsService settings,
      PlatformTransactionManager transactionManager) {
    this.emby = emby;
    this.courses = courses;
    this.sync = sync;
    this.reader = reader;
    this.settings = settings;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /** 无关键词读取所有绑定候选，并附加本地状态；归档课程也算已存在。 */
  public List<Candidate> candidates() {
    var existing =
        courses.findAll().stream()
            .filter(c -> "EMBY".equals(c.externalSource()))
            .collect(java.util.stream.Collectors.toMap(Course::externalRef, c -> c));
    return emby.searchSources("").stream()
        .map(
            source -> {
              Course course = existing.get(source.id());
              return new Candidate(
                  source.id(),
                  source.name(),
                  source.itemType(),
                  source.hasPrimaryImage(),
                  course == null ? null : course.id(),
                  course == null ? "NEW" : course.status().name());
            })
        .toList();
  }

  /** 当前页按需计数，沿用完整分页读取，失败时明确报错而不是显示零课时。 */
  public int resourceCount(String sourceId) {
    requireBoundSource(sourceId);
    return emby.listChildren(sourceId).size();
  }

  /** 固定来源校验后代理封面，浏览器不会收到 Emby 地址或令牌。 */
  public EmbyDtos.Cover cover(String sourceId) {
    requireBoundSource(sourceId);
    return emby.readCover(sourceId);
  }

  /** 重试返回原身份；完整读取后才开短事务，唯一索引兜底并发导入。 */
  public ImportResult importOne(String sourceId) {
    requireBoundSource(sourceId);
    Course existing = courses.findByExternalRef("EMBY", sourceId).orElse(null);
    if (existing != null) return existingResult(existing);
    var metadata = reader.readCourseMetadata(sourceId);
    var resources = reader.readResources(sourceId);
    try {
      return transaction.execute(
          status -> {
            Course concurrent = courses.findByExternalRef("EMBY", sourceId).orElse(null);
            if (concurrent != null) return existingResult(concurrent);
            Course created = sync.createFromSnapshot(sourceId, 0, metadata, resources);
            return new ImportResult(created.id(), created.title(), "IMPORTED");
          });
    } catch (DataIntegrityViolationException exception) {
      // 仅相同课程来源的竞争返回幂等结果；课时身份冲突仍拒绝整门导入。
      return courses
          .findByExternalRef("EMBY", sourceId)
          .map(this::existingResult)
          .orElseThrow(
              () ->
                  new BusinessException(
                      HttpStatus.CONFLICT, "COURSE_IMPORT_CONFLICT", "课程课时已被其他课程引用，请检查 Emby 来源"));
    }
  }

  /** 已归档只提示恢复入口，不通过导入暗中恢复课程。 */
  private ImportResult existingResult(Course course) {
    return new ImportResult(
        course.id(),
        course.title(),
        "ARCHIVED".equals(course.status().name()) ? "ARCHIVED" : "EXISTS");
  }

  /**
   * 归属以已绑定媒体库的用户作用域查询为准，不把 Emby 物理父链当作媒体库视图。
   *
   * <p>父节点可能是 AggregateFolder 等不可建课节点；沿父链调用 getSource 会误拒绝合法候选。 每次使用当前绑定重新查询，仍拒绝伪造
   * ID、解绑来源与书籍，不信任浏览器提交的候选快照。
   */
  private void requireBoundSource(String sourceId) {
    if (sourceId == null || !sourceId.matches("[A-Za-z0-9_-]{1,128}")) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "EMBY_SOURCE_INVALID", "请选择有效课程");
    }
    if (settings.current().embyLibraries().isEmpty()) throw outsideScope();
    boolean bound =
        emby.searchSources("").stream()
            .anyMatch(
                source ->
                    sourceId.equals(source.id())
                        && Set.of("Series", "Movie").contains(source.itemType()));
    if (!bound) throw outsideScope();
  }

  /** 返回固定中文错误，避免把远端路径或未经校验的输入反射到页面。 */
  private BusinessException outsideScope() {
    return new BusinessException(
        HttpStatus.CONFLICT, "EMBY_SOURCE_OUTSIDE_BINDINGS", "课程不在已保存的视频媒体库范围内，请刷新媒体库绑定与候选课程");
  }

  /** 导入选择器安全快照。 */
  public record Candidate(
      String id,
      String name,
      String itemType,
      boolean hasPrimaryImage,
      String courseId,
      String status) {}

  /** 单项结果便于响应丢失后的安全重试。 */
  public record ImportResult(String courseId, String title, String status) {}
}
