package com.shangan.focus.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.focus.application.MockExamPresetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 系统设置中的模拟考试预置 API，预置始终按当前登录用户隔离。 */
@RestController
@RequestMapping("/api/v1/mock-exam-presets")
public class MockExamPresetController {
  private final MockExamPresetService presets;

  public MockExamPresetController(MockExamPresetService presets) {
    this.presets = presets;
  }

  @GetMapping
  List<MockExamPresetService.PresetView> list(CurrentUser user) {
    return presets.list(user.userId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  MockExamPresetService.PresetView create(
      CurrentUser user, @Valid @RequestBody PresetRequest request) {
    return presets.create(user.userId(), request.toCommand());
  }

  @PutMapping("/{presetId}")
  MockExamPresetService.PresetView update(
      CurrentUser user, @PathVariable String presetId, @Valid @RequestBody PresetRequest request) {
    return presets.update(user.userId(), presetId, request.toCommand());
  }

  @DeleteMapping("/{presetId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(CurrentUser user, @PathVariable String presetId) {
    presets.delete(user.userId(), presetId);
  }

  record PresetRequest(
      @NotBlank @Size(max = 80) String name,
      @Min(60) @Max(43_200) long durationSeconds,
      int sortOrder) {
    MockExamPresetService.PresetCommand toCommand() {
      return new MockExamPresetService.PresetCommand(name, durationSeconds, sortOrder);
    }
  }
}
