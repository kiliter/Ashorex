package com.shangan.ai.application;

import dev.langchain4j.invocation.InvocationParameters;

/** 模型流式边界，业务服务只依赖事件语义，测试可使用确定性 Fake。 */
public interface AiChatEngine {
  void stream(String memoryId, String prompt, InvocationParameters parameters, Listener listener);

  interface Listener {
    void onToolStarted(String name);

    void onToolCompleted(String name, String result);

    void onDelta(String text);

    void onComplete(String modelName, int inputTokens, int outputTokens);

    void onError(Throwable error);
  }
}
