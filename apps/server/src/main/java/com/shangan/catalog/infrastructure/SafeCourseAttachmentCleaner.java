package com.shangan.catalog.infrastructure;

import com.shangan.catalog.application.CourseAttachmentCleaner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只允许删除模拟考试附件根目录内的文件，拒绝越界路径和目录本身。 */
@Component
public class SafeCourseAttachmentCleaner implements CourseAttachmentCleaner {

  private final Path attachmentRoot;

  public SafeCourseAttachmentCleaner(
      @Value("${app.mock-exam-attachments-dir:./data/mock-exams}") String attachmentDirectory) {
    this.attachmentRoot = Path.of(attachmentDirectory).toAbsolutePath().normalize();
  }

  @Override
  public void delete(List<String> storagePaths) {
    for (String storagePath : storagePaths) {
      Path target = Path.of(storagePath).toAbsolutePath().normalize();
      if (target.equals(attachmentRoot) || !target.startsWith(attachmentRoot)) {
        throw new IllegalStateException("课程关联附件路径不在受控目录内");
      }
      try {
        Files.deleteIfExists(target);
      } catch (IOException exception) {
        throw new IllegalStateException("课程关联附件清理失败", exception);
      }
    }
  }
}
