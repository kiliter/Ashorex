package com.shangan.todo.application;

import com.shangan.common.api.BusinessException;
import com.shangan.todo.infrastructure.TodoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提供给在线模块的显式只读边界；不允许客户端给任务名称或跨用户任务建立引用。 */
@Service
public class TodoActivityQuery {
  private final TodoRepository todos;

  public TodoActivityQuery(TodoRepository todos) {
    this.todos = todos;
  }

  /** 心跳绑定任务前校验归属与类型；不存在和越权使用同一错误。 */
  @Transactional(readOnly = true)
  public void validate(String userId, String todoId, String page) {
    var todo =
        todos
            .findById(todoId)
            .filter(t -> t.userId().equals(userId))
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "待办不存在"));
    if (("PLAYER".equals(page) && !"COURSE".equals(todo.todoType().name()))
        || ("FOCUS".equals(page) && !"FOCUS".equals(todo.todoType().name()))) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "PRESENCE_ACTIVITY_INVALID", "页面与任务类型不匹配");
    }
  }

  /** 查询时重新核对归属，任务删除后返回空而非让在线列表失败。 */
  @Transactional(readOnly = true)
  public String title(String userId, String todoId) {
    if (todoId == null) return null;
    return todos
        .findById(todoId)
        .filter(t -> t.userId().equals(userId))
        .map(t -> t.title())
        .orElse(null);
  }
}
