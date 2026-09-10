package com.shangan.appupdate;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 发布数据只接受正式版本和当前平台的完整附件，不能误选服务端镜像。 */
class AppReleaseTest {
  @Test
  void selectsPlatformAssetAndRejectsIncompleteRelease() throws Exception {
    var json =
        new ObjectMapper()
            .readTree(
                """
      {"tag_name":"v2.6.0","draft":false,"prerelease":false,"body":"修复说明","published_at":"2026-09-10T00:00:00Z",
       "assets":[{"name":"Ashorex-2.6.0-android.apk","size":123,"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
       "browser_download_url":"https://github.com/kiliter/Ashorex/releases/download/v2.6.0/Ashorex-2.6.0-android.apk"}]}
      """);
    assertThat(AppRelease.parse(json, "android").version()).isEqualTo("2.6.0");
    assertThat(AppRelease.parse(json, "android").size()).isEqualTo(123);
    assertThat(AppRelease.parse(json, "ios").downloadable()).isFalse();
  }

  @Test
  void rejectsUntrustedAssetAndInvalidPlatform() throws Exception {
    assertThatThrownBy(() -> AppRelease.platform("macos")).hasMessageContaining("平台");
    assertThatThrownBy(() -> AppRelease.tag("../../main")).hasMessageContaining("版本");
    assertThatThrownBy(
            () ->
                GithubReleaseClient.validateTarget(
                    java.net.URI.create("https://evil.example/file")))
        .hasMessageContaining("下载");
  }
}
