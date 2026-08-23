package com.fbrl.adapter.in.web.demo;

import com.fbrl.adapter.in.web.dto.AuditChainVerificationResponse;
import com.fbrl.adapter.in.web.dto.OutboxEventResponse;
import com.fbrl.adapter.in.web.dto.PageResponse;
import com.fbrl.application.port.in.GetOutboxEventsUseCase;
import com.fbrl.application.port.in.GetOutboxEventsUseCase.GetOutboxEventsQuery;
import com.fbrl.application.port.in.VerifyAuditChainUseCase;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.OutboxEvent;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo/audit")
public class DemoAuditController {

  private final VerifyAuditChainUseCase demoVerifyAuditChainUseCase;
  private final GetOutboxEventsUseCase demoGetOutboxEventsUseCase;

  public DemoAuditController(
      @Qualifier("demo") VerifyAuditChainUseCase demoVerifyAuditChainUseCase,
      @Qualifier("demo") GetOutboxEventsUseCase demoGetOutboxEventsUseCase) {
    this.demoVerifyAuditChainUseCase = demoVerifyAuditChainUseCase;
    this.demoGetOutboxEventsUseCase = demoGetOutboxEventsUseCase;
  }

  @GetMapping("/verify")
  public ResponseEntity<AuditChainVerificationResponse> verify() {
    var result = demoVerifyAuditChainUseCase.verify();
    return ResponseEntity.ok(AuditChainVerificationResponse.from(result));
  }

  @GetMapping("/events")
  public ResponseEntity<PageResponse<OutboxEventResponse>> getEvents(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    PagedResult<OutboxEvent> result =
        demoGetOutboxEventsUseCase.getEvents(new GetOutboxEventsQuery(page, size));
    List<OutboxEventResponse> content =
        result.items().stream().map(OutboxEventResponse::from).toList();
    return ResponseEntity.ok(PageResponse.of(content, result.totalElements(), page, size));
  }
}
