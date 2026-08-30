package com.shangan.learning.application;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.learning.infrastructure.WatchSessionBootstrapRepository;
import com.shangan.media.emby.EmbyPlaybackClient;
import com.shangan.planning.application.DailyPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 创建观看会话并校验票据与持久化会话的一致性。 */
@Service
public class PlaybackSessionService {
  private static final int HEARTBEAT_INTERVAL_SECONDS = 10;

  private final CatalogQueryService catalog;
  private final DailyPlanService plans;
  private final EmbyPlaybackClient emby;
  private final WatchSessionBootstrapRepository sessions;
  private final PlaybackSessionWriter writer;
  private final PlaybackTicketService tickets;
  private final IdGenerator ids;

  public PlaybackSessionService(
      CatalogQueryService catalog,
      DailyPlanService plans,
      EmbyPlaybackClient emby,
      WatchSessionBootstrapRepository sessions,
      PlaybackSessionWriter writer,
      PlaybackTicketService tickets,
      IdGenerator ids) {
    this.catalog = catalog;
    this.plans = plans;
    this.emby = emby;
    this.sessions = sessions;
    this.writer = writer;
    this.tickets = tickets;
    this.ids = ids;
  }

  /** Emby 网络选择在事务外完成，随后使用短事务写入本地会话。 */
  public PlaybackSession create(
      String userId, String mediaItemId, String planItemId, String clientDeviceId) {
    var lesson =
        catalog
            .findLesson(mediaItemId)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "LESSON_NOT_FOUND", "课时不存在"));
    plans.validateVideoLink(userId, planItemId, mediaItemId);
    EmbyPlaybackClient.Selection selection = emby.select(lesson.embyItemId());
    String sessionId = ids.nextId();
    String deviceId = selection.deviceId() + ":" + clientDeviceId;
    writer.insert(
        new WatchSessionBootstrapRepository.SessionPlayback(
            sessionId,
            userId,
            mediaItemId,
            lesson.embyItemId(),
            planItemId,
            deviceId,
            selection.playSessionId(),
            selection.upstreamPath(),
            selection.hls(),
            lesson.durationMs()));
    String ticket = tickets.issue(userId, mediaItemId, sessionId, deviceId);
    String url = "/api/v1/playback/" + ticket + (selection.hls() ? "/master.m3u8" : "/stream");
    return new PlaybackSession(sessionId, url, 0, lesson.durationMs(), HEARTBEAT_INTERVAL_SECONDS);
  }

  @Transactional(readOnly = true)
  public PlaybackContext verify(String ticket) {
    PlaybackTicketService.Claims claims = tickets.verify(ticket, null);
    var session =
        sessions
            .find(claims.sessionId())
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.UNAUTHORIZED, "PLAYBACK_SESSION_INVALID", "播放会话无效"));
    if (!session.userId().equals(claims.userId())
        || !session.mediaItemId().equals(claims.mediaItemId())
        || !session.deviceId().equals(claims.deviceId())) {
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "PLAYBACK_SESSION_INVALID", "播放会话无效");
    }
    return new PlaybackContext(session.upstreamPath(), session.embyItemId(), session.hls());
  }

  public record PlaybackSession(
      String sessionId,
      String ticketUrl,
      long trustedPositionMs,
      long durationMs,
      int heartbeatIntervalSeconds) {}

  public record PlaybackContext(String upstreamPath, String embyItemId, boolean hls) {
    public boolean allows(String path) {
      return path.startsWith("/Videos/" + embyItemId + "/");
    }
  }
}
