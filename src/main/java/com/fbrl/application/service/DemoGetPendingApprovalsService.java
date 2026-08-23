package com.fbrl.application.service;

import com.fbrl.application.port.in.GetPendingApprovalsUseCase;
import com.fbrl.application.port.out.LoadApprovalRequestPort;
import com.fbrl.domain.model.ApprovalStatus;
import com.fbrl.domain.model.TransferApprovalRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Qualifier("demo")
public class DemoGetPendingApprovalsService implements GetPendingApprovalsUseCase {

  private final LoadApprovalRequestPort demoLoadApprovalRequestPort;

  public DemoGetPendingApprovalsService(
      @Qualifier("demo") LoadApprovalRequestPort demoLoadApprovalRequestPort) {
    this.demoLoadApprovalRequestPort = demoLoadApprovalRequestPort;
  }

  @Override
  public List<TransferApprovalRequest> getPendingApprovals() {
    return demoLoadApprovalRequestPort.loadByStatus(ApprovalStatus.PENDING);
  }
}
