package com.shangan.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.common.auth.CurrentUser;
import com.shangan.media.emby.EmbyStreamProxy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** 媒体 Item ID 与 MediaSourceId 不是同一身份；验证固定流路径与 Range 透传。 */
class PlaybackControllerTest {
  @Test
  void streamsWithoutInventingMediaSourceId() throws Exception {
    var catalog = mock(CatalogQueryService.class);
    var proxy = mock(EmbyStreamProxy.class);
    var resource =
        new LearningResource(
            "resource",
            "course",
            ResourceType.VIDEO,
            "课程",
            1,
            1000L,
            null,
            "item-123",
            null,
            true,
            CatalogStatus.ACTIVE,
            null);
    when(catalog.requireVisibleResource("resource")).thenReturn(resource);
    var headers = new HttpHeaders();
    headers.set("Content-Type", "video/mp4");
    headers.set("Content-Range", "bytes 0-2/3");
    when(proxy.open(anyString(), any(), any()))
        .thenReturn(
            new EmbyStreamProxy.ProxyResponse(
                206,
                headers,
                new ByteArrayInputStream(new byte[] {1, 2, 3}),
                URI.create("https://example.invalid/video")));
    var response =
        new PlaybackController(catalog, proxy)
            .stream(
                new CurrentUser("user", "学习者", "LEARNER", "Asia/Shanghai"),
                "resource",
                "bytes=0-2",
                "etag");
    verify(proxy).open("/Videos/item-123/stream?static=true", "bytes=0-2", "etag");
    assertThat(response.getStatusCode().value()).isEqualTo(206);
    assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes 0-2/3");
    var output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);
    assertThat(output.toByteArray()).containsExactly(1, 2, 3);
  }
}
