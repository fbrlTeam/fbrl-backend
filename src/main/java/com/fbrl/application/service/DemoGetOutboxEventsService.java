package com.fbrl.application.service;

import com.fbrl.application.port.in.GetOutboxEventsUseCase;
import com.fbrl.application.port.out.LoadOutboxEventsPort;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.OutboxEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Qualifier("demo")
public class DemoGetOutboxEventsService implements GetOutboxEventsUseCase {

  private final LoadOutboxEventsPort demoLoadOutboxEventsPort;

  public DemoGetOutboxEventsService(
      @Qualifier("demo") LoadOutboxEventsPort demoLoadOutboxEventsPort) {
    this.demoLoadOutboxEventsPort = demoLoadOutboxEventsPort;
  }

  @Override
  public PagedResult<OutboxEvent> getEvents(GetOutboxEventsQuery query) {
    return demoLoadOutboxEventsPort.loadPage(query.page(), query.size());
  }
}
