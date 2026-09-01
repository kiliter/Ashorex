package com.shangan.planning.application;

/** 日终结算关闭作战单的跨模块应用边界。 */
public interface DayEndPlanCloser {

  /** 按用户所有权关闭指定作战单，并执行幂等欠债结算。 */
  void closeForDayEnd(String userId, String planId);
}
