package com.fbrl.application.service;

import com.fbrl.application.port.in.SearchTransferApprovalsUseCase;
import com.fbrl.application.port.out.LoadApprovalRequestPort;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.TransferApprovalRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Qualifier("demo")
public class DemoSearchTransferApprovalsService implements SearchTransferApprovalsUseCase {

  private final LoadApprovalRequestPort demoLoadApprovalRequestPort;

  public DemoSearchTransferApprovalsService(
      @Qualifier("demo") LoadApprovalRequestPort demoLoadApprovalRequestPort) {
    this.demoLoadApprovalRequestPort = demoLoadApprovalRequestPort;
  }

  @Override
  public PagedResult<TransferApprovalRequest> search(SearchTransferApprovalsQuery query) {
    return demoLoadApprovalRequestPort.search(
        query.status(), query.from(), query.to(), query.page(), query.size());
  }
}
