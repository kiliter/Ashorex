package com.shangan.todo.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.shangan.todo.TodoFixtures;
import com.shangan.todo.infrastructure.TodoRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 跨模块查询仅返回归属任务的名称，不连接数据库。 */
class TodoActivityQueryTest {
  @Test
  void 他人任务与不存在使用相同错误且不泄露名称() {
    var repository = mock(TodoRepository.class);
    var query = new TodoActivityQuery(repository);
    var todo = TodoFixtures.course().build();
    when(repository.findById(todo.id())).thenReturn(Optional.of(todo));
    assertThatThrownBy(() -> query.validate("other-user", todo.id(), "PLAYER")).hasMessage("待办不存在");
    assertThat(query.title("other-user", todo.id())).isNull();
    when(repository.findById(todo.id())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> query.validate(todo.userId(), todo.id(), "PLAYER"))
        .hasMessage("待办不存在");
    assertThat(query.title(todo.userId(), todo.id())).isNull();
  }

  @Test
  void 同用户课程可读名称但不可标记为专注页() {
    var repository = mock(TodoRepository.class);
    var query = new TodoActivityQuery(repository);
    var todo = TodoFixtures.course().build();
    when(repository.findById(todo.id())).thenReturn(Optional.of(todo));
    query.validate(todo.userId(), todo.id(), "PLAYER");
    assertThat(query.title(todo.userId(), todo.id())).isEqualTo(todo.title());
    assertThatThrownBy(() -> query.validate(todo.userId(), todo.id(), "FOCUS"))
        .hasMessage("页面与任务类型不匹配");
  }
}
