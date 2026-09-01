package com.shangan.quiz.infrastructure;

import com.shangan.quiz.domain.Question;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 题库、答题尝试与逐题答案的持久化边界。 */
public interface QuestionRepository {
  List<Question> findByMedia(String mediaItemId, boolean enabledOnly);

  boolean hasEnabled(String mediaItemId);

  /** 按课程一次性统计每个课时的正式题目数量，供管理台局部轮询使用。 */
  Map<String, Integer> countByCourse(String courseId);

  Optional<Question> findById(String questionId);

  boolean hasAnswers(String questionId);

  void saveQuestion(Question question, Instant now);

  void insertAttempt(Attempt attempt, List<Answer> answers, Instant now);

  List<Attempt> findAttempts(String userId, String mediaItemId);

  record Attempt(
      String id,
      String userId,
      String mediaItemId,
      int score,
      int correctCount,
      int totalCount,
      long durationMs,
      Instant submittedAt) {
    /** 创建尚未落库、提交时间由 Repository 使用事务时钟填写的答题尝试。 */
    public Attempt(
        String id,
        String userId,
        String mediaItemId,
        int score,
        int correctCount,
        int totalCount,
        long durationMs) {
      this(id, userId, mediaItemId, score, correctCount, totalCount, durationMs, null);
    }
  }

  record Answer(
      String id, String questionId, String selectedOptionId, boolean correct, long durationMs) {}
}
