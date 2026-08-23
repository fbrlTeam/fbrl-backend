package com.fbrl.adapter.in.web.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fbrl.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fbrl.adapter.out.persistence.OutboxPersistenceAdapter;
import com.fbrl.adapter.out.persistence.demo.DemoOutboxPersistenceAdapter;
import com.fbrl.application.port.out.SaveAdminUserPort;
import com.fbrl.application.service.DemoDataResetService;
import com.fbrl.domain.model.AdminRole;
import com.fbrl.domain.model.AdminUser;
import com.fbrl.domain.model.OutboxEvent;
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
@DisplayName("DemoAuditController 통합 테스트 — DEMO 계정용 감사 이벤트/해시체인 검증 조회")
class DemoAuditControllerTest {

  private static final String DEMO_USERNAME = "demo-audit-test-demo-account";
  private static final String ADMIN_USERNAME = "demo-audit-test-admin-account";
  private static final String PASSWORD = "correct-password-123";
  private static final String EVENTS_URL = "/api/v1/demo/audit/events";
  private static final String VERIFY_URL = "/api/v1/demo/audit/verify";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SaveAdminUserPort saveAdminUserPort;
  @Autowired private AdminUserPersistenceAdapter adminUserPersistenceAdapter;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private DemoDataResetService demoDataResetService;
  @Autowired private DemoOutboxPersistenceAdapter demoOutboxPersistenceAdapter;
  @Autowired private OutboxPersistenceAdapter outboxPersistenceAdapter;

  private String demoToken;
  private String adminToken;

  @BeforeEach
  void setUp() throws Exception {
    outboxPersistenceAdapter.deleteAllInBatch();
    adminUserPersistenceAdapter.deleteAllInBatch();
    saveAdminUserPort.save(
        AdminUser.create(DEMO_USERNAME, passwordEncoder.encode(PASSWORD), AdminRole.DEMO));
    saveAdminUserPort.save(
        AdminUser.create(ADMIN_USERNAME, passwordEncoder.encode(PASSWORD), AdminRole.ADMIN));
    demoToken = login(DEMO_USERNAME);
    adminToken = login(ADMIN_USERNAME);

    demoDataResetService.reset();
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
  @DisplayName("DEMO 계정으로 데모 감사 이벤트 목록을 조회하면 200과 함께 리셋 시딩분 3건을 반환한다.")
  void demoAccount_canListDemoAuditEvents() throws Exception {
    mockMvc
        .perform(get(EVENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(demoToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.content[0].entryHash").exists());
  }

  @Test
  @DisplayName("ADMIN 계정도 데모 감사 이벤트 목록에 여전히 접근 가능하다(회귀 없음).")
  void adminAccount_canStillListDemoAuditEvents() throws Exception {
    mockMvc
        .perform(get(EVENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3));
  }

  @Test
  @DisplayName("토큰 없이 데모 감사 이벤트 목록을 조회하면 401을 반환한다.")
  void demoAuditEvents_withoutToken_returns401() throws Exception {
    mockMvc
        .perform(get(EVENTS_URL))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("데모 리셋 직후 감사 체인 검증은 리셋이 변조한 마지막 이벤트를 실제로 탐지한다.")
  void demoAccount_verifyDetectsResetTamperedEvent() throws Exception {
    List<OutboxEvent> events = demoOutboxPersistenceAdapter.loadAllOrderedById();
    Long tamperedId = events.get(events.size() - 1).getId();

    mockMvc
        .perform(get(VERIFY_URL).header(HttpHeaders.AUTHORIZATION, bearer(demoToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(jsonPath("$.brokenAtId").value(tamperedId))
        .andExpect(jsonPath("$.reason").exists());
  }

  @Test
  @DisplayName("ADMIN 계정도 데모 감사 체인 검증에 여전히 접근 가능하다(회귀 없음).")
  void adminAccount_canStillVerifyDemoAuditChain() throws Exception {
    mockMvc
        .perform(get(VERIFY_URL).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false));
  }

  @Test
  @DisplayName("토큰 없이 감사 체인 검증을 시도하면 401을 반환한다.")
  void demoAuditVerify_withoutToken_returns401() throws Exception {
    mockMvc
        .perform(get(VERIFY_URL))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("데모 리셋으로 생긴 감사 이벤트는 운영 감사 이벤트와 섞이지 않는다.")
  void demoAuditEvents_doNotMixWithProductionAuditEvents() throws Exception {
    assertThat(outboxPersistenceAdapter.loadAllOrderedById()).isEmpty();

    mockMvc
        .perform(get("/api/v1/audit/events").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }
}
