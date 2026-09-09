package com.shangan.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.Course;
import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 封面只能从可见课程解析来源；不连接数据库或外部媒体服务。 */
class CourseCoverServiceTest {
  private final CourseRepository courses = mock(CourseRepository.class);
  private final EmbyGateway emby = mock(EmbyGateway.class);
  private final CourseCoverService service = new CourseCoverService(courses, emby);

  @Test
  void visibleCourseUsesServerSideSource() {
    when(courses.findById("local")).thenReturn(Optional.of(course(CatalogStatus.ACTIVE, false)));
    var image = new EmbyDtos.Cover(new byte[] {1, 2, 3}, "image/jpeg");
    when(emby.readCover("remote")).thenReturn(image);
    assertThat(service.cover("local")).isEqualTo(image);
    verify(emby).readCover("remote");
  }

  @Test
  void missingArchivedAndLostCoursesNeverReadRemoteImage() {
    for (var course :
        new Course[] {
          null, course(CatalogStatus.ARCHIVED, false), course(CatalogStatus.ACTIVE, true)
        }) {
      when(courses.findById("local")).thenReturn(Optional.ofNullable(course));
      assertThatThrownBy(() -> service.cover("local"))
          .isInstanceOf(BusinessException.class)
          .hasMessage("课程不存在");
    }
    verifyNoInteractions(emby);
  }

  /** 构造不同可见状态的课程，不携带任何真实媒体凭据。 */
  private Course course(CatalogStatus status, boolean missing) {
    return new Course(
        "local", "EMBY", "remote", "课程", "", null, 0, status, missing, null, null, null);
  }
}
