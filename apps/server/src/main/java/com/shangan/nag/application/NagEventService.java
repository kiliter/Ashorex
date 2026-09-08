package com.shangan.nag.application;

import com.shangan.nag.domain.NagTransportMode;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 单实例 SSE 连接注册表；不存消息历史，断线由数据库待回应查询补偿。 */
@Service
public class NagEventService {
  private final Map<String, CopyOnWriteArrayList<SseEmitter>> connections =
      new ConcurrentHashMap<>();
  private final NagTransportService transport;
  // 网络写入脱离催办请求线程；弱网客户端不能拖住管理员的发送请求。
  private final java.util.concurrent.ExecutorService sender =
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
  private final java.util.Set<SseEmitter> sending = ConcurrentHashMap.newKeySet();

  public NagEventService(NagTransportService transport) {
    this.transport = transport;
  }

  /** 仅由认证用户订阅自己的事件；一分钟换连，避免旧认证无限持有连接。 */
  public synchronized SseEmitter subscribe(String userId) {
    SseEmitter emitter = new SseEmitter(60_000L);
    var clients = connections.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>());
    clients.add(emitter);
    Runnable remove = () -> remove(clients, emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(
        () -> {
          remove.run();
          emitter.complete();
        });
    emitter.onError(ignored -> remove.run());
    // 限制同一账号的连接数，异常重连不能无限累积；允许多设备同时在线。
    while (clients.size() > 5) {
      SseEmitter oldest = clients.removeFirst();
      oldest.complete();
    }
    send(clients, emitter, SseEmitter.event().name("ready").data("ready"));
    return emitter;
  }

  /** 必须在提交后发送，避免客户端收到事件时尚查不到催办；回滚不通知。 */
  @TransactionalEventListener
  public void onAvailable(NagAvailable event) {
    sender.execute(
        () -> {
          if (transport.current() != NagTransportMode.SSE) return;
          var clients = connections.get(event.userId());
          if (clients == null) return;
          for (SseEmitter emitter : clients) {
            sendAsync(clients, emitter, SseEmitter.event().name("nag").data(event.nagId()));
          }
        });
  }

  /** 注释帧防止反向代理空闲断开，不写在线状态或有效操作。 */
  @Scheduled(fixedDelay = 15_000)
  public void keepAlive() {
    connections.forEach(
        (userId, clients) -> {
          for (SseEmitter emitter : clients)
            sendAsync(clients, emitter, SseEmitter.event().comment("keepalive"));
        });
  }

  /** 每个连接最多一个在途写入；慢连接不积压事件，心跳会补查全部未回应催办。 */
  private void sendAsync(
      CopyOnWriteArrayList<SseEmitter> clients,
      SseEmitter emitter,
      SseEmitter.SseEventBuilder event) {
    if (!sending.add(emitter)) return;
    sender.execute(
        () -> {
          try {
            send(clients, emitter, event);
          } finally {
            sending.remove(emitter);
          }
        });
  }

  /** 已断开连接只移除，不让一次网络失败破坏已提交的催办。 */
  private void send(
      CopyOnWriteArrayList<SseEmitter> clients,
      SseEmitter emitter,
      SseEmitter.SseEventBuilder event) {
    try {
      emitter.send(event);
    } catch (IOException | IllegalStateException exception) {
      remove(clients, emitter);
      emitter.complete();
    }
  }

  /** 与订阅互斥地回收空注册表，反复登录不同账号也不会残留用户键。 */
  private synchronized void remove(CopyOnWriteArrayList<SseEmitter> clients, SseEmitter emitter) {
    clients.remove(emitter);
    if (clients.isEmpty()) connections.values().removeIf(current -> current == clients);
  }

  /** 关闭服务时释放全部长连接。 */
  @PreDestroy
  public void close() {
    connections.values().forEach(clients -> clients.forEach(SseEmitter::complete));
    connections.clear();
    sender.shutdownNow();
  }
}
