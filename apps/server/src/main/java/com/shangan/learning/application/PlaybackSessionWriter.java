package com.shangan.learning.application;

import com.shangan.learning.infrastructure.WatchSessionBootstrapRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在 Emby 网络选择完成后，以独立短事务保存观看会话。 */
@Service
public class PlaybackSessionWriter {
  private final WatchSessionBootstrapRepository sessions;
  private final Clock clock;

  public PlaybackSessionWriter(WatchSessionBootstrapRepository sessions, Clock clock) {
    this.sessions = sessions;
    this.clock = clock;
  }

  @Transactional
  public void insert(WatchSessionBootstrapRepository.SessionPlayback session) {
    sessions.insert(session, clock.instant());
  }
}
