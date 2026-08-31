package com.shangan.ai.content.infrastructure;

import com.shangan.ai.content.domain.QuizGenerationDraft;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** AI 题目草稿、选项和幂等发布标记的持久化边界。 */
public interface QuizGenerationDraftRepository {

  void save(QuizGenerationDraft draft);

  Optional<QuizGenerationDraft> findById(String draftId);

  List<QuizGenerationDraft> findByCourse(String courseId);

  boolean hasReadyDraft(String mediaItemId);

  void markPublished(String draftId, Map<String, String> questionIdsByItemId, Instant publishedAt);

  void updateItem(QuizGenerationDraft.Item item);

  void deleteItem(String itemId);
}
