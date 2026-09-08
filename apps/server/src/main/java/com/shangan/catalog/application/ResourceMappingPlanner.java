package com.shangan.catalog.application;

import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceMetadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 计算远端快照与本地资源的映射方案。
 *
 * <p>匹配优先级（Spec 12.3）：当前 externalRef → 课程内唯一来源指纹 → 课程内唯一标题且时长差 ≤2 秒 → 管理员确认。 一对多、多对一或其他歧义一律不自动合并，进入
 * {@code ambiguous}。
 */
@Component
public class ResourceMappingPlanner {

  private static final long TITLE_DURATION_TOLERANCE_MS = 2_000L;

  /** 生成映射方案；输入为本地资源与远端元数据，输出为四类处置结果。 */
  public MappingPlan plan(List<LearningResource> local, List<ResourceMetadata> remote) {
    Map<String, LearningResource> byExternalRef = new LinkedHashMap<>();
    Map<String, List<LearningResource>> byFingerprint = new LinkedHashMap<>();
    Map<String, List<LearningResource>> byTitle = new LinkedHashMap<>();
    for (LearningResource resource : local) {
      byExternalRef.put(resource.externalRef(), resource);
      if (resource.sourceFingerprint() != null && !resource.sourceFingerprint().isBlank()) {
        byFingerprint
            .computeIfAbsent(resource.sourceFingerprint(), key -> new ArrayList<>())
            .add(resource);
      }
      byTitle
          .computeIfAbsent(normalizedTitle(resource.title()), key -> new ArrayList<>())
          .add(resource);
    }

    List<InPlace> inPlace = new ArrayList<>();
    List<ResourceMetadata> created = new ArrayList<>();
    List<Ambiguous> ambiguous = new ArrayList<>();
    java.util.Set<String> matchedLocalIds = new java.util.LinkedHashSet<>();

    // 先占用所有精确身份，避免较早返回的模糊候选抢走后续精确匹配。
    for (ResourceMetadata candidate : remote) {
      LearningResource exact = byExternalRef.get(candidate.externalRef());
      if (exact != null && matchedLocalIds.add(exact.id())) {
        inPlace.add(new InPlace(exact, candidate, MatchedBy.EXTERNAL_REF));
      }
    }
    java.util.Set<String> exactLocalIds = java.util.Set.copyOf(matchedLocalIds);
    for (ResourceMetadata candidate : remote) {
      if (byExternalRef.containsKey(candidate.externalRef())) continue;
      List<LearningResource> fingerprints =
          unmatched(byFingerprint.get(candidate.sourceFingerprint()), exactLocalIds);
      if (fingerprints.size() > 1) {
        ambiguous.add(new Ambiguous(candidate, fingerprints));
        continue;
      }
      Optional<LearningResource> byPrint =
          uniqueUnmatched(byFingerprint.get(candidate.sourceFingerprint()), exactLocalIds);
      if (candidate.sourceFingerprint() != null && byPrint.isPresent()) {
        LearningResource target = byPrint.get();
        inPlace.add(new InPlace(target, candidate, MatchedBy.FINGERPRINT));
        continue;
      }

      List<LearningResource> sameTitle =
          unmatched(byTitle.get(normalizedTitle(candidate.title())), exactLocalIds);
      List<LearningResource> titleAndDuration =
          sameTitle.stream().filter(resource -> durationClose(resource, candidate)).toList();
      if (titleAndDuration.size() == 1) {
        LearningResource target = titleAndDuration.getFirst();
        inPlace.add(new InPlace(target, candidate, MatchedBy.TITLE_DURATION));
        continue;
      }
      if (titleAndDuration.size() > 1 || sameTitle.size() > 1) {
        ambiguous.add(new Ambiguous(candidate, List.copyOf(sameTitle)));
        continue;
      }
      if (sameTitle.size() == 1 && candidate.sourceFingerprint() == null) {
        // 标题唯一但时长差超过容差，且远端没有指纹可判定，交给管理员确认。
        ambiguous.add(new Ambiguous(candidate, List.copyOf(sameTitle)));
        continue;
      }
      created.add(candidate);
    }

    // 模糊匹配统一定稿：多个远端争用同一本地身份时全部留给人工确认，不能按返回顺序决定赢家。
    Map<String, Long> claims =
        inPlace.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    item -> item.local().id(),
                    LinkedHashMap::new,
                    java.util.stream.Collectors.counting()));
    inPlace.removeIf(
        item -> {
          if (claims.get(item.local().id()) <= 1) return false;
          ambiguous.add(new Ambiguous(item.remote(), List.of(item.local())));
          return true;
        });
    inPlace.forEach(item -> matchedLocalIds.add(item.local().id()));

    // 歧义只等待人工判定，不把尚未确定消失的候选课时下架。
    var ambiguousLocalIds =
        ambiguous.stream()
            .flatMap(item -> item.candidates().stream())
            .map(LearningResource::id)
            .collect(java.util.stream.Collectors.toSet());
    List<LearningResource> markedUnavailable =
        local.stream()
            .filter(resource -> !matchedLocalIds.contains(resource.id()))
            .filter(resource -> !ambiguousLocalIds.contains(resource.id()))
            .filter(LearningResource::available)
            .toList();

    return new MappingPlan(
        List.copyOf(inPlace),
        List.copyOf(created),
        List.copyOf(markedUnavailable),
        List.copyOf(ambiguous));
  }

  private Optional<LearningResource> uniqueUnmatched(
      List<LearningResource> candidates, java.util.Set<String> matched) {
    List<LearningResource> remaining = unmatched(candidates, matched);
    return remaining.size() == 1 ? Optional.of(remaining.getFirst()) : Optional.empty();
  }

  private List<LearningResource> unmatched(
      List<LearningResource> candidates, java.util.Set<String> matched) {
    if (candidates == null) {
      return List.of();
    }
    return candidates.stream().filter(resource -> !matched.contains(resource.id())).toList();
  }

  private boolean durationClose(LearningResource local, ResourceMetadata remote) {
    if (local.durationMs() == null || remote.durationMs() == null) {
      return false;
    }
    return Math.abs(local.durationMs() - remote.durationMs()) <= TITLE_DURATION_TOLERANCE_MS;
  }

  private String normalizedTitle(String title) {
    return title == null ? "" : title.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /** 匹配依据，写入 resource_source_mappings 便于回溯。 */
  public enum MatchedBy {
    EXTERNAL_REF,
    FINGERPRINT,
    TITLE_DURATION,
    MANUAL
  }

  /** 原位映射：只改 externalRef 与同步字段，本地资源 ID 不变。 */
  public record InPlace(LearningResource local, ResourceMetadata remote, MatchedBy matchedBy) {}

  /** 歧义项：远端条目与多个本地候选无法唯一对应，必须人工确认。 */
  public record Ambiguous(ResourceMetadata remote, List<LearningResource> candidates) {}

  /** 完整映射方案。 */
  public record MappingPlan(
      List<InPlace> inPlace,
      List<ResourceMetadata> created,
      List<LearningResource> markedUnavailable,
      List<Ambiguous> ambiguous) {

    public boolean requiresManualConfirmation() {
      return !ambiguous.isEmpty();
    }
  }
}
