package com.fbrl.adapter.in.web.demo;

import com.fbrl.adapter.in.web.dto.BatchJobExecutionSummaryResponse;
import com.fbrl.adapter.in.web.dto.PageResponse;
import com.fbrl.application.port.in.GetBatchJobExecutionHistoryUseCase;
import com.fbrl.application.port.in.GetBatchJobExecutionHistoryUseCase.GetBatchJobExecutionHistoryQuery;
import com.fbrl.application.port.out.BatchJobExecutionSummary;
import com.fbrl.application.port.out.PagedResult;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo/batch-jobs")
public class DemoBatchJobExecutionController {

  private final GetBatchJobExecutionHistoryUseCase demoGetBatchJobExecutionHistoryUseCase;

  public DemoBatchJobExecutionController(
      @Qualifier("demo")
          GetBatchJobExecutionHistoryUseCase demoGetBatchJobExecutionHistoryUseCase) {
    this.demoGetBatchJobExecutionHistoryUseCase = demoGetBatchJobExecutionHistoryUseCase;
  }

  @GetMapping("/{jobName}/executions")
  public ResponseEntity<PageResponse<BatchJobExecutionSummaryResponse>> getExecutions(
      @PathVariable String jobName,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PagedResult<BatchJobExecutionSummary> result =
        demoGetBatchJobExecutionHistoryUseCase.getHistory(
            new GetBatchJobExecutionHistoryQuery(jobName, page, size));
    List<BatchJobExecutionSummaryResponse> content =
        result.items().stream().map(BatchJobExecutionSummaryResponse::from).toList();
    return ResponseEntity.ok(PageResponse.of(content, result.totalElements(), page, size));
  }
}
