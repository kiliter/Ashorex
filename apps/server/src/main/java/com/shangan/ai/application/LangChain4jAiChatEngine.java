package com.shangan.ai.application;

import com.shangan.ai.application.RuntimeAiChatEngine.StudyAssistant;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.output.TokenUsage;

/** 把 LangChain4j TokenStream 转换为稳定的应用层事件，不向业务层泄露框架类型。 */
public class LangChain4jAiChatEngine implements AiChatEngine {
  private final StudyAssistant assistant;
  private final String configuredModelName;
  private final boolean configured;

  public LangChain4jAiChatEngine(
      StudyAssistant assistant, String configuredModelName, boolean configured) {
    this.assistant = assistant;
    this.configuredModelName = configuredModelName;
    this.configured = configured;
  }

  @Override
  public void stream(
      String memoryId, String prompt, InvocationParameters parameters, Listener listener) {
    if (!configured) {
      listener.onError(new IllegalStateException("AI 服务尚未配置"));
      return;
    }
    assistant
        .chat(memoryId, prompt, parameters)
        .beforeToolExecution(value -> listener.onToolStarted(value.request().name()))
        .onToolExecuted(value -> listener.onToolCompleted(value.request().name(), value.result()))
        .onPartialResponse(listener::onDelta)
        .onCompleteResponse(
            response -> {
              TokenUsage usage = response.tokenUsage();
              listener.onComplete(
                  response.modelName() == null ? configuredModelName : response.modelName(),
                  usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                  usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());
            })
        .onError(listener::onError)
        .start();
  }
}
