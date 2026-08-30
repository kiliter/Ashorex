package com.shangan.quiz.application;

import com.shangan.planning.application.VideoTaskRequirementPort;
import com.shangan.quiz.infrastructure.QuestionRepository;
import org.springframework.stereotype.Component;

/** 在锁定计划时把当前是否存在启用题目快照到 VIDEO 任务。 */
@Component
public class QuizRequirementAdapter implements VideoTaskRequirementPort {
  private final QuestionRepository questions;

  public QuizRequirementAdapter(QuestionRepository questions) {
    this.questions = questions;
  }

  @Override
  public boolean quizRequired(String mediaItemId) {
    return questions.hasEnabled(mediaItemId);
  }
}
