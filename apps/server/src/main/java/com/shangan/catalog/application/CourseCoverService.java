package com.shangan.catalog.application;

import com.shangan.catalog.infrastructure.CourseRepository;
import com.shangan.common.api.BusinessException;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 以本地课程 ID 读取封面；先校验可见性，远端读取不占用数据库事务。 */
@Service
public class CourseCoverService {
  private final CourseRepository courses;
  private final EmbyGateway emby;

  public CourseCoverService(CourseRepository courses, EmbyGateway emby) {
    this.courses = courses;
    this.emby = emby;
  }

  /** 不接受客户端 URL 或 Emby 来源 ID；沿用网关的图片类型、大小和超时限制。 */
  public EmbyDtos.Cover cover(String courseId) {
    var course =
        courses
            .findById(courseId)
            .filter(c -> c.visibleToLearners())
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在"));
    return emby.readCover(course.externalRef());
  }
}
