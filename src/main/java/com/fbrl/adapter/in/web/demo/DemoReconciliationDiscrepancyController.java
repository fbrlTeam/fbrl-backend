package com.fbrl.adapter.in.web.demo;

import com.fbrl.adapter.in.web.dto.PageResponse;
import com.fbrl.adapter.in.web.dto.ReconciliationDiscrepancyResponse;
import com.fbrl.application.port.in.SearchReconciliationDiscrepanciesUseCase;
import com.fbrl.application.port.in.SearchReconciliationDiscrepanciesUseCase.SearchReconciliationDiscrepanciesQuery;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.ReconciliationDiscrepancy;
import com.fbrl.domain.model.ReconciliationStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo/reconciliation-discrepancies")
public class DemoReconciliationDiscrepancyController {

  private final SearchReconciliationDiscrepanciesUseCase
      demoSearchReconciliationDiscrepanciesUseCase;

  public DemoReconciliationDiscrepancyController(
      @Qualifier("demo")
          SearchReconciliationDiscrepanciesUseCase demoSearchReconciliationDiscrepanciesUseCase) {
    this.demoSearchReconciliationDiscrepanciesUseCase =
        demoSearchReconciliationDiscrepanciesUseCase;
  }

  @GetMapping
  public ResponseEntity<PageResponse<ReconciliationDiscrepancyResponse>> search(
      @RequestParam(required = false) ReconciliationStatus status,
      @RequestParam LocalDate from,
      @RequestParam LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PagedResult<ReconciliationDiscrepancy> result =
        demoSearchReconciliationDiscrepanciesUseCase.search(
            new SearchReconciliationDiscrepanciesQuery(status, from, to, page, size));
    List<ReconciliationDiscrepancyResponse> content =
        result.items().stream().map(ReconciliationDiscrepancyResponse::from).toList();
    return ResponseEntity.ok(PageResponse.of(content, result.totalElements(), page, size));
  }
}
