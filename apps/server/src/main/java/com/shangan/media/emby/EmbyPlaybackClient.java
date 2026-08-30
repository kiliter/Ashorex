package com.shangan.media.emby;

import com.shangan.common.api.BusinessException;
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

  public Selection select(String embyItemId) {
    if (!properties.configured() || properties.userId().isBlank()) throw unavailable();
    String deviceId = UUID.randomUUID().toString();
    String playSessionId = UUID.randomUUID().toString();
    try {
      String body =
          RestClient.builder()
              .baseUrl(properties.baseUrl())
              .defaultHeader("X-Emby-Token", properties.apiKey())
              .build()
              .post()
              .uri(
                  builder ->
                      builder
                          .path("/Items/{id}/PlaybackInfo")
                          .queryParam("UserId", properties.userId())
                          .queryParam("DeviceId", deviceId)
                          .queryParam("StartTimeTicks", 0)
                          .build(embyItemId))
              .body(Map.of())
              .retrieve()
              .body(String.class);
      JsonNode source = json.readTree(body).path("MediaSources").get(0);
      if (source == null) throw unavailable();
      String mediaSourceId = source.path("Id").asText();
      String container = source.path("Container").asText("").toLowerCase();
      boolean direct =
          source.path("SupportsDirectStream").asBoolean(false)
              && java.util.List.of("mp4", "m4v", "mov").contains(container);
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
              : "/Videos/"
                  + embyItemId
                  + "/master.m3u8?MediaSourceId="
                  + mediaSourceId
                  + "&VideoCodec=h264&AudioCodec=aac&DeviceId="
                  + deviceId
                  + "&PlaySessionId="
                  + playSessionId;
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

  public record Selection(
      String upstreamPath, boolean hls, String deviceId, String playSessionId) {}
}
