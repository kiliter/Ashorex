package com.shangan.todo.application;

import com.shangan.catalog.application.ResourceProgressPort;
import com.shangan.todo.infrastructure.TodoRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 把 Todo 模块的课时累计状态暴露给课程库模块，作为跨模块只读端口的实现。 */
@Component
public class LessonWatchStateAdapter implements ResourceProgressPort {

  private final TodoRepository todos;

  public LessonWatchStateAdapter(TodoRepository todos) {
    this.todos = todos;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, ResourceProgress> progressOf(String userId, List<String> resourceIds) {
    return convert(todos.watchStatesOf(userId, resourceIds));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, ResourceProgress> progressOfCourse(String userId, String courseId) {
    return convert(todos.watchStatesOfCourse(userId, courseId));
  }

  private Map<String, ResourceProgress> convert(Map<String, TodoRepository.WatchState> states) {
    Map<String, ResourceProgress> result = new LinkedHashMap<>();
    states.forEach(
        (resourceId, state) ->
            result.put(
                resourceId,
                new ResourceProgress(
                    state.maxPositionMs(),
                    state.maxPositionPage(),
                    state.totalWatchedMs(),
                    state.completedCount())));
    return Map.copyOf(result);
  }
}
