package com.shangan.nag.infrastructure;

import com.shangan.nag.domain.Nag;
import com.shangan.nag.domain.NagChannelType;
import com.shangan.nag.domain.NagStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 催办与投递流水的持久化边界。 */
public interface NagRepository {

  Optional<Nag> findById(String id);

  /** 该用户当前待回应的催办，供心跳响应下发。 */
  Optional<Nag> findAwaitingByUser(String userId);

  /** 自动催办幂等查询。 */
  boolean autoNagExists(String userId, LocalDate localDate, int thresholdLevel);

  int countOn(String userId, LocalDate localDate);

  List<Nag> findByUser(String userId, int limit);

  /** 按学员本地日期范围聚合报告催办次数，避免列表分页上限截断统计。 */
  ResponseCounts countResponsesBetween(String userId, LocalDate start, LocalDate end);

  record ResponseCounts(int total, int responded) {}

  List<Nag> findRecent(int limit);

  /** 某督学人名下学员的催办时间线。 */
  List<Nag> findByUsers(List<String> userIds, int limit);

  /** 全屏已投递但超时仍未回应的催办，用于渠道降级。 */
  List<Nag> findFullscreenAwaitingBefore(Instant threshold);

  void insert(Nag nag);

  void markDelivered(String nagId, Instant deliveredAt);

  void markResponded(String nagId, String reasonTag, String reasonText, Instant respondedAt);

  void markExpired(String nagId);

  /** 按学员本地日期结束旧催办，已回应历史保持不变。 */
  void expireBefore(String userId, LocalDate today);

  void insertDelivery(
      String id, String nagId, NagChannelType channel, String status, String detail, Instant now);

  List<Delivery> deliveriesOf(String nagId);

  /** 批量取多条催办的投递流水，供后台一次性渲染，避免逐行 N+1 查询。 */
  List<Delivery> deliveriesOfAll(List<String> nagIds);

  /** 某催办是否已通过指定渠道投递过，避免降级重复发送。 */
  boolean deliveredVia(String nagId, NagChannelType channel);

  /** 投递流水。 */
  record Delivery(
      String id,
      String nagId,
      NagChannelType channel,
      String status,
      String detail,
      Instant createdAt) {}

  /** 催办状态统计，供后台概览使用。 */
  record StatusCount(NagStatus status, int count) {}

  List<StatusCount> countByStatus();
}
