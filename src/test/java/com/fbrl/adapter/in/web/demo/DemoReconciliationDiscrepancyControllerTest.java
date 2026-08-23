package com.fbrl.adapter.in.web.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fbrl.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fbrl.adapter.out.persistence.ReconciliationDiscrepancyPersistenceAdapter;
import com.fbrl.adapter.out.persistence.demo.DemoReconciliationDiscrepancyPersistenceAdapter;
import com.fbrl.application.port.out.SaveAdminUserPort;
import com.fbrl.domain.model.AdminRole;
import com.fbrl.domain.model.AdminUser;
import com.fbrl.domain.model.Money;
import com.fbrl.domain.model.ReconciliationDiscrepancy;
import java.time.LocalDate;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("DemoReconciliationDiscrepancyController 통합 테스트 — DEMO 계정용 대사 불일치 조회")
class DemoReconciliationDiscrepancyControllerTest {

  private static final String DEMO_USERNAME = "demo-reconciliation-test-demo-account";
  private static final String ADMIN_USERNAME = "demo-reconciliation-test-admin-account";
  private static final String PASSWORD = "correct-password-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SaveAdminUserPort saveAdminUserPort;
  @Autowired private AdminUserPersistenceAdapter adminUserPersistenceAdapter;
  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired
  private DemoReconciliationDiscrepancyPersistenceAdapter
      demoReconciliationDiscrepancyPersistenceAdapter;

  @Autowired
  private ReconciliationDiscrepancyPersistenceAdapter reconciliationDiscrepancyPersistenceAdapter;

  private String demoToken;
  private String adminToken;

  @BeforeEach
  void setUp() throws Exception {
    demoReconciliationDiscrepancyPersistenceAdapter.deleteAllInBatch();
    reconciliationDiscrepancyPersistenceAdapter.deleteAllInBatch();

    adminUserPersistenceAdapter.deleteAllInBatch();
    saveAdminUserPort.save(
        AdminUser.create(DEMO_USERNAME, passwordEncoder.encode(PASSWORD), AdminRole.DEMO));
    saveAdminUserPort.save(
        AdminUser.create(ADMIN_USERNAME, passwordEncoder.encode(PASSWORD), AdminRole.ADMIN));
    demoToken = login(DEMO_USERNAME);
    adminToken = login(ADMIN_USERNAME);

    LocalDate day1 = LocalDate.of(2026, 8, 1);
    LocalDate day2 = LocalDate.of(2026, 8, 2);
    demoReconciliationDiscrepancyPersistenceAdapter.saveAll(
        List.of(
            ReconciliationDiscrepancy.mismatch(
                "DEMO-RECON-111", day1, Money.wons(9000), Money.wons(8500)),
            ReconciliationDiscrepancy.noSnapshot("DEMO-RECON-222", day2)));
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
  @DisplayName("DEMO 계정으로 status/기간 필터로 조회하면 해당 조건에 맞는 페이지를 반환한다.")
  void demoAccount_canSearchWithFilters() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/reconciliation-discrepancies")
                .header(HttpHeaders.AUTHORIZATION, bearer(demoToken))
                .param("status", "MISMATCH")
                .param("from", "2026-08-01")
                .param("to", "2026-08-02"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].accountNumber").value("DEMO-RECON-111"))
        .andExpect(jsonPath("$.content[0].status").value("MISMATCH"));
  }

  @Test
  @DisplayName("ADMIN 계정도 데모 대사 불일치 조회에 여전히 접근 가능하다(회귀 없음).")
  void adminAccount_canStillSearch() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/reconciliation-discrepancies")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .param("from", "2026-08-01")
                .param("to", "2026-08-02"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  @DisplayName("토큰 없이 조회를 시도하면 401을 반환한다.")
  void demoReconciliationDiscrepancies_withoutToken_returns401() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/reconciliation-discrepancies")
                .param("from", "2026-08-01")
                .param("to", "2026-08-02"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("전체 건수보다 큰 페이지를 요청하면 빈 content를 반환하되 totalElements는 정확하다.")
  void demoReconciliationDiscrepancies_pageBeyondTotal_returnsEmptyContent() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/reconciliation-discrepancies")
                .header(HttpHeaders.AUTHORIZATION, bearer(demoToken))
                .param("from", "2026-08-01")
                .param("to", "2026-08-02")
                .param("page", "5")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  @DisplayName("데모 대사 불일치는 운영 대사 불일치 조회 결과와 섞이지 않는다.")
  void demoReconciliationDiscrepancies_doNotMixWithProductionDiscrepancies() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/reconciliation-discrepancies")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .param("from", "2026-08-01")
                .param("to", "2026-08-02"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }
}
