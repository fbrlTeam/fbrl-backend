package com.fbrl.adapter.in.web.dto;

import com.fbrl.domain.model.ApprovalStatus;
import com.fbrl.domain.model.ExecutionStatus;
import com.fbrl.domain.model.TransferApprovalRequest;
import java.math.BigDecimal;
import java.time.Instant;

public record TransferApprovalDetailResponse(
    String requestId,
    String makerId,
    String checkerId,
    String fromAccountNumber,
    String toAccountNumber,
    BigDecimal amount,
    ApprovalStatus status,
    String rejectionReason,
    ExecutionStatus executionStatus,
    String executionFailureReason,
    Instant requestedAt,
    Instant decidedAt) {
  public static TransferApprovalDetailResponse from(TransferApprovalRequest request) {
    return new TransferApprovalDetailResponse(
        request.getRequestId(),
        request.getMakerId(),
        request.getCheckerId(),
        request.getFromAccountNumber(),
        request.getToAccountNumber(),
        request.getAmount().getAmount(),
        request.getStatus(),
        request.getRejectionReason(),
        request.getExecutionStatus(),
        request.getExecutionFailureReason(),
        request.getRequestedAt(),
        request.getDecidedAt());
  }
}
