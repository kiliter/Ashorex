package com.shangan.learning.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.learning.application.PlaybackSessionService;
import com.shangan.media.emby.EmbyProperties;
import com.shangan.media.emby.EmbyStreamProxy;
import java.io.ByteArrayInputStream;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 播放代理回归测试，确保 HLS 分片始终按字节流返回而不是进入 JSON 转换器。 */
class PlaybackProxyControllerTest {

  @Test
  void hlsTransportStreamSegmentIsWrittenDirectlyToResponse() throws Exception {
    byte[] segment = new byte[] {0x47, 0x40, 0x00, 0x10};
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.CONTENT_TYPE, "video/mp2t");
    headers.setContentLength(segment.length);
    PlaybackSessionService playback =
        new PlaybackSessionService(null, null, null, null, null, null, null, null, null, null) {
          @Override
          public PlaybackContext verify(String ticket) {
            return new PlaybackContext("/Videos/lesson-1/master.m3u8", "lesson-1", true);
          }
        };
    EmbyStreamProxy proxy =
        new EmbyStreamProxy(new EmbyProperties("https://emby.example", "secret", "user-1")) {
          @Override
          public ProxyResponse open(String pathAndQuery, String range, String ifRange) {
            return new ProxyResponse(
                200,
                headers,
                new ByteArrayInputStream(segment),
                URI.create("https://emby.example/Videos/lesson-1/segment.ts"));
          }
        };

    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new PlaybackProxyController(playback, proxy)).build();

    mockMvc
        .perform(get("/api/v1/playback/ticket-1/proxy/Videos/lesson-1/segment.ts"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("video/mp2t"))
        .andExpect(content().bytes(segment));
  }
}
