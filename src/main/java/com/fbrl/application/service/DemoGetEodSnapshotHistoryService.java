package com.fbrl.application.service;

import com.fbrl.application.port.in.GetEodSnapshotHistoryUseCase;
import com.fbrl.application.port.out.LoadEodSnapshotHistoryPort;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.EodSnapshot;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Qualifier("demo")
public class DemoGetEodSnapshotHistoryService implements GetEodSnapshotHistoryUseCase {

  private final LoadEodSnapshotHistoryPort demoLoadEodSnapshotHistoryPort;

  public DemoGetEodSnapshotHistoryService(
      @Qualifier("demo") LoadEodSnapshotHistoryPort demoLoadEodSnapshotHistoryPort) {
    this.demoLoadEodSnapshotHistoryPort = demoLoadEodSnapshotHistoryPort;
  }

  @Override
  public PagedResult<EodSnapshot> getHistory(GetEodSnapshotHistoryQuery query) {
    return demoLoadEodSnapshotHistoryPort.byAccountNumber(
        query.accountNumber(), query.from(), query.to(), query.page(), query.size());
  }
}
