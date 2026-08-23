package com.fbrl.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fbrl.adapter.out.persistence.demo.DemoAccountPersistenceAdapter;
import com.fbrl.adapter.out.persistence.demo.DemoApprovalRequestPersistenceAdapter;
import com.fbrl.adapter.out.persistence.demo.DemoEodSnapshotPersistenceAdapter;
import com.fbrl.adapter.out.persistence.demo.DemoLedgerEntryPersistenceAdapter;
import com.fbrl.adapter.out.persistence.demo.DemoOutboxPersistenceAdapter;
import com.fbrl.adapter.out.persistence.demo.DemoReconciliationDiscrepancyPersistenceAdapter;
import java.math.BigDecimal;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("데모 데이터 리셋 대차평형 통합 테스트 — 리셋 시딩과 데모 EOD 정산 Job의 상호작용")
class DemoDataResetTrialBalanceIntegrationTest {

  @Autowired private DemoDataResetService demoDataResetService;
  @Autowired private DemoAccountPersistenceAdapter demoAccountPersistenceAdapter;
  @Autowired private DemoLedgerEntryPersistenceAdapter demoLedgerEntryPersistenceAdapter;
  @Autowired private DemoEodSnapshotPersistenceAdapter demoEodSnapshotPersistenceAdapter;

  @Autowired
  private DemoReconciliationDiscrepancyPersistenceAdapter
      demoReconciliationDiscrepancyPersistenceAdapter;

  @Autowired private DemoApprovalRequestPersistenceAdapter demoApprovalRequestPersistenceAdapter;
  @Autowired private DemoOutboxPersistenceAdapter demoOutboxPersistenceAdapter;

  @Autowired
  @Qualifier("demo")
  private DataSource demoDataSource;

  @Autowired
  @Qualifier("demoJobOperator")
  private JobOperator demoJobOperator;

  @Autowired
  @Qualifier("demoEodSettlementJob")
  private Job demoEodSettlementJob;

  @Autowired
  @Qualifier("demoJobRepository")
  private JobRepository demoJobRepository;

  @BeforeEach
  void setUp() {
    new JobRepositoryTestUtils(demoJobRepository).removeJobExecutions();

    demoOutboxPersistenceAdapter.deleteAllInBatch();
    demoApprovalRequestPersistenceAdapter.deleteAllInBatch();
    demoReconciliationDiscrepancyPersistenceAdapter.deleteAllInBatch();
    demoEodSnapshotPersistenceAdapter.deleteAllInBatch();
    demoLedgerEntryPersistenceAdapter.deleteAllInBatch();
    demoAccountPersistenceAdapter.deleteAllInBatch();
  }

  @Test
  @DisplayName(
      "리셋 후 원장 전체의 DEBIT 합계와 CREDIT 합계가 정확히 일치한다"
          + "(demoTrialBalanceVerificationStep과 동일한 대조 로직을 SQL로 직접 재현).")
  void reset_ledgerDebitCreditSumsAreBalanced() {
    demoDataResetService.reset();

    Map<String, Object> totals = queryTrialBalanceTotals();
    BigDecimal totalDebit = (BigDecimal) totals.get("total_debit");
    BigDecimal totalCredit = (BigDecimal) totals.get("total_credit");

    assertThat(totalDebit.compareTo(totalCredit))
        .as("총 DEBIT(%s)과 총 CREDIT(%s)의 차이가 없어야 한다", totalDebit, totalCredit)
        .isZero();
  }

  @Test
  @DisplayName("리셋 직후 데모 EOD 정산 Job을 온디맨드로 트리거하면 대차평형 위반 없이 COMPLETED로 끝난다.")
  void reset_thenTriggerDemoEodJob_completesSuccessfully() throws Exception {
    demoDataResetService.reset();

    JobParameters jobParameters =
        new JobParametersBuilder().addString("settlementDate", "2026-08-23").toJobParameters();

    JobExecution execution = demoJobOperator.start(demoEodSettlementJob, jobParameters);

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
  }

  private Map<String, Object> queryTrialBalanceTotals() {
    JdbcTemplate demoJdbcTemplate = new JdbcTemplate(demoDataSource);
    return demoJdbcTemplate.queryForMap(
        "select "
            + "coalesce(sum(case when direction = 'DEBIT' then amount else 0 end), 0) as total_debit, "
            + "coalesce(sum(case when direction = 'CREDIT' then amount else 0 end), 0) as total_credit "
            + "from ledger_entries");
  }
}
