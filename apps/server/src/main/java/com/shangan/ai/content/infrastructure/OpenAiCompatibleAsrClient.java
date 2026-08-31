package com.shangan.ai.content.infrastructure;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 调用 OpenAI-compatible 音频转写接口，并按顺序拼接 mlx-audio NDJSON 的 text。 */
@Component
public class OpenAiCompatibleAsrClient {

  private final ObjectMapper json;

  public OpenAiCompatibleAsrClient(ObjectMapper json) {
    this.json = json;
  }

  /** 只消费 text 字段，明确忽略 accumulated，避免流式累计结果被重复拼接。 */
  public String transcribe(Path audioPath, RuntimeIntegrationSettings.Asr configuration) {
    if (!configuration.configured()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "ASR_NOT_CONFIGURED", "请先在服务配置中填写 ASR 地址和模型");
    }
    try {
      var requestFactory = new SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(15));
      requestFactory.setReadTimeout(java.time.Duration.ofSeconds(configuration.timeoutSeconds()));
      RestClient client =
          RestClient.builder()
              .baseUrl(configuration.baseUrl())
              .requestFactory(requestFactory)
              .build();
      MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
      form.add("file", new FileSystemResource(audioPath));
      form.add("model", configuration.model());
      form.add("language", configuration.language());
      form.add("stream", "true");
      form.add("chunk_duration", Integer.toString(configuration.chunkDurationSeconds()));

      String transcript =
          client
              .post()
              .uri("/v1/audio/transcriptions")
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .headers(
                  headers -> {
                    if (configuration.apiKey() != null && !configuration.apiKey().isBlank()) {
                      headers.setBearerAuth(configuration.apiKey());
                    }
                  })
              .body(form)
              .exchange(
                  (request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) throw failed();
                    StringBuilder result = new StringBuilder();
                    try (BufferedReader reader =
                        new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                      String line;
                      while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        JsonNode event = json.readTree(line);
                        if (event.has("error") && !event.path("error").isNull()) throw failed();
                        if (event.has("text") && !event.path("text").isNull()) {
                          result.append(event.path("text").asText());
                        }
                      }
                    }
                    return result.toString();
                  });
      if (transcript == null || transcript.isBlank()) throw failed();
      return transcript.trim();
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw failed();
    }
  }

  private BusinessException failed() {
    return new BusinessException(
        HttpStatus.BAD_GATEWAY, "ASR_REQUEST_FAILED", "转写服务调用失败，请查看任务日志后重试");
  }
}
