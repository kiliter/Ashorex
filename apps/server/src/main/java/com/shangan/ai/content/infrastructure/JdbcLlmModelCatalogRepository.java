package com.shangan.ai.content.infrastructure;

import com.shangan.ai.content.domain.LlmModelCatalogEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 使用短事务整体刷新模型目录，刷新失败前不会修改旧缓存。 */
@Repository
public class JdbcLlmModelCatalogRepository implements LlmModelCatalogRepository {

  private final JdbcClient jdbc;

  public JdbcLlmModelCatalogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<LlmModelCatalogEntry> findAll(String query, boolean activeOnly) {
    String term = query == null ? "" : query.trim().toLowerCase();
    return jdbc.sql(
            "select * from llm_model_catalog where "
                + (activeOnly ? "active=1 and " : "")
                + "(lower(model_id) like :term or lower(display_name) like :term) "
                + "order by active desc, display_name, model_id")
        .param("term", "%" + term + "%")
        .query(this::map)
        .list();
  }

  @Override
  public Optional<LlmModelCatalogEntry> findById(String modelId) {
    return jdbc.sql("select * from llm_model_catalog where model_id=:modelId")
        .param("modelId", modelId)
        .query(this::map)
        .optional();
  }

  @Override
  public long count() {
    return jdbc.sql("select count(*) from llm_model_catalog").query(Long.class).single();
  }

  /** 先停用旧模型再 Upsert 新快照，任意写入失败都会整体回滚。 */
  @Override
  @Transactional
  public void replaceSnapshot(List<LlmModelCatalogEntry> entries) {
    jdbc.sql("update llm_model_catalog set active=0").update();
    for (LlmModelCatalogEntry entry : entries) {
      jdbc.sql(
              """
              insert into llm_model_catalog (
                model_id,display_name,context_length,max_completion_tokens,
                tokenizer,supported_parameters_json,fetched_at,active
              ) values (
                :modelId,:displayName,:contextLength,:maxCompletionTokens,
                :tokenizer,:supportedParametersJson,:fetchedAt,1
              )
              on conflict(model_id) do update set
                display_name=excluded.display_name,
                context_length=excluded.context_length,
                max_completion_tokens=excluded.max_completion_tokens,
                tokenizer=excluded.tokenizer,
                supported_parameters_json=excluded.supported_parameters_json,
                fetched_at=excluded.fetched_at,
                active=1
              """)
          .param("modelId", entry.modelId())
          .param("displayName", entry.displayName())
          .param("contextLength", entry.contextLength())
          .param("maxCompletionTokens", entry.maxCompletionTokens())
          .param("tokenizer", entry.tokenizer())
          .param("supportedParametersJson", entry.supportedParametersJson())
          .param("fetchedAt", entry.fetchedAt().toEpochMilli())
          .update();
    }
  }

  private LlmModelCatalogEntry map(ResultSet row, int rowNumber) throws SQLException {
    return new LlmModelCatalogEntry(
        row.getString("model_id"),
        row.getString("display_name"),
        row.getInt("context_length"),
        row.getInt("max_completion_tokens"),
        row.getString("tokenizer"),
        row.getString("supported_parameters_json"),
        Instant.ofEpochMilli(row.getLong("fetched_at")),
        row.getInt("active") == 1);
  }
}
