package com.shangan.identity.infrastructure;

import com.shangan.identity.application.UserAttachmentCleaner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只允许删除模拟考试附件根目录内的文件，拒绝越界路径和目录本身。 */
@Component
public class SafeUserAttachmentCleaner implements UserAttachmentCleaner {

  private final Path attachmentRoot;

  public SafeUserAttachmentCleaner(
      @Value("${app.mock-exam-attachments-dir:./data/mock-exams}") String attachmentDirectory) {
    this.attachmentRoot = Path.of(attachmentDirectory).toAbsolutePath().normalize();
  }

  @Override
  public void delete(List<String> storagePaths) {
    for (String storagePath : storagePaths) {
      Path target = Path.of(storagePath).toAbsolutePath().normalize();
      if (target.equals(attachmentRoot) || !target.startsWith(attachmentRoot)) {
        throw new IllegalStateException("用户关联附件路径不在受控目录内");
      }
      try {
        Files.deleteIfExists(target);
      } catch (IOException exception) {
        throw new IllegalStateException("用户关联附件清理失败", exception);
      }
    }
  }
}
