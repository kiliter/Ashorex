package com.shangan.ai.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 验证三类内容任务只能沿冻结状态机前进。 */
class ContentGenerationJobTest {

  @Test
  void allowsTranscriptionAndSummaryHappyPaths() {
    assertThat(
            ContentGenerationJob.Status.QUEUED.canTransitionTo(
                ContentGenerationJob.Type.TRANSCRIBE, ContentGenerationJob.Status.FETCHING_AUDIO))
        .isTrue();
    assertThat(
            ContentGenerationJob.Status.FETCHING_AUDIO.canTransitionTo(
                ContentGenerationJob.Type.TRANSCRIBE, ContentGenerationJob.Status.TRANSCRIBING))
        .isTrue();
    assertThat(
            ContentGenerationJob.Status.TRANSCRIBING.canTransitionTo(
                ContentGenerationJob.Type.TRANSCRIBE, ContentGenerationJob.Status.READY))
        .isTrue();
    assertThat(
            ContentGenerationJob.Status.SUMMARIZING.canTransitionTo(
                ContentGenerationJob.Type.SUMMARIZE, ContentGenerationJob.Status.READY))
        .isTrue();
  }

  @Test
  void rejectsCrossTypeAndTerminalTransitionsButAllowsFailure() {
    assertThatThrownBy(
            () ->
                ContentGenerationJob.Status.QUEUED.requireTransition(
                    ContentGenerationJob.Type.SUMMARIZE,
                    ContentGenerationJob.Status.FETCHING_AUDIO))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            ContentGenerationJob.Status.GENERATING_QUIZ.canTransitionTo(
                ContentGenerationJob.Type.GENERATE_QUIZ,
                ContentGenerationJob.Status.READY_FOR_REVIEW))
        .isTrue();
    assertThat(
            ContentGenerationJob.Status.READY.canTransitionTo(
                ContentGenerationJob.Type.SUMMARIZE, ContentGenerationJob.Status.FAILED))
        .isFalse();
    assertThat(
            ContentGenerationJob.Status.SUMMARIZING.canTransitionTo(
                ContentGenerationJob.Type.SUMMARIZE, ContentGenerationJob.Status.FAILED))
        .isTrue();
  }
}
