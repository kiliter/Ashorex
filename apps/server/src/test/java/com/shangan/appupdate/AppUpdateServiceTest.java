package com.shangan.appupdate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangan.common.api.BusinessException;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** 无数据库下载协议测试，检查流关闭、Range 透传、错误隔离与缺附件状态。 */
class AppUpdateServiceTest {
  private final GithubReleaseClient github = mock(GithubReleaseClient.class);
  private final AppUpdateService service = new AppUpdateService(github);

  private void release() throws Exception {
    when(github.release("v2.6.0"))
        .thenReturn(
            new ObjectMapper()
                .readTree(
                    """
      {"tag_name":"v2.6.0","assets":[{"name":"Ashorex-2.6.0-android.apk","size":6,
      "digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "browser_download_url":"https://github.com/kiliter/Ashorex/releases/download/v2.6.0/Ashorex-2.6.0-android.apk"}]}
      """));
  }

  @Test
  void streamsPartialResponseWithRangeAndClosesUpstream() throws Exception {
    release();
    var connection = mock(HttpURLConnection.class);
    when(github.open(any(URI.class), eq("bytes=3-"), eq("etag"))).thenReturn(connection);
    when(connection.getResponseCode()).thenReturn(206);
    when(connection.getHeaderField("Content-Range")).thenReturn("bytes 3-5/6");
    when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {4, 5, 6}));
    var response = new MockHttpServletResponse();
    service.download("v2.6.0", "android", "bytes=3-", "etag", response);
    assertThat(response.getStatus()).isEqualTo(206);
    assertThat(response.getHeader("Content-Range")).isEqualTo("bytes 3-5/6");
    assertThat(response.getContentAsByteArray()).containsExactly(4, 5, 6);
    assertThat(response.getHeader("Content-Disposition")).contains("Ashorex-2.6.0-android.apk");
    verify(connection).disconnect();
  }

  @Test
  void rejectsMultipleRangesWithoutCallingGithub() {
    assertThatThrownBy(
            () ->
                service.download(
                    "v2.6.0", "android", "bytes=0-1,3-4", null, new MockHttpServletResponse()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("单段");
    verifyNoInteractions(github);
  }

  @Test
  void stripsUpstreamErrorBodyForUnsatisfiableRange() throws Exception {
    release();
    var connection = mock(HttpURLConnection.class);
    when(github.open(any(), any(), any())).thenReturn(connection);
    when(connection.getResponseCode()).thenReturn(416);
    when(connection.getHeaderField("Content-Range")).thenReturn("bytes */6");
    var response = new MockHttpServletResponse();
    service.download("v2.6.0", "android", "bytes=8-", null, response);
    assertThat(response.getStatus()).isEqualTo(416);
    assertThat(response.getContentAsByteArray()).isEmpty();
    assertThat(response.getHeader("Content-Range")).isEqualTo("bytes */6");
    verify(connection, never()).getInputStream();
    verify(connection).disconnect();
  }

  @Test
  void incompleteIosPageDoesNotOfferBrokenDownload() throws Exception {
    release();
    assertThat(service.iosPage("v2.6.0")).contains("尚未发布完整").doesNotContain("<a href=");
  }
}
