package com.fbrl.adapter.in.web.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fbrl.adapter.out.persistence.AccountPersistenceAdapter;
import com.fbrl.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fbrl.adapter.out.persistence.demo.DemoAccountPersistenceAdapter;
import com.fbrl.adapter.out.persistence.demo.DemoLedgerEntryPersistenceAdapter;
import com.fbrl.application.port.out.SaveAdminUserPort;
import com.fbrl.application.port.out.SaveLedgerEntryPort;
import com.fbrl.domain.model.Account;
import com.fbrl.domain.model.AdminRole;
import com.fbrl.domain.model.AdminUser;
import com.fbrl.domain.model.LedgerDirection;
import com.fbrl.domain.model.LedgerEntry;
import com.fbrl.domain.model.Money;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("DemoAccountController 통합 테스트 — DEMO 계정용 계좌 상세/원장 조회")
class DemoAccountControllerTest {

  private static final String DEMO_USERNAME = "demo-account-get-test-demo-account";
  private static final String ADMIN_USERNAME = "demo-account-get-test-admin-account";
  private static final String PASSWORD = "correct-password-123";
  private static final String ACCOUNT_NUMBER = "DEMO-ACCOUNT-GET-111";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SaveAdminUserPort saveAdminUserPort;
  @Autowired private AdminUserPersistenceAdapter adminUserPersistenceAdapter;
  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private DemoAccountPersistenceAdapter demoAccountPersistenceAdapter;
  @Autowired private DemoLedgerEntryPersistenceAdapter demoLedgerEntryPersistenceAdapter;
  @Autowired private AccountPersistenceAdapter accountPersistenceAdapter;

  @Autowired
  @Qualifier("demo")
  private SaveLedgerEntryPort demoSaveLedgerEntryPort;

  private String demoToken;
  private String adminToken;

  @BeforeEach
  void setUp() throws Exception {
    demoLedgerEntryPersistenceAdapter.deleteAllInBatch();
    demoAccountPersistenceAdapter.deleteAllInBatch();
    accountPersistenceAdapter.deleteAllInBatch();

    adminUserPersistenceAdapter.deleteAllInBatch();
    saveAdminUserPort.save(
        AdminUser.create(DEMO_USERNAME, passwordEncoder.encode(PASSWORD), AdminRole.DEMO));
    saveAdminUserPort.save(
        AdminUser.create(ADMIN_USERNAME, passwordEncoder.encode(PASSWORD), AdminRole.ADMIN));
    demoToken = login(DEMO_USERNAME);
    adminToken = login(ADMIN_USERNAME);

    demoAccountPersistenceAdapter.save(Account.create(ACCOUNT_NUMBER));
    demoSaveLedgerEntryPort.saveAll(
        List.of(
            LedgerEntry.of(
                ACCOUNT_NUMBER,
                LedgerDirection.CREDIT,
                Money.wons(50_000),
                "TEST_SEED",
                Instant.now())));
  }

  private String login(String username) throws Exception {
    String loginBody = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
    String responseBody =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode json = objectMapper.readTree(responseBody);
    return json.get("token").asText();
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }

  @Test
  @DisplayName("DEMO 계정으로 데모 계좌 상세를 조회하면 200과 함께 잔액을 반환한다.")
  void demoAccount_canGetDemoAccountDetail() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/accounts/" + ACCOUNT_NUMBER)
                .header(HttpHeaders.AUTHORIZATION, bearer(demoToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountNumber").value(ACCOUNT_NUMBER))
        .andExpect(jsonPath("$.balance").value(50_000));
  }

  @Test
  @DisplayName("ADMIN 계정도 데모 계좌 상세 조회에 여전히 접근 가능하다(회귀 없음).")
  void adminAccount_canStillGetDemoAccountDetail() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/accounts/" + ACCOUNT_NUMBER)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(50_000));
  }

  @Test
  @DisplayName("토큰 없이 데모 계좌 상세를 조회하면 401을 반환한다.")
  void demoAccountDetail_withoutToken_returns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/demo/accounts/" + ACCOUNT_NUMBER))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("데모 계좌는 운영 계좌 조회 결과와 섞이지 않는다.")
  void demoAccount_doesNotLeakIntoProductionAccountLookup() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/accounts/" + ACCOUNT_NUMBER)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("DEMO 계정으로 데모 원장 이력을 조회하면 200과 함께 시딩된 항목을 반환한다.")
  void demoAccount_canListDemoLedgerEntries() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/accounts/" + ACCOUNT_NUMBER + "/ledger-entries")
                .header(HttpHeaders.AUTHORIZATION, bearer(demoToken))
                .param("from", Instant.EPOCH.toString())
                .param("to", Instant.now().plusSeconds(60).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].accountNumber").value(ACCOUNT_NUMBER))
        .andExpect(jsonPath("$.content[0].direction").value("CREDIT"));
  }

  @Test
  @DisplayName("ADMIN 계정도 데모 원장 이력 조회에 여전히 접근 가능하다(회귀 없음).")
  void adminAccount_canStillListDemoLedgerEntries() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/accounts/" + ACCOUNT_NUMBER + "/ledger-entries")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .param("from", Instant.EPOCH.toString())
                .param("to", Instant.now().plusSeconds(60).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  @DisplayName("토큰 없이 데모 원장 이력을 조회하면 401을 반환한다.")
  void demoLedgerEntries_withoutToken_returns401() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/accounts/" + ACCOUNT_NUMBER + "/ledger-entries")
                .param("from", Instant.EPOCH.toString())
                .param("to", Instant.now().toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("전체 건수보다 큰 페이지를 요청하면 빈 content를 반환하되 totalElements는 정확하다.")
  void demoLedgerEntries_pageBeyondTotal_returnsEmptyContent() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/accounts/" + ACCOUNT_NUMBER + "/ledger-entries")
                .header(HttpHeaders.AUTHORIZATION, bearer(demoToken))
                .param("from", Instant.EPOCH.toString())
                .param("to", Instant.now().plusSeconds(60).toString())
                .param("page", "5")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  @DisplayName("데모 원장 이력은 운영 원장 이력과 섞이지 않는다.")
  void demoLedgerEntries_doNotMixWithProductionLedgerEntries() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/accounts/" + ACCOUNT_NUMBER + "/ledger-entries")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .param("from", Instant.EPOCH.toString())
                .param("to", Instant.now().plusSeconds(60).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }
}
