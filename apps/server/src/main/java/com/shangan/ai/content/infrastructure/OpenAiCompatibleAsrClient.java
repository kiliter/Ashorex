package com.shangan.ai.content.infrastructure;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;
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

/** 调用 OpenAI-compatible 音频转写接口，并按顺序拼接流式响应中的 text。 */
@Component
public class OpenAiCompatibleAsrClient {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleAsrClient.class);
  private static final String ASR_TEXT_MARKER = "<asr_text>";
  private static final Pattern REPEATED_ASR_SEGMENT_PREFIX =
      Pattern.compile("(?i)(?:language\\s+[^<\\r\\n]*?)?<asr_text>\\s*");

  private final ObjectMapper json;

  public OpenAiCompatibleAsrClient(ObjectMapper json) {
    this.json = json;
  }

  /** 只拼接每条响应的 text 字段；单个 JSON 和 NDJSON 都使用同一条解析路径。 */
  public String transcribe(Path audioPath, RuntimeIntegrationSettings.Asr configuration) {
    if (!configuration.configured()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "ASR_NOT_CONFIGURED", "请先在服务配置中填写 ASR 地址和模型");
    }
    try {
      var requestFactory = new SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(15));
      requestFactory.setReadTimeout(java.time.Duration.ofSeconds(configuration.timeoutSeconds()));
      RestClient client = RestClient.builder().requestFactory(requestFactory).build();
      MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
      form.add("file", new FileSystemResource(audioPath));
      form.add("model", configuration.model());
      form.add("language", configuration.language());
      form.add("stream", "true");
      form.add("chunk_duration", Integer.toString(configuration.chunkDurationSeconds()));

      String transcript =
          client
              .post()
              .uri(resolveTranscriptionUrl(configuration.baseUrl()))
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
      String cleanedTranscript = cleanAsrText(transcript);
      if (cleanedTranscript == null || cleanedTranscript.isBlank()) throw failed();
      return cleanedTranscript;
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      // 错误消息只给管理员稳定文案，真实原因必须落到服务日志，否则任务失败无从排查。
      log.warn("ASR 转写调用失败", exception);
      throw failed();
    }
  }

  /** 清理 Qwen3-ASR 首段及长音频内部各片段重复返回的语言头和文本控制标记。 */
  private String cleanAsrText(String raw) {
    if (raw == null || raw.isBlank()) {
      return raw;
    }
    int firstMarker = raw.indexOf(ASR_TEXT_MARKER);
    String transcript =
        firstMarker >= 0 ? raw.substring(firstMarker + ASR_TEXT_MARKER.length()) : raw;
    return REPEATED_ASR_SEGMENT_PREFIX.matcher(transcript).replaceAll("").trim();
  }

  /** 兼容配置项以服务根地址或 /v1 结尾，避免生成重复的 /v1/v1 路径。 */
  private String resolveTranscriptionUrl(String configuredBaseUrl) {
    String baseUrl = configuredBaseUrl.strip().replaceAll("/+$", "");
    return baseUrl.endsWith("/v1")
        ? baseUrl + "/audio/transcriptions"
        : baseUrl + "/v1/audio/transcriptions";
  }

  private BusinessException failed() {
    return new BusinessException(
        HttpStatus.BAD_GATEWAY, "ASR_REQUEST_FAILED", "转写服务调用失败，请查看任务日志后重试");
  }
}
