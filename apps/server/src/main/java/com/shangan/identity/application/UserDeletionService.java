package com.shangan.identity.application;

import com.shangan.common.api.BusinessException;
import com.shangan.identity.domain.User;
import com.shangan.identity.infrastructure.UserDeletionRepository;
import com.shangan.identity.infrastructure.UserRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 管理员删除普通用户及其全部学习记录；删除不可恢复，必须通过用户名二次确认。 */
@Service
public class UserDeletionService {

  private static final Logger log = LoggerFactory.getLogger(UserDeletionService.class);

  private final UserRepository users;
  private final UserDeletionRepository deletions;
  private final UserAttachmentCleaner attachmentCleaner;

  public UserDeletionService(
      UserRepository users,
      UserDeletionRepository deletions,
      UserAttachmentCleaner attachmentCleaner) {
    this.users = users;
    this.deletions = deletions;
    this.attachmentCleaner = attachmentCleaner;
  }

  /**
   * 删除用户及其全部关联记录。
   *
   * <p>管理员账号不可删除，避免后台失去唯一入口。附件磁盘路径必须在数据库删除之前读取，删除完成后才清理文件。
   *
   * @param userId 待删除用户 ID
   * @param confirmedUsername 管理员手工输入的用户名，必须与目标用户完全一致
   */
  @Transactional
  public void delete(String userId, String confirmedUsername) {
    User user =
        users
            .findById(userId)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
    if ("ADMIN".equals(user.role())) {
      throw new BusinessException(HttpStatus.CONFLICT, "USER_DELETE_ADMIN_FORBIDDEN", "管理员账号不能删除");
    }
    String confirmed = confirmedUsername == null ? "" : confirmedUsername.trim();
    if (!user.username().equals(confirmed)) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "USER_DELETE_CONFIRMATION_MISMATCH", "请输入与该用户完全一致的用户名以确认删除");
    }
    List<String> attachmentPaths = deletions.findMockExamAttachmentPaths(userId);
    deletions.deleteUserGraph(userId);
    log.warn("已删除用户及其全部学习记录：userId={} attachmentCount={}", userId, attachmentPaths.size());
    afterCommit(attachmentPaths);
  }

  /** 文件清理只能在数据库提交成功后运行，失败只记录日志，不回滚已完成的删除。 */
  private void afterCommit(List<String> attachmentPaths) {
    if (attachmentPaths.isEmpty()) {
      return;
    }
    Runnable action =
        () -> {
          try {
            attachmentCleaner.delete(attachmentPaths);
          } catch (RuntimeException exception) {
            log.error("删除用户后的附件清理失败，fileCount={}", attachmentPaths.size());
          }
        };
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }
}
