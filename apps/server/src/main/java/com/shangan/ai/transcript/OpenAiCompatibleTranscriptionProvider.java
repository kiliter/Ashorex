package com.shangan.ai.transcript;

import com.shangan.common.integration.IntegrationSettingsProvider;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** OpenAI-compatible ASR HTTP 实现；API Key 仅放在服务端 Authorization 请求头。 */
@Component
public class OpenAiCompatibleTranscriptionProvider implements TranscriptionProvider {
  private final IntegrationSettingsProvider settings;
  private final ObjectMapper json;

  @Autowired
  public OpenAiCompatibleTranscriptionProvider(
      IntegrationSettingsProvider settings, ObjectMapper json) {
    this.settings = settings;
    this.json = json;
  }

  /** 为不启动 Spring 的 HTTP 契约测试保留固定配置构造器。 */
  public OpenAiCompatibleTranscriptionProvider(
      String baseUrl, String apiKey, String model, Duration timeout, ObjectMapper json) {
    this(
        () ->
            new RuntimeIntegrationSettings(
                new RuntimeIntegrationSettings.Emby("", "", ""),
                new RuntimeIntegrationSettings.Llm("", "", "", 16_000, 0.2, 120),
                new RuntimeIntegrationSettings.Asr(
                    baseUrl, apiKey, model, Math.toIntExact(timeout.toSeconds())),
                new RuntimeIntegrationSettings.Mcp("", "", "web_search,web_extract", 20),
                0),
        json);
  }

  private RestClient client(RuntimeIntegrationSettings.Asr configuration) {
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    requestFactory.setReadTimeout(Duration.ofSeconds(configuration.timeoutSeconds()));
    return RestClient.builder()
        .baseUrl(configuration.baseUrl())
        .requestFactory(requestFactory)
        .build();
  }

  @Override
  public TranscriptionResult transcribe(Path audioChunk, TranscriptionRequest request) {
    RuntimeIntegrationSettings.Asr configuration = settings.current().asr();
    if (!configuration.configured()) {
      throw new TranscriptionProviderException("ASR 服务尚未配置");
    }
    try {
      MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
      body.add("file", new FileSystemResource(audioChunk));
      body.add("model", configuration.model());
      body.add("response_format", "verbose_json");
      body.add("timestamp_granularities[]", "segment");
      if (request.language() != null && !request.language().isBlank()) {
        body.add("language", request.language());
      }
      String response =
          client(configuration)
              .post()
              .uri("/audio/transcriptions")
              .header("Authorization", "Bearer " + configuration.apiKey())
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(body)
              .retrieve()
              .body(String.class);
      return parse(response, request.chunkStartMs(), configuration.model());
    } catch (TranscriptionProviderException exception) {
      throw exception;
    } catch (Exception exception) {
      // 不传播第三方响应或请求头，后台只保存稳定的安全错误。
      throw new TranscriptionProviderException("ASR 转写请求失败", exception);
    }
  }

  private TranscriptionResult parse(String response, long chunkStartMs, String model)
      throws Exception {
    JsonNode root = json.readTree(response);
    List<TranscriptionSegment> segments = new ArrayList<>();
    JsonNode values = root.path("segments");
    if (values.isArray()) {
      for (JsonNode value : values) {
        String text = value.path("text").asText("").trim();
        if (text.isBlank()) continue;
        long start = chunkStartMs + Math.max(0, Math.round(value.path("start").asDouble() * 1000));
        long end = chunkStartMs + Math.max(0, Math.round(value.path("end").asDouble() * 1000));
        segments.add(new TranscriptionSegment(start, Math.max(start, end), text));
      }
    }
    if (segments.isEmpty()) {
      String text = root.path("text").asText("").trim();
      if (!text.isBlank()) segments.add(new TranscriptionSegment(chunkStartMs, chunkStartMs, text));
    }
    if (segments.isEmpty()) throw new TranscriptionProviderException("ASR 未返回可用文本");
    return new TranscriptionResult(segments, model);
  }

  public static class TranscriptionProviderException extends RuntimeException {
    TranscriptionProviderException(String message) {
      super(message);
    }

    TranscriptionProviderException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
