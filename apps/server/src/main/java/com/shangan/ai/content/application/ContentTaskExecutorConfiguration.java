package com.shangan.ai.content.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 课程内容生产使用的独立线程池配置；每个池只保留一个工作线程以维持串行语义。 */
@Configuration
public class ContentTaskExecutorConfiguration {

  public static final String ASR_EXECUTOR = "asrContentTaskExecutor";
  public static final String LLM_EXECUTOR = "llmContentTaskExecutor";

  /** ASR 外部调用独占线程池，避免长时间转写占用 LLM 调用线程。 */
  @Bean(name = ASR_EXECUTOR)
  public ThreadPoolTaskExecutor asrContentTaskExecutor() {
    return singleThreadExecutor("content-asr-");
  }

  /** 摘要和出题共用 LLM 线程池，两类 LLM 调用保持串行。 */
  @Bean(name = LLM_EXECUTOR)
  public ThreadPoolTaskExecutor llmContentTaskExecutor() {
    return singleThreadExecutor("content-llm-");
  }

  /** 创建预启动的单线程执行器，服务空闲时也能明确报告线程池存活。 */
  private ThreadPoolTaskExecutor singleThreadExecutor(String threadNamePrefix) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix(threadNamePrefix);
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(10);
    executor.setPrestartAllCoreThreads(true);
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.setAwaitTerminationSeconds(5);
    return executor;
  }
}
