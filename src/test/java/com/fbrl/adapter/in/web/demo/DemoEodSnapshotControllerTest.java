package com.fbrl.adapter.in.web.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fbrl.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fbrl.adapter.out.persistence.EodSnapshotPersistenceAdapter;
import com.fbrl.adapter.out.persistence.demo.DemoEodSnapshotPersistenceAdapter;
import com.fbrl.application.port.out.SaveAdminUserPort;
import com.fbrl.domain.model.AdminRole;
import com.fbrl.domain.model.AdminUser;
import com.fbrl.domain.model.EodSnapshot;
import com.fbrl.domain.model.Money;
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
@DisplayName("DemoEodSnapshotController 통합 테스트 — DEMO 계정용 EOD 스냅샷 조회")
class DemoEodSnapshotControllerTest {

  private static final String DEMO_USERNAME = "demo-eod-snapshot-test-demo-account";
  private static final String ADMIN_USERNAME = "demo-eod-snapshot-test-admin-account";
  private static final String PASSWORD = "correct-password-123";
  private static final String ACCOUNT_NUMBER = "DEMO-EOD-SNAPSHOT-111";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SaveAdminUserPort saveAdminUserPort;
  @Autowired private AdminUserPersistenceAdapter adminUserPersistenceAdapter;
  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private DemoEodSnapshotPersistenceAdapter demoEodSnapshotPersistenceAdapter;
  @Autowired private EodSnapshotPersistenceAdapter eodSnapshotPersistenceAdapter;

  private String demoToken;
  private String adminToken;

  @BeforeEach
  void setUp() throws Exception {
    demoEodSnapshotPersistenceAdapter.deleteAllInBatch();
    eodSnapshotPersistenceAdapter.deleteAllInBatch();

    adminUserPersistenceAdapter.deleteAllInBatch();
    saveAdminUserPort.save(
        AdminUser.create(DEMO_USERNAME, passwordEncoder.encode(PASSWORD), AdminRole.DEMO));
    saveAdminUserPort.save(
        AdminUser.create(ADMIN_USERNAME, passwordEncoder.encode(PASSWORD), AdminRole.ADMIN));
    demoToken = login(DEMO_USERNAME);
    adminToken = login(ADMIN_USERNAME);

    demoEodSnapshotPersistenceAdapter.saveAll(
        List.of(
            EodSnapshot.of(
                ACCOUNT_NUMBER,
                Money.wons(10_000_000),
                Money.wons(1000),
                LocalDate.of(2026, 8, 16)),
            EodSnapshot.of(
                "DEMO-EOD-SNAPSHOT-222",
                Money.wons(5_000_000),
                Money.wons(500),
                LocalDate.of(2026, 8, 16))));
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
  @DisplayName("DEMO 계정으로 날짜별 데모 EOD 스냅샷을 조회하면 200과 함께 그날 전체 계좌 스냅샷을 반환한다.")
  void demoAccount_canGetDemoSnapshotsByDate() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/eod-snapshots")
                .header(HttpHeaders.AUTHORIZATION, bearer(demoToken))
                .param("date", "2026-08-16"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  @DisplayName("DEMO 계정으로 계좌별 데모 EOD 스냅샷 이력을 조회하면 200을 반환한다.")
  void demoAccount_canGetDemoSnapshotHistoryByAccount() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/eod-snapshots/" + ACCOUNT_NUMBER)
                .header(HttpHeaders.AUTHORIZATION, bearer(demoToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].accountNumber").value(ACCOUNT_NUMBER));
  }

  @Test
  @DisplayName("ADMIN 계정도 두 데모 EOD 스냅샷 조회 모두 여전히 접근 가능하다(회귀 없음).")
  void adminAccount_canStillGetBothDemoEndpoints() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/eod-snapshots")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .param("date", "2026-08-16"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));

    mockMvc
        .perform(
            get("/api/v1/demo/eod-snapshots/" + ACCOUNT_NUMBER)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  @DisplayName("토큰 없이 데모 EOD 스냅샷을 조회하면 401을 반환한다.")
  void demoEodSnapshots_withoutToken_returns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/demo/eod-snapshots").param("date", "2026-08-16"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("전체 건수보다 큰 페이지를 요청하면 빈 content를 반환하되 totalElements는 정확하다.")
  void demoEodSnapshots_pageBeyondTotal_returnsEmptyContent() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/demo/eod-snapshots")
                .header(HttpHeaders.AUTHORIZATION, bearer(demoToken))
                .param("date", "2026-08-16")
                .param("page", "5")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  @DisplayName("데모 EOD 스냅샷은 운영 EOD 스냅샷 조회 결과와 섞이지 않는다.")
  void demoEodSnapshots_doNotMixWithProductionSnapshots() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/eod-snapshots")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .param("date", "2026-08-16"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }
}
