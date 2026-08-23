package com.fbrl.application.service;

import com.fbrl.application.port.in.GetPendingApprovalsUseCase;
import com.fbrl.application.port.out.LoadApprovalRequestPort;
import com.fbrl.domain.model.ApprovalStatus;
import com.fbrl.domain.model.TransferApprovalRequest;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class GetPendingApprovalsService implements GetPendingApprovalsUseCase {

  private final LoadApprovalRequestPort loadApprovalRequestPort;

  public GetPendingApprovalsService(LoadApprovalRequestPort loadApprovalRequestPort) {
    this.loadApprovalRequestPort = loadApprovalRequestPort;
  }

  @Override
  public List<TransferApprovalRequest> getPendingApprovals() {
    return loadApprovalRequestPort.loadByStatus(ApprovalStatus.PENDING);
  }
}
