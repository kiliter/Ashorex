package com.shangan.catalog.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.domain.ResourceMetadata;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 纯逻辑验证导入范围、重复保护与网络/事务顺序，不启动数据库。 */
class CourseImportServiceTest {
  private final EmbyGateway emby = mock(EmbyGateway.class);
  private final CourseRepository courses = mock(CourseRepository.class);
  private final CourseSyncService sync = mock(CourseSyncService.class);
  private final EmbyCatalogReader reader = mock(EmbyCatalogReader.class);
  private final RuntimeIntegrationSettingsService settings =
      mock(RuntimeIntegrationSettingsService.class);
  private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
  private CourseImportService service;

  @BeforeEach
  void setUp() {
    service = new CourseImportService(emby, courses, sync, reader, settings, tx);
    when(emby.getSource("source"))
        .thenReturn(new EmbyDtos.MediaSource("source", "课程", "Series", "", "library"));
    binding(RuntimeIntegrationSettings.EmbyLibraryType.MIXED);
  }

  @Test
  void discoversWithoutKeywordAndIncludesArchivedIdentity() {
    when(emby.searchSources(""))
        .thenReturn(List.of(new EmbyDtos.MediaSource("source", "课程", "Series", "", "library")));
    when(courses.findAll()).thenReturn(List.of(course(CatalogStatus.ARCHIVED)));
    assertThat(service.candidates())
        .containsExactly(
            new CourseImportService.Candidate(
                "source", "课程", "Series", false, "course", "ARCHIVED"));
    verifyNoInteractions(reader, sync, tx);
  }

  @Test
  void repeatedImportReturnsOriginalWithoutReadingOrWriting() {
    when(courses.findByExternalRef("EMBY", "source"))
        .thenReturn(Optional.of(course(CatalogStatus.ACTIVE)));
    assertThat(service.importOne("source"))
        .isEqualTo(new CourseImportService.ImportResult("course", "课程", "EXISTS"));
    verifyNoInteractions(reader, sync, tx);
  }

  @Test
  void archivedImportDoesNotRestoreOrDuplicate() {
    when(courses.findByExternalRef("EMBY", "source"))
        .thenReturn(Optional.of(course(CatalogStatus.ARCHIVED)));
    assertThat(service.importOne("source").status()).isEqualTo("ARCHIVED");
    verifyNoInteractions(reader, sync, tx);
  }

  @Test
  void remoteFailureDoesNotBeginTransactionOrLeaveCourse() {
    when(reader.readResources("source"))
        .thenThrow(
            new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "EMBY_UNAVAILABLE", "媒体服务暂时不可用"));
    assertThatThrownBy(() -> service.importOne("source")).isInstanceOf(BusinessException.class);
    verifyNoInteractions(tx, sync);
  }

  @Test
  void fullRemoteSnapshotPrecedesShortWriteTransaction() {
    var metadata =
        new ResourceMetadata.CourseMetadata(
            "source", "课程", "", null, List.of(), List.of(), List.of());
    when(reader.readCourseMetadata("source")).thenReturn(metadata);
    when(reader.readResources("source")).thenReturn(List.of());
    var status = new SimpleTransactionStatus();
    when(tx.getTransaction(any())).thenReturn(status);
    when(sync.createFromSnapshot("source", 0, metadata, List.of()))
        .thenReturn(course(CatalogStatus.ACTIVE));
    assertThat(service.importOne("source").status()).isEqualTo("IMPORTED");
    var order = inOrder(reader, tx, sync);
    order.verify(reader).readCourseMetadata("source");
    order.verify(reader).readResources("source");
    order.verify(tx).getTransaction(any());
    order.verify(sync).createFromSnapshot("source", 0, metadata, List.of());
    order.verify(tx).commit(status);
  }

  @Test
  void concurrentImportReturnsCourseFoundInsideTransaction() {
    when(courses.findByExternalRef("EMBY", "source"))
        .thenReturn(Optional.empty(), Optional.of(course(CatalogStatus.ACTIVE)));
    when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    assertThat(service.importOne("source").status()).isEqualTo("EXISTS");
    verifyNoInteractions(sync);
  }

  @Test
  void rejectsBooksAndTypeMismatchBeforeReadingResources() {
    for (var mode :
        List.of(
            RuntimeIntegrationSettings.EmbyLibraryType.BOOK,
            RuntimeIntegrationSettings.EmbyLibraryType.MOVIE)) {
      binding(mode);
      assertThatThrownBy(() -> service.importOne("source"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("不在已保存");
    }
    verifyNoInteractions(reader, sync, tx);
  }

  @Test
  void rejectsSourceAbsentFromBindingsAndPathInput() {
    when(emby.searchSources("")).thenReturn(List.of());
    assertThatThrownBy(() -> service.importOne("source"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不在已保存");
    assertThatThrownBy(() -> service.importOne("../source"))
        .isInstanceOf(BusinessException.class)
        .hasMessage("请选择有效课程");
    verifyNoInteractions(reader, sync, tx);
  }

  @Test
  void rejectsRemovedBindingsWithStableScopeError() {
    when(settings.current()).thenReturn(RuntimeIntegrationSettings.defaults());
    assertThatThrownBy(() -> service.importOne("source"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不在已保存");
    verify(emby, never()).getSource("library");
    verifyNoInteractions(reader, sync, tx);
  }

  /** 媒体库视图与物理父链不一致时，候选归属仍以绑定库查询为准。 */
  @Test
  void detailsAcceptsScopedCandidateWithoutValidatingPhysicalAncestors() {
    when(emby.searchSources(""))
        .thenReturn(
            List.of(new EmbyDtos.MediaSource("source", "课程", "Series", "", "physical-root")));
    when(emby.getSource("source"))
        .thenReturn(new EmbyDtos.MediaSource("source", "课程", "Series", "", "physical-root"));
    when(emby.getSource("physical-root"))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST, "EMBY_SOURCE_TYPE_UNSUPPORTED", "父节点不能作为课程"));
    when(emby.listChildren("source"))
        .thenReturn(List.of(new EmbyDtos.MediaItem("episode", "第一讲", 1000, 1)));
    assertThat(service.resourceCount("source")).isEqualTo(1);
    verify(emby, never()).getSource("physical-root");
  }

  /** 明确配置测试绑定，不使用任何真实凭据或数据库。 */
  private void binding(RuntimeIntegrationSettings.EmbyLibraryType mode) {
    when(emby.searchSources(""))
        .thenReturn(
            mode == RuntimeIntegrationSettings.EmbyLibraryType.MIXED
                    || mode == RuntimeIntegrationSettings.EmbyLibraryType.SERIES
                ? List.of(new EmbyDtos.MediaSource("source", "课程", "Series", "", "library"))
                : List.of());
    when(settings.current())
        .thenReturn(
            new RuntimeIntegrationSettings(
                null,
                List.of(new RuntimeIntegrationSettings.EmbyLibrary("library", "媒体库", mode)),
                null,
                null,
                0));
  }

  private Course course(CatalogStatus status) {
    return new Course(
        "course", "EMBY", "source", "课程", "", null, 0, status, false, null, null, null);
  }
}
