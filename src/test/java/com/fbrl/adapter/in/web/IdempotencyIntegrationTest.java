package com.fbrl.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fbrl.adapter.in.web.dto.TransferMoneyRequest;
import com.fbrl.adapter.out.persistence.AccountPersistenceAdapter;
import com.fbrl.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fbrl.adapter.out.persistence.EodSnapshotPersistenceAdapter;
import com.fbrl.adapter.out.persistence.LedgerEntryPersistenceAdapter;
import com.fbrl.application.port.out.SaveAdminUserPort;
import com.fbrl.application.port.out.SaveLedgerEntryPort;
import com.fbrl.application.service.AccountBalanceCalculator;
import com.fbrl.domain.model.Account;
import com.fbrl.domain.model.AdminUser;
import com.fbrl.domain.model.LedgerDirection;
import com.fbrl.domain.model.LedgerEntry;
import com.fbrl.domain.model.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("송금 API 멱등성 통합 테스트")
class IdempotencyIntegrationTest {

  private static final String ADMIN_USERNAME = "idempotency-test-admin";
  private static final String ADMIN_PASSWORD = "password-123";

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private AccountPersistenceAdapter accountPersistenceAdapter;

  @Autowired private LedgerEntryPersistenceAdapter ledgerEntryPersistenceAdapter;

  @Autowired private EodSnapshotPersistenceAdapter eodSnapshotPersistenceAdapter;

  @Autowired private AdminUserPersistenceAdapter adminUserPersistenceAdapter;

  @Autowired private SaveAdminUserPort saveAdminUserPort;

  @Autowired private SaveLedgerEntryPort saveLedgerEntryPort;

  @Autowired private AccountBalanceCalculator accountBalanceCalculator;

  @Autowired private PasswordEncoder passwordEncoder;

  private final String SENDER_ACCOUNT = "111-111";
  private final String RECEIVER_ACCOUNT = "222-222";

  private String bearerToken;

  @BeforeEach
  void setUp() throws Exception {
    ledgerEntryPersistenceAdapter.deleteAllInBatch();
    eodSnapshotPersistenceAdapter.deleteAllInBatch();
    accountPersistenceAdapter.deleteAllInBatch();
    adminUserPersistenceAdapter.deleteAllInBatch();

    accountPersistenceAdapter.save(Account.create(SENDER_ACCOUNT));
    accountPersistenceAdapter.save(Account.create(RECEIVER_ACCOUNT));

    saveLedgerEntryPort.saveAll(
        List.of(
            LedgerEntry.of(
                SENDER_ACCOUNT,
                LedgerDirection.CREDIT,
                Money.wons(1_000_000),
                "TEST_SEED",
                Instant.now())));

    saveAdminUserPort.save(
        AdminUser.create(ADMIN_USERNAME, passwordEncoder.encode(ADMIN_PASSWORD)));

    String loginResponse =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\""
                            + ADMIN_USERNAME
                            + "\",\"password\":\""
                            + ADMIN_PASSWORD
                            + "\"}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    bearerToken = "Bearer " + objectMapper.readTree(loginResponse).get("token").asText();
  }

  @Test
  @DisplayName("동일한 X-Idempotency-Key로 두 번 연속 요청 시 두 번째 요청은 AOP에 의해 차단된다.")
  void duplicateRequestWithSameIdempotencyKey_ShouldBeBlocked() throws Exception {

    String idempotencyKey = UUID.randomUUID().toString();

    TransferMoneyRequest request =
        new TransferMoneyRequest(SENDER_ACCOUNT, RECEIVER_ACCOUNT, BigDecimal.valueOf(10_000));

    mockMvc
        .perform(
            post("/api/v1/transfers")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/v1/transfers")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_IDEMPOTENCY_KEY"))
        .andExpect(
            jsonPath("$.message")
                .value("이미 처리되었거나 처리 중인 요청입니다. (X-Idempotency-Key: " + idempotencyKey + ")"));

    Account sender = accountPersistenceAdapter.findByAccountNumber(SENDER_ACCOUNT).orElseThrow();

    assertThat(accountBalanceCalculator.calculate(sender))
        .isEqualTo(Money.of(BigDecimal.valueOf(990_000)));
  }
}
