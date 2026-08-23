package com.fbrl.application.service;

import com.fbrl.application.port.in.GetLedgerEntriesUseCase;
import com.fbrl.application.port.out.LoadLedgerEntriesPort;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.LedgerEntry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Qualifier("demo")
public class DemoGetLedgerEntriesService implements GetLedgerEntriesUseCase {

  private final LoadLedgerEntriesPort demoLoadLedgerEntriesPort;

  public DemoGetLedgerEntriesService(
      @Qualifier("demo") LoadLedgerEntriesPort demoLoadLedgerEntriesPort) {
    this.demoLoadLedgerEntriesPort = demoLoadLedgerEntriesPort;
  }

  @Override
  public PagedResult<LedgerEntry> getLedgerEntries(GetLedgerEntriesQuery query) {
    return demoLoadLedgerEntriesPort.loadByAccountNumberAndPeriod(
        query.accountNumber(), query.from(), query.to(), query.page(), query.size());
  }
}
