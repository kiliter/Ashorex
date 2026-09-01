package com.shangan.learning.application;

import com.shangan.learning.infrastructure.VideoProgressRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 对其他模块公开只读的视频学习进度，避免 API Controller 直接访问持久化仓库。 */
@Service
public class VideoProgressQueryService {
  private final VideoProgressRepository progress;

  public VideoProgressQueryService(VideoProgressRepository progress) {
    this.progress = progress;
  }

  /** 按用户和课时读取累计可信进度。 */
  @Transactional(readOnly = true)
  public Optional<ProgressView> find(String userId, String mediaItemId) {
    return progress
        .find(userId, mediaItemId)
        .map(value -> new ProgressView(value.maxVerifiedPositionMs(), value.completedAt()));
  }

  /** 课程列表展示所需的最小进度快照。 */
  public record ProgressView(long maxVerifiedPositionMs, Instant completedAt) {}
}
