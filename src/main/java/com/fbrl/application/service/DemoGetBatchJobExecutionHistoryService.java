package com.fbrl.application.service;

import com.fbrl.application.port.in.GetBatchJobExecutionHistoryUseCase;
import com.fbrl.application.port.out.BatchJobExecutionSummary;
import com.fbrl.application.port.out.LoadBatchJobExecutionHistoryPort;
import com.fbrl.application.port.out.PagedResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Qualifier("demo")
public class DemoGetBatchJobExecutionHistoryService implements GetBatchJobExecutionHistoryUseCase {

  private final LoadBatchJobExecutionHistoryPort demoLoadBatchJobExecutionHistoryPort;

  public DemoGetBatchJobExecutionHistoryService(
      @Qualifier("demo") LoadBatchJobExecutionHistoryPort demoLoadBatchJobExecutionHistoryPort) {
    this.demoLoadBatchJobExecutionHistoryPort = demoLoadBatchJobExecutionHistoryPort;
  }

  @Override
  public PagedResult<BatchJobExecutionSummary> getHistory(GetBatchJobExecutionHistoryQuery query) {
    return demoLoadBatchJobExecutionHistoryPort.recentExecutions(
        query.jobName(), query.page(), query.size());
  }
}
