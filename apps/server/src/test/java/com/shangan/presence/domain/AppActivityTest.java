package com.shangan.presence.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 页面与活动必须来自白名单，避免泄露路由和矛盾状态。 */
class AppActivityTest {
  @Test
  void 旧客户端为未知() {
    assertThat(AppActivity.parse(null, null, null)).isEqualTo(AppActivity.unknown());
  }

  @Test
  void 视频播放与专注状态不可混用() {
    assertThatThrownBy(() -> AppActivity.parse("PLAYER", "FOCUS_RUNNING", null))
        .hasMessageContaining("页面与活动状态不匹配");
  }

  @Test
  void 拒绝任意路由() {
    assertThatThrownBy(() -> AppActivity.parse("/player/private?token=secret", "BROWSING", null))
        .hasMessage("页面或活动状态不支持");
  }

  @Test
  void 播放暂停保持原任务身份() {
    var activity = AppActivity.parse("PLAYER", "VIDEO_PAUSED", "todo-1");
    assertThat(activity.page().label()).isEqualTo("视频页");
    assertThat(activity.state().label()).isEqualTo("已暂停");
    assertThat(activity.todoId()).isEqualTo("todo-1");
  }
}
