package com.fbrl.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fbrl.adapter.in.web.dto.RejectTransferRequest;
import com.fbrl.adapter.in.web.dto.RequestTransferApprovalRequest;
import com.fbrl.adapter.out.persistence.AccountPersistenceAdapter;
import com.fbrl.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fbrl.adapter.out.persistence.ApprovalPersistenceAdapter;
import com.fbrl.adapter.out.persistence.EodSnapshotPersistenceAdapter;
import com.fbrl.adapter.out.persistence.LedgerEntryPersistenceAdapter;
import com.fbrl.application.port.out.SaveAdminUserPort;
import com.fbrl.application.port.out.SaveLedgerEntryPort;
import com.fbrl.domain.model.Account;
import com.fbrl.domain.model.AdminUser;
import com.fbrl.domain.model.LedgerDirection;
import com.fbrl.domain.model.LedgerEntry;
import com.fbrl.domain.model.Money;
import com.fbrl.domain.model.TransferApprovalRequest;
import java.math.BigDecimal;
import java.time.Instant;
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
@DisplayName("TransferApprovalController 통합 테스트")
class TransferApprovalControllerTest {

  private static final String MAKER_USERNAME = "approval-test-maker";
  private static final String CHECKER_USERNAME = "approval-test-checker";
  private static final String PASSWORD = "correct-password-123";
  private static final String SENDER = "111-111";
  private static final String RECEIVER = "222-222";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SaveAdminUserPort saveAdminUserPort;
  @Autowired private AdminUserPersistenceAdapter adminUserPersistenceAdapter;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private ApprovalPersistenceAdapter approvalPersistenceAdapter;
  @Autowired private AccountPersistenceAdapter accountPersistenceAdapter;
  @Autowired private LedgerEntryPersistenceAdapter ledgerEntryPersistenceAdapter;
  @Autowired private EodSnapshotPersistenceAdapter eodSnapshotPersistenceAdapter;
  @Autowired private SaveLedgerEntryPort saveLedgerEntryPort;

  private String makerToken;
  private String checkerToken;

  @BeforeEach
  void setUp() throws Exception {
    approvalPersistenceAdapter.deleteAllInBatch();
    ledgerEntryPersistenceAdapter.deleteAllInBatch();
    eodSnapshotPersistenceAdapter.deleteAllInBatch();
    accountPersistenceAdapter.deleteAllInBatch();
    adminUserPersistenceAdapter.deleteAllInBatch();

    saveAdminUserPort.save(AdminUser.create(MAKER_USERNAME, passwordEncoder.encode(PASSWORD)));
    saveAdminUserPort.save(AdminUser.create(CHECKER_USERNAME, passwordEncoder.encode(PASSWORD)));
    makerToken = login(MAKER_USERNAME);
    checkerToken = login(CHECKER_USERNAME);

    accountPersistenceAdapter.save(Account.create(SENDER));
    accountPersistenceAdapter.save(Account.create(RECEIVER));
    saveLedgerEntryPort.saveAll(
        List.of(
            LedgerEntry.of(
                SENDER,
                LedgerDirection.CREDIT,
                Money.wons(50_000_000),
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
  @DisplayName("승인이 필요한 금액으로 요청하면 PENDING 상태와 함께 200 OK를 반환하고, 기안자는 인증된 사용자명으로 기록된다.")
  void requestApprovalSuccess() throws Exception {
    RequestTransferApprovalRequest request =
        new RequestTransferApprovalRequest(SENDER, RECEIVER, BigDecimal.valueOf(20_000_000));

    String responseBody =
        mockMvc
            .perform(
                post("/api/v1/transfer-approvals")
                    .header(HttpHeaders.AUTHORIZATION, bearer(makerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String requestId = objectMapper.readTree(responseBody).get("requestId").asText();
    TransferApprovalRequest saved =
        approvalPersistenceAdapter.loadByRequestId(requestId).orElseThrow();
    assertThat(saved.getMakerId()).isEqualTo(MAKER_USERNAME);
  }

  @Test
  @DisplayName("토큰 없이 승인 요청을 시도하면 401을 반환한다.")
  void requestApprovalWithoutToken_returns401() throws Exception {
    RequestTransferApprovalRequest request =
        new RequestTransferApprovalRequest(SENDER, RECEIVER, BigDecimal.valueOf(20_000_000));

    mockMvc
        .perform(
            post("/api/v1/transfer-approvals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("threshold 미만 금액으로 요청하면 ApprovalNotRequiredException이 터져 400 Bad Request를 반환한다.")
  void requestApprovalBelowThreshold() throws Exception {
    RequestTransferApprovalRequest request =
        new RequestTransferApprovalRequest(SENDER, RECEIVER, BigDecimal.valueOf(1_000_000));

    mockMvc
        .perform(
            post("/api/v1/transfer-approvals")
                .header(HttpHeaders.AUTHORIZATION, bearer(makerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("APPROVAL_NOT_REQUIRED"));
  }

  @Test
  @DisplayName("대기 중인 승인 요청 목록을 조회하면 200 OK와 함께 목록을 반환한다.")
  void getPendingApprovalsSuccess() throws Exception {
    TransferApprovalRequest pending =
        TransferApprovalRequest.request(MAKER_USERNAME, SENDER, RECEIVER, Money.wons(20_000_000));
    approvalPersistenceAdapter.save(pending);

    mockMvc
        .perform(
            get("/api/v1/transfer-approvals/pending")
                .header(HttpHeaders.AUTHORIZATION, bearer(checkerToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].requestId").value(pending.getRequestId()))
        .andExpect(jsonPath("$[0].makerId").value(MAKER_USERNAME));
  }

  @Test
  @DisplayName("존재하는 requestId로 단건 조회하면 200 OK와 함께 상세 정보를 반환한다.")
  void getApprovalRequestSuccess() throws Exception {
    TransferApprovalRequest request =
        TransferApprovalRequest.request(MAKER_USERNAME, SENDER, RECEIVER, Money.wons(20_000_000));
    request.reject(CHECKER_USERNAME, "한도 초과 우려");
    approvalPersistenceAdapter.save(request);

    mockMvc
        .perform(
            get("/api/v1/transfer-approvals/" + request.getRequestId())
                .header(HttpHeaders.AUTHORIZATION, bearer(checkerToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").value(request.getRequestId()))
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectionReason").value("한도 초과 우려"));
  }

  @Test
  @DisplayName(
      "존재하지 않는 requestId로 단건 조회하면 ApprovalRequestNotFoundException이 터져 404 Not Found를 반환한다.")
  void getApprovalRequestNotFound() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/transfer-approvals/missing")
                .header(HttpHeaders.AUTHORIZATION, bearer(checkerToken)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("APPROVAL_REQUEST_NOT_FOUND"));
  }

  @Test
  @DisplayName("기안자와 다른 관리자 계정으로 승인하면 실제로 이체까지 실행되고 APPROVED 상태를 반환한다.")
  void approveByDifferentAdmin_succeedsAndExecutesTransfer() throws Exception {
    TransferApprovalRequest request =
        TransferApprovalRequest.request(MAKER_USERNAME, SENDER, RECEIVER, Money.wons(20_000_000));
    approvalPersistenceAdapter.save(request);

    mockMvc
        .perform(
            post("/api/v1/transfer-approvals/" + request.getRequestId() + "/approve")
                .header(HttpHeaders.AUTHORIZATION, bearer(checkerToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    mockMvc
        .perform(
            get("/api/v1/transfer-approvals/" + request.getRequestId())
                .header(HttpHeaders.AUTHORIZATION, bearer(checkerToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.executionStatus").value("EXECUTED"));

    TransferApprovalRequest persisted =
        approvalPersistenceAdapter.loadByRequestId(request.getRequestId()).orElseThrow();
    assertThat(persisted.getCheckerId()).isEqualTo(CHECKER_USERNAME);
  }

  @Test
  @DisplayName(
      "기안자 본인의 로그인 세션으로 자신이 기안한 요청을 승인하면 SelfApprovalNotAllowedException이 터져 400 Bad Request를 반환한다.")
  void approveBySameLoginSession_isRejectedAsSelfApproval() throws Exception {
    RequestTransferApprovalRequest requestBody =
        new RequestTransferApprovalRequest(SENDER, RECEIVER, BigDecimal.valueOf(20_000_000));

    String responseBody =
        mockMvc
            .perform(
                post("/api/v1/transfer-approvals")
                    .header(HttpHeaders.AUTHORIZATION, bearer(makerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String requestId = objectMapper.readTree(responseBody).get("requestId").asText();

    mockMvc
        .perform(
            post("/api/v1/transfer-approvals/" + requestId + "/approve")
                .header(HttpHeaders.AUTHORIZATION, bearer(makerToken)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SELF_APPROVAL_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("거절 사유 없이 거절을 요청하면 @Valid 검증에 실패하여 400 Bad Request를 반환한다.")
  void rejectValidationFailureWhenReasonBlank() throws Exception {
    TransferApprovalRequest request =
        TransferApprovalRequest.request(MAKER_USERNAME, SENDER, RECEIVER, Money.wons(20_000_000));
    approvalPersistenceAdapter.save(request);
    RejectTransferRequest rejectBody = new RejectTransferRequest("");

    mockMvc
        .perform(
            post("/api/v1/transfer-approvals/" + request.getRequestId() + "/reject")
                .header(HttpHeaders.AUTHORIZATION, bearer(checkerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rejectBody)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @Test
  @DisplayName("다른 관리자 계정으로 정상 거절 요청 시 REJECTED 상태와 함께 200 OK를 반환하고, 사유와 승인자가 토큰의 사용자명으로 저장된다.")
  void rejectByDifferentAdmin_succeeds() throws Exception {
    TransferApprovalRequest request =
        TransferApprovalRequest.request(MAKER_USERNAME, SENDER, RECEIVER, Money.wons(20_000_000));
    approvalPersistenceAdapter.save(request);
    RejectTransferRequest rejectBody = new RejectTransferRequest("한도 초과 우려");

    mockMvc
        .perform(
            post("/api/v1/transfer-approvals/" + request.getRequestId() + "/reject")
                .header(HttpHeaders.AUTHORIZATION, bearer(checkerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rejectBody)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    TransferApprovalRequest persisted =
        approvalPersistenceAdapter.loadByRequestId(request.getRequestId()).orElseThrow();
    assertThat(persisted.getCheckerId()).isEqualTo(CHECKER_USERNAME);
    assertThat(persisted.getRejectionReason()).isEqualTo("한도 초과 우려");
  }

  @Test
  @DisplayName(
      "기안자 본인의 로그인 세션으로 자신이 기안한 요청을 거절하면 SelfApprovalNotAllowedException이 터져 400 Bad Request를 반환한다.")
  void rejectBySameLoginSession_isRejectedAsSelfApproval() throws Exception {
    RequestTransferApprovalRequest requestBody =
        new RequestTransferApprovalRequest(SENDER, RECEIVER, BigDecimal.valueOf(20_000_000));

    String responseBody =
        mockMvc
            .perform(
                post("/api/v1/transfer-approvals")
                    .header(HttpHeaders.AUTHORIZATION, bearer(makerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String requestId = objectMapper.readTree(responseBody).get("requestId").asText();
    RejectTransferRequest rejectBody = new RejectTransferRequest("한도 초과 우려");

    mockMvc
        .perform(
            post("/api/v1/transfer-approvals/" + requestId + "/reject")
                .header(HttpHeaders.AUTHORIZATION, bearer(makerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rejectBody)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SELF_APPROVAL_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("status/기간 필터로 이력을 조회하면 해당 조건에 맞는 페이지를 반환한다.")
  void search_withFilters_returnsMatchingPage() throws Exception {
    TransferApprovalRequest pending =
        TransferApprovalRequest.request(MAKER_USERNAME, SENDER, RECEIVER, Money.wons(20_000_000));
    approvalPersistenceAdapter.save(pending);
    TransferApprovalRequest rejected =
        TransferApprovalRequest.request(MAKER_USERNAME, SENDER, RECEIVER, Money.wons(20_000_000));
    rejected.reject(CHECKER_USERNAME, "한도 초과 우려");
    approvalPersistenceAdapter.save(rejected);

    mockMvc
        .perform(
            get("/api/v1/transfer-approvals")
                .header(HttpHeaders.AUTHORIZATION, bearer(checkerToken))
                .param("status", "PENDING")
                .param("from", Instant.now().minusSeconds(60).toString())
                .param("to", Instant.now().plusSeconds(60).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].requestId").value(pending.getRequestId()))
        .andExpect(jsonPath("$.content[0].status").value("PENDING"));
  }

  @Test
  @DisplayName("토큰 없이 이력 조회를 시도하면 401을 반환한다.")
  void search_withoutToken_returns401() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/transfer-approvals")
                .param("from", Instant.now().minusSeconds(60).toString())
                .param("to", Instant.now().toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("전체 건수보다 큰 페이지를 요청하면 빈 content를 반환하되 totalElements는 정확하다.")
  void search_pageBeyondTotal_returnsEmptyContent() throws Exception {
    TransferApprovalRequest pending =
        TransferApprovalRequest.request(MAKER_USERNAME, SENDER, RECEIVER, Money.wons(20_000_000));
    approvalPersistenceAdapter.save(pending);

    mockMvc
        .perform(
            get("/api/v1/transfer-approvals")
                .header(HttpHeaders.AUTHORIZATION, bearer(checkerToken))
                .param("from", Instant.now().minusSeconds(60).toString())
                .param("to", Instant.now().plusSeconds(60).toString())
                .param("page", "5")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.totalElements").value(1));
  }
}
