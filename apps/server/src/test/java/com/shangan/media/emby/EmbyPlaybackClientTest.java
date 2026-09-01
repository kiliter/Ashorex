package com.shangan.media.emby;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 验证 iOS 直放必须同时满足容器和视频编码条件，防止模拟器只播声音却黑屏。 */
class EmbyPlaybackClientTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void directlyPlaysH264InsideMp4() throws Exception {
    assertThat(EmbyPlaybackClient.supportsIosDirectPlayback(source("mp4", true, "Video", "h264")))
        .isTrue();
  }

  @Test
  void transcodesNonH264VideoEvenWhenContainerIsMp4() throws Exception {
    assertThat(EmbyPlaybackClient.supportsIosDirectPlayback(source("mp4", true, "Video", "hevc")))
        .isFalse();
    assertThat(EmbyPlaybackClient.supportsIosDirectPlayback(source("mp4", true, "Video", "vp9")))
        .isFalse();
  }

  @Test
  void transcodesUnknownCodecOrIncompatibleContainer() throws Exception {
    assertThat(EmbyPlaybackClient.supportsIosDirectPlayback(source("mkv", true, "Video", "h264")))
        .isFalse();
    assertThat(EmbyPlaybackClient.supportsIosDirectPlayback(source("mp4", true, "Audio", "aac")))
        .isFalse();
  }

  /** 创建与 Emby PlaybackInfo 一致的最小媒体源，避免测试依赖网络。 */
  private static tools.jackson.databind.JsonNode source(
      String container, boolean direct, String streamType, String codec) throws Exception {
    return JSON.readTree(
        """
        {
          "Container": "%s",
          "SupportsDirectStream": %s,
          "MediaStreams": [{"Type": "%s", "Codec": "%s"}]
        }
        """
            .formatted(container, direct, streamType, codec));
  }
}
