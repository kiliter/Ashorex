package com.shangan.presence.domain;

import com.shangan.common.api.BusinessException;
import org.springframework.http.HttpStatus;

/** 只保存本 App 当前页面和活动；枚举白名单禁止原始路由、任意文本进入快照。 */
public record AppActivity(Page page, State state, String todoId) {
  public enum Page {
    UNKNOWN("未知页面"),
    HOME("首页"),
    LIBRARY("学习页"),
    COURSE("课程详情"),
    PLAYER("视频页"),
    FOCUS("专注页"),
    STATS("数据页"),
    PROFILE("我的"),
    TODO("待办详情"),
    PENDING("未完成汇总"),
    GOALS("考试目标"),
    NAG("催办页"),
    OTHER("其他页面");
    private final String label;

    Page(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  public enum State {
    UNKNOWN("未知状态"),
    BROWSING("停留页面"),
    VIDEO_LOADING("加载中"),
    VIDEO_PLAYING("播放中"),
    VIDEO_PAUSED("已暂停"),
    VIDEO_BUFFERING("缓冲中"),
    VIDEO_ERROR("播放失败"),
    VIDEO_ENDED("播放结束"),
    FOCUS_RUNNING("专注计时中"),
    FOCUS_PAUSED("专注已暂停"),
    FOCUS_IDLE("准备开始"),
    FOCUS_FINISHED("专注已完成"),
    FOCUS_STOPPED("专注已停止"),
    FOCUS_ABANDONED("专注已跳过"),
    RESPONDING("回应催办中");
    private final String label;

    State(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  public static AppActivity unknown() {
    return new AppActivity(Page.UNKNOWN, State.UNKNOWN, null);
  }

  /** 缺失字段兼容旧客户端；有值时严格校验组合，错误不回显客户端原文。 */
  public static AppActivity parse(String page, String state, String todoId) {
    Page p;
    State s;
    try {
      p = page == null ? Page.UNKNOWN : Page.valueOf(page);
      s = state == null ? State.UNKNOWN : State.valueOf(state);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "PRESENCE_ACTIVITY_INVALID", "页面或活动状态不支持");
    }
    boolean matches =
        s == State.UNKNOWN
            || switch (p) {
              case PLAYER -> s.name().startsWith("VIDEO_");
              case FOCUS -> s.name().startsWith("FOCUS_");
              case NAG -> s == State.RESPONDING;
              case UNKNOWN -> false;
              default -> s == State.BROWSING;
            };
    if (!matches
        || (todoId != null
            && (todoId.isBlank()
                || todoId.length() > 36
                || !(p == Page.PLAYER || p == Page.FOCUS || p == Page.TODO)))) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "PRESENCE_ACTIVITY_INVALID", "页面与活动状态不匹配");
    }
    return new AppActivity(p, s, todoId);
  }
}
