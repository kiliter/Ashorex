package com.shangan.catalog.application;

import com.shangan.common.api.BusinessException;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 编排 Emby 来源联想、批量来源验证、短事务建课和逐门串行同步。 */
@Service
public class CourseBatchService {

  private static final int MAX_BATCH_SIZE = 50;

  private final EmbyGateway emby;
  private final CourseBatchWriter writer;
  private final CourseSynchronizer synchronizer;

  public CourseBatchService(
      EmbyGateway emby, CourseBatchWriter writer, CourseSynchronizer synchronizer) {
    this.emby = emby;
    this.writer = writer;
    this.synchronizer = synchronizer;
  }

  /** 搜索只返回 Emby 安全元数据，供创建页和重新绑定页共用。 */
  public List<EmbyDtos.MediaSource> searchSources(String query) {
    return emby.searchSources(query == null ? "" : query.trim());
  }

  /** 全部来源验证成功后才写库；单门同步失败不影响其他已准备课程。 */
  public BatchResult addAndSynchronize(List<String> submittedSourceIds) {
    List<String> sourceIds = normalizedSourceIds(submittedSourceIds);
    List<EmbyDtos.MediaSource> sources = new ArrayList<>();
    for (String sourceId : sourceIds) {
      EmbyDtos.MediaSource source = emby.getSource(sourceId);
      if (!source.id().equals(sourceId)) {
        throw new BusinessException(HttpStatus.CONFLICT, "EMBY_SOURCE_CHANGED", "媒体来源已发生变化，请重新选择");
      }
      sources.add(source);
    }

    List<BatchItem> results = new ArrayList<>();
    for (CourseBatchWriter.PreparedCourse prepared : writer.prepare(sources)) {
      if (prepared.action() == CourseBatchWriter.Action.SKIPPED) {
        results.add(
            new BatchItem(
                prepared.source().id(),
                prepared.source().name(),
                prepared.course().id(),
                prepared.action(),
                SyncStatus.NOT_REQUESTED,
                "已存在活动课程，未重复添加"));
        continue;
      }
      try {
        synchronizer.syncCourse(prepared.course().id());
        results.add(
            new BatchItem(
                prepared.source().id(),
                prepared.source().name(),
                prepared.course().id(),
                prepared.action(),
                SyncStatus.SUCCESS,
                prepared.action() == CourseBatchWriter.Action.CREATED ? "课程已创建并同步" : "课程已恢复并同步"));
      } catch (RuntimeException exception) {
        results.add(
            new BatchItem(
                prepared.source().id(),
                prepared.source().name(),
                prepared.course().id(),
                prepared.action(),
                SyncStatus.FAILED,
                prepared.action() == CourseBatchWriter.Action.CREATED
                    ? "课程已创建，首次同步失败，可稍后重试"
                    : "课程已恢复，同步失败，可稍后重试"));
      }
    }
    return new BatchResult(List.copyOf(results));
  }

  private List<String> normalizedSourceIds(List<String> submittedSourceIds) {
    Set<String> uniqueIds = new LinkedHashSet<>();
    if (submittedSourceIds != null) {
      for (String sourceId : submittedSourceIds) {
        if (sourceId != null && !sourceId.isBlank()) {
          uniqueIds.add(sourceId.trim());
        }
      }
    }
    if (uniqueIds.isEmpty()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "EMBY_SOURCE_REQUIRED", "请至少选择一个 Emby 来源");
    }
    if (uniqueIds.size() > MAX_BATCH_SIZE) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "EMBY_SOURCE_BATCH_TOO_LARGE", "每次最多批量添加 50 个 Emby 来源");
    }
    return List.copyOf(uniqueIds);
  }

  /** 一次批量添加的逐项安全结果。 */
  public record BatchResult(List<BatchItem> items) {}

  /** 一项来源的建课动作与事务外同步结果。 */
  public record BatchItem(
      String sourceId,
      String sourceName,
      String courseId,
      CourseBatchWriter.Action action,
      SyncStatus syncStatus,
      String message) {}

  /** 同步未请求、成功或失败；失败原因不携带上游异常内容。 */
  public enum SyncStatus {
    NOT_REQUESTED,
    SUCCESS,
    FAILED
  }
}
