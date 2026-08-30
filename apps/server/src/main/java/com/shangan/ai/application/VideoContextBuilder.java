package com.shangan.ai.application;

import com.shangan.ai.domain.AiConversation.Citation;
import com.shangan.ai.transcript.TranscriptSearchRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

/** 按固定预算装配视频上下文，并明确标记转写是不可执行的不可信内容。 */
@Component
public class VideoContextBuilder {
  private static final long POSITION_RADIUS_MS = 180_000;
  private static final int SEARCH_LIMIT = 8;
  private final TranscriptSearchRepository transcripts;

  public VideoContextBuilder(TranscriptSearchRepository transcripts) {
    this.transcripts = transcripts;
  }

  public VideoContext build(String mediaItemId, long currentPositionMs, String question) {
    var summary = transcripts.summary(mediaItemId);
    var nearby = transcripts.around(mediaItemId, currentPositionMs, POSITION_RADIUS_MS);
    var matches = transcripts.search(mediaItemId, question, SEARCH_LIMIT);
    long sectionStart = Math.max(0, currentPositionMs - POSITION_RADIUS_MS);
    long sectionEnd = currentPositionMs + POSITION_RADIUS_MS;
    if (!matches.isEmpty()) {
      sectionStart =
          Math.min(
              sectionStart,
              matches.stream().mapToLong(value -> value.startMs()).min().orElse(sectionStart));
      sectionEnd =
          Math.max(
              sectionEnd,
              matches.stream().mapToLong(value -> value.endMs()).max().orElse(sectionEnd));
    }
    var sections = transcripts.sectionsOverlapping(mediaItemId, sectionStart, sectionEnd);
    StringBuilder context = new StringBuilder();
    context.append("<untrusted_transcript media_item_id=\"").append(mediaItemId).append("\">\n");
    summary.ifPresent(value -> context.append("全局摘要：").append(value.summary()).append("\n"));
    sections.forEach(
        value ->
            context
                .append("分段摘要[")
                .append(value.startMs())
                .append('-')
                .append(value.endMs())
                .append("]：")
                .append(value.summary())
                .append("\n"));
    LinkedHashMap<String, TranscriptSearchRepository.TranscriptMatch> segments =
        new LinkedHashMap<>();
    nearby.forEach(value -> segments.put(value.segmentId(), value));
    matches.forEach(value -> segments.putIfAbsent(value.segmentId(), value));
    segments
        .values()
        .forEach(
            value ->
                context
                    .append("转写[")
                    .append(value.startMs())
                    .append('-')
                    .append(value.endMs())
                    .append("]：")
                    .append(value.text())
                    .append("\n"));
    context.append("</untrusted_transcript>");
    List<Citation> citations = new ArrayList<>();
    segments
        .values()
        .forEach(
            value ->
                citations.add(
                    new Citation(
                        "VIDEO", "视频 " + formatPosition(value.startMs()), null, value.startMs())));
    return new VideoContext(context.toString(), List.copyOf(citations), summary.isPresent());
  }

  public VideoSummary getSummary(String mediaItemId) {
    return transcripts
        .summary(mediaItemId)
        .map(
            value ->
                new VideoSummary(value.summary(), value.outlineJson(), value.generatedAtEpochMs()))
        .orElse(new VideoSummary("视频内容仍在处理中", "[]", 0));
  }

  public TranscriptSearchResult search(String mediaItemId, String query) {
    return new TranscriptSearchResult(
        transcripts.search(mediaItemId, query, SEARCH_LIMIT).stream()
            .map(value -> new TranscriptHit(value.startMs(), value.endMs(), value.text()))
            .toList());
  }

  private String formatPosition(long positionMs) {
    long seconds = positionMs / 1000;
    return "%02d:%02d".formatted(seconds / 60, seconds % 60);
  }

  public record VideoContext(String promptContext, List<Citation> citations, boolean ready) {}

  public record VideoSummary(String summary, String outlineJson, long generatedAtEpochMs) {}

  public record TranscriptSearchResult(List<TranscriptHit> hits) {}

  public record TranscriptHit(long startMs, long endMs, String text) {}
}
