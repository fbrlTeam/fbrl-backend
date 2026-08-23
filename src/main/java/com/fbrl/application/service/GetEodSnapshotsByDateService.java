package com.fbrl.application.service;

import com.fbrl.application.port.in.GetEodSnapshotsByDateUseCase;
import com.fbrl.application.port.out.LoadEodSnapshotHistoryPort;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.EodSnapshot;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class GetEodSnapshotsByDateService implements GetEodSnapshotsByDateUseCase {

  private final LoadEodSnapshotHistoryPort loadEodSnapshotHistoryPort;

  public GetEodSnapshotsByDateService(LoadEodSnapshotHistoryPort loadEodSnapshotHistoryPort) {
    this.loadEodSnapshotHistoryPort = loadEodSnapshotHistoryPort;
  }

  @Override
  public PagedResult<EodSnapshot> getByDate(GetEodSnapshotsByDateQuery query) {
    return loadEodSnapshotHistoryPort.byDate(query.date(), query.page(), query.size());
  }
}
