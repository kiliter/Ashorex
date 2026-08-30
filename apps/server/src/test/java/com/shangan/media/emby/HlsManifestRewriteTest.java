package com.shangan.media.emby;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** 验证 HLS 相对路径重写和跨主机拒绝规则。 */
class HlsManifestRewriteTest {
  private final EmbyStreamProxy proxy =
      new EmbyStreamProxy(new EmbyProperties("https://emby.example.test", "secret", "user-1"));

  @Test
  void rewritesRelativeManifestLinesThroughSameTicket() {
    String rewritten =
        proxy.rewriteManifest(
            "ticket-1",
            "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\nmain.m3u8?MediaSourceId=x\n",
            URI.create("https://emby.example.test/Videos/1/master.m3u8"));
    assertThat(rewritten)
        .contains(
            "/api/v1/playback/ticket-1/proxy/Videos/1/main.m3u8?MediaSourceId=x",
            "#EXT-X-MAP:URI=\"/api/v1/playback/ticket-1/proxy/Videos/1/init.mp4\"");
  }

  @Test
  void rejectsAbsoluteUrlForDifferentHost() {
    assertThatThrownBy(
            () ->
                proxy.rewriteManifest(
                    "ticket-1",
                    "#EXTM3U\nhttps://evil.example/segment.ts\n",
                    URI.create("https://emby.example.test/Videos/1/master.m3u8")))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void rejectsEncodedPathTraversal() {
    assertThatThrownBy(
            () ->
                proxy.rewriteManifest(
                    "ticket-1",
                    "#EXTM3U\n%2e%2e/Users\n",
                    URI.create("https://emby.example.test/Videos/1/master.m3u8")))
        .isInstanceOf(BusinessException.class);
  }
}
