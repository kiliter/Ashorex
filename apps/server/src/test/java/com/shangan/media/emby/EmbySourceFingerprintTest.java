package com.shangan.media.emby;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证媒体来源指纹稳定且不会包含原始服务器路径。 */
class EmbySourceFingerprintTest {

  @Test
  void normalizesEquivalentPathsAndKeepsRawPathOutOfFingerprint() {
    String first = EmbySourceFingerprint.fromPath("  D:\\Study\\Course\\01.mp4  ");
    String second = EmbySourceFingerprint.fromPath("D:/Study/Course/01.mp4");

    assertThat(first).isEqualTo(second).startsWith("path-sha256-v1:");
    assertThat(first).doesNotContain("Study", "Course", "01.mp4");
    assertThat(EmbySourceFingerprint.fromPath(" ")).isNull();
  }
}
