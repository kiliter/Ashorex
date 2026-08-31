package com.shangan.media.emby;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 从固定 Emby 源站流式下载 16 kHz 单声道 MP3，不请求或缓冲完整视频。 */
@Component
public class EmbyAudioClient {

  private final ObjectMapper json;
  private final Path tempDirectory;

  public EmbyAudioClient(
      ObjectMapper json,
      @Value("${app.content.temp-directory:${java.io.tmpdir}}") String tempDirectory) {
    this.json = json;
    this.tempDirectory = Path.of(tempDirectory).toAbsolutePath().normalize();
  }

  /** 先解析 MediaSourceId，再把 Emby 音频响应边读边写入临时 MP3。 */
  public DownloadedAudio download(
      String embyItemId, RuntimeIntegrationSettings.Emby configuration) {
    if (!configuration.configured() || configuration.userId().isBlank()) throw unavailable();
    Path target = null;
    try {
      var requestFactory = new SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(15));
      requestFactory.setReadTimeout(java.time.Duration.ofHours(2));
      RestClient client =
          RestClient.builder()
              .baseUrl(configuration.baseUrl())
              // Emby 和部分反向代理对 HTTP/2 兼容不一致，音频流固定使用简单 HTTP 客户端。
              .requestFactory(requestFactory)
              .defaultHeader("X-Emby-Token", configuration.apiKey())
              .build();
      String playbackInfo =
          client
              .post()
              .uri(
                  builder ->
                      builder
                          .path("/Items/{id}/PlaybackInfo")
                          .queryParam("UserId", configuration.userId())
                          .build(embyItemId))
              .body(Map.of())
              .retrieve()
              .body(String.class);
      JsonNode source = json.readTree(playbackInfo).path("MediaSources").get(0);
      if (source == null || source.path("Id").asText().isBlank()) throw unavailable();

      Files.createDirectories(tempDirectory);
      target = Files.createTempFile(tempDirectory, "shangan-audio-", ".mp3");
      Path finalTarget = target;
      client
          .get()
          .uri(
              builder ->
                  builder
                      .path("/Audio/{id}/stream.mp3")
                      .queryParam("MediaSourceId", source.path("Id").asText())
                      .queryParam("AudioCodec", "mp3")
                      .queryParam("AudioSampleRate", 16000)
                      .queryParam("MaxAudioChannels", 1)
                      .queryParam("AudioBitRate", 64000)
                      .build(embyItemId))
          .exchange(
              (request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw unavailable();
                Files.copy(response.getBody(), finalTarget, StandardCopyOption.REPLACE_EXISTING);
                return null;
              });
      long size = Files.size(target);
      if (size <= 0) throw unavailable();
      return new DownloadedAudio(target, size);
    } catch (BusinessException exception) {
      deleteQuietly(target);
      throw exception;
    } catch (Exception exception) {
      deleteQuietly(target);
      throw unavailable();
    }
  }

  private void deleteQuietly(Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // 清理失败不会覆盖原始业务异常，任务层仍会记录安全的失败码。
    }
  }

  private BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE, "EMBY_AUDIO_UNAVAILABLE", "暂时无法取得课时音频");
  }

  /** 调用方使用 try-with-resources，确保成功、失败或取消后都删除临时音频。 */
  public record DownloadedAudio(Path path, long sizeBytes) implements AutoCloseable {
    @Override
    public void close() {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
        // 临时文件清理失败由操作日志监控，不向外暴露本机路径。
      }
    }
  }
}
