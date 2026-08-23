package com.fbrl.adapter.in.web.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fbrl.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fbrl.application.port.out.SaveAdminUserPort;
import com.fbrl.domain.model.AdminRole;
import com.fbrl.domain.model.AdminUser;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@SpringBatchTest
@AutoConfigureMockMvc
@DisplayName("DemoBatchJobExecutionController 통합 테스트 — DEMO 계정용 데모 배치 실행 이력 조회")
class DemoBatchJobExecutionControllerTest {

  private static final String DEMO_USERNAME = "demo-batch-history-test-demo-account";
  private static final String ADMIN_USERNAME = "demo-batch-history-test-admin-account";
  private static final String PASSWORD = "correct-password-123";
  private static final String JOB_NAME = "demoBatchHistoryTestJob";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SaveAdminUserPort saveAdminUserPort;
  @Autowired private AdminUserPersistenceAdapter adminUserPersistenceAdapter;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JobRepository jobRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired
  @Qualifier("demoJobRepository")
  private JobRepository demoJobRepository;

  private String demoToken;
  private String adminToken;

  @BeforeEach
  void setUp() throws Exception {
    jdbcTemplate.execute(
        "TRUNCATE TABLE batch_job_execution_context, batch_step_execution_context, "
            + "batch_step_execution, batch_job_execution_params, batch_job_execution, "
            + "batch_job_instance RESTART IDENTITY CASCADE");
    new JobRepositoryTestUtils(demoJobRepository).removeJobExecutions();

    adminUserPersistenceAdapter.deleteAllInBatch();
    saveAdminUserPort.save(
        AdminUser.create(DEMO_USERNAME, passwordEncoder.encode(PASSWORD), AdminRole.DEMO));
    saveAdminUserPort.save(
        AdminUser.create(ADMIN_USERNAME, passwordEncoder.encode(PASSWORD), AdminRole.ADMIN));
    demoToken = login(DEMO_USERNAME);
    adminToken = login(ADMIN_USERNAME);
  }

  private void createExecution(JobRepository targetJobRepository, String jobName) {
    JobParameters jobParameters =
        new JobParametersBuilder()
            .addString("runId", UUID.randomUUID().toString())
            .toJobParameters();
    JobInstance jobInstance = targetJobRepository.createJobInstance(jobName, jobParameters);
    JobExecution jobExecution =
        targetJobRepository.createJobExecution(jobInstance, jobParameters, new ExecutionContext());
    jobExecution.setStatus(BatchStatus.COMPLETED);
    jobExecution.setStartTime(LocalDateTime.now());
    jobExecution.setEndTime(LocalDateTime.now());
    jobExecution.setExitStatus(ExitStatus.COMPLETED);
    targetJobRepository.update(jobExecution);
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
  @DisplayName("DEMO 계정으로 데모 배치 실행 이력을 조회하면 200과 함께 목록을 반환한다.")
  void demoAccount_canListDemoBatchExecutions() throws Exception {
    createExecution(demoJobRepository, JOB_NAME);

    mockMvc
        .perform(
            get("/api/v1/demo/batch-jobs/" + JOB_NAME + "/executions")
                .header(HttpHeaders.AUTHORIZATION, bearer(demoToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].jobName").value(JOB_NAME))
        .andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
  }

  @Test
  @DisplayName("ADMIN 계정도 데모 배치 실행 이력 조회에 여전히 접근 가능하다(회귀 없음).")
  void adminAccount_canStillListDemoBatchExecutions() throws Exception {
    createExecution(demoJobRepository, JOB_NAME);

    mockMvc
        .perform(
            get("/api/v1/demo/batch-jobs/" + JOB_NAME + "/executions")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  @DisplayName("토큰 없이 데모 배치 실행 이력을 조회하면 401을 반환한다.")
  void demoBatchExecutions_withoutToken_returns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/demo/batch-jobs/" + JOB_NAME + "/executions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("같은 jobName이라도 데모 배치 이력과 운영 배치 이력은 서로 섞이지 않는다.")
  void demoAndProductionBatchExecutions_doNotMix() throws Exception {
    createExecution(demoJobRepository, JOB_NAME);
    createExecution(jobRepository, JOB_NAME);

    mockMvc
        .perform(
            get("/api/v1/demo/batch-jobs/" + JOB_NAME + "/executions")
                .header(HttpHeaders.AUTHORIZATION, bearer(demoToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));

    mockMvc
        .perform(
            get("/api/v1/batch-jobs/" + JOB_NAME + "/executions")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
  }
}
