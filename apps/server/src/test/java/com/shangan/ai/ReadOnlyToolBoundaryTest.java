package com.shangan.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.shangan.ai.application.ReadOnlyStudyTools;
import dev.langchain4j.agent.tool.Tool;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 通过反射锁死内部 AI 工具名称、返回值和依赖边界，防止未来误注册写操作。 */
class ReadOnlyToolBoundaryTest {
  @Test
  void exposesOnlyFrozenReadToolsAndHasNoRepositoryDependency() {
    var methods =
        Arrays.stream(ReadOnlyStudyTools.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Tool.class))
            .toList();
    Set<String> names =
        methods.stream()
            .map(method -> method.getAnnotation(Tool.class).name())
            .collect(Collectors.toSet());

    assertThat(names).containsExactlyInAnyOrderElementsOf(ReadOnlyStudyTools.ALLOWED_TOOL_NAMES);
    assertThat(names).allMatch(name -> name.startsWith("get_") || name.startsWith("search_"));
    assertThat(methods)
        .allMatch(method -> method.getReturnType() != Void.TYPE)
        .allMatch(method -> method.getReturnType() != Object.class);
    assertThat(Arrays.stream(ReadOnlyStudyTools.class.getDeclaredFields()).map(Field::getType))
        .noneMatch(type -> type.getSimpleName().endsWith("Repository"));
  }
}
