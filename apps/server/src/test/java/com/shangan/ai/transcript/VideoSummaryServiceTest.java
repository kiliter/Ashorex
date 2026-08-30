package com.shangan.ai.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证分段边界处于 5~10 分钟，并且全局摘要确实消费全部分段摘要。 */
class VideoSummaryServiceTest {
  @Test
  void buildsFiveToTenMinuteSectionsAndGlobalSummaryFromEverySection() {
    RecordingSummaryProvider provider = new RecordingSummaryProvider();
    Instant now = Instant.parse("2026-08-30T04:00:00Z");
    VideoSummaryService service =
        new VideoSummaryService(provider, Clock.fixed(now, ZoneOffset.UTC));

    var result =
        service.generate(
            "media-1",
            List.of(
                new VideoSummaryService.SegmentInput(0, 240_000, "第一部分"),
                new VideoSummaryService.SegmentInput(240_000, 360_000, "第二部分"),
                new VideoSummaryService.SegmentInput(360_000, 540_000, "第三部分"),
                new VideoSummaryService.SegmentInput(540_000, 720_000, "第四部分")));

    assertThat(result.sections())
        .extracting(section -> section.endMs() - section.startMs())
        .containsExactly(360_000L, 360_000L)
        .allSatisfy(duration -> assertThat(duration).isBetween(300_000L, 600_000L));
    assertThat(provider.untrustedInputs)
        .hasSize(2)
        .allSatisfy(text -> assertThat(text).contains("<untrusted_transcript>"));
    assertThat(provider.globalSections).hasSize(2);
    assertThat(result.globalSummary()).isEqualTo("全局摘要");
    assertThat(result.generatedAt()).isEqualTo(now);
  }

  private static final class RecordingSummaryProvider
      implements VideoSummaryService.SummaryProvider {
    private final List<String> untrustedInputs = new ArrayList<>();
    private List<VideoSummaryService.SectionResult> globalSections = List.of();

    @Override
    public VideoSummaryService.GeneratedText summarizeSection(
        String mediaItemId, int sectionIndex, String untrustedText) {
      untrustedInputs.add(untrustedText);
      return new VideoSummaryService.GeneratedText("分段摘要 " + sectionIndex, "model-1");
    }

    @Override
    public VideoSummaryService.GeneratedText summarizeGlobal(
        String mediaItemId, List<VideoSummaryService.SectionResult> sections) {
      globalSections = sections;
      return new VideoSummaryService.GeneratedText("全局摘要", "model-1");
    }
  }
}
