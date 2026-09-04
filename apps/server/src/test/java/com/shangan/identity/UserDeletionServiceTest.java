package com.shangan.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.identity.application.UserAttachmentCleaner;
import com.shangan.identity.application.UserDeletionService;
import com.shangan.identity.domain.User;
import com.shangan.identity.infrastructure.UserDeletionRepository;
import com.shangan.identity.infrastructure.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 覆盖删除用户的管理员保护、用户名二次确认、关联图删除顺序和磁盘附件清理。 */
class UserDeletionServiceTest {

  @Test
  void deleteRemovesUserGraphAndCleansAttachments() {
    FakeUserRepository users = new FakeUserRepository();
    users.put(user("user-1", "student", "USER"));
    FakeDeletionRepository deletions =
        new FakeDeletionRepository(Map.of("user-1", List.of("/data/mock-exams/user-1/a.jpg")));
    RecordingCleaner cleaner = new RecordingCleaner();
    UserDeletionService service = new UserDeletionService(users, deletions, cleaner);

    service.delete("user-1", "student");

    assertThat(deletions.deletedUserIds).containsExactly("user-1");
    assertThat(cleaner.deletedPaths).containsExactly("/data/mock-exams/user-1/a.jpg");
    // 附件路径必须在删除数据库行之前读取，否则级联删除后就查不到文件位置。
    assertThat(deletions.callOrder).containsExactly("findPaths:user-1", "deleteGraph:user-1");
  }

  @Test
  void deleteRejectsAdministratorAccount() {
    FakeUserRepository users = new FakeUserRepository();
    users.put(user("admin-1", "admin", "ADMIN"));
    FakeDeletionRepository deletions = new FakeDeletionRepository(Map.of());
    RecordingCleaner cleaner = new RecordingCleaner();
    UserDeletionService service = new UserDeletionService(users, deletions, cleaner);

    assertThatThrownBy(() -> service.delete("admin-1", "admin"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error -> {
              BusinessException business = (BusinessException) error;
              assertThat(business.errorCode()).isEqualTo("USER_DELETE_ADMIN_FORBIDDEN");
              assertThat(business.status()).isEqualTo(HttpStatus.CONFLICT);
            });
    assertThat(deletions.deletedUserIds).isEmpty();
    assertThat(cleaner.deletedPaths).isEmpty();
  }

  @Test
  void deleteRequiresMatchingUsernameConfirmation() {
    FakeUserRepository users = new FakeUserRepository();
    users.put(user("user-1", "student", "USER"));
    FakeDeletionRepository deletions = new FakeDeletionRepository(Map.of());
    RecordingCleaner cleaner = new RecordingCleaner();
    UserDeletionService service = new UserDeletionService(users, deletions, cleaner);

    assertThatThrownBy(() -> service.delete("user-1", "wrong-name"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error -> {
              BusinessException business = (BusinessException) error;
              assertThat(business.errorCode()).isEqualTo("USER_DELETE_CONFIRMATION_MISMATCH");
              assertThat(business.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
    assertThat(deletions.deletedUserIds).isEmpty();
  }

  @Test
  void deleteAcceptsConfirmationWithSurroundingWhitespace() {
    FakeUserRepository users = new FakeUserRepository();
    users.put(user("user-1", "student", "USER"));
    FakeDeletionRepository deletions = new FakeDeletionRepository(Map.of());
    UserDeletionService service = new UserDeletionService(users, deletions, new RecordingCleaner());

    service.delete("user-1", "  student  ");

    assertThat(deletions.deletedUserIds).containsExactly("user-1");
  }

  @Test
  void deleteRejectsUnknownUser() {
    FakeUserRepository users = new FakeUserRepository();
    FakeDeletionRepository deletions = new FakeDeletionRepository(Map.of());
    UserDeletionService service = new UserDeletionService(users, deletions, new RecordingCleaner());

    assertThatThrownBy(() -> service.delete("missing", "student"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error -> {
              BusinessException business = (BusinessException) error;
              assertThat(business.errorCode()).isEqualTo("USER_NOT_FOUND");
              assertThat(business.status()).isEqualTo(HttpStatus.NOT_FOUND);
            });
    assertThat(deletions.deletedUserIds).isEmpty();
  }

  @Test
  void attachmentCleanupFailureDoesNotFailCompletedDeletion() {
    FakeUserRepository users = new FakeUserRepository();
    users.put(user("user-1", "student", "USER"));
    FakeDeletionRepository deletions =
        new FakeDeletionRepository(Map.of("user-1", List.of("/data/mock-exams/user-1/a.jpg")));
    UserAttachmentCleaner failing =
        paths -> {
          throw new IllegalStateException("磁盘不可写");
        };
    UserDeletionService service = new UserDeletionService(users, deletions, failing);

    // 数据库删除已提交，文件清理失败只记录日志，不得把异常抛回管理后台。
    service.delete("user-1", "student");

    assertThat(deletions.deletedUserIds).containsExactly("user-1");
  }

  private User user(String id, String username, String role) {
    return new User(
        id, username, "hash", "显示名", role, "Asia/Shanghai", "NORMAL", 50, "23:59", true);
  }

  /** 记录删除调用顺序和入参的删除仓储替身。 */
  private static final class FakeDeletionRepository implements UserDeletionRepository {
    private final Map<String, List<String>> attachmentPaths;
    private final List<String> deletedUserIds = new ArrayList<>();
    private final List<String> callOrder = new ArrayList<>();

    private FakeDeletionRepository(Map<String, List<String>> attachmentPaths) {
      this.attachmentPaths = attachmentPaths;
    }

    @Override
    public List<String> findMockExamAttachmentPaths(String userId) {
      callOrder.add("findPaths:" + userId);
      return attachmentPaths.getOrDefault(userId, List.of());
    }

    @Override
    public void deleteUserGraph(String userId) {
      callOrder.add("deleteGraph:" + userId);
      deletedUserIds.add(userId);
    }
  }

  private static final class RecordingCleaner implements UserAttachmentCleaner {
    private final List<String> deletedPaths = new ArrayList<>();

    @Override
    public void delete(List<String> storagePaths) {
      deletedPaths.addAll(storagePaths);
    }
  }

  /** 只提供按 ID 查找能力的用户仓储替身，其余方法在删除路径中不应被调用。 */
  private static final class FakeUserRepository implements UserRepository {
    private final Map<String, User> stored = new LinkedHashMap<>();

    void put(User user) {
      stored.put(user.id(), user);
    }

    private UnsupportedOperationException unexpected() {
      return new UnsupportedOperationException("删除用户不应调用该仓储方法");
    }

    @Override
    public Optional<User> findById(String id) {
      return Optional.ofNullable(stored.get(id));
    }

    @Override
    public Optional<User> findByUsername(String username) {
      throw unexpected();
    }

    @Override
    public List<User> findAll() {
      throw unexpected();
    }

    @Override
    public boolean hasAdministrator() {
      throw unexpected();
    }

    @Override
    public void insert(User user, Instant createdAt) {
      throw unexpected();
    }

    @Override
    public void updatePreferences(
        String userId,
        String timezone,
        String aliveCheckLevel,
        int aliveCheckIntervalPercent,
        String dayEndLocalTime,
        Instant now) {
      throw unexpected();
    }

    @Override
    public void setEnabled(String userId, boolean enabled, Instant now) {
      throw unexpected();
    }

    @Override
    public void revokeRefreshTokensByUserId(String userId, Instant revokedAt) {
      throw unexpected();
    }

    @Override
    public void insertRefreshToken(
        String id, String userId, String tokenHash, Instant expiresAt, Instant createdAt) {
      throw unexpected();
    }

    @Override
    public Optional<RefreshTokenRecord> findRefreshTokenByHash(String tokenHash) {
      throw unexpected();
    }

    @Override
    public void revokeRefreshToken(String id, Instant revokedAt) {
      throw unexpected();
    }
  }
}
