package com.shangan.catalog.application;

import com.shangan.catalog.domain.MediaItem;
import com.shangan.media.emby.EmbyDtos;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 以保守的一对一规则规划 Emby 外部标识更新，任何歧义都交给管理员确认。 */
@Component
public class MediaMappingPlanner {

  private static final long LEGACY_DURATION_TOLERANCE_MS = 2_000L;
  public static final String CONFIRM_AS_NEW = "__NEW__";

  /**
   * 生成不写数据库的映射计划。
   *
   * @param localItems 当前课程全部本地课时
   * @param remoteItems 新媒体来源的完整远端快照
   * @param confirmedMappings 管理员确认的 remote Emby ID 到本地课时 ID 映射
   */
  public Plan plan(
      List<MediaItem> localItems,
      List<EmbyDtos.MediaItem> remoteItems,
      Map<String, String> confirmedMappings) {
    Map<String, MediaItem> localById = indexLocalById(localItems);
    Map<String, MediaItem> localByEmbyId = new HashMap<>();
    for (MediaItem local : localItems) {
      localByEmbyId.put(local.embyItemId(), local);
    }

    List<Mapping> mappings = new ArrayList<>();
    List<Conflict> conflicts = new ArrayList<>();
    Set<String> claimedLocalIds = new HashSet<>();
    Set<String> claimedRemoteIds = new HashSet<>();

    applyConfirmedMappings(
        remoteItems,
        confirmedMappings,
        localById,
        mappings,
        conflicts,
        claimedLocalIds,
        claimedRemoteIds);

    // 当前 Item ID 仍然存在时优先沿用原课时，不参与后续启发式匹配。
    for (EmbyDtos.MediaItem remote : remoteItems) {
      if (claimedRemoteIds.contains(remote.id())) {
        continue;
      }
      MediaItem local = localByEmbyId.get(remote.id());
      if (local != null && claimedLocalIds.add(local.id())) {
        mappings.add(new Mapping(local.id(), remote, MatchType.ITEM_ID));
        claimedRemoteIds.add(remote.id());
      }
    }

    applyFingerprintMappings(
        localItems, remoteItems, mappings, conflicts, claimedLocalIds, claimedRemoteIds);
    applyLegacyMappings(
        localItems, remoteItems, mappings, conflicts, claimedLocalIds, claimedRemoteIds);

    for (EmbyDtos.MediaItem remote : remoteItems) {
      if (!claimedRemoteIds.contains(remote.id())
          && conflicts.stream().noneMatch(conflict -> conflict.remote().id().equals(remote.id()))) {
        mappings.add(new Mapping(null, remote, MatchType.NEW));
        claimedRemoteIds.add(remote.id());
      }
    }

    List<String> unavailableLocalIds =
        localItems.stream().map(MediaItem::id).filter(id -> !claimedLocalIds.contains(id)).toList();
    return new Plan(
        List.copyOf(mappings), List.copyOf(conflicts), List.copyOf(unavailableLocalIds));
  }

  private void applyConfirmedMappings(
      List<EmbyDtos.MediaItem> remoteItems,
      Map<String, String> confirmedMappings,
      Map<String, MediaItem> localById,
      List<Mapping> mappings,
      List<Conflict> conflicts,
      Set<String> claimedLocalIds,
      Set<String> claimedRemoteIds) {
    Map<String, EmbyDtos.MediaItem> remoteById = new LinkedHashMap<>();
    for (EmbyDtos.MediaItem remote : remoteItems) {
      remoteById.put(remote.id(), remote);
    }
    Map<String, Long> selectedLocalCounts = new HashMap<>();
    confirmedMappings.values().stream()
        .filter(value -> !CONFIRM_AS_NEW.equals(value))
        .forEach(value -> selectedLocalCounts.merge(value, 1L, Long::sum));
    for (Map.Entry<String, String> confirmed : confirmedMappings.entrySet()) {
      EmbyDtos.MediaItem remote = remoteById.get(confirmed.getKey());
      if (remote != null && CONFIRM_AS_NEW.equals(confirmed.getValue())) {
        mappings.add(new Mapping(null, remote, MatchType.NEW));
        claimedRemoteIds.add(remote.id());
        continue;
      }
      MediaItem local = localById.get(confirmed.getValue());
      if (remote == null) {
        continue;
      }
      if (local == null || selectedLocalCounts.getOrDefault(local.id(), 0L) > 1L) {
        conflicts.add(new Conflict(remote, local == null ? List.of() : List.of(local)));
        continue;
      }
      claimedLocalIds.add(local.id());
      mappings.add(new Mapping(local.id(), remote, MatchType.ADMIN_CONFIRMED));
      claimedRemoteIds.add(remote.id());
    }
  }

  private void applyFingerprintMappings(
      List<MediaItem> localItems,
      List<EmbyDtos.MediaItem> remoteItems,
      List<Mapping> mappings,
      List<Conflict> conflicts,
      Set<String> claimedLocalIds,
      Set<String> claimedRemoteIds) {
    Map<String, List<MediaItem>> candidatesByRemoteId = new LinkedHashMap<>();
    for (EmbyDtos.MediaItem remote : remoteItems) {
      if (claimedRemoteIds.contains(remote.id()) || remote.sourceFingerprint() == null) {
        continue;
      }
      List<MediaItem> candidates =
          localItems.stream()
              .filter(local -> !claimedLocalIds.contains(local.id()))
              .filter(local -> remote.sourceFingerprint().equals(local.sourceFingerprint()))
              .toList();
      candidatesByRemoteId.put(remote.id(), candidates);
    }

    // 同一个旧课时若被多个远端项命中也属于歧义，不能按遍历顺序把第一个静默映射。
    Map<String, Long> localCandidateCounts = new HashMap<>();
    candidatesByRemoteId.values().stream()
        .flatMap(List::stream)
        .forEach(local -> localCandidateCounts.merge(local.id(), 1L, Long::sum));

    for (EmbyDtos.MediaItem remote : remoteItems) {
      if (claimedRemoteIds.contains(remote.id()) || remote.sourceFingerprint() == null) {
        continue;
      }
      List<MediaItem> candidates = candidatesByRemoteId.getOrDefault(remote.id(), List.of());
      if (candidates.size() == 1
          && localCandidateCounts.getOrDefault(candidates.getFirst().id(), 0L) == 1L) {
        MediaItem local = candidates.getFirst();
        mappings.add(new Mapping(local.id(), remote, MatchType.SOURCE_FINGERPRINT));
        claimedLocalIds.add(local.id());
        claimedRemoteIds.add(remote.id());
      } else if (!candidates.isEmpty()) {
        conflicts.add(new Conflict(remote, candidates));
      }
    }
  }

  private void applyLegacyMappings(
      List<MediaItem> localItems,
      List<EmbyDtos.MediaItem> remoteItems,
      List<Mapping> mappings,
      List<Conflict> conflicts,
      Set<String> claimedLocalIds,
      Set<String> claimedRemoteIds) {
    Map<String, List<MediaItem>> candidatesByRemoteId = new LinkedHashMap<>();
    for (EmbyDtos.MediaItem remote : remoteItems) {
      if (claimedRemoteIds.contains(remote.id()) || containsConflict(conflicts, remote.id())) {
        continue;
      }
      List<MediaItem> candidates =
          localItems.stream()
              .filter(local -> local.sourceFingerprint() == null)
              .filter(local -> !claimedLocalIds.contains(local.id()))
              .filter(local -> sameLegacyMetadata(local, remote))
              .toList();
      candidatesByRemoteId.put(remote.id(), candidates);
    }

    Map<String, Long> localCandidateCounts = new HashMap<>();
    candidatesByRemoteId.values().stream()
        .flatMap(List::stream)
        .forEach(local -> localCandidateCounts.merge(local.id(), 1L, Long::sum));

    for (EmbyDtos.MediaItem remote : remoteItems) {
      if (claimedRemoteIds.contains(remote.id()) || containsConflict(conflicts, remote.id())) {
        continue;
      }
      List<MediaItem> candidates = candidatesByRemoteId.getOrDefault(remote.id(), List.of());
      if (candidates.size() == 1 && localCandidateCounts.get(candidates.getFirst().id()) == 1L) {
        MediaItem local = candidates.getFirst();
        mappings.add(new Mapping(local.id(), remote, MatchType.UNIQUE_LEGACY_METADATA));
        claimedLocalIds.add(local.id());
        claimedRemoteIds.add(remote.id());
      } else if (!candidates.isEmpty()) {
        conflicts.add(new Conflict(remote, candidates));
      }
    }
  }

  private boolean sameLegacyMetadata(MediaItem local, EmbyDtos.MediaItem remote) {
    return normalizeTitle(local.title()).equals(normalizeTitle(remote.title()))
        && Math.abs(local.durationMs() - remote.durationMs()) <= LEGACY_DURATION_TOLERANCE_MS;
  }

  private String normalizeTitle(String title) {
    return Normalizer.normalize(title == null ? "" : title.trim(), Normalizer.Form.NFKC)
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  private boolean containsConflict(List<Conflict> conflicts, String remoteId) {
    return conflicts.stream().anyMatch(conflict -> conflict.remote().id().equals(remoteId));
  }

  private Map<String, MediaItem> indexLocalById(List<MediaItem> localItems) {
    Map<String, MediaItem> result = new LinkedHashMap<>();
    for (MediaItem local : localItems) {
      result.put(local.id(), local);
    }
    return result;
  }

  /** 一次快照写入前的完整映射计划。 */
  public record Plan(
      List<Mapping> mappings, List<Conflict> conflicts, List<String> unavailableLocalIds) {
    public boolean hasConflicts() {
      return !conflicts.isEmpty();
    }

    /** 后台预览使用：统计无需人工确认即可保留本地课时身份的自动映射。 */
    public long automaticMappingCount() {
      return mappings.stream()
          .filter(mapping -> mapping.matchType() != MatchType.NEW)
          .filter(mapping -> mapping.matchType() != MatchType.ADMIN_CONFIRMED)
          .count();
    }

    /** 后台预览使用：统计无法与历史课时匹配、将新增为本地课时的视频。 */
    public long newItemCount() {
      return mappings.stream().filter(mapping -> mapping.matchType() == MatchType.NEW).count();
    }
  }

  /** 一个远端视频与本地课时的确定映射；本地 ID 为空表示新增课时。 */
  public record Mapping(String localMediaItemId, EmbyDtos.MediaItem remote, MatchType matchType) {}

  /** 需要管理员确认的一项远端视频及其可能的历史课时。 */
  public record Conflict(EmbyDtos.MediaItem remote, List<MediaItem> candidates) {
    public Conflict {
      candidates = List.copyOf(candidates);
    }
  }

  /** 映射审计类型；ITEM_ID 和 NEW 不产生外部 ID 变更审计。 */
  public enum MatchType {
    ITEM_ID,
    SOURCE_FINGERPRINT,
    UNIQUE_LEGACY_METADATA,
    ADMIN_CONFIRMED,
    NEW
  }
}
