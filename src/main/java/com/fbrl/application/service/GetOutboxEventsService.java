package com.fbrl.application.service;

import com.fbrl.application.port.in.GetOutboxEventsUseCase;
import com.fbrl.application.port.out.LoadOutboxEventsPort;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.OutboxEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class GetOutboxEventsService implements GetOutboxEventsUseCase {

  private final LoadOutboxEventsPort loadOutboxEventsPort;

  public GetOutboxEventsService(LoadOutboxEventsPort loadOutboxEventsPort) {
    this.loadOutboxEventsPort = loadOutboxEventsPort;
  }

  @Override
  public PagedResult<OutboxEvent> getEvents(GetOutboxEventsQuery query) {
    return loadOutboxEventsPort.loadPage(query.page(), query.size());
  }
}
