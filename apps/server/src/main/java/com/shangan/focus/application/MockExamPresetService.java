package com.shangan.focus.application;

import com.shangan.common.IdGenerator;
import com.shangan.common.api.BusinessException;
import com.shangan.focus.infrastructure.MockExamPresetRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理用户自己的模拟考试预置；作战单只复制预置快照，不持有可变引用语义。 */
@Service
public class MockExamPresetService {
  private static final List<PresetCommand> DEFAULT_PRESETS =
      List.of(
          new PresetCommand("行测", 7_200, 0),
          new PresetCommand("申论", 10_800, 1),
          new PresetCommand("大作文", 10_800, 2));

  private final MockExamPresetRepository presets;
  private final IdGenerator ids;
  private final Clock clock;

  public MockExamPresetService(MockExamPresetRepository presets, IdGenerator ids, Clock clock) {
    this.presets = presets;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<PresetView> list(String userId) {
    return presets.findAll(userId).stream().map(PresetView::from).toList();
  }

  @Transactional
  public PresetView create(String userId, PresetCommand command) {
    PresetCommand valid = validate(command);
    var row =
        new MockExamPresetRepository.PresetRow(
            ids.nextId(), userId, valid.name().trim(), valid.durationSeconds(), valid.sortOrder());
    presets.insert(row, clock.instant());
    return PresetView.from(row);
  }

  /** 幂等补齐系统默认预置；只按名称判断缺失，不覆盖用户已经调整过的同名预置。 */
  @Transactional
  public List<PresetView> createDefaults(String userId) {
    var existing = presets.findAll(userId);
    var names =
        existing.stream()
            .map(MockExamPresetRepository.PresetRow::name)
            .collect(java.util.stream.Collectors.toSet());
    for (PresetCommand command : DEFAULT_PRESETS) {
      if (names.contains(command.name())) continue;
      presets.insert(
          new MockExamPresetRepository.PresetRow(
              ids.nextId(), userId, command.name(), command.durationSeconds(), command.sortOrder()),
          clock.instant());
    }
    return presets.findAll(userId).stream().map(PresetView::from).toList();
  }

  @Transactional
  public PresetView update(String userId, String presetId, PresetCommand command) {
    requireOwned(userId, presetId);
    PresetCommand valid = validate(command);
    presets.update(
        userId,
        presetId,
        valid.name().trim(),
        valid.durationSeconds(),
        valid.sortOrder(),
        clock.instant());
    return PresetView.from(requireOwned(userId, presetId));
  }

  @Transactional
  public void delete(String userId, String presetId) {
    if (!presets.delete(userId, presetId)) {
      throw notFound();
    }
  }

  @Transactional(readOnly = true)
  public MockExamPresetRepository.PresetRow requireOwned(String userId, String presetId) {
    return presets.findOwned(userId, presetId).orElseThrow(this::notFound);
  }

  private PresetCommand validate(PresetCommand command) {
    if (command == null
        || command.name() == null
        || command.name().trim().isEmpty()
        || command.name().trim().length() > 80
        || command.durationSeconds() < 60
        || command.durationSeconds() > 43_200) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "MOCK_EXAM_PRESET_INVALID", "考试名称或时长无效");
    }
    return command;
  }

  private BusinessException notFound() {
    return new BusinessException(HttpStatus.NOT_FOUND, "MOCK_EXAM_PRESET_NOT_FOUND", "模拟考试预置不存在");
  }

  public record PresetCommand(String name, long durationSeconds, int sortOrder) {}

  public record PresetView(String id, String name, long durationSeconds, int sortOrder) {
    static PresetView from(MockExamPresetRepository.PresetRow row) {
      return new PresetView(row.id(), row.name(), row.durationSeconds(), row.sortOrder());
    }
  }
}
