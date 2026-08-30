package com.shangan.ai.infrastructure;

import com.shangan.ai.domain.AiConversation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** AI 会话持久化边界，所有查询都显式带 user_id，避免跨用户聊天内容泄漏。 */
@Repository
public class JdbcAiConversationRepository {
  private final JdbcClient jdbc;

  public JdbcAiConversationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(AiConversation conversation) {
    jdbc.sql(
            """
            insert into ai_conversations(
              id,user_id,scope,media_item_id,title,history_summary,created_at,updated_at
            ) values(:id,:userId,:scope,:mediaId,:title,:summary,:createdAt,:updatedAt)
            """)
        .param("id", conversation.id())
        .param("userId", conversation.userId())
        .param("scope", conversation.scope())
        .param("mediaId", conversation.mediaItemId())
        .param("title", conversation.title())
        .param("summary", conversation.historySummary())
        .param("createdAt", conversation.createdAt().toEpochMilli())
        .param("updatedAt", conversation.updatedAt().toEpochMilli())
        .update();
  }

  public Optional<AiConversation> findOwned(String userId, String id) {
    return jdbc.sql(
            """
            select id,user_id,scope,media_item_id,title,history_summary,created_at,updated_at
              from ai_conversations where id=:id and user_id=:userId
            """)
        .param("id", id)
        .param("userId", userId)
        .query(this::conversation)
        .optional();
  }

  public List<AiConversation> findByUser(String userId) {
    return jdbc.sql(
            """
            select id,user_id,scope,media_item_id,title,history_summary,created_at,updated_at
              from ai_conversations where user_id=:userId order by updated_at desc limit 100
            """)
        .param("userId", userId)
        .query(this::conversation)
        .list();
  }

  public void insertMessage(AiConversation.Message message) {
    jdbc.sql(
            """
            insert into ai_messages(
              id,conversation_id,role,content,status,citations_json,model_name,
              input_tokens,output_tokens,created_at,updated_at
            ) values(:id,:conversationId,:role,:content,:status,:citations,:model,
                     :inputTokens,:outputTokens,:createdAt,:updatedAt)
            """)
        .param("id", message.id())
        .param("conversationId", message.conversationId())
        .param("role", message.role())
        .param("content", message.content())
        .param("status", message.status())
        .param("citations", message.citationsJson())
        .param("model", message.modelName())
        .param("inputTokens", message.inputTokens())
        .param("outputTokens", message.outputTokens())
        .param("createdAt", message.createdAt().toEpochMilli())
        .param("updatedAt", message.updatedAt().toEpochMilli())
        .update();
    jdbc.sql("update ai_conversations set updated_at=:updatedAt where id=:id")
        .param("updatedAt", message.updatedAt().toEpochMilli())
        .param("id", message.conversationId())
        .update();
  }

  public List<AiConversation.Message> findMessages(String userId, String conversationId) {
    return jdbc.sql(
            """
            select m.id,m.conversation_id,m.role,m.content,m.status,m.citations_json,m.model_name,
                   m.input_tokens,m.output_tokens,m.created_at,m.updated_at
              from ai_messages m
              join ai_conversations c on c.id=m.conversation_id
             where c.user_id=:userId and c.id=:conversationId
             -- 同一毫秒内 UUID 无时序含义，rowid 保留真实写入顺序。
             order by m.created_at,m.rowid
            """)
        .param("userId", userId)
        .param("conversationId", conversationId)
        .query(this::message)
        .list();
  }

  private AiConversation conversation(java.sql.ResultSet row, int number)
      throws java.sql.SQLException {
    return new AiConversation(
        row.getString("id"),
        row.getString("user_id"),
        row.getString("scope"),
        row.getString("media_item_id"),
        row.getString("title"),
        row.getString("history_summary"),
        Instant.ofEpochMilli(row.getLong("created_at")),
        Instant.ofEpochMilli(row.getLong("updated_at")));
  }

  private AiConversation.Message message(java.sql.ResultSet row, int number)
      throws java.sql.SQLException {
    return new AiConversation.Message(
        row.getString("id"),
        row.getString("conversation_id"),
        row.getString("role"),
        row.getString("content"),
        row.getString("status"),
        row.getString("citations_json"),
        row.getString("model_name"),
        row.getInt("input_tokens"),
        row.getInt("output_tokens"),
        Instant.ofEpochMilli(row.getLong("created_at")),
        Instant.ofEpochMilli(row.getLong("updated_at")));
  }
}
