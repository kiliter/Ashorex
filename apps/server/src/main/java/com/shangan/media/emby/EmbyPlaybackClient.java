package com.shangan.media.emby;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 查询 Emby PlaybackInfo，优先选择 iOS 可直放源，否则回退 H.264/AAC HLS。 */
@Component
public class EmbyPlaybackClient {
  private final EmbyProperties properties;
  private final ObjectMapper json;

  public EmbyPlaybackClient(EmbyProperties properties, ObjectMapper json) {
    this.properties = properties;
    this.json = json;
  }

  public Selection select(String embyItemId, long startPositionMs) {
    RuntimeIntegrationSettings.Emby configuration = properties.current();
    if (!configuration.configured() || configuration.userId().isBlank()) throw unavailable();
    String deviceId = UUID.randomUUID().toString();
    String playSessionId = UUID.randomUUID().toString();
    try {
      String body =
          RestClient.builder()
              .baseUrl(configuration.baseUrl())
              .defaultHeader("X-Emby-Token", configuration.apiKey())
              .build()
              .post()
              .uri(
                  builder ->
                      builder
                          .path("/Items/{id}/PlaybackInfo")
                          .queryParam("UserId", configuration.userId())
                          .queryParam("DeviceId", deviceId)
                          .queryParam("StartTimeTicks", Math.max(0, startPositionMs) * 10_000)
                          .build(embyItemId))
              .body(Map.of())
              .retrieve()
              .body(String.class);
      JsonNode source = json.readTree(body).path("MediaSources").get(0);
      if (source == null) throw unavailable();
      String mediaSourceId = source.path("Id").asText();
      boolean direct = supportsIosDirectPlayback(source);
      String path =
          direct
              ? "/Videos/"
                  + embyItemId
                  + "/stream?Static=true&MediaSourceId="
                  + mediaSourceId
                  + "&DeviceId="
                  + deviceId
                  + "&PlaySessionId="
                  + playSessionId
                  + "&StartTimeTicks="
                  + Math.max(0, startPositionMs) * 10_000
              : "/Videos/"
                  + embyItemId
                  + "/master.m3u8?MediaSourceId="
                  + mediaSourceId
                  + "&VideoCodec=h264&AudioCodec=aac&DeviceId="
                  + deviceId
                  + "&PlaySessionId="
                  + playSessionId
                  + "&StartTimeTicks="
                  + Math.max(0, startPositionMs) * 10_000;
      return new Selection(path, !direct, deviceId, playSessionId);
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  private BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE, "EMBY_PLAYBACK_UNAVAILABLE", "暂时无法创建播放会话");
  }

  /**
   * 只有容器和视频编码都能由 iOS AVPlayer 稳定解码时才直放。MP4 只是容器，内部若为 HEVC、VP9 或 AV1，模拟器会出现有声音、有进度但画面全黑，因此其余情况统一交给
   * Emby 转成 H.264/AAC HLS。
   */
  static boolean supportsIosDirectPlayback(JsonNode source) {
    String container = source.path("Container").asText("").toLowerCase();
    if (!source.path("SupportsDirectStream").asBoolean(false)
        || !java.util.List.of("mp4", "m4v", "mov").contains(container)) {
      return false;
    }
    for (JsonNode stream : source.path("MediaStreams")) {
      if (!"video".equalsIgnoreCase(stream.path("Type").asText())) continue;
      String codec = stream.path("Codec").asText("").toLowerCase();
      return "h264".equals(codec) || "avc".equals(codec) || "avc1".equals(codec);
    }
    return false;
  }

  public record Selection(
      String upstreamPath, boolean hls, String deviceId, String playSessionId) {}
}
