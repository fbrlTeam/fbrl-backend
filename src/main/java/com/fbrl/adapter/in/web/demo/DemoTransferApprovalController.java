package com.fbrl.adapter.in.web.demo;

import com.fbrl.adapter.in.web.dto.ApprovalDecisionResponse;
import com.fbrl.adapter.in.web.dto.PageResponse;
import com.fbrl.adapter.in.web.dto.PendingApprovalResponse;
import com.fbrl.adapter.in.web.dto.RejectTransferRequest;
import com.fbrl.adapter.in.web.dto.RequestTransferApprovalRequest;
import com.fbrl.adapter.in.web.dto.TransferApprovalDetailResponse;
import com.fbrl.application.port.in.ApproveTransferUseCase.ApproveTransferCommand;
import com.fbrl.application.port.in.DemoApproveTransferUseCase;
import com.fbrl.application.port.in.DemoGetApprovalRequestUseCase;
import com.fbrl.application.port.in.DemoRejectTransferUseCase;
import com.fbrl.application.port.in.DemoRequestTransferApprovalUseCase;
import com.fbrl.application.port.in.GetPendingApprovalsUseCase;
import com.fbrl.application.port.in.RequestTransferApprovalUseCase.ApprovalRequestResult;
import com.fbrl.application.port.in.SearchTransferApprovalsUseCase;
import com.fbrl.application.port.in.SearchTransferApprovalsUseCase.SearchTransferApprovalsQuery;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.ApprovalStatus;
import com.fbrl.domain.model.TransferApprovalRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo/transfer-approvals")
public class DemoTransferApprovalController {

  private final DemoRequestTransferApprovalUseCase demoRequestTransferApprovalUseCase;
  private final DemoApproveTransferUseCase demoApproveTransferUseCase;
  private final DemoRejectTransferUseCase demoRejectTransferUseCase;
  private final DemoGetApprovalRequestUseCase demoGetApprovalRequestUseCase;
  private final SearchTransferApprovalsUseCase demoSearchTransferApprovalsUseCase;
  private final GetPendingApprovalsUseCase demoGetPendingApprovalsUseCase;

  public DemoTransferApprovalController(
      DemoRequestTransferApprovalUseCase demoRequestTransferApprovalUseCase,
      DemoApproveTransferUseCase demoApproveTransferUseCase,
      DemoRejectTransferUseCase demoRejectTransferUseCase,
      DemoGetApprovalRequestUseCase demoGetApprovalRequestUseCase,
      @Qualifier("demo") SearchTransferApprovalsUseCase demoSearchTransferApprovalsUseCase,
      @Qualifier("demo") GetPendingApprovalsUseCase demoGetPendingApprovalsUseCase) {
    this.demoRequestTransferApprovalUseCase = demoRequestTransferApprovalUseCase;
    this.demoApproveTransferUseCase = demoApproveTransferUseCase;
    this.demoRejectTransferUseCase = demoRejectTransferUseCase;
    this.demoGetApprovalRequestUseCase = demoGetApprovalRequestUseCase;
    this.demoSearchTransferApprovalsUseCase = demoSearchTransferApprovalsUseCase;
    this.demoGetPendingApprovalsUseCase = demoGetPendingApprovalsUseCase;
  }

  @GetMapping
  public ResponseEntity<PageResponse<TransferApprovalDetailResponse>> search(
      @RequestParam(required = false) ApprovalStatus status,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PagedResult<TransferApprovalRequest> result =
        demoSearchTransferApprovalsUseCase.search(
            new SearchTransferApprovalsQuery(status, from, to, page, size));
    List<TransferApprovalDetailResponse> content =
        result.items().stream().map(TransferApprovalDetailResponse::from).toList();
    return ResponseEntity.ok(PageResponse.of(content, result.totalElements(), page, size));
  }

  @GetMapping("/pending")
  public ResponseEntity<List<PendingApprovalResponse>> getPendingApprovals() {
    List<PendingApprovalResponse> responses =
        demoGetPendingApprovalsUseCase.getPendingApprovals().stream()
            .map(PendingApprovalResponse::from)
            .toList();
    return ResponseEntity.ok(responses);
  }

  @PostMapping
  public ResponseEntity<ApprovalDecisionResponse> requestApproval(
      @Valid @RequestBody RequestTransferApprovalRequest request, Authentication authentication) {
    ApprovalRequestResult result =
        demoRequestTransferApprovalUseCase.requestApproval(
            request.toCommand(authentication.getName()));
    return ResponseEntity.ok(new ApprovalDecisionResponse(result.requestId(), result.status()));
  }

  @GetMapping("/{requestId}")
  public ResponseEntity<TransferApprovalDetailResponse> getApprovalRequest(
      @PathVariable String requestId) {
    TransferApprovalDetailResponse response =
        TransferApprovalDetailResponse.from(
            demoGetApprovalRequestUseCase.getByRequestId(requestId));
    return ResponseEntity.ok(response);
  }

  @PostMapping("/{requestId}/approve")
  public ResponseEntity<ApprovalDecisionResponse> approve(
      @PathVariable String requestId, Authentication authentication) {
    var result =
        demoApproveTransferUseCase.approve(
            new ApproveTransferCommand(requestId, authentication.getName()));
    return ResponseEntity.ok(new ApprovalDecisionResponse(result.requestId(), result.status()));
  }

  @PostMapping("/{requestId}/reject")
  public ResponseEntity<ApprovalDecisionResponse> reject(
      @PathVariable String requestId,
      @Valid @RequestBody RejectTransferRequest request,
      Authentication authentication) {
    var result =
        demoRejectTransferUseCase.reject(request.toCommand(requestId, authentication.getName()));
    return ResponseEntity.ok(new ApprovalDecisionResponse(result.requestId(), result.status()));
  }
}
