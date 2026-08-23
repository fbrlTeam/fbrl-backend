package com.fbrl.adapter.out.persistence.demo;

import com.fbrl.domain.model.ReconciliationStatus;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DemoReconciliationDiscrepancyJpaRepository
    extends JpaRepository<DemoReconciliationDiscrepancyEntity, Long> {

  @Query(
      value =
          "select r from DemoReconciliationDiscrepancyEntity r "
              + "where (:status is null or r.status = :status) "
              + "and r.settlementDate >= :from and r.settlementDate <= :to",
      countQuery =
          "select count(r) from DemoReconciliationDiscrepancyEntity r "
              + "where (:status is null or r.status = :status) "
              + "and r.settlementDate >= :from and r.settlementDate <= :to")
  Page<DemoReconciliationDiscrepancyEntity> search(
      @Param("status") ReconciliationStatus status,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      Pageable pageable);
}
