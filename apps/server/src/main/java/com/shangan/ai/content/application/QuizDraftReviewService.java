package com.shangan.ai.content.application;

import com.shangan.ai.content.domain.QuizGenerationDraft;
import com.shangan.ai.content.infrastructure.QuizGenerationDraftRepository;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提供题目草稿列表、人工编辑和删除，正式发布由独立事务服务完成。 */
@Service
public class QuizDraftReviewService {

  private final QuizGenerationDraftRepository drafts;
  private final IdGenerator ids;

  public QuizDraftReviewService(QuizGenerationDraftRepository drafts, IdGenerator ids) {
    this.drafts = drafts;
    this.ids = ids;
  }

  public List<QuizGenerationDraft> list(String courseId) {
    return drafts.findByCourse(courseId);
  }

  /** 编辑前验证草稿归属和未发布状态，再整体替换当前题目的选项。 */
  public void updateItem(
      String courseId,
      String draftId,
      String itemId,
      String questionType,
      String content,
      String explanation,
      int sortOrder,
      List<String> optionContents,
      int correctOptionIndex) {
    QuizGenerationDraft draft = requireEditable(courseId, draftId);
    QuizGenerationDraft.Item existing =
        draft.items().stream()
            .filter(item -> item.id().equals(itemId))
            .findFirst()
            .orElseThrow(() -> invalid("草稿题目不存在"));
    List<QuizGenerationDraft.Option> options = new ArrayList<>();
    List<String> safeOptions = optionContents == null ? List.of() : optionContents;
    for (int index = 0; index < safeOptions.size(); index++) {
      options.add(
          new QuizGenerationDraft.Option(
              ids.nextId(), safeOptions.get(index), index == correctOptionIndex, index));
    }
    QuizGenerationDraft.Item updated =
        new QuizGenerationDraft.Item(
            existing.id(), questionType, content, explanation, sortOrder, null, options);
    updated.validate();
    drafts.updateItem(updated);
  }

  public void deleteItem(String courseId, String draftId, String itemId) {
    QuizGenerationDraft draft = requireEditable(courseId, draftId);
    if (draft.items().stream().noneMatch(item -> item.id().equals(itemId))) {
      throw invalid("草稿题目不存在");
    }
    drafts.deleteItem(itemId);
  }

  /** 批量驳回待审核草稿；驳回只改变状态并保留内容用于回看。 */
  @Transactional
  public int reject(String courseId, List<String> draftIds) {
    List<QuizGenerationDraft> selected = requireSelected(courseId, draftIds, false);
    for (QuizGenerationDraft draft : selected) {
      if (draft.status() != QuizGenerationDraft.Status.READY_FOR_REVIEW) {
        throw invalid("只有待审核草稿可以驳回");
      }
      drafts.markRejected(draft.id());
    }
    return selected.size();
  }

  /** 批量删除未发布草稿；正式题库已经引用的已发布草稿禁止删除。 */
  @Transactional
  public int deleteDrafts(String courseId, List<String> draftIds) {
    List<QuizGenerationDraft> selected = requireSelected(courseId, draftIds, true);
    for (QuizGenerationDraft draft : selected) {
      if (draft.status() == QuizGenerationDraft.Status.PUBLISHED) {
        throw invalid("已发布草稿不能删除");
      }
      drafts.deleteDraft(draft.id());
    }
    return selected.size();
  }

  private List<QuizGenerationDraft> requireSelected(
      String courseId, List<String> draftIds, boolean allowRejected) {
    List<String> distinctIds =
        draftIds == null
            ? List.of()
            : draftIds.stream().filter(Objects::nonNull).distinct().toList();
    if (distinctIds.isEmpty()) throw invalid("请至少选择一份题目草稿");
    List<QuizGenerationDraft> selected = new ArrayList<>();
    for (String draftId : distinctIds) {
      QuizGenerationDraft draft = drafts.findById(draftId).orElseThrow(() -> invalid("题目草稿不存在"));
      if (!draft.courseId().equals(courseId)) throw invalid("所选草稿不属于当前课程");
      if (!allowRejected && draft.status() == QuizGenerationDraft.Status.REJECTED) {
        throw invalid("已驳回草稿不能重复驳回");
      }
      selected.add(draft);
    }
    return List.copyOf(selected);
  }

  private QuizGenerationDraft requireEditable(String courseId, String draftId) {
    QuizGenerationDraft draft = drafts.findById(draftId).orElseThrow(() -> invalid("题目草稿不存在"));
    if (!draft.courseId().equals(courseId)
        || draft.status() != QuizGenerationDraft.Status.READY_FOR_REVIEW) {
      throw invalid("题目草稿不属于当前课程或已经发布");
    }
    return draft;
  }

  private BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "QUIZ_DRAFT_INVALID", message);
  }
}
