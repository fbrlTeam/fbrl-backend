package com.fbrl.application.service;

import com.fbrl.application.port.in.SearchReconciliationDiscrepanciesUseCase;
import com.fbrl.application.port.out.LoadReconciliationDiscrepancyPort;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.ReconciliationDiscrepancy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Qualifier("demo")
public class DemoSearchReconciliationDiscrepanciesService
    implements SearchReconciliationDiscrepanciesUseCase {

  private final LoadReconciliationDiscrepancyPort demoLoadReconciliationDiscrepancyPort;

  public DemoSearchReconciliationDiscrepanciesService(
      @Qualifier("demo") LoadReconciliationDiscrepancyPort demoLoadReconciliationDiscrepancyPort) {
    this.demoLoadReconciliationDiscrepancyPort = demoLoadReconciliationDiscrepancyPort;
  }

  @Override
  public PagedResult<ReconciliationDiscrepancy> search(
      SearchReconciliationDiscrepanciesQuery query) {
    return demoLoadReconciliationDiscrepancyPort.search(
        query.status(), query.from(), query.to(), query.page(), query.size());
  }
}
