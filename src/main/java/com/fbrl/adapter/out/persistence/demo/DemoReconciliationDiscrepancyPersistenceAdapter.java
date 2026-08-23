package com.fbrl.adapter.out.persistence.demo;

import com.fbrl.application.port.out.LoadReconciliationDiscrepancyPort;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.application.port.out.SaveReconciliationDiscrepancyPort;
import com.fbrl.domain.exception.DuplicateReconciliationDiscrepancyException;
import com.fbrl.domain.model.ReconciliationDiscrepancy;
import com.fbrl.domain.model.ReconciliationStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@Qualifier("demo")
public class DemoReconciliationDiscrepancyPersistenceAdapter
    implements SaveReconciliationDiscrepancyPort, LoadReconciliationDiscrepancyPort {

  private final DemoReconciliationDiscrepancyJpaRepository
      demoReconciliationDiscrepancyJpaRepository;
  private final DemoReconciliationDiscrepancyMapper demoReconciliationDiscrepancyMapper;

  public DemoReconciliationDiscrepancyPersistenceAdapter(
      DemoReconciliationDiscrepancyJpaRepository demoReconciliationDiscrepancyJpaRepository,
      DemoReconciliationDiscrepancyMapper demoReconciliationDiscrepancyMapper) {
    this.demoReconciliationDiscrepancyJpaRepository = demoReconciliationDiscrepancyJpaRepository;
    this.demoReconciliationDiscrepancyMapper = demoReconciliationDiscrepancyMapper;
  }

  @Override
  public void saveAll(List<ReconciliationDiscrepancy> discrepancies) {
    List<DemoReconciliationDiscrepancyEntity> entities =
        discrepancies.stream().map(demoReconciliationDiscrepancyMapper::toEntity).toList();

    try {
      demoReconciliationDiscrepancyJpaRepository.saveAll(entities);
    } catch (DataIntegrityViolationException e) {
      throw new DuplicateReconciliationDiscrepancyException(
          "이미 대사가 완료된 데모 계좌·정산일자 조합이 청크에 포함되어 있습니다. 청크 크기: " + discrepancies.size());
    }
  }

  @Override
  public PagedResult<ReconciliationDiscrepancy> search(
      ReconciliationStatus status, LocalDate from, LocalDate to, int page, int size) {
    Page<DemoReconciliationDiscrepancyEntity> result =
        demoReconciliationDiscrepancyJpaRepository.search(
            status, from, to, PageRequest.of(page, size));
    List<ReconciliationDiscrepancy> items =
        result.getContent().stream().map(demoReconciliationDiscrepancyMapper::toDomain).toList();
    return new PagedResult<>(items, result.getTotalElements());
  }

  public List<ReconciliationDiscrepancy> findAll() {
    return demoReconciliationDiscrepancyJpaRepository.findAll().stream()
        .map(demoReconciliationDiscrepancyMapper::toDomain)
        .toList();
  }

  public void deleteAllInBatch() {
    demoReconciliationDiscrepancyJpaRepository.deleteAllInBatch();
  }
}
