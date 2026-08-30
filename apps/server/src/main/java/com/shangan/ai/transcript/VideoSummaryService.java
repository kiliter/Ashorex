package com.shangan.ai.transcript;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 按 5~10 分钟边界生成分段摘要，再使用全部分段摘要生成全局摘要。 */
@Service
public class VideoSummaryService {
  private static final long MIN_SECTION_MS = 5 * 60 * 1000L;
  private static final long MAX_SECTION_MS = 10 * 60 * 1000L;

  private final SummaryProvider provider;
  private final Clock clock;

  public VideoSummaryService(SummaryProvider provider, Clock clock) {
    this.provider = provider;
    this.clock = clock;
  }

  public SummaryBundle generate(String mediaItemId, List<SegmentInput> segments) {
    if (segments.isEmpty()) throw new SummaryGenerationException("没有可用于摘要的转写文本");
    List<List<SegmentInput>> groups = sectionGroups(segments);
    List<SectionResult> sections = new ArrayList<>();
    String modelName = "";
    for (int index = 0; index < groups.size(); index++) {
      List<SegmentInput> group = groups.get(index);
      long startMs = group.getFirst().startMs();
      long endMs = group.getLast().endMs();
      GeneratedText generated =
          provider.summarizeSection(mediaItemId, index, untrustedTranscript(group));
      requireText(generated);
      modelName = generated.modelName();
      sections.add(new SectionResult(index, startMs, endMs, generated.text()));
    }
    GeneratedText global = provider.summarizeGlobal(mediaItemId, List.copyOf(sections));
    requireText(global);
    if (!global.modelName().isBlank()) modelName = global.modelName();
    return new SummaryBundle(List.copyOf(sections), global.text(), modelName, clock.instant());
  }

  private List<List<SegmentInput>> sectionGroups(List<SegmentInput> source) {
    List<List<SegmentInput>> groups = new ArrayList<>();
    List<SegmentInput> current = new ArrayList<>();
    for (SegmentInput segment : source) {
      if (!current.isEmpty() && segment.endMs() - current.getFirst().startMs() > MAX_SECTION_MS) {
        groups.add(List.copyOf(current));
        current.clear();
      }
      current.add(segment);
      if (segment.endMs() - current.getFirst().startMs() >= MIN_SECTION_MS) {
        groups.add(List.copyOf(current));
        current.clear();
      }
    }
    if (!current.isEmpty()) {
      if (!groups.isEmpty()
          && current.getLast().endMs() - groups.getLast().getFirst().startMs() <= MAX_SECTION_MS) {
        List<SegmentInput> merged = new ArrayList<>(groups.removeLast());
        merged.addAll(current);
        groups.add(List.copyOf(merged));
      } else {
        groups.add(List.copyOf(current));
      }
    }
    return groups;
  }

  private String untrustedTranscript(List<SegmentInput> segments) {
    StringBuilder content = new StringBuilder("<untrusted_transcript>\n");
    for (SegmentInput segment : segments) {
      content
          .append('[')
          .append(segment.startMs())
          .append('-')
          .append(segment.endMs())
          .append("] ")
          .append(segment.text())
          .append('\n');
    }
    return content.append("</untrusted_transcript>").toString();
  }

  private void requireText(GeneratedText generated) {
    if (generated == null || generated.text() == null || generated.text().isBlank()) {
      throw new SummaryGenerationException("摘要模型未返回有效内容");
    }
  }

  public interface SummaryProvider {
    GeneratedText summarizeSection(String mediaItemId, int sectionIndex, String untrustedText);

    GeneratedText summarizeGlobal(String mediaItemId, List<SectionResult> sections);
  }

  public record GeneratedText(String text, String modelName) {}

  public record SegmentInput(long startMs, long endMs, String text) {}

  public record SectionResult(int sectionIndex, long startMs, long endMs, String summary) {}

  public record SummaryBundle(
      List<SectionResult> sections, String globalSummary, String modelName, Instant generatedAt) {}

  public static class SummaryGenerationException extends RuntimeException {
    SummaryGenerationException(String message) {
      super(message);
    }
  }
}
