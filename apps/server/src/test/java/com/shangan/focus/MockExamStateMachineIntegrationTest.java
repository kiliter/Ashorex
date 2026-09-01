package com.shangan.focus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.focus.application.MockExamPresetService;
import com.shangan.focus.application.MockExamService;
import com.shangan.planning.application.BattleOrderService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 验证模拟考试必须经过服务端倒计时状态和试卷附件后才能完成。 */
@SpringBootTest
@Import(MockExamStateMachineIntegrationTest.FixedClockConfiguration.class)
class MockExamStateMachineIntegrationTest {
  @TempDir static Path temporaryDirectory;

  @Autowired MockExamPresetService presets;
  @Autowired BattleOrderService battleOrders;
  @Autowired MockExamService mockExams;
  @Autowired JdbcClient jdbc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + temporaryDirectory.resolve("mock-exam.db"));
    registry.add(
        "app.mock-exam-attachments-dir",
        () -> temporaryDirectory.resolve("attachments").toString());
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from mock_exam_attachments").update();
    jdbc.sql("delete from mock_exam_sessions").update();
    jdbc.sql("delete from mock_exam_presets").update();
    jdbc.sql("delete from daily_plan_revisions").update();
    jdbc.sql("delete from daily_plan_items").update();
    jdbc.sql("delete from daily_plans").update();
    jdbc.sql("delete from users").update();
    jdbc.sql(
            """
            insert into users (
              id,username,password_hash,display_name,role,timezone,
              alive_check_level,day_end_local_time,enabled,created_at,updated_at
            ) values ('user-1','learner','x','学习者','USER','Asia/Shanghai','NORMAL','23:59',1,1,1)
            """)
        .update();
    jdbc.sql(
            """
            insert into users (
              id,username,password_hash,display_name,role,timezone,
              alive_check_level,day_end_local_time,enabled,created_at,updated_at
            ) values ('user-2','other','x','其他用户','USER','Asia/Shanghai','NORMAL','23:59',1,1,1)
            """)
        .update();
  }

  @Test
  void earlySubmissionRequiresAtLeastOnePaperPhotoBeforeCompletion() {
    var preset =
        presets.create("user-1", new MockExamPresetService.PresetCommand("申论模拟", 7_200, 0));
    var order =
        battleOrders.save(
            "user-1",
            LocalDate.of(2026, 9, 1),
            new BattleOrderService.SaveCommand(
                0,
                List.of(
                    new BattleOrderService.ItemCommand(null, "MOCK_EXAM", null, preset.id(), 0))));
    String planItemId = order.items().getFirst().id();

    var running = mockExams.start("user-1", planItemId);
    assertThat(running.status()).isEqualTo("RUNNING");
    assertThat(running.deadlineAt()).isEqualTo(Instant.parse("2026-09-01T04:00:00Z"));

    var awaitingUpload = mockExams.submitEarly("user-1", running.id());
    assertThat(awaitingUpload.status()).isEqualTo("AWAITING_UPLOAD");
    assertThat(itemStatus(planItemId)).isEqualTo("PENDING");

    byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01};
    var completed =
        mockExams.addAttachment(
            "user-1", running.id(), new MockExamService.AttachmentUpload("试卷第一页.png", png));

    assertThat(completed.session().status()).isEqualTo("COMPLETED");
    assertThat(completed.attachments()).hasSize(1);
    assertThat(itemStatus(planItemId)).isEqualTo("COMPLETED");
  }

  @Test
  void completedExamStillAcceptsAdditionalPaperPagesButRejectsInvalidFilesAndOtherUsers() {
    var preset =
        presets.create("user-1", new MockExamPresetService.PresetCommand("行测模拟", 7_200, 0));
    var order =
        battleOrders.save(
            "user-1",
            LocalDate.of(2026, 9, 1),
            new BattleOrderService.SaveCommand(
                0,
                List.of(
                    new BattleOrderService.ItemCommand(null, "MOCK_EXAM", null, preset.id(), 0))));
    var running = mockExams.start("user-1", order.items().getFirst().id());
    mockExams.submitEarly("user-1", running.id());

    assertThatThrownBy(
            () ->
                mockExams.addAttachment(
                    "user-1",
                    running.id(),
                    new MockExamService.AttachmentUpload("伪造图片.png", new byte[] {1, 2, 3})))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo("MOCK_EXAM_ATTACHMENT_INVALID"));

    byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01};
    var first =
        mockExams.addAttachment(
            "user-1", running.id(), new MockExamService.AttachmentUpload("../../第一页.png", png));
    var second =
        mockExams.addAttachment(
            "user-1", running.id(), new MockExamService.AttachmentUpload("第二页.png", png));

    assertThat(first.session().status()).isEqualTo("COMPLETED");
    assertThat(second.attachments()).hasSize(2);
    assertThat(second.attachments().getFirst().originalFilename()).isEqualTo("第一页.png");
    assertThatThrownBy(() -> mockExams.details("user-2", running.id()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo("MOCK_EXAM_SESSION_NOT_FOUND"));
  }

  @Test
  void newUserReceivesThreeDefaultExamPresets() {
    var defaults = presets.createDefaults("user-1");

    assertThat(defaults)
        .extracting(
            MockExamPresetService.PresetView::name,
            MockExamPresetService.PresetView::durationSeconds)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("行测", 7_200L),
            org.assertj.core.groups.Tuple.tuple("申论", 10_800L),
            org.assertj.core.groups.Tuple.tuple("大作文", 10_800L));
    assertThat(presets.createDefaults("user-1")).hasSize(3);
  }

  private String itemStatus(String itemId) {
    return jdbc.sql("select status from daily_plan_items where id=:id")
        .param("id", itemId)
        .query(String.class)
        .single();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-09-01T02:00:00Z"), ZoneOffset.UTC);
    }
  }
}
