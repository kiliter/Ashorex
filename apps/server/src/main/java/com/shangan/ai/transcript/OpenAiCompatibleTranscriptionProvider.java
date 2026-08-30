package com.shangan.ai.transcript;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
  private final String apiKey;
  private final String model;
  private final RestClient http;
  private final ObjectMapper json;

  public OpenAiCompatibleTranscriptionProvider(
      @Value("${app.ai.asr.base-url:}") String baseUrl,
      @Value("${app.ai.asr.api-key:}") String apiKey,
      @Value("${app.ai.asr.model:}") String model,
      @Value("${app.ai.asr.timeout:PT2M}") Duration timeout,
      ObjectMapper json) {
    this.apiKey = apiKey == null ? "" : apiKey;
    this.model = model == null ? "" : model;
    this.json = json;
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    requestFactory.setReadTimeout(timeout);
    this.http =
        RestClient.builder()
            .baseUrl(baseUrl == null ? "" : baseUrl.replaceAll("/+$", ""))
            .requestFactory(requestFactory)
            .build();
  }

  @Override
  public TranscriptionResult transcribe(Path audioChunk, TranscriptionRequest request) {
    if (apiKey.isBlank() || model.isBlank()) {
      throw new TranscriptionProviderException("ASR 服务尚未配置");
    }
    try {
      MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
      body.add("file", new FileSystemResource(audioChunk));
      body.add("model", model);
      body.add("response_format", "verbose_json");
      body.add("timestamp_granularities[]", "segment");
      if (request.language() != null && !request.language().isBlank()) {
        body.add("language", request.language());
      }
      String response =
          http.post()
              .uri("/audio/transcriptions")
              .header("Authorization", "Bearer " + apiKey)
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(body)
              .retrieve()
              .body(String.class);
      return parse(response, request.chunkStartMs());
    } catch (TranscriptionProviderException exception) {
      throw exception;
    } catch (Exception exception) {
      // 不传播第三方响应或请求头，后台只保存稳定的安全错误。
      throw new TranscriptionProviderException("ASR 转写请求失败", exception);
    }
  }

  private TranscriptionResult parse(String response, long chunkStartMs) throws Exception {
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
