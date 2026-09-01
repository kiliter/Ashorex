package com.shangan.ai.content.infrastructure;

import com.shangan.ai.content.domain.ContentGenerationJob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用短 SQL 事务维护全局串行队列；外部请求期间不持有连接。 */
@Repository
public class JdbcContentGenerationJobRepository implements ContentGenerationJobRepository {

  private final JdbcClient jdbc;

  public JdbcContentGenerationJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(ContentGenerationJob job) {
    jdbc.sql(
            """
            insert into content_generation_jobs (
              id,course_id,media_item_id,job_type,status,requested_question_count,
              overwrite_existing,queued_at,asr_model,llm_model,llm_context_length,
              llm_max_completion_tokens,attempt,created_by
            ) values (
              :id,:courseId,:mediaItemId,:jobType,:status,:requestedQuestionCount,
              :overwriteExisting,:queuedAt,:asrModel,:llmModel,:llmContextLength,
              :llmMaxCompletionTokens,:attempt,:createdBy
            )
            """)
        .param("id", job.id())
        .param("courseId", job.courseId())
        .param("mediaItemId", job.mediaItemId())
        .param("jobType", job.type().name())
        .param("status", job.status().name())
        .param("requestedQuestionCount", job.requestedQuestionCount())
        .param("overwriteExisting", job.overwriteExisting() ? 1 : 0)
        .param("queuedAt", job.queuedAt().toEpochMilli())
        .param("asrModel", job.asrModel())
        .param("llmModel", job.llmModel())
        .param("llmContextLength", job.llmContextLength())
        .param("llmMaxCompletionTokens", job.llmMaxCompletionTokens())
        .param("attempt", job.attempt())
        .param("createdBy", job.createdBy())
        .update();
  }

  @Override
  public Optional<ContentGenerationJob> findNextQueued() {
    return jdbc.sql(
            """
            select job.* from content_generation_jobs job
            join courses course on course.id=job.course_id
            join media_items media on media.id=job.media_item_id
            where job.status='QUEUED'
            order by job.queued_at,course.sort_order,course.name,
                     media.sort_order,media.title,
                     case job.job_type
                       when 'TRANSCRIBE' then 0
                       when 'SUMMARIZE' then 1
                       else 2
                     end,
                     job.id
            limit 1
            """)
        .query(this::mapJob)
        .optional();
  }

  @Override
  public Optional<ContentGenerationJob> findById(String jobId) {
    return jdbc.sql("select * from content_generation_jobs where id=:id")
        .param("id", jobId)
        .query(this::mapJob)
        .optional();
  }

  @Override
  public List<ContentGenerationJob> findRecent(
      String courseId, String type, String status, int limit) {
    return jdbc.sql(
            """
            select * from content_generation_jobs
            where (:courseId='' or course_id=:courseId)
              and (:type='' or job_type=:type)
              and (:status='' or status=:status)
            order by queued_at desc,id desc limit :limit
            """)
        .param("courseId", safe(courseId))
        .param("type", safe(type))
        .param("status", safe(status))
        .param("limit", Math.max(1, Math.min(500, limit)))
        .query(this::mapJob)
        .list();
  }

  /** 使用数据库聚合课程任务，避免管理台按课程逐条查询。 */
  @Override
  public List<CourseTaskSummary> summarizeByCourse() {
    return jdbc.sql(
            """
            select course_id,
              count(distinct media_item_id || ':' || cast(queued_at as text)) workflow_count,
              count(*) task_count,
              sum(case when status='QUEUED' then 1 else 0 end) queued_count,
              sum(case when status in (
                'FETCHING_AUDIO','TRANSCRIBING','SUMMARIZING','GENERATING_QUIZ'
              ) then 1 else 0 end) running_count,
              sum(case when status='FAILED' then 1 else 0 end) failed_count,
              max(queued_at) last_queued_at
            from content_generation_jobs
            group by course_id
            order by last_queued_at desc,course_id
            """)
        .query(
            (row, number) ->
                new CourseTaskSummary(
                    row.getString("course_id"),
                    row.getLong("workflow_count"),
                    row.getLong("task_count"),
                    row.getLong("queued_count"),
                    row.getLong("running_count"),
                    row.getLong("failed_count"),
                    Instant.ofEpochMilli(row.getLong("last_queued_at"))))
        .list();
  }

  /** 同一 queued_at 是“AI 一下”创建的工作流批次标识，单阶段任务也自然形成单阶段工作流。 */
  @Override
  public List<WorkflowTaskSummary> summarizeWorkflows(String courseId, int limit) {
    return jdbc.sql(
            """
            select media_item_id,queued_at,
              max(case when job_type='TRANSCRIBE' then id end) transcribe_job_id,
              max(case when job_type='TRANSCRIBE' then status end) transcribe_status,
              max(case when job_type='TRANSCRIBE' then total_ms end) transcribe_total_ms,
              max(case when job_type='SUMMARIZE' then id end) summarize_job_id,
              max(case when job_type='SUMMARIZE' then status end) summarize_status,
              max(case when job_type='SUMMARIZE' then total_ms end) summarize_total_ms,
              max(case when job_type='GENERATE_QUIZ' then id end) quiz_job_id,
              max(case when job_type='GENERATE_QUIZ' then status end) quiz_status,
              max(case when job_type='GENERATE_QUIZ' then total_ms end) quiz_total_ms
            from content_generation_jobs
            where course_id=:courseId
            group by media_item_id,queued_at
            order by queued_at desc,media_item_id
            limit :limit
            """)
        .param("courseId", courseId)
        .param("limit", Math.max(1, Math.min(500, limit)))
        .query(
            (row, number) ->
                new WorkflowTaskSummary(
                    row.getString("media_item_id"),
                    Instant.ofEpochMilli(row.getLong("queued_at")),
                    row.getString("transcribe_job_id"),
                    row.getString("transcribe_status"),
                    longOrNull(row, "transcribe_total_ms"),
                    row.getString("summarize_job_id"),
                    row.getString("summarize_status"),
                    longOrNull(row, "summarize_total_ms"),
                    row.getString("quiz_job_id"),
                    row.getString("quiz_status"),
                    longOrNull(row, "quiz_total_ms")))
        .list();
  }

  /** 工作流详情按完整复合键读取，阶段顺序固定为转写、摘要、出题。 */
  @Override
  public List<ContentGenerationJob> findWorkflow(
      String courseId, String mediaItemId, Instant queuedAt) {
    return jdbc.sql(
            """
            select * from content_generation_jobs
            where course_id=:courseId and media_item_id=:mediaItemId and queued_at=:queuedAt
            order by case job_type
              when 'TRANSCRIBE' then 0
              when 'SUMMARIZE' then 1
              else 2
            end,id
            """)
        .param("courseId", courseId)
        .param("mediaItemId", mediaItemId)
        .param("queuedAt", queuedAt.toEpochMilli())
        .query(this::mapJob)
        .list();
  }

  @Override
  public boolean transition(
      String jobId,
      ContentGenerationJob.Status expected,
      ContentGenerationJob.Status target,
      Instant occurredAt) {
    String timestamps =
        expected == ContentGenerationJob.Status.QUEUED
            ? ",started_at=:occurredAt"
            : target.terminal() ? ",finished_at=:occurredAt" : "";
    return jdbc.sql(
                "update content_generation_jobs set status=:target"
                    + timestamps
                    + " where id=:id and status=:expected")
            .param("target", target.name())
            .param("occurredAt", occurredAt.toEpochMilli())
            .param("id", jobId)
            .param("expected", expected.name())
            .update()
        == 1;
  }

  @Override
  public void updateMetrics(String jobId, Metrics value) {
    jdbc.sql(
            """
            update content_generation_jobs set
              audio_duration_ms=:audioDurationMs,fetch_ms=:fetchMs,
              transcribe_ms=:transcribeMs,summarize_ms=:summarizeMs,
              quiz_generate_ms=:quizGenerateMs,total_ms=:totalMs,
              prompt_tokens=:promptTokens,completion_tokens=:completionTokens
            where id=:id
            """)
        .param("audioDurationMs", value.audioDurationMs())
        .param("fetchMs", value.fetchMs())
        .param("transcribeMs", value.transcribeMs())
        .param("summarizeMs", value.summarizeMs())
        .param("quizGenerateMs", value.quizGenerateMs())
        .param("totalMs", value.totalMs())
        .param("promptTokens", value.promptTokens())
        .param("completionTokens", value.completionTokens())
        .param("id", jobId)
        .update();
  }

  @Override
  public void fail(
      String jobId, String errorCode, String errorMessage, Instant finishedAt, long totalMs) {
    jdbc.sql(
            """
            update content_generation_jobs set status='FAILED',finished_at=:finishedAt,
              total_ms=:totalMs,error_code=:errorCode,error_message=:errorMessage
            where id=:id and status not in ('READY','READY_FOR_REVIEW','FAILED')
            """)
        .param("finishedAt", finishedAt.toEpochMilli())
        .param("totalMs", Math.max(0, totalMs))
        .param("errorCode", safe(errorCode))
        .param("errorMessage", truncate(errorMessage, 500))
        .param("id", jobId)
        .update();
  }

  @Override
  public int failInterrupted(Instant finishedAt) {
    return jdbc.sql(
            """
            update content_generation_jobs set status='FAILED',finished_at=:finishedAt,
              error_code='SERVER_RESTARTED',error_message='服务重启，执行中的任务已终止'
            where status in ('FETCHING_AUDIO','TRANSCRIBING','SUMMARIZING','GENERATING_QUIZ')
            """)
        .param("finishedAt", finishedAt.toEpochMilli())
        .update();
  }

  @Override
  public void addLog(ContentGenerationJob.Log log) {
    jdbc.sql(
            "insert into content_generation_job_logs "
                + "(id,job_id,occurred_at,level,stage,message) "
                + "values (:id,:jobId,:occurredAt,:level,:stage,:message)")
        .param("id", log.id())
        .param("jobId", log.jobId())
        .param("occurredAt", log.occurredAt().toEpochMilli())
        .param("level", log.level())
        .param("stage", truncate(log.stage(), 80))
        .param("message", truncate(log.message(), 500))
        .update();
  }

  @Override
  public List<ContentGenerationJob.Log> findLogs(String jobId) {
    return jdbc.sql(
            "select * from content_generation_job_logs where job_id=:jobId "
                + "order by occurred_at,id")
        .param("jobId", jobId)
        .query(
            (row, number) ->
                new ContentGenerationJob.Log(
                    row.getString("id"),
                    row.getString("job_id"),
                    Instant.ofEpochMilli(row.getLong("occurred_at")),
                    row.getString("level"),
                    row.getString("stage"),
                    row.getString("message")))
        .list();
  }

  @Override
  public QueueStats stats(Instant since) {
    return jdbc.sql(
            """
            select
              sum(case when status='QUEUED' then 1 else 0 end) queued,
              sum(case when status in ('FETCHING_AUDIO','TRANSCRIBING','SUMMARIZING','GENERATING_QUIZ') then 1 else 0 end) running,
              sum(case when status in ('READY','READY_FOR_REVIEW') and finished_at>=:since then 1 else 0 end) succeeded_since,
              sum(case when status='FAILED' and finished_at>=:since then 1 else 0 end) failed_since,
              coalesce(avg(fetch_ms),0) average_fetch_ms,
              coalesce(avg(transcribe_ms),0) average_transcribe_ms,
              coalesce(avg(summarize_ms),0) average_summarize_ms,
              coalesce(avg(audio_duration_ms),0) average_audio_duration_ms,
              coalesce(avg(total_ms),0) average_total_ms
            from content_generation_jobs
            """)
        .param("since", since.toEpochMilli())
        .query(
            (row, number) ->
                new QueueStats(
                    row.getLong("queued"),
                    row.getLong("running"),
                    row.getLong("succeeded_since"),
                    row.getLong("failed_since"),
                    row.getLong("average_fetch_ms"),
                    row.getLong("average_transcribe_ms"),
                    row.getLong("average_summarize_ms"),
                    row.getLong("average_audio_duration_ms"),
                    row.getLong("average_total_ms")))
        .single();
  }

  private ContentGenerationJob mapJob(ResultSet row, int rowNumber) throws SQLException {
    return new ContentGenerationJob(
        row.getString("id"),
        row.getString("course_id"),
        row.getString("media_item_id"),
        ContentGenerationJob.Type.valueOf(row.getString("job_type")),
        ContentGenerationJob.Status.valueOf(row.getString("status")),
        row.getInt("requested_question_count"),
        row.getInt("overwrite_existing") == 1,
        Instant.ofEpochMilli(row.getLong("queued_at")),
        instantOrNull(row, "started_at"),
        instantOrNull(row, "finished_at"),
        longOrNull(row, "audio_duration_ms"),
        longOrNull(row, "fetch_ms"),
        longOrNull(row, "transcribe_ms"),
        longOrNull(row, "summarize_ms"),
        longOrNull(row, "quiz_generate_ms"),
        longOrNull(row, "total_ms"),
        row.getString("asr_model"),
        row.getString("llm_model"),
        integerOrNull(row, "llm_context_length"),
        integerOrNull(row, "llm_max_completion_tokens"),
        integerOrNull(row, "prompt_tokens"),
        integerOrNull(row, "completion_tokens"),
        row.getInt("attempt"),
        row.getString("error_code"),
        row.getString("error_message"),
        row.getString("created_by"));
  }

  private Instant instantOrNull(ResultSet row, String column) throws SQLException {
    Long value = longOrNull(row, column);
    return value == null ? null : Instant.ofEpochMilli(value);
  }

  private Long longOrNull(ResultSet row, String column) throws SQLException {
    Object value = row.getObject(column);
    return value == null ? null : row.getLong(column);
  }

  private Integer integerOrNull(ResultSet row, String column) throws SQLException {
    Object value = row.getObject(column);
    return value == null ? null : row.getInt(column);
  }

  private String truncate(String value, int maximum) {
    String safe = safe(value);
    return safe.length() <= maximum ? safe : safe.substring(0, maximum);
  }

  private String safe(String value) {
    return value == null ? "" : value.trim();
  }
}
