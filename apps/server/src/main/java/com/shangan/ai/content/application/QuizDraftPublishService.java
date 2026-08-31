package com.shangan.ai.content.application;

import com.shangan.ai.content.domain.QuizGenerationDraft;
import com.shangan.ai.content.infrastructure.QuizGenerationDraftRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.quiz.domain.Question;
import com.shangan.quiz.infrastructure.QuestionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 在事务外校验全部草稿，再用一个短事务追加正式题目并写入幂等发布标记。 */
@Service
public class QuizDraftPublishService {

  private final QuizGenerationDraftRepository drafts;
  private final QuestionRepository questions;
  private final IdGenerator ids;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public QuizDraftPublishService(
      QuizGenerationDraftRepository drafts,
      QuestionRepository questions,
      IdGenerator ids,
      Clock clock,
      TransactionTemplate transactions) {
    this.drafts = drafts;
    this.questions = questions;
    this.ids = ids;
    this.clock = clock;
    this.transactions = transactions;
  }

  /** 已发布草稿会被安全跳过，因此浏览器重复提交不会创建重复题目。 */
  public PublishResult publish(String courseId, List<String> selectedDraftIds) {
    List<String> distinctIds =
        selectedDraftIds == null
            ? List.of()
            : selectedDraftIds.stream().filter(Objects::nonNull).distinct().toList();
    if (distinctIds.isEmpty()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "QUIZ_DRAFT_SELECTION_EMPTY", "请至少选择一份题目草稿");
    }

    List<PreparedDraft> prepared = new ArrayList<>();
    for (String draftId : distinctIds) {
      QuizGenerationDraft draft =
          drafts
              .findById(draftId)
              .orElseThrow(
                  () ->
                      new BusinessException(
                          HttpStatus.NOT_FOUND, "QUIZ_DRAFT_NOT_FOUND", "题目草稿不存在"));
      if (!draft.courseId().equals(courseId)) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "QUIZ_DRAFT_INVALID", "所选草稿不属于当前课程");
      }
      if (draft.status() == QuizGenerationDraft.Status.PUBLISHED) continue;
      draft.validate();
      prepared.add(prepare(draft));
    }
    if (prepared.isEmpty()) return new PublishResult(0, 0);

    Instant now = clock.instant();
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              int questionCount = 0;
              for (PreparedDraft value : prepared) {
                Map<String, String> publishedIds = new LinkedHashMap<>();
                for (PreparedQuestion preparedQuestion : value.questions()) {
                  questions.saveQuestion(preparedQuestion.question(), now);
                  publishedIds.put(
                      preparedQuestion.draftItemId(), preparedQuestion.question().id());
                  questionCount++;
                }
                drafts.markPublished(value.draftId(), publishedIds, now);
              }
              return new PublishResult(prepared.size(), questionCount);
            }));
  }

  private PreparedDraft prepare(QuizGenerationDraft draft) {
    List<PreparedQuestion> values = new ArrayList<>();
    for (QuizGenerationDraft.Item item : draft.items()) {
      String questionId = ids.nextId();
      List<Question.Option> options = new ArrayList<>();
      for (QuizGenerationDraft.Option option : item.options()) {
        options.add(
            new Question.Option(
                ids.nextId(), option.content(), option.correct(), option.sortOrder()));
      }
      Question question =
          new Question(
              questionId,
              draft.mediaItemId(),
              item.questionType(),
              item.content(),
              item.explanation(),
              true,
              item.sortOrder(),
              options);
      question.validate();
      values.add(new PreparedQuestion(item.id(), question));
    }
    return new PreparedDraft(draft.id(), List.copyOf(values));
  }

  private record PreparedDraft(String draftId, List<PreparedQuestion> questions) {}

  private record PreparedQuestion(String draftItemId, Question question) {}

  public record PublishResult(int publishedDraftCount, int publishedQuestionCount) {}
}
