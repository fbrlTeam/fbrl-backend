package com.fbrl.adapter.out.persistence.demo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DemoEodSnapshotJpaRepository extends JpaRepository<DemoEodSnapshotEntity, Long> {
  Optional<DemoEodSnapshotEntity> findTopByAccountNumberOrderByComputedAtDesc(String accountNumber);

  List<DemoEodSnapshotEntity> findByAccountNumberInAndSettlementDate(
      List<String> accountNumbers, LocalDate settlementDate);

  @Query(
      value =
          "select e from DemoEodSnapshotEntity e "
              + "where e.accountNumber = :accountNumber "
              + "and (cast(:from as date) is null or e.settlementDate >= :from) "
              + "and (cast(:to as date) is null or e.settlementDate <= :to)",
      countQuery =
          "select count(e) from DemoEodSnapshotEntity e "
              + "where e.accountNumber = :accountNumber "
              + "and (cast(:from as date) is null or e.settlementDate >= :from) "
              + "and (cast(:to as date) is null or e.settlementDate <= :to)")
  Page<DemoEodSnapshotEntity> search(
      @Param("accountNumber") String accountNumber,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      Pageable pageable);

  Page<DemoEodSnapshotEntity> findBySettlementDate(LocalDate settlementDate, Pageable pageable);
}
