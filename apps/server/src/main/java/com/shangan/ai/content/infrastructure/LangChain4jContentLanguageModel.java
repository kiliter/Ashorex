package com.shangan.ai.content.infrastructure;

import com.shangan.ai.content.application.ContentLanguageModel;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 使用稳定版 LangChain4j 调用 CPA 或其他 OpenAI-compatible Chat Completions。 */
@Component
public class LangChain4jContentLanguageModel implements ContentLanguageModel {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(LangChain4jContentLanguageModel.class);

  /** 每次任务阶段从不可变配置快照创建模型，后台新配置不会改变正在执行的请求。 */
  @Override
  public GenerationResult generate(
      String systemPrompt,
      String userPrompt,
      RuntimeIntegrationSettings.Llm configuration,
      boolean jsonResponse) {
    if (!configuration.configured()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "LLM_NOT_CONFIGURED", "请先配置 LLM 地址、模型和上下文长度");
    }
    try {
      var httpClient =
          JdkHttpClient.builder()
              .httpClientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1))
              .connectTimeout(Duration.ofSeconds(15))
              .readTimeout(Duration.ofSeconds(configuration.timeoutSeconds()));
      var builder =
          OpenAiChatModel.builder()
              .httpClientBuilder(httpClient)
              .baseUrl(configuration.baseUrl())
              .apiKey(
                  configuration.apiKey() == null || configuration.apiKey().isBlank()
                      ? "not-required"
                      : configuration.apiKey())
              .modelName(configuration.model())
              .maxCompletionTokens(configuration.maxCompletionTokens())
              .temperature(0.2)
              .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
              .maxRetries(0)
              .logRequests(false)
              .logResponses(false);
      // 仅在管理员明确选择时发送，空值保持普通非推理模型兼容性。
      if (configuration.reasoningEffort() != null && !configuration.reasoningEffort().isBlank()) {
        builder.reasoningEffort(configuration.reasoningEffort());
      }
      if (jsonResponse) builder.responseFormat("json_object");
      ChatResponse response =
          builder
              .build()
              .chat(List.of(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt)));
      String text = response.aiMessage().text();
      if (text == null || text.isBlank()) throw failed();
      TokenUsage usage = response.tokenUsage();
      return new GenerationResult(
          text.trim(),
          usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
          usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      // 错误消息只给管理员稳定文案，真实原因必须落到服务日志，否则任务失败无从排查。
      log.warn("LLM 内容生成调用失败：model={}", configuration.model(), exception);
      throw failed();
    }
  }

  private BusinessException failed() {
    return new BusinessException(
        HttpStatus.BAD_GATEWAY, "LLM_REQUEST_FAILED", "内容模型调用失败，请查看任务日志后重试");
  }
}
