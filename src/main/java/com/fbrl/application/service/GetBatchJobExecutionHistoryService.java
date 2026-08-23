package com.fbrl.application.service;

import com.fbrl.application.port.in.GetBatchJobExecutionHistoryUseCase;
import com.fbrl.application.port.out.BatchJobExecutionSummary;
import com.fbrl.application.port.out.LoadBatchJobExecutionHistoryPort;
import com.fbrl.application.port.out.PagedResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class GetBatchJobExecutionHistoryService implements GetBatchJobExecutionHistoryUseCase {

  private final LoadBatchJobExecutionHistoryPort loadBatchJobExecutionHistoryPort;

  public GetBatchJobExecutionHistoryService(
      LoadBatchJobExecutionHistoryPort loadBatchJobExecutionHistoryPort) {
    this.loadBatchJobExecutionHistoryPort = loadBatchJobExecutionHistoryPort;
  }

  @Override
  public PagedResult<BatchJobExecutionSummary> getHistory(GetBatchJobExecutionHistoryQuery query) {
    return loadBatchJobExecutionHistoryPort.recentExecutions(
        query.jobName(), query.page(), query.size());
  }
}
