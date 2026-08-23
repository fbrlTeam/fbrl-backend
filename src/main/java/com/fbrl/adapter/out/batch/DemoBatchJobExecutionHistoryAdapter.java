package com.fbrl.adapter.out.batch;

import com.fbrl.application.port.out.BatchJobExecutionSummary;
import com.fbrl.application.port.out.LoadBatchJobExecutionHistoryPort;
import com.fbrl.application.port.out.PagedResult;
import java.util.List;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@Qualifier("demo")
public class DemoBatchJobExecutionHistoryAdapter implements LoadBatchJobExecutionHistoryPort {

  private final JobRepository demoJobRepository;

  public DemoBatchJobExecutionHistoryAdapter(
      @Qualifier("demoJobRepository") JobRepository demoJobRepository) {
    this.demoJobRepository = demoJobRepository;
  }

  @Override
  public PagedResult<BatchJobExecutionSummary> recentExecutions(
      String jobName, int page, int size) {
    List<JobInstance> instances = demoJobRepository.getJobInstances(jobName, page * size, size);
    List<BatchJobExecutionSummary> summaries =
        instances.stream()
            .flatMap(instance -> demoJobRepository.getJobExecutions(instance).stream())
            .map(this::toSummary)
            .toList();
    return new PagedResult<>(summaries, countInstances(jobName));
  }

  private long countInstances(String jobName) {
    try {
      return demoJobRepository.getJobInstanceCount(jobName);
    } catch (NoSuchJobException e) {
      return 0;
    }
  }

  private BatchJobExecutionSummary toSummary(JobExecution execution) {
    return new BatchJobExecutionSummary(
        execution.getJobInstance().getJobName(),
        execution.getStatus().name(),
        execution.getStartTime(),
        execution.getEndTime(),
        execution.getExitStatus() == null ? null : execution.getExitStatus().getExitDescription());
  }
}
