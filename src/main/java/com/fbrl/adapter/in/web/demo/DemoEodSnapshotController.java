package com.fbrl.adapter.in.web.demo;

import com.fbrl.adapter.in.web.dto.EodSnapshotResponse;
import com.fbrl.adapter.in.web.dto.PageResponse;
import com.fbrl.application.port.in.GetEodSnapshotHistoryUseCase;
import com.fbrl.application.port.in.GetEodSnapshotHistoryUseCase.GetEodSnapshotHistoryQuery;
import com.fbrl.application.port.in.GetEodSnapshotsByDateUseCase;
import com.fbrl.application.port.in.GetEodSnapshotsByDateUseCase.GetEodSnapshotsByDateQuery;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.EodSnapshot;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo/eod-snapshots")
public class DemoEodSnapshotController {

  private final GetEodSnapshotHistoryUseCase demoGetEodSnapshotHistoryUseCase;
  private final GetEodSnapshotsByDateUseCase demoGetEodSnapshotsByDateUseCase;

  public DemoEodSnapshotController(
      @Qualifier("demo") GetEodSnapshotHistoryUseCase demoGetEodSnapshotHistoryUseCase,
      @Qualifier("demo") GetEodSnapshotsByDateUseCase demoGetEodSnapshotsByDateUseCase) {
    this.demoGetEodSnapshotHistoryUseCase = demoGetEodSnapshotHistoryUseCase;
    this.demoGetEodSnapshotsByDateUseCase = demoGetEodSnapshotsByDateUseCase;
  }

  @GetMapping
  public ResponseEntity<PageResponse<EodSnapshotResponse>> getByDate(
      @RequestParam LocalDate date,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PagedResult<EodSnapshot> result =
        demoGetEodSnapshotsByDateUseCase.getByDate(
            new GetEodSnapshotsByDateQuery(date, page, size));
    List<EodSnapshotResponse> content =
        result.items().stream().map(EodSnapshotResponse::from).toList();
    return ResponseEntity.ok(PageResponse.of(content, result.totalElements(), page, size));
  }

  @GetMapping("/{accountNumber}")
  public ResponseEntity<PageResponse<EodSnapshotResponse>> getHistory(
      @PathVariable String accountNumber,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PagedResult<EodSnapshot> result =
        demoGetEodSnapshotHistoryUseCase.getHistory(
            new GetEodSnapshotHistoryQuery(accountNumber, from, to, page, size));
    List<EodSnapshotResponse> content =
        result.items().stream().map(EodSnapshotResponse::from).toList();
    return ResponseEntity.ok(PageResponse.of(content, result.totalElements(), page, size));
  }
}
