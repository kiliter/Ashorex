package com.shangan.upgrade;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

/** 旧 App 维护时明确失败，不伪成功；健康和版本探测仍能执行。 */
class UpgradeMaintenanceFilterTest {
  @Test
  void 维护拦截旧版写请求并保留健康通道() throws Exception {
    var service = mock(UpgradeService.class);
    when(service.maintenance()).thenReturn(true);
    var filter = new UpgradeMaintenanceFilter(service);
    for (String path :
        new String[] {"/api/v1/heartbeat", "/api/v1/todos/id/progress", "/admin/api/session"}) {
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();
      filter.doFilter(new MockHttpServletRequest("POST", path), response, chain);
      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(response.getHeader("Retry-After")).isEqualTo("30");
      assertThat(response.getContentAsString()).contains("SERVER_UPGRADING");
      assertThat(chain.getRequest()).isNull();
    }
    for (String path : new String[] {"/actuator/health", "/internal/upgrade-readiness"}) {
      var request = new MockHttpServletRequest("GET", path);
      var chain = new MockFilterChain();
      filter.doFilter(request, new MockHttpServletResponse(), chain);
      assertThat(chain.getRequest()).isSameAs(request);
    }
  }
}
