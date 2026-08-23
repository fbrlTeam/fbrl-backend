package com.fbrl.application.service;

import com.fbrl.application.port.in.GetEodSnapshotsByDateUseCase;
import com.fbrl.application.port.out.LoadEodSnapshotHistoryPort;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.EodSnapshot;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Qualifier("demo")
public class DemoGetEodSnapshotsByDateService implements GetEodSnapshotsByDateUseCase {

  private final LoadEodSnapshotHistoryPort demoLoadEodSnapshotHistoryPort;

  public DemoGetEodSnapshotsByDateService(
      @Qualifier("demo") LoadEodSnapshotHistoryPort demoLoadEodSnapshotHistoryPort) {
    this.demoLoadEodSnapshotHistoryPort = demoLoadEodSnapshotHistoryPort;
  }

  @Override
  public PagedResult<EodSnapshot> getByDate(GetEodSnapshotsByDateQuery query) {
    return demoLoadEodSnapshotHistoryPort.byDate(query.date(), query.page(), query.size());
  }
}
