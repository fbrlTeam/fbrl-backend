package com.fbrl.adapter.out.persistence.demo;

import com.fbrl.application.port.out.LoadEodSnapshotByDatePort;
import com.fbrl.application.port.out.LoadEodSnapshotHistoryPort;
import com.fbrl.application.port.out.LoadLatestEodSnapshotPort;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.application.port.out.SaveEodSnapshotPort;
import com.fbrl.domain.exception.DuplicateEodSnapshotException;
import com.fbrl.domain.exception.EodSnapshotPersistenceException;
import com.fbrl.domain.model.EodSnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@Qualifier("demo")
public class DemoEodSnapshotPersistenceAdapter
    implements SaveEodSnapshotPort,
        LoadLatestEodSnapshotPort,
        LoadEodSnapshotByDatePort,
        LoadEodSnapshotHistoryPort {

  private final DemoEodSnapshotJpaRepository demoEodSnapshotJpaRepository;
  private final DemoEodSnapshotMapper demoEodSnapshotMapper;

  public DemoEodSnapshotPersistenceAdapter(
      DemoEodSnapshotJpaRepository demoEodSnapshotJpaRepository,
      DemoEodSnapshotMapper demoEodSnapshotMapper) {
    this.demoEodSnapshotJpaRepository = demoEodSnapshotJpaRepository;
    this.demoEodSnapshotMapper = demoEodSnapshotMapper;
  }

  @Override
  public void saveAll(List<EodSnapshot> snapshots) {
    List<DemoEodSnapshotEntity> entities =
        snapshots.stream().map(demoEodSnapshotMapper::toEntity).toList();

    try {
      demoEodSnapshotJpaRepository.saveAll(entities);
    } catch (DataIntegrityViolationException e) {
      throw new DuplicateEodSnapshotException(
          "이미 정산이 완료된 데모 계좌·정산일자 조합이 청크에 포함되어 있습니다. 청크 크기: " + snapshots.size());
    }
  }

  @Override
  public Optional<EodSnapshot> findLatestByAccountNumber(String accountNumber) {
    return demoEodSnapshotJpaRepository
        .findTopByAccountNumberOrderByComputedAtDesc(accountNumber)
        .map(demoEodSnapshotMapper::toDomain);
  }

  @Override
  public Map<String, EodSnapshot> loadByAccountNumbersAndDate(
      List<String> accountNumbers, LocalDate date) {
    try {
      return demoEodSnapshotJpaRepository
          .findByAccountNumberInAndSettlementDate(accountNumbers, date)
          .stream()
          .map(demoEodSnapshotMapper::toDomain)
          .collect(Collectors.toMap(EodSnapshot::accountNumber, Function.identity()));
    } catch (DataAccessException e) {
      throw new EodSnapshotPersistenceException("데모 EOD 스냅샷 배치 조회 중 인프라 예외가 발생했습니다.", e);
    }
  }

  @Override
  public PagedResult<EodSnapshot> byAccountNumber(
      String accountNumber, LocalDate from, LocalDate to, int page, int size) {
    Page<DemoEodSnapshotEntity> result =
        demoEodSnapshotJpaRepository.search(accountNumber, from, to, PageRequest.of(page, size));
    List<EodSnapshot> items =
        result.getContent().stream().map(demoEodSnapshotMapper::toDomain).toList();
    return new PagedResult<>(items, result.getTotalElements());
  }

  @Override
  public PagedResult<EodSnapshot> byDate(LocalDate date, int page, int size) {
    Page<DemoEodSnapshotEntity> result =
        demoEodSnapshotJpaRepository.findBySettlementDate(date, PageRequest.of(page, size));
    List<EodSnapshot> items =
        result.getContent().stream().map(demoEodSnapshotMapper::toDomain).toList();
    return new PagedResult<>(items, result.getTotalElements());
  }

  public void deleteAllInBatch() {
    demoEodSnapshotJpaRepository.deleteAllInBatch();
  }
}
