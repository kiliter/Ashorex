package com.shangan.ai.transcript;

import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.media.emby.EmbyProperties;
import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** 仅从服务端固定 Emby 主机构造转写媒体源，客户端不能覆盖目标地址。 */
@Component
class EmbyTranscriptionMediaSource {
  private final EmbyProperties properties;

  EmbyTranscriptionMediaSource(EmbyProperties properties) {
    this.properties = properties;
  }

  Source resolve(String embyItemId) {
    RuntimeIntegrationSettings.Emby configuration = properties.current();
    if (!configuration.configured()) throw new MediaSourceException("Emby 服务尚未配置");
    URI uri =
        UriComponentsBuilder.fromUriString(configuration.baseUrl())
            .pathSegment("Videos", embyItemId, "stream")
            .queryParam("Static", true)
            .build()
            .encode()
            .toUri();
    return new Source(uri, Map.of("X-Emby-Token", configuration.apiKey()));
  }

  record Source(URI uri, Map<String, String> headers) {}

  static class MediaSourceException extends RuntimeException {
    MediaSourceException(String message) {
      super(message);
    }
  }
}
