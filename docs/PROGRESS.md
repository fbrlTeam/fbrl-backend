# FBRL (Financial Backend Reliability Lab) 진행 상황

## 팀 구성

| 이름 | 역할 | 담당 업무 |
|---|---|---|
| 김주영 (본인) | Backend | 실시간 거래 파이프라인, Saga 오케스트레이션, Redisson 분산 락, Spring Batch 엔진 구축 |
| 김준희 | Infra / SRE | Kubernetes 클러스터, ArgoCD GitOps, Prometheus/Grafana 관측성 구축, Chaos Mesh 결함 주입 |

Chaos Mesh 결함 주입은 노션 "프로젝트 개요" 문서에 인프라(김준희) 담당으로 명시되어 있음. 백엔드는 "어떤 장애 시나리오로 무엇을 검증할지" 정의 + 장애 주입 후 애플리케이션(서킷 브레이커, 재시도 등)이 의도대로 반응하는지 검증하는 역할, 실제 CRD/클러스터 실행은 인프라와 협업.

이 프로젝트는 1인 개발이 아니라 Backend(본인)/Infra·SRE(김준희) 2인 협업 프로젝트임 — Chaos Mesh, K8s 클러스터 운영, GitOps/관측성 구축은 Infra 담당 영역이므로, 이런 영역을 "혼자 다 해야 하는지" 판단할 때는 먼저 노션 "프로젝트 개요"의 팀원 역할표를 확인할 것.

## 기술 스택

- Java 17 / Spring Boot 4.0.7 / 헥사고날 아키텍처 (Ports & Adapters)
- PostgreSQL 16 (wal_level=logical, Debezium CDC 기반)
- Redisson, Redis / Spring Batch 6.0.4 · ShedLock · Kafka · Kubernetes Lease API (client-java 27.0.0)

## ✅ 완료된 작업

### 과제 1-2: 기본 구조 + 동시성 제어

- 헥사고날 기본 구조 (Account 도메인, In/Out Port, JPA 어댑터)
- Redisson 분산 락 (`@DistributedLock` AOP, REQUIRES_NEW)
- API 멱등성 (Redis SETNX, `@CheckIdempotency`)
- 락 성능 벤치마크 (분산락 vs 비관적락 vs 낙관적락, 100 스레드)

### 과제 3: 계좌 개설 & 잔액조회 API (완료, PR 리뷰 대기)

브랜치: `feat/account-api` → `develop`

- `Account.open()` 도메인 팩토리 (초기 잔액 0원 정책, SSOT)
- `CreateAccountUseCase` / `GetAccountUseCase` (Port In)
- `AccountNumberPolicy` (SecureRandom 채번, 약 36.5bit 엔트로피)
- `AccountCreationExecutor` (REQUIRES_NEW 트랜잭션, self-invocation 회피)
- 채번 충돌 시 최대 3회 재시도 (`CreateAccountService`)
- `AccountPersistenceAdapter`: `DataIntegrityViolationException` → `DuplicateAccountNumberException` 예외 번역
- `AccountController` (POST/GET), `AccountResponse` DTO, `GlobalExceptionHandler` 409 매핑
- 단위 테스트 3종 (`CreateAccountServiceTest`, `GetAccountServiceTest`, `AccountControllerTest`)
- 전체 테스트(`./gradlew test`) 통과 확인

### 과제 4: Transactional Outbox & 감사장부(Audit Log) 패턴 (완료)

브랜치: `feat/outbox-pattern` → `develop`

- `OutboxEvent` 도메인 모델 (PENDING/SENT/FAILED 상태 전이, Rich Domain Model)
- `TransferCompletedEvent` 도메인 이벤트 (record, 계좌 정보만 최소 선택하여 정보 노출 최소화)
- `SaveOutboxEventPort` / `PayloadSerializerPort` (Port Out)
- `OutboxEventJpaEntity` / `OutboxEventJpaRepository` / `OutboxPersistenceAdapter` (예외 번역 없이 전파 → 트랜잭션 롤백 보장)
- `JacksonPayloadSerializerAdapter` (Jackson 3 `JsonMapper`, 불변 객체로 스레드 세이프)
- `TransferMoneyService`의 `@DistributedLock` → REQUIRES_NEW 트랜잭션 경계 안에 Outbox insert 통합
- `TransferMoneyServiceTest` Mock 2개 추가 (`SaveOutboxEventPort`, `PayloadSerializerPort`)
- 리팩토링: `LockComparisonService`를 `application.service` → `adapter.out.persistence`로 이동 (JPA 락 실험 도구는 인프라 계층 소속)
- 리팩토링: `AccountJpaRepository` public → package-private 전환, 관련 테스트 3종(`LockComparisonTest`, `TransferConcurrencyTest`, `IdempotencyIntegrationTest`) 패키지 정리
- 전체 테스트(`./gradlew test`) 통과 확인
- (선택, 보류) Debezium CDC + PostgreSQL 전환 — 헥사고날 구조상 인프라 어댑터만 교체하면 되므로 확장 과제로 남김

### 과제 5: Outbox Polling Publisher (완료)

브랜치: `feat/outbox-polling-publisher` → `develop`

- `OutboxEventJpaRepository`: `findByStatusOrderByCreatedAtAsc(status, Pageable)` 커스텀 쿼리 추가
- `LoadPendingOutboxEventsPort` / `EventPublisherPort` (Port Out) 신규 추가
- `PublishPendingOutboxEventsUseCase` (Port In) — `PublishResult(publishedCount, failedCount)` 요약 반환
- `PublishPendingOutboxEventsService`: 이벤트 단위 즉시 커밋(오케스트레이션 메서드엔 `@Transactional` 미부여), "Kafka 발행 → 성공 확인 후 `markAsSent()`" 순서 고정, 한 건 실패해도 나머지 계속 처리(DLQ와 동일 철학)
- `OutboxPersistenceAdapter`: `LoadPendingOutboxEventsPort` 구현 추가 (Pageable 변환은 어댑터 내부로 캡슐화)
- `OutboxPollingScheduler` (`adapter.in.scheduler`): `@Scheduled(fixedDelay)` 트리거 전용, 배치크기/주기는 `outbox.polling.*` 설정으로 외부화
- `KafkaEventPublisherAdapter`: `key=aggregateId`로 파티션 고정(같은 계좌 이벤트 순서 보장), `send().get()`으로 동기 확인 후 성공/실패 판정
- `KafkaProducerConfig`: Boot 4.0 자동생성 `KafkaTemplate<Object,Object>` 타입 불일치 문제로 `KafkaTemplate<String,String>` 빈 직접 정의
- `KafkaTopicConfig`: `NewTopic` 빈으로 `transfer-events` 토픽 파티션/복제계수 명시 생성 (auto.create 기본값에 의존 금지)
- `build.gradle`: `spring-kafka` → `spring-boot-starter-kafka(-test)` 교체 (Boot 4.0 모듈 분리로 자동설정 클래스가 starter 없이는 클래스패스에 없음)
- `docker-compose.yml`: kafka 서비스 추가 (KRaft 단일 노드, `apache/kafka:3.9.0`)
- `PublishPendingOutboxEventsServiceTest`: PENDING 없음 / 발행 성공 시 순서(InOrder) 검증 / 부분 실패 시 나머지 계속 처리 3종
- 전체 테스트(`./gradlew test`) 통과 확인
- (보류) 실제 Kafka 브로커 E2E 수동 검증(IntelliJ Kafka 플러그인으로 콘솔 확인) — 로컬 계좌 잔액 시딩 방법 정리되면 재시도 예정

**리팩토링 메모 해소 (과제 4에서 이월된 항목)**

- `AccountPersistenceAdapter.findByAccountNumber()`: `DataAccessException` → `AccountPersistenceException`(신규 도메인 예외)로 번역, `GlobalExceptionHandler`에 500 매핑 추가 (기존 임시로 쓰였던 `IllegalStateException` 제거)
- `DistributedLockAspect`: `@Order(Ordered.HIGHEST_PRECEDENCE)` 클래스 레벨 적용 확인 완료

### 과제 6: Saga 패턴 (완료 — 트랙 1 "실시간 금융 트랜잭션 & 분산 동시성 제어" 마무리)

브랜치: `feat/saga-orchestration` → `develop` (PR #16, 커밋 75c062b) → `develop` → `main` (PR #17, 사용자 직접 머지)

**도메인/개념 학습**

- 2PC의 가용성 문제 → Saga(로컬 트랜잭션 체이닝 + 보상 트랜잭션) → Choreography vs Orchestration(감사 추적성 때문에 Orchestration 채택) → Orchestrator = 영속화된 상태 머신
- `SagaStatus` 도메인 모델 (`domain.model`, `canTransitionTo()`로 상태 전이 규칙을 Switch Expression exhaustiveness로 캡슐화)
- `@Enumerated(EnumType.STRING)` vs `ORDINAL`: enum 선언 순서 변경 시 기존 저장 데이터 의미가 조용히 오염되는 문제 → STRING 채택 (`TransferSagaJpaEntity`에 적용)
- `InvalidSagaTransitionException` / `InvalidTransferAmountException` (`domain.exception`)
- `TransferSaga` 도메인 모델 (`domain.model`, `start()`/`reconstruct()` 이원화)
- 버그 발견 및 수정: `SagaStatus.canTransitionTo()`에 `STARTED -> FAILED` 전이가 누락되어, 출금 자체가 실패한 케이스(`saga.fail()`)에서 `InvalidSagaTransitionException`이 잘못 터지는 문제 확인 → 전이 규칙에 `STARTED -> FAILED` 추가하여 해결

**Account 패턴과의 정합성 맞춤 (SSOT)**

- `TransferSaga`에 기술적 PK `id`(Long, nullable) 필드 추가, `version` 타입 `long` → `Long`(nullable)로 변경 — `Account.id`/`Account.version`과 동일한 "nullable 소유 + 매퍼가 매번 완전한 엔티티를 재구성해 `save()` 단일 호출로 insert/update 통일" 컨벤션에 맞춤
- `reconstruct()` 시그니처에 `id` 파라미터 추가

**영속성 어댑터 4종 (`adapter.out.persistence`)**

- `TransferSagaJpaEntity`: `AccountEntity`와 동일하게 Money는 BigDecimal로 풀어서 저장(도메인 프레임워크 무의존 원칙 유지), `SagaStatus`는 enum 타입 그대로 필드에 두고 어댑터 계층 어노테이션(`@Enumerated`)만 부여(도메인 클래스 자체는 오염 안 됨)
- `SagaMapper`: `AccountMapper`와 동일 패턴 (`toDomain()` / `toEntity()` 단일 메서드, null 방어)
- `TransferSagaJpaRepository`: package-private JpaRepository, YAGNI 원칙에 따라 커스텀 쿼리 없이 기본 `save()`만 사용
- `SagaPersistenceAdapter`: `ObjectOptimisticLockingFailureException`(하위 타입) → `DataAccessException`(상위 타입) 순서로 catch(순서 뒤바뀌면 낙관적 락 충돌이 영원히 안 잡힘)하여 각각 `ConcurrentSagaModificationException`(409) / `SagaPersistenceException`(500)으로 번역

**참여자 어댑터 2종 (`adapter.out.participant`, 신규 패키지 — 향후 MSA 분리 시 이 안의 구현체만 HTTP/gRPC 클라이언트로 교체)**

- `WithdrawalParticipantAdapter` / `DepositParticipantAdapter`: 예외를 오케스트레이터로 절대 전파하지 않고 `Result(boolean success, String failureReason)`로 항상 수렴시킴 — 알려진 도메인 예외(`AccountNotFoundException`, `InsufficientBalanceException`)뿐 아니라 예기치 못한 `RuntimeException`까지 잡아서 `Result(false, ...)`로 변환(안 하면 실패 처리 경로가 두 갈래로 나뉘어 정합성 깨짐), 원인은 `log.error`로 서버 로그에 남김
- 계좌 하나 단위로 `@DistributedLock(key = "#accountNumber")` 적용

**예외 2종 + GlobalExceptionHandler**

- `SagaPersistenceException`(500, `AccountPersistenceException`과 동일 패턴 — 인프라 예외 메시지 비노출)
- `ConcurrentSagaModificationException`(409, "재시도 안내" 형태의 actionable 메시지로 고정 — 프레임워크 원문 메시지 비노출)

**AI 에이전트 위임 작업 검증 (이번 세션에서 확립한 워크플로)**

- package-private 접근제어자 위반 전수 수정을 AI 에이전트(Claude Code)에 위임 — 판단 기준(cleanup만 필요하면 어댑터 위임 / 락 세부구현 검증이면 패키지 이동)과 "리포지토리를 다시 public으로 되돌리지 말 것" 금지 규칙을 명시한 작업 지시서로 위임
- 에이전트가 만든 `TransferSagaJpaRepository`가 실수로 public으로 생성된 것을 git diff 리뷰로 발견 → package-private으로 재수정 지시
- git diff/status 결과가 터미널에서 반복적으로 잘려서(--stat, pager) 안 보이는 문제 발생 → 최종적으로 GitHub 웹에서 직접 커밋(75c062b)의 file tree diff를 열람하여 26개 파일 전체를 실제로 검증 완료 (untracked 신규 파일 누락 여부, 캡슐화 회귀 여부 등)
- 감사(audit) 결과 미해결 2건 모두 최종 오탐으로 확인: `LockComparisonService`는 의도된 인프라 계층 벤치마크 도구, `AccountJpaRepository`는 실제로 package-private 유지 중이었음(사용자가 직접 코드 확인)

### 과제 7: Spring Batch 6.0.4 인프라 검증 (완료 — 트랙 2 "EOD 대규모 금융 배치 플랫폼" 착수)

브랜치: `feat/spring-batch-foundation` → `develop`

- `build.gradle`: `spring-boot-starter-batch`(→ `spring-batch-core` 6.0.4 자동 포함), `spring-batch-test` 추가
- `application.yml`: `spring.batch.job.enabled: false`(파드 재시작마다 Job 중복 자동실행되는 사고 예방), `spring.batch.jdbc.initialize-schema: always`
- `EodInfraCheckJobConfig` (`adapter.in.batch`): JobRepository ↔ MariaDB 배선만 검증하는 최소 Job/Step/Tasklet (업무 로직 없음, 과제 8에서 실제 EOD 로직으로 교체 예정)
- `EodInfraCheckJobConfigTest`: `JobOperatorTestUtils.startJob()`으로 명시적 실행 → `BatchStatus.COMPLETED` 검증
- 전체 테스트(`./gradlew test`) 통과 확인
- 노션 "프로젝트 개요" 기술 스택 최신화 (Spring Boot 3.x / Spring Batch 5.x → 4.0.7 / 6.x)

### 과제 8: EOD 정산 Job 실전 구현 (완료 — Chunk-oriented, 이자 계산/마감 스냅샷)

브랜치: `feat/eod-settlement-job` → `develop`

**도메인 계층**

- `EodSnapshot` 도메인 모델(record) — 마감 스냅샷은 한 번 기록되면 절대 변경되지 않는 불변 사실이라 Account/TransferSaga(class)와 다르게 record로 설계, `totalBalance()`는 저장 필드가 아닌 계산 메서드(SSOT)
- `InterestPolicy`(record) — 단리(simple interest) 일할 이자 계산, 이자 계산 규칙을 EodSnapshot과 별도 타입으로 분리(SRP/OCP, Saga 참여자 어댑터 분리와 동일한 논리). 연이자율÷365 중간 계산은 scale 10 유지 후 최종만 원단위 반올림(오차 누적 방지)
- `InvalidInterestRateException`, `DuplicateEodSnapshotException` (`domain.exception`)
- `InterestPolicyTest` — 깨끗하게 나눠떨어지는 이자율/순환소수 반올림/0원 경계값 등 5종

**포트 & 영속성 계층**

- `LoadAllAccountsPort` / `SaveEodSnapshotPort` (Port Out) — page/size 원시값만 받고 Pageable 변환은 어댑터 내부로 캡슐화(Outbox 폴링 컨벤션과 동일), Writer가 청크 단위로 받으므로 `saveAll(List)`만 정의(YAGNI)
- `EodSnapshotJpaEntity` — `@Version` 없음(INSERT 후 UPDATE 없는 불변 레코드), `(account_number, settlement_date)` 복합 유니크 제약으로 재시작 시 중복 저장 방지, setter 없음
- `EodSnapshotMapper`, `EodSnapshotJpaRepository`(package-private, YAGNI)
- `EodSnapshotPersistenceAdapter` — `DataIntegrityViolationException` → `DuplicateEodSnapshotException` 예외 번역
- `AccountPersistenceAdapter`에 `LoadAllAccountsPort` 구현 추가 — id 기준 정렬 명시(정렬 없는 OFFSET 페이징은 페이지 간 순서 일관성 미보장)

**배치 계층 (`adapter.in.batch`)**

- `AccountItemReader`(커스텀 `ItemStreamReader`) — `RepositoryItemReader` 대신 직접 구현(`AccountJpaRepository`가 package-private이라 헥사고날 경계상 접근 불가), `ExecutionContext`에 readCount 저장해 재시작 시 페이지/skip 위치 정확히 복원
- `AccountInterestItemProcessor` — Account+InterestPolicy+settlementDate 조립만 하는 얇은 배선 코드, `@StepScope` + `@Value("#{jobParameters['settlementDate']}")`
- `EodSnapshotItemWriter` — Chunk를 `SaveEodSnapshotPort.saveAll()`에 위임
- `EodSettlementJobConfig` — `StepBuilder(name, jobRepository).chunk(1000).transactionManager(tm)...`(Spring Batch 6.0.4 GA 방식)
- `EodSettlementJobConfigTest` — 종단 간 테스트, `JobOperatorTestUtils`가 상속한 `launchJob(JobParameters)`로 실행, `BatchStatus.COMPLETED` + 실제 이자 계산값(연 3.65%, 100만원→100원) 검증
- 전체 테스트(`./gradlew test`) 통과, 빌드 성공 확인

**핵심 개념**

- Chunk-oriented Processing: 커밋 단위 = 청크 단위(재시작 시 청크 시작점부터 재개, 개별 아이템 단위 아님)
- JobParameters = Job의 정체성: JobRepository가 "Job 이름 + JobParameters" 조합으로 JobInstance를 식별 → settlementDate를 `LocalDate.now()` 대신 명시적 JobParameter로 넘겨야 재시작 시 같은 JobInstance로 인식되어 이어서 처리됨
- `@StepScope` 지연 바인딩(Late Binding): JobParameters는 Job 실행 시점에야 결정되는데 스프링 빈은 기동 시 미리 생성됨 → `@StepScope`로 빈 생성 자체를 Step 시작 시점까지 미룸

**트러블슈팅 (Spring Batch 6.0.4 GA API, 공식 문서로 3회 재확인하며 정정)**

- `ChunkOrientedStepBuilder`를 `new`로 직접 생성하는 코드는 6.0.0-M2(마일스톤) 문서 기준이었고, 실제 6.0.4 GA는 `new StepBuilder(name, jobRepository).chunk(size).transactionManager(tm)...` 방식(공식 마이그레이션 가이드로 재확인)
- `JobOperatorTestUtils extends JobLauncherTestUtils` — `startJob(JobParameters)`가 `@StepScope` 빈에 파라미터를 전달 못하는 버그(spring-batch#5216, 6.0.x, "Closed as not planned"로 미해결) → 상속받은 `launchJob(JobParameters)`(6.2+ 제거 예정 구버전 API)를 대신 사용
- `JobParameters`/`JobParametersBuilder`가 `org.springframework.batch.core.job.parameters` 패키지로 이동(6.0 패키지 재구성 목록에 추가 확인)
- `Account` 생성자가 private으로 강화됨 — `new Account(...)` 대신 `Account.create()`/`reconstruct()` 정적 팩토리 사용 필요(SSOT 정책 강화 반영)

### 과제 9: ShedLock 분산 스케줄 락 (완료 — 다중 인스턴스 중복 실행 방지)

브랜치: `feat/shedlock-scheduler` → `develop`

- `build.gradle`: `shedlock-spring:7.7.0`, `shedlock-provider-redis-spring:7.7.0`(Spring Boot 4.x 호환 계열은 7.x.x, 공식 호환성 표 기준)
- `ShedLockConfig`(`global.config`) — `@EnableScheduling` + `@EnableSchedulerLock(defaultLockAtMostFor="10m")`, `RedisConnectionFactory` 기반 `RedisLockProvider`(spring-boot-starter-data-redis가 자동 구성해준 빈 재사용 — ShedLock 공식 프로바이더 중 Redisson 전용은 없음)
- `EodSettlementScheduler`(`adapter.in.scheduler`) — `@Scheduled(cron="${eod.batch.cron:0 0 2 * * *}")` + `@SchedulerLock(lockAtLeastFor="5m")`, `JobOperator.start(Job, JobParameters)` 사용(JobLauncher는 6.0부터 deprecated)
- `JobInstanceAlreadyCompleteException`은 별도 catch하여 INFO 로그(정상 시나리오로 취급), 그 외는 ERROR
- `EodSettlementSchedulerTest` — `JobOperator`를 `@MockitoBean`으로 대체, `CountDownLatch`로 5개 스레드 동시 트리거 → `jobOperator.start()` 호출이 정확히 1회인지 검증
- 전체 테스트(`./gradlew test`) 통과, 빌드 성공 확인

**핵심 개념**

- ShedLock은 상호 배제(기다림)가 아니라 선점 후 스킵 패턴 — "이미 실행 중이면 기다리지 않고 그냥 건너뜀"(README: "execution on other nodes does not wait, it is simply skipped"). Redisson 분산 락(상호 배제, 순서대로 다 처리)과 근본적으로 다른 용도
- `lockAtMostFor`: 인스턴스가 락을 쥔 채로 죽어도 무조건 이 시간 후 락이 풀리는 안전장치, 정상 실행 시간보다 넉넉히 길게 설정
- `lockAtLeastFor`: Job이 실제로는 순식간에 끝나더라도, 최소 이 시간 동안은 락을 강제로 유지시켜 "찰나의 틈"으로 다른 인스턴스가 끼어드는 것을 방지하는 안전장치. 다만 이 값이 클수록 같은 작업을 짧은 간격으로 재실행/재트리거할 때 "이미 락이 걸려있다"고 판단되는 구간도 함께 길어짐(→ 과제 11 트러블슈팅 참고)
- `ShedLockConfig`(인프라 설정, 앱 전체 1개)와 `@SchedulerLock`(개별 작업 설정, 작업마다 1개)은 별개 — 스케줄러가 늘어나도 LockProvider는 안 건드림

**트러블슈팅**

- `JobOperator`(비-deprecated) vs `JobLauncher`(6.0부터 deprecated, 6.2+ 제거 예정) — 프로덕션 코드는 `JobOperator.start(Job, JobParameters)` 사용, 관련 예외(`JobInstanceAlreadyCompleteException` 등)는 `org.springframework.batch.core.launch` 패키지(JobOperator와 동일 패키지라 별도 import 불필요)
- Redisson을 쓰고 있어도 ShedLock 공식 프로바이더 중 Redisson 전용은 없음(README 확인) — spring-boot-starter-data-redis가 자동 구성해주는 `RedisConnectionFactory` 기반 `shedlock-provider-redis-spring`을 대신 사용
- Mockito 가짜 객체 메서드 호출 시에도 원본 메서드의 checked exception 선언이 그대로 적용됨 — `verify(mock).checkedMethod()` 호출부도 그 예외를 처리해야 함(테스트 메서드에 `throws Exception`으로 포괄 선언)

### 과제 10: Kubernetes Lease API 기반 리더 선출 연동 (완료 — 트랙 2 "EOD 대규모 금융 배치 플랫폼" 마무리)

브랜치: `feat/k8s-lease-election` → `develop`

**선행 학습 세션 (착수 전, 코드 작성 없이 개념만 정리)**

- API Server = 클러스터의 단일 진입점 — 모든 요청(Lease 조회/갱신 포함)이 반드시 거쳐감. 인증(401, "누구냐") → 인가(403, "권한 있냐") 순서로 관문 통과. `AccountController`가 클라이언트-DB 사이의 유일한 관문 역할을 하는 것과 동일한 논리.
- ServiceAccount/RBAC = Pod의 신원 + 최소 권한 — ServiceAccount는 Pod용 신원(JWT의 sub와 유사). Role은 "무엇을 할 수 있는가"(get/create/update만 명시, delete나 `*`는 배제 — `LoadPendingOutboxEventsPort`가 필요한 범위만 노출한 것과 동일 사고방식). RoleBinding은 ServiceAccount ↔ Role 연결.
- Lease 리더 선출 = 낙관적 락 기반 경합 — holderIdentity + renewTime + resourceVersion(JPA `@Version`과 동일 원리)
- Split-Brain 위험 및 EOD 정산 Job에 대입한 실제 리스크(중복 수행 시 자원 낭비 2배, DB 유니크 제약은 저장 시점의 최후 방어선일 뿐 그 전 읽기/계산 낭비는 못 막음)
- 로컬 검증 환경: minikube/kind/Testcontainers K3s 비교 후 kind로 결정(리소스 가볍고, `kubectl get lease`로 resourceVersion 변화를 눈으로 직접 관찰하는 것을 우선순위로 둠)

**수동 실습: resourceVersion 낙관적 락 직접 재현**

- kind 클러스터(`kind create cluster --name fbrl-lease-lab`)에 Lease 오브젝트를 `kubectl create`로 직접 생성
- 동일 resourceVersion을 가진 두 YAML 사본을 만들어 순서대로 `kubectl replace` → 먼저 적용한 쪽은 성공(resourceVersion 자동 증가), 나중 쪽은 `409 Conflict: the object has been modified` 확인 — DB `@Version` 충돌과 동일한 메커니즘을 API Server 레벨에서 직접 재현

**RBAC (`k8s/rbac/`)**

- ServiceAccount(`eod-settlement-leader-election`) — Pod용 신원
- Role — `apiGroups: coordination.k8s.io`, `resources: leases`, `verbs: get/list/watch/create/update`만 부여(delete·`*` 배제, 최소 권한 원칙)
- RoleBinding — 위 ServiceAccount ↔ Role 연결. `roleRef.apiGroup`은 Lease가 아니라 "Role이라는 오브젝트 자체"의 소속 그룹(`rbac.authorization.k8s.io`)이라는 점에 주의(다루는 대상의 소속과 자기 자신의 소속은 별개)
- `kubectl auth can-i create/delete leases --as=system:serviceaccount:...`로 최종 권한 시뮬레이션 검증 완료

**애플리케이션 계층**

- `build.gradle`: `io.kubernetes:client-java:27.0.0`, `client-java-extended:27.0.0` 추가(LeaderElector/LeaseLock은 extended 모듈 소속, starter 없이 순수 라이브러리라 자동설정 없음 — 배선은 전부 수동)
- `LeaderElectionPort`(`application.port.out`) — Runnable(onStartLeading/onStopLeading) + Consumer\<String\>(onNewLeader) 콜백 시그니처만 노출, K8s Java Client 타입은 시그니처에 절대 등장하지 않음(도메인 순수성 유지, `LoadAccountPort`에 EntityManager가 없는 것과 동일 원칙)
- `LeaderElectionProperties`(`global.config`, record + `@ConfigurationProperties`) — enabled/namespace/leaseName/leaseDurationSeconds/renewDeadlineSeconds/retryPeriodSeconds. `@ConfigurationPropertiesScan`을 메인 클래스에 추가(향후 설정 클래스가 늘어나도 메인 클래스를 계속 안 건드려도 되도록)
- `KubernetesApiClientConfig`(`global.config`) — `ClientBuilder.cluster()`(클러스터 내부 ServiceAccount 토큰 인증)를 우선 시도, 실패 시 `Config.defaultClient()`(로컬 kubeconfig)로 폴백하여 배포 환경/로컬 개발 환경 모두 동일 코드로 동작
- `KubernetesLeaderElectionAdapter`(`adapter.out.kubernetes`) — `LeaderElectionPort` 구현체. `LeaseLock(namespace, leaseName, identity, apiClient)` + `LeaderElectionConfig(lock, leaseDuration, renewDeadline, retryPeriod)`로 LeaderElector 구성. `LeaderElector.run()`은 블로킹 호출이므로 daemon `ExecutorService`(단일 스레드)에서 실행(non-daemon으로 두면 리더 선출 루프가 절대 스스로 안 끝나 graceful shutdown이 SIGKILL로 강제 종료될 위험). identity는 `POD_NAME` 환경변수(K8s Downward API) 우선, 없으면 hostname으로 폴백
- `@PreDestroy`로 `leaderElector.close()`(AutoCloseable) 호출 — graceful shutdown 시 리더 자격을 능동적으로 반납하여, leaseDuration(15초)을 다 기다리지 않고 팔로워가 더 빨리 리더를 이어받도록 함
- 두 `@Configuration`/`@Component`(`KubernetesApiClientConfig`, `KubernetesLeaderElectionAdapter`) 모두에 `@ConditionalOnProperty(k8s.leader-election.enabled=true)` 적용 — kind/kubeconfig가 없는 환경(CI, 다른 개발자 PC)에서 기존 `@SpringBootTest` 전체가 ApplicationContext 로딩 실패로 깨지는 것을 방지(기본값 false)

**수동 검증 (kind 클러스터 대상)**

- `kubectl get lease -o yaml -w`로 실시간 관찰하며 애플리케이션 기동
- 리더 획득: holderIdentity가 애플리케이션 hostname으로 설정됨, 콘솔에 `onStartLeading` 로그 확인
- 지속 갱신: resourceVersion이 retryPeriodSeconds(2초) 간격으로 계속 증가
- Graceful shutdown: Ctrl+C 시 `@PreDestroy` 로그 확인 및 resourceVersion 갱신 즉시 중단 확인
- 검증에 사용한 임시 ApplicationRunner(`LeaderElectionVerificationRunner`)는 검증 완료 후 삭제(재사용되지 않는 코드는 남기지 않음, YAGNI)

**핵심 개념**

- resourceVersion은 Lease뿐 아니라 모든 K8s 오브젝트의 공통 메타데이터(`metadata.resourceVersion`) — Service 전용 개념이 아니라 오브젝트 전체에 걸친 낙관적 락 메커니즘
- leaseDuration(외부/팔로워 관점의 만료 판단 기준) vs renewDeadline(리더 본인의 자기 검열 기준, 항상 leaseDuration보다 짧게 잡아 GC pause 등으로 갱신 실패 시 API가 강제로 뺏기 전에 스스로 물러남) — 이 여유 구간이 없으면 리더가 자신의 갱신 실패를 스스로 의심할 기준이 없어져 Split-Brain 겹침 구간이 길어짐
- ShedLock(즉시 skip, 상호 배제 아님) / Redisson(pub·sub 기반 블로킹 대기) / K8s Lease 리더 선출(지속 갱신 기반 단일 리더 고정) — 프로젝트 기획 문서 기준으로 이 프로젝트는 세 가지 분산 스케줄링 대안을 나란히 구현해 비교 실험하는 것이 목적이며, K8s 리더 선출이 ShedLock을 대체하는 관계가 아님(둘 다 유지)
- LeaderElector는 AutoCloseable — CancellationToken 등을 직접 구현할 필요 없이 `close()` 한 줄로 정리 가능

**트러블슈팅**

- `ClientBuilder.cluster()`의 실패는 `IOException`이 아니라 `IllegalStateException`(내부적으로 `NumberFormatException` 래핑) — 로컬 환경엔 `KUBERNETES_SERVICE_HOST`/`PORT` 환경변수가 없어 발생. catch 타입을 `IOException`에서 `Exception`으로 넓혀야 로컬 kubeconfig 폴백이 실제로 동작함(라이브러리 내부 구현이 어떤 unchecked exception을 던질지는 실제로 돌려보기 전엔 확신할 수 없음)
- 손으로 `kubectl create`한 불완전한 Lease(leaseTransitions 필드 누락 상태)를 LeaderElector가 읽으려 하면 `NullPointerException`(`getLeaseTransitions().intValue()`의 auto-unboxing) — `kubectl delete` 후 LeaderElector가 처음부터 새로 생성하게 하면 해결(라이브러리가 스스로 만드는 Lease는 모든 필드가 채워져 있음)
- `LeaseLock` 생성자 시그니처: `(namespace, name, identity, ApiClient)` / `LeaderElectionConfig` 생성자 시그니처: `(lock, leaseDuration, renewDeadline, retryPeriod)` — client-java-extended 27.0.0 기준(버전마다 시그니처가 다를 수 있어 공식 소스/javadoc으로 재확인 필요)
- `@Bean` 메서드에서 예외가 나면 그 빈 하나만 실패하는 게 아니라 ApplicationContext 전체 로딩이 실패 — K8s 관련 설정처럼 "항상 뜬다고 보장 못 하는 외부 인프라"에 의존하는 빈은 반드시 `@ConditionalOnProperty` 등으로 기본 비활성화해 무관한 기존 테스트까지 연쇄로 깨지지 않게 방어할 것

### 과제 11: Kafka Consumer Non-blocking Retry Topic & DLT (완료 — 트랙 3 "장애 복구 & 카오스 엔지니어링" 착수)

브랜치: `feat/kafka-dlq-retry-topic` → `develop` (PR 예정)

**개념 학습**

- Non-blocking Retry Topic: 실패한 이벤트를 별도 토픽으로 위임하고 메인 컨슈머는 즉시 다음 이벤트로 넘어가는 패턴 — "같은 계좌 내 이벤트 순서"를 일부러 희생하고 "다른 계좌들의 처리량"을 지키는 트레이드오프임을 확인(blocking retry 시 무관한 계좌 이벤트까지 전부 발이 묶이는 Consumer Lag 폭증 문제 방지)
- DLQ(범용 메시징 용어) vs DLT(Kafka 전용 용어, Dead Letter Topic) 구분 — Kafka는 큐가 아닌 토픽 기반이라 Spring Kafka API 전체가 DLT로 통일
- 파티션 수 일치의 의미: 재시도/DLT 토픽 파티션 수를 원본과 동일하게 맞추면 key(계좌번호) 보존과 결합되어 동일 key가 항상 동일 파티션으로 해시됨(`hash(key) % 파티션수`) → 특별한 설정이 아니라 자연스러운 결과물이며, 장애 시 복구 처리량을 원본과 동등하게 유지하는 목적

**포트 계층 (ISP 적용)**

- `PayloadDeserializerPort`(`application.port.out`) 신규 — 기존 `PayloadSerializerPort`(직렬화 전용)와 별도 인터페이스로 분리(클라이언트별 필요 메서드만 노출, ISP), 구현체는 `JacksonPayloadSerializerAdapter` 하나로 통합(ISP는 인터페이스 표면의 문제이지 구현체 개수의 문제가 아님)
- `ProcessTransferEventUseCase`(`application.port.in`) 신규 — `TransferCompletedEvent`를 그대로 파라미터로 받음, 1:1 Command 래핑 생략(이미 번역이 끝난 순수 도메인 타입을 또 감싸는 것은 Premature Abstraction)

**도메인 예외 계층**

- `NonRetryableEventProcessingException`(`domain.exception`, 추상 상위 타입) 신규 — "재시도해도 결과가 달라지지 않는 결정론적 실패"의 공통 상위 타입으로 설계, 향후 새 결정론적 실패 예외가 생겨도 이 타입만 상속하면 `KafkaRetryTopicConfig` 재수정 없이 자동으로 재시도 제외 대상에 포함됨(OCP)
- `PayloadDeserializationException`이 위 상위 타입을 상속하도록 구성, cause(원인 예외) 보존 필수화

**어댑터 계층**

- `JacksonPayloadSerializerAdapter` — `PayloadSerializerPort` + `PayloadDeserializerPort` 동시 구현, `deserialize()`에서 Jackson 3의 unchecked `JacksonException`을 의도적으로 catch하여 `PayloadDeserializationException`으로 번역(인프라 예외 노출 금지 원칙 적용)
- `KafkaRetryTopicConfig`(`adapter.in.kafka`) — maxAttempts(4)(최초 1회+재시도 3회) + 지수 백오프(1s→2s→4s..., 최대 30s), `notRetryOn(NonRetryableEventProcessingException.class)` + `traversingCauses()`, `includeTopic("transfer-events")`로 적용 대상 명시, `autoCreateTopics(true, 3, 1)`로 재시도/DLT 토픽까지 브로커 auto-create에 의존하지 않고 명시적 생성(기존 `KafkaTopicConfig` 원칙의 확장)
- `TransferEventConsumer`(`adapter.in.kafka`) — `@KafkaListener`에서 역직렬화 실패 시 예외를 절대 삼키지 않고 그대로 전파(재시도/DLT 판단은 `RetryTopicConfiguration`에 위임, 관심사 분리), `@DltHandler`는 실패 없는 로깅만 수행(최후 보루이므로 여기서 또 실패하면 메시지 완전 유실)
- `ProcessTransferEventService`(`application.service`) — 현재는 로깅 수준 최소 구현(배선 검증 단계, 실제 알림/감사로그 로직은 후속 과제)

**테스트**

- `JacksonPayloadSerializerAdapterTest` — 직렬화↔역직렬화 라운드트립, 깨진 JSON/스키마 불일치 시 `PayloadDeserializationException` + cause 보존 검증
- `TransferEventConsumerTest` — 정상 처리 시 UseCase 호출 검증, 역직렬화 실패 시 예외가 삼켜지지 않고 그대로 전파되는지 + 부작용(UseCase 미호출) 검증
- 전체 테스트(`./gradlew test`) 통과 확인
- (보류) 실제 Kafka 브로커로 재시도 토픽 → DLT 라우팅까지 흘러가는 통합 테스트는 범위를 분리하여 다음 세션에서 진행

**부수 발견 및 별도 수정: ShedLock 테스트 격리 결함 (과제 9 후속)**

브랜치: `fix/shedlock-test-isolation` → `develop` (별도 PR, Kafka 작업과 분리 — Atomic PR 원칙)

- 오늘 작업 중 `./gradlew test`에서 `EodSettlementSchedulerTest`가 `WantedButNotInvoked`(`jobOperator.start()` 0회 호출)로 실패하는 것을 발견
- 원인 규명: `EodSettlementScheduler`의 `@SchedulerLock(lockAtLeastFor = "5m")`로 Job이 끝나도 Redis 락(`job-lock:fbrl-backend:eodSettlementJob`)이 최소 5분간 유지됨 — 테스트가 락 키를 정리하지 않아 5분 내 재실행 시 5개 스레드 전부가 "이미 걸린 락"을 만나 아무도 실행되지 못함(ShedLock 자체는 의도대로 정상 동작, 테스트 격리 미흡이 원인)
- 수정: `EodSettlementSchedulerTest`에 `@BeforeEach`로 락 키 삭제 로직 추가(`@AfterEach`가 아닌 `@BeforeEach`를 택한 이유: 이전 실행이 비정상 종료됐을 때도 다음 실행이 무조건 깨끗한 상태에서 시작하도록 보장하기 위함 — 기존 `deleteAllInBatch()` 컨벤션과 동일한 방어적 설계 원칙)
- AI 에이전트(Claude Code)에게 진단 및 1차 수정 위임 → `@AfterEach`로 작성된 초안을 지침 컨벤션 근거로 반려하고 `@BeforeEach`로 재작성 요청 → 연속 재실행 검증 + 전체 테스트 스위트 통과 확인
- 세션 중 두 작업의 브랜치가 뒤섞이는 사고 발생(Kafka WIP가 fix 브랜치 워킹 디렉토리에 얹힘) → `git log`/`git status --short`/`git diff`로 커밋 경계 및 파일별 변경 내용을 직접 검증 후 `git stash`로 안전하게 분리 이동

### 과제 12: Resilience4j 서킷 브레이커 (완료 — 트랙 3 "장애 복구 & 카오스 엔지니어링" 두 번째 과제)

브랜치: `feat/resilience4j-circuit-breaker` → `develop` (PR 예정)

**개념 학습**

- 서킷 브레이커 3상태(CLOSED/OPEN/HALF_OPEN) — Kafka 발행은 post-commit 비동기 경로(`OutboxPollingScheduler`에서 호출)라 DB 호출과 달리 롤백 안전망이 없고, 실패해도 되돌릴 트랜잭션 자체가 없다는 점이 우선순위 판단 근거
- Retry Topic(과제 11)과의 역할 분리: Retry는 "이벤트 단위 재시도", 서킷 브레이커는 "Kafka 자체 생사 판단 후 시도 자체를 차단" — 배타적이지 않고 함께 사용
- 실패 판정 방식 두 갈래: 예외 기반(`recordExceptions`) vs 반환값 기반(`recordResult` 커스텀 Predicate) — 어댑터가 예외를 던지는 컨벤션이면 전자만으로 충분, boolean 등으로 성공/실패를 번역해 반환하는 컨벤션이면 후자로 보강 필요(우리 프로젝트는 이미 예외를 던지는 컨벤션이라 전자만 사용)

**의존성/설정**

- `resilience4j-spring-boot4:2.4.0` 버전 명시 고정 (BOM 미반영 버그, resilience4j/resilience4j#2427 아직 open)
- `spring-boot-starter-aop` → `spring-boot-starter-aspectj`로 아티팩트명 변경 확인 후 반영 (Boot 4.0 리네임, spring-projects/spring-boot#42948)
- `application.yml`: `kafkaEventPublisher` 인스턴스 — COUNT_BASED, slidingWindowSize 10, minimumNumberOfCalls 10, failureRateThreshold 50%, waitDurationInOpenState 30s, permittedNumberOfCallsInHalfOpenState 5, automaticTransitionFromOpenToHalfOpenEnabled true

**어댑터 계층**

- `KafkaEventPublisherAdapter#publish()`에 `@CircuitBreaker(name="kafkaEventPublisher", fallbackMethod="publishFallback")` 적용
- 기존 "예외를 `EventPublishException`으로 번역해 던지는" 컨벤션 그대로 유지 — fallback도 `CallNotPermittedException` 포함 모든 실패를 `EventPublishException`으로 재변환해 `PublishPendingOutboxEventsService`의 "실패 시 `markAsSent()` 호출 안 함" 계약 보존
- fallback 메서드는 실패를 무마시키는 곳이 아님: 예외를 삼키고 조용히 리턴하면 호출부가 성공으로 오인해 `markAsSent()`를 잘못 호출할 위험 → 항상 `EventPublishException`을 재던짐

**리팩토링**

- `EventPublishException`을 `adapter.out.messaging` → `domain.exception`으로 이동 (포트 대칭성 위반 + application 계층이 adapter 패키지를 import해야 하는 의존성 역전 문제 발견 후 수정)

**테스트**

- `KafkaEventPublisherAdapterCircuitBreakerTest` — `@SpringBootTest` + `@MockitoBean(KafkaTemplate)` 조합으로 실제 AOP 프록시를 통과시켜 검증 (`new`로 직접 생성 시 self-invocation과 동일한 이유로 서킷 브레이커가 전혀 개입하지 않는 함정 확인 — `@CircuitBreaker`도 결국 Spring AOP 프록시 기반)
- 실패 10회 누적 시 OPEN 전환 검증
- OPEN 상태에서 `kafkaTemplate.send()` 자체가 호출되지 않음(Fail Fast) 검증
- 전체 테스트(`./gradlew test`) 통과 확인

**트러블슈팅**

- Boot 4.0: `spring-boot-starter-aop`가 `spring-boot-starter-aspectj`로 리네임됨(공식 이슈 #42948). 기존 이름으로 의존성을 추가하면 "버전을 찾을 수 없음" 형태의 에러가 나서 마치 버전 문제처럼 보이지만 실제로는 그 이름의 아티팩트 자체가 더 이상 없는 것 — Kafka starter, webmvc-test 패키지 이동과 동일 계열의 함정
- Boot 4.0: `@MockBean`/`@SpyBean` 완전 제거(3.4부터 deprecated, 4.0에서 삭제), `org.springframework.test.context.bean.override.mockito.MockitoBean`/`MockitoSpyBean`으로 교체 필요

### 과제 13: PostgreSQL 전환(MariaDB → PostgreSQL) (완료)

브랜치: `feat/postgresql-migration` → `develop` (PR #27)

- Debezium CDC 도입(과제 14)을 위한 선행 작업으로 MariaDB에서 PostgreSQL(`wal_level=logical`)로 전환.
- `build.gradle`: MariaDB 드라이버 → `org.postgresql:postgresql` JDBC 드라이버 교체.
- `docker-compose.yml`: `mariadb` 서비스 → `postgres`(`wal_level=logical` 설정 포함) 서비스로 교체.
- `application.yaml`: `spring.datasource.url`/`driver-class-name`/`spring.jpa.database-platform`을 PostgreSQL 기준으로 변경.
- AUTO_INCREMENT/IDENTITY 채번 전략, 테이블/컬럼 네이밍, 기존 JPA 락 사용 방식(`@Version`, `@Lock`)이 두 DB에서 동일하게 동작함을 확인 — 도메인 계층은 변경 없음(헥사고날 구조상 인프라 어댑터 교체만으로 DB 전환이 끝남).
- 전체 테스트(`./gradlew test`) 통과 확인.

### 과제 14: Outbox Polling → Debezium CDC 전환 (완료)

브랜치: `feat/outbox-cdc-debezium` → `develop` (PR #28)

**배경**

- 과제 5에서 구현한 Outbox Polling 방식(`OutboxPollingScheduler`가 주기적으로 PENDING 이벤트를 조회해 Kafka로 발행)은 폴링 주기만큼 지연이 발생하고 폴링 자체가 부하로 작용. PostgreSQL(과제 13)의 논리적 복제(WAL)를 Kafka Connect + Debezium Outbox Event Router로 구독하면 폴링 없이 더 실시간에 가까운 발행이 가능.

**구현 내용**

- 발행 주체가 애플리케이션에서 인프라(Kafka Connect)로 완전히 이전되면서, `OutboxPollingScheduler`, `PublishPendingOutboxEventsUseCase`/`PublishPendingOutboxEventsService`, `LoadPendingOutboxEventsPort`, `EventPublisherPort`/`KafkaEventPublisherAdapter`, `EventPublishException`, 그리고 이 경로를 보호하던 Resilience4j 서킷 브레이커 설정(과제 12)까지 전부 삭제 — 더 이상 존재 이유가 없는 컴포넌트들.
- `OutboxEvent`(`domain.model`)는 발행 확인 상태(`Status` enum, `markAsSent()`/`markAsFailed()`)를 제거하고 순수 append-only 로그로 전환.
- `outbox_event.payload` 컬럼이 `@Lob` 매핑 시 PostgreSQL에서 `oid`(Large Object) 타입으로 생성되어 논리적 복제로 본문 캡처가 안 되는 문제를 커넥터를 직접 붙여 검증하다 발견 — `@Column(columnDefinition = "text")`로 변경해 해결.
- Debezium Outbox Event Router(`debezium/outbox-connector.json`)는 `route.by.field`/`table.field.event.*` 설정으로 `aggregate_type`/`aggregate_id`/`event_type`/`payload` 컬럼명을 그대로 매핑, `route.topic.replacement`를 고정값 `transfer-events`로 오버라이드해 기존 `TransferEventConsumer`/Retry Topic 설정(과제 11)은 전혀 건드리지 않음.
- `table.field.event.timestamp`는 `created_at`이 `timestamptz`(Debezium 표현상 STRING)라 이 필드가 요구하는 INT64와 맞지 않아 커넥터가 즉시 실패하는 것을 확인 — 미사용으로 두고 Debezium이 소스 커밋 시각을 자동 사용하도록 함.
- 커넥터 등록 후 실제 INSERT → `transfer-events` 토픽 수신까지 로컬에서 직접 검증 완료.
- 전체 테스트(`./gradlew test`) 통과 확인.

### 과제 15: Outbox 해시체인 기반 불변 감사로그 (완료)

브랜치: `feat/hash-chained-audit-log` → `main`(PR #30) → `develop`(PR #34, `chore/merge-main-hash-chain-into-develop`)

**배경**

- `OutboxEvent`는 과제 14에서 append-only 로그로 전환됐지만, "누구도 사후에 내용을 조용히 고칠 수 없다"는 보장까지는 없었음. 감사로그로서 신뢰받으려면 항목 하나라도 변조되면 반드시 감지할 수 있어야 함.

**동시성 제어 설계**

- 여러 트랜잭션이 동시에 `OutboxEvent`를 저장할 수 있는데("직전 해시 조회 → 다음 해시 계산" 구간에 직렬화가 없으면 체인이 갈라지거나 두 항목이 같은 `previousHash`를 참조하며 동시에 커밋될 위험), `outbox_chain_tail` 단일 행 테이블을 같은 REQUIRES_NEW 트랜잭션 안에서 `SELECT ... FOR UPDATE`로 잠그는 방식 채택 — Redisson 분산 락(Redis 장애 시 감사로그 기록 자체가 막힘) 대신 DB 트랜잭션 하나로 완결. 최초 기동 시 tail 행 부트스트랩 경합은 `INSERT ... ON CONFLICT DO NOTHING`으로 제거.

**구현 내용**

- `OutboxEvent`(`domain.model`)에 `previousHash`/`entryHash` 필드 추가. 해시는 Jackson 직렬화 대신 `(aggregateType, aggregateId, eventType, payload, createdAt.toString(), previousHash)`를 `"|"`로 명시적으로 이어붙여 SHA-256으로 계산(`recomputeEntryHash()`) — 라이브러리 버전에 따라 직렬화 포맷이 달라지면 검증 재현성이 깨지기 때문. `id`는 DB IDENTITY라 해시 계산 시점(insert 이전)엔 알 수 없어 해시 입력에서 의도적으로 제외.
- 제네시스(체인 첫 항목)의 `previousHash`는 `null` 대신 64자리 `"0"` 문자열 상수(`OutboxEvent.GENESIS_PREVIOUS_HASH`)로 표현 — DB 컬럼을 NOT NULL로 유지하고 "null이면 특별 취급"하는 분기를 없애기 위함.
- `OutboxChainTailJpaEntity`/`OutboxChainTailJpaRepository`(신규) — `OutboxPersistenceAdapter.save()`가 저장 시 tail을 `SELECT ... FOR UPDATE`로 잠그고 `previousHash`를 확정한 뒤 insert, 커밋 시점에 tail 갱신.
- `VerifyAuditChainUseCase`/`VerifyAuditChainService`(`application`)와 `GET /api/v1/audit/verify`(`AuditController`, 신규) 추가 — 전체 체인을 id 오름차순으로 순회하며 `previousHash` 연결과 `entryHash` 재계산 일치 여부를 검증, 불일치 시 끊어진 `id`와 사유 반환.
- Debezium 커넥터(과제 14)는 변경 없음 — 매핑 대상 컬럼이 그대로라 신규 컬럼(`previous_hash`, `entry_hash`)은 CDC 라우팅에 영향 없음.

**테스트**

- `OutboxChainConcurrencyTest` — 서로 다른 계좌쌍 50건 동시 송금(계좌별 Redisson 락으로는 서로 경합하지 않도록 의도적으로 분리) 시 체인이 갈라지지 않고 정확히 이어짐을 검증(entryHash/previousHash 각 50개 모두 유일).
- `VerifyAuditChainServiceTest`, `OutboxEventTest` 추가.
- 실제로 앱을 띄워 송금 2건 후 검증 API가 `valid=true`를 반환하는 것과, DB에서 직접 `payload`를 변조한 뒤 `entryHash` 불일치로 정확한 `id`에서 감지되는 것까지 직접 확인.

**트러블슈팅**

- `main` 브랜치(이 작업)와 `develop` 브랜치(과제 16, 복식부기 원장)가 병행 개발되며 서로 다른 시점에 각각 병합됨 — 이후 `develop`과 `main`을 병합하는 과정(`chore/merge-main-hash-chain-into-develop`)에서 `Account.create(String, Money)` 2-args 시그니처가 과제 16 작업으로 제거된 것을, 텍스트 충돌 없이 통과한 `OutboxChainConcurrencyTest`가 컴파일 실패로 뒤늦게 드러냄 — `Account.create(accountNumber)` + `LedgerEntry` 시딩으로 수정.
- 과제 16(복식부기 원장) 작업 중, 로컬 Postgres에 (당시 develop에는 아직 merge되지 않은 상태였던) 이 브랜치의 스키마 잔재(`outbox_event.entry_hash`/`previous_hash` NOT NULL, `outbox_chain_tail` 테이블)가 남아있어 100스레드 동시성 테스트가 매번 실패하는 원인이 된 적 있음(순수 로컬 스키마 drift, 상세는 과제 16 참고).

### 과제 16: 복식부기 원장(Double-entry Ledger) 도입 (완료)

브랜치: `feat/double-entry-ledger` → `develop`

**배경**

- 기존 `Account.balance`는 이체마다 직접 +/- 되는 저장 필드(read-modify-write)였음 — 이를 "해당 계좌 `LedgerEntry`의 합"으로 계산되는 파생값(SSOT)으로 전환. 목표는 append-only INSERT만으로 잔액 정합성을 자체 검증 가능하게 만드는 것(대차평형/trial balance 원칙).
- 코드 작성 전에 4가지 설계 결정을 옵션(a/b/c) + 트레이드오프로 먼저 보고하고 사용자 확정을 받은 뒤 구현 — 이번 세션에서 처음으로 "설계 승인 → 구현" 2단계 워크플로를 적용.

**설계 결정**

1. **잔액 계산 시점 — 앵커+델타 하이브리드**: 가장 최근 `EodSnapshot.totalBalance()`를 앵커로 삼고, 그 이후 발생한 `LedgerEntry` 합을 델타로 더함(`AccountBalanceCalculator`). 매일 EOD가 지날 때마다 델타 구간이 리셋되어 스캔 범위가 항상 "최대 하루치"로 bounded됨 — 순수 실시간 전체 합산(스캔 비용 무제한 증가) vs 캐시 필드(SSOT 이원화) 사이 절충안으로 채택.
2. **동시성 제어 — 기존 Redisson 분산 락 유지, DB CHECK 제약 미도입**: 잔액이 LedgerEntry SUM 파생값이 되면 "잔액 음수 금지"는 집계 제약이라 PostgreSQL `CHECK`로 직접 표현 불가(다른 행을 참조/집계 불가) — 트리거로 우회하면 매 이체마다 전체 스캔이 재발해 애초 목표(append-only)와 역행하므로 기각. 락 보유 구간 안에서 애플리케이션 레벨로 계산한 잔액이 요청 금액 이상인지 확인 후 커밋.
3. **마이그레이션 — opening-balance 시딩 후 balance 필드 제거, 순서 강제**: `OpeningBalanceMigrationService`가 기존 balance 값을 보존하는 `OPENING_BALANCE` `LedgerEntry` 쌍(상대계정: `SystemAccounts.OPENING_BALANCE_SOURCE`, accounts 테이블에 실제 row 없는 sentinel)으로 시딩. `LedgerEntry.transferPair`를 재사용해 상대계정 없는 단일 다리 시딩(대차평형 깨짐)을 원천적으로 배제 — 지시서 문구("1건씩")보다 대차평형 원칙을 우선함을 명시적으로 근거 들어 반영.
4. **대차평형 검증 — 거래 단위 즉시(구조적 강제) + 시스템 전체는 EOD 배치**: `LedgerEntry.transferPair(from, to, amount, txId, at)`가 두 다리에 동일한 `amount` 인스턴스를 재사용하는 시그니처라서 합이 0이 아닌 쌍 자체를 만들 수 없음(validate-after가 아닌 invariant-by-construction). 시스템 전체 SUM=0 검증은 `VerifyTrialBalanceUseCase`/`VerifyTrialBalanceService`로 구현해 `EodSettlementJobConfig`의 `eodSettlementJob`에 `trialBalanceVerificationStep`으로 추가 — 불일치 시 `TrialBalanceViolationException`으로 배치 스텝이 실패해 알림.

**추가 반영 (설계 확정 후 리뷰에서 지적됨)**

- `SystemAccounts.OPENING_BALANCE_SOURCE`를 `domain.model`의 SSOT 상수로 추출(`OpeningBalanceMigrationService`/`TransferMoneyService` 공용 참조). 처음엔 accounts 테이블에 해당 row가 "없어서" 조회 실패로 우연히 이체가 막히는 구조였는데, 나중에 어떤 경로로든 이 계좌번호로 실제 Account row가 생기면 방어가 조용히 사라지는 문제가 있어 `TransferMoneyService`에 명시적 가드 클로즈(`assertNotReservedAccount`)를 추가하고 `ReservedAccountException`(`domain.exception`)을 던지도록 변경.

**리팩토링**

- `Account.balance` 저장 필드/`deposit()`/`withdraw()` 제거, 순수 함수 `calculateBalance(anchorBalance, entriesSinceAnchor)`로 전환(포트 의존 없이 도메인 순수성 유지, 앵커·델타 조회는 application 계층의 `AccountBalanceCalculator`가 담당).
- `LockComparisonService`(과제 1-2, 비관적/낙관적/Redisson 3종 락 벤치마크 — 감사에서도 "의도된 인프라 벤치마크 도구"로 재확인된 이력)는 `balance` 컬럼이 사라지면서 전제가 깨져, 락 비교 대상을 신규 `AccountLockAnchorJpaEntity`(`@Version` 보유, 도메인 모델 매핑 없이 인프라 계층에서만 쓰는 전용 엔티티)로 교체해 벤치마크 목적 자체는 그대로 보존. 실제 이체 경로(`TransferMoneyService`, `Withdrawal·DepositParticipantAdapter`)는 여전히 Redisson 분산 락만 사용.

**트러블슈팅**

- 이 프로젝트는 Flyway/Liquibase 없이 `ddl-auto: update`만 사용 — `balance` 필드를 Java 엔티티에서 지워도 Hibernate가 기존 물리 컬럼을 DROP하지 않아, 로컬 Postgres에 남아있던 `NOT NULL balance` 컬럼 때문에 모든 계좌 INSERT가 깨짐(`DataIntegrityViolationException` → 엉뚱하게 `DuplicateAccountNumberException`으로 오역). 컬럼이 비어있음을 확인 후 `ALTER TABLE accounts DROP COLUMN balance`로 직접 정리 — 실제 배포 환경에서는 별도 마이그레이션으로 처리 필요.
- 같은 원리로, 로컬 Postgres에 (당시 develop에는 merge되지 않은) 다른 브랜치의 해시체인 감사로그 스키마 잔재(`outbox_event.entry_hash`/`previous_hash` NOT NULL, `outbox_chain_tail` 테이블)가 남아있어 100스레드 동시성 테스트(`TransferConcurrencyTest`)가 매번 실패 — Redisson 락 문제로 오인하기 쉬운 증상이었으나 원인은 순수 로컬 스키마 drift였음. 동일하게 `ALTER TABLE ... DROP COLUMN` / 잔재 테이블 DROP으로 해결.

**테스트**

- 신규 8개(`LedgerEntryTest`, `AccountBalanceCalculatorTest`, `VerifyTrialBalanceServiceTest`, `TrialBalanceVerificationTaskletTest`, `OpeningBalanceMigrationServiceTest`, `TransferMoneyServiceTest`의 예약 계좌 가드 테스트 2종 포함) + 기존 `balance` API 변경에 따른 11개 파일 수정.
- 전체 테스트(`./gradlew test`) 61개 통과, `./gradlew spotlessCheck` 통과.

### 과제 17: 분산 트레이싱(OpenTelemetry) 도입 (완료)

브랜치: `feat/opentelemetry-tracing` → `develop` (PR #35)

**배경**

- Saga(REQUIRES_NEW로 분리된 여러 빈) + Outbox 저장 + Debezium CDC + Kafka Consumer(재시도 토픽 포함)를 거치는 하나의 이체 요청에서 장애 발생 지점을 추적할 방법이 없었음. Micrometer Tracing(OTel bridge)으로 요청 시작부터 Consumer 처리까지 하나의 trace로 연결하는 것이 목표.
- 코드 작성 전 4가지 설계 결정(계측 방식 / Outbox→Kafka context 전파 방법 / trace_id를 entryHash에 포함할지 / Exporter 목적지)을 옵션+트레이드오프로 먼저 보고하고 사용자 확정 후 구현.

**설계 결정**

1. **계측 방식 — Micrometer Tracing (OTel bridge)**: Boot 4.0.7 네이티브 스택(`spring-boot-starter-opentelemetry`)과 정합적이고, `spring-boot-starter-aspectj`와도 자연스럽게 결합. 다만 실제 구현 단계에서 애초 근거로 들었던 `@Observed` 선언적 계측 대신 `Tracer` API로 span을 수동 생성하는 쪽으로 방향을 바꿈 — 이유는 (a) `DepositParticipantPort.deposit()`이 실제 입금과 보상 트랜잭션 두 지점에서 호출되는데 `@Observed`는 정적 애노테이션이라 같은 메서드의 두 호출을 다른 span 이름으로 구분할 수 없었고, (b) `Tracer`로 직접 감싸면 AOP 프록시 자체를 타지 않아 self-invocation 리스크 카테고리가 통째로 사라지는 부수효과가 있었기 때문. `@Observed`의 선언적 간결함은 포기한 트레이드오프.
2. **Outbox → Debezium CDC → Kafka Consumer 구간 trace context 전파 — 전용 컬럼**: `outbox_event`에 `trace_id`/`span_id` 컬럼을 추가하고 Debezium Outbox EventRouter SMT의 `table.fields.additional.placement`로 Kafka 헤더에 실어 전달, Consumer가 W3C traceparent 형식(`00-{traceId}-{spanId}-01`)으로 재구성해 `Propagator.extract()`로 부모 span을 복원. payload JSON 필드에 넣는 방식(옵션 a)은 entryHash 계산 입력에 payload가 포함되므로 자동으로 옵션 3을 "포함"으로 강제하게 되는 문제가 있어 기각.
3. **entryHash 계산에 trace_id 제외**: 감사로그(entryHash)는 업무적 사실 변조 여부를 증명하는 무결성 대상이고, 트레이스 ID는 샘플링/인프라 설정에 따라 달라질 수 있는 관측성 메타데이터라 목적이 다름 — `OutboxEvent.withTraceContext()`는 entryHash 계산 이후에만 적용해 분리.
4. **Exporter 목적지 — 로컬은 Jaeger, 배포 환경은 Infra 협의 필요**: docker-compose에 Jaeger all-in-one(OTLP 수신) 추가해 즉시 로컬 검증 가능하게 구성. 실제 배포 환경 Prometheus/Grafana 스택과 연동할 OTLP Collector 엔드포인트는 Infra 담당(김준희)과 별도 협의 필요 — 아직 미정.

**검증 (리뷰에서 지적받아 추가로 확인)**

- 자동 리뷰에서 "Debezium이 실제로 DB 컬럼을 Kafka 헤더로 옮겨주는지는 `outbox-connector.json` 설정 파일 하나만 믿고 있는 상태"라는 지적을 받아, 로컬에 Jaeger + Kafka Connect를 직접 띄우고 커넥터를 등록한 뒤 실제 이체 1건을 실행해 확인함:
  - `outbox_event` 테이블에 저장된 `trace_id`/`span_id`가 `kafka-console-consumer --property print.headers=true`로 읽은 실제 `transfer-events` 메시지 헤더 값과 정확히 일치.
  - Jaeger UI(`http://localhost:16686`)에서 `http post /api/v1/transfers` → `outbox.save` → `transfer-event.consume` 3개 span이 동일 trace_id로 연결됨을 실물로 확인. `transfer-event.consume` span은 이후 발견된 `Money` VO Jackson 역직렬화 실패(아래 참고)로 `otel.status_code=ERROR` + 예외 스택트레이스가 함께 기록됨 — 트레이싱이 실제 장애 지점을 정확히 짚어주는 것도 같이 확인됨.
  - 다만 이 확인은 수동 검증이며, 자동화된 통합 테스트(`TransferTraceContinuityIntegrationTest`)는 여전히 `TransferEventConsumer.consume()`을 직접 호출하는 방식이라 Debezium 라우팅 자체는 커버하지 않음 — 기존에 이미 보류 처리된 "실제 Kafka 브로커 기반 통합 테스트" 항목과 같은 종류의 갭이라 다음 작업 backlog에 병기.

**부수 발견 (이번 작업 범위 밖, 미수정)**

- `Money` VO(`domain.model`)에 Jackson creator가 없어 `TransferCompletedEvent`(payload에 `Money` 포함) 실제 역직렬화가 항상 실패함. 기존 테스트가 전부 Mock 기반(`PayloadDeserializerPort`를 목으로 대체)이라 지금까지 드러나지 않았던 것으로 보임 — 실제 배포 환경이라면 `transfer-events` 토픽 메시지가 전부 재시도 후 DLT로 빠지고 있었을 가능성. 별도 이슈로 다뤄야 함.

**테스트**

- 신규 3개(`OutboxPersistenceAdapterTest`, `TransferSagaOrchestratorTest`, `TransferTraceContinuityIntegrationTest`) + 기존 2개(`OutboxEventTest`, `TransferEventConsumerTest`) 확장.
- 전체 테스트(`./gradlew test`) 78개 통과, `./gradlew spotlessCheck` 통과.

### 과제 18: Money VO Jackson 역직렬화 버그 수정 (완료)

브랜치: `fix/money-vo-jackson-deserialization` → `develop`

**근본 원인**

- `Money`(domain.model)는 `private` 생성자만 가진 불변 VO라 Jackson이 기본 전략(무인자 생성자 + setter)으로 역직렬화할 방법이 없었음. `TransferCompletedEvent`가 `Money` 필드를 포함하므로, 이 이벤트를 담은 페이로드는 직렬화(쓰기)는 성공하지만 역직렬화(읽기)는 항상 실패하는 비대칭 구조였음.

**왜 지금까지 안 드러났는지**

- `PayloadDeserializerPort`를 호출하는 쪽(`TransferEventConsumer`)의 기존 테스트가 전부 `PayloadDeserializerPort`를 Mock으로 대체하고 있어서, 실제 `JsonMapper`가 `Money`를 역직렬화하는 경로 자체가 테스트에서 한 번도 실행되지 않았음. Mock 테스트 통과가 "실제 역직렬화가 된다"는 증명이 아니었음.

**영향 범위 추정**

- serialize(쓰기)는 getter만 있으면 되므로 실패하지 않음 — `TransferCompletedEvent`가 도입된 2026-08-05(과제 4, Outbox 통합) 시점부터도 이 부분은 문제없었음.
- 실제로 deserialize(읽기)가 호출되는 지점은 `TransferEventConsumer`뿐이고, 이 컨슈머가 도입된 시점이 2026-08-14(과제 11, Kafka Consumer Retry/DLT 도입). 따라서 실제 배포 환경이었다면 **2026-08-14부터** `transfer-events` 토픽 메시지가 전부 재시도 후 DLT로 빠졌을 것으로 추정 — 과제 17(분산 트레이싱, 2026-08-15) 작업 중 Jaeger 트레이스 실물 확인 과정에서 처음 발견됨(과제 17 "부수 발견" 참고).

**수정 내용**

- 기존 정적 팩토리 `Money.of(BigDecimal)`에 `@JsonCreator`/`@JsonProperty("amount")`(`com.fasterxml.jackson.annotation` — Jackson 3 `tools.jackson.databind`에서도 annotations 모듈은 이 구 패키지를 그대로 씀) 추가. `Money.of()` → private 생성자 → `validate()` 경로를 그대로 타므로 검증 로직(음수/null 금액 방지)을 우회하지 않음.
- 전수조사 결과 `Money`를 필드로 가진 Jackson 직렬화 대상은 `TransferCompletedEvent`가 유일. 웹 DTO(`AccountResponse`, `TransferMoneyRequest`)는 애초에 경계에서 `BigDecimal`로 변환하는 컨벤션이라 동일 버그 클래스에서 벗어나 있음.

**재발 방지책 (테스트)**

- `JacksonPayloadSerializerAdapterTest`에 Mock 없이 실제 `JsonMapper`로 `TransferCompletedEvent`(Money 포함)를 직렬화→역직렬화하는 왕복 테스트 추가 — 이번 버그가 안 잡혔던 이유가 Mock 기반 테스트뿐이었기 때문이므로, 재발 방지의 핵심은 이 실제-왕복 테스트임.
- 음수 금액이 담긴 JSON을 역직렬화했을 때 `InvalidMoneyException`이 원인 체인에 그대로 보존되는지 확인하는 테스트 추가 — creator 애노테이션이 검증 로직을 우회하는 새 생성 경로를 만들지 않았음을 실측으로 확인.

**테스트**

- 신규 2개(`serializeThenDeserialize_transferCompletedEventWithMoney_roundTrip`, `serializeThenDeserialize_moneyWithNegativeAmount_preservesDomainValidationException`).
- 전체 테스트(`./gradlew test`) 80개 통과, `./gradlew spotlessCheck` 통과.

### 과제 19: Maker-Checker(이중 승인) 도입 (완료 — 4-eyes principle)

브랜치: `feat/maker-checker-approval` → `develop`

**배경**

- 고액·정정 거래에서 한 사람의 실수/부정을 막기 위해, 기안자(Maker)가 이체를 요청하고 승인자(Checker)가 별도로 승인해야만 실제 자금 이동이 시작되는 내부통제 절차 도입. 대부분의 은행 규제가 요구하는 4-eyes principle 구현이 목표.
- 코드 작성 전 4가지 설계 결정(배치/승인조건/신원표현/거절처리)을 옵션+트레이드오프로 먼저 보고하고 사용자 확정 후 구현(과제 16, 17과 동일한 "설계 승인 → 구현" 2단계 워크플로).
- 착수 전 리서치에서 중요한 사실 발견: 작업지시서는 승인 완료 후 `StartTransferSagaUseCase`(Saga)를 트리거하는 것을 전제했으나, 실제 웹에 연결된 이체 경로는 `TransferMoneyController → TransferMoneyUseCase`(`TransferMoneyService`, 복식부기 원장 기반)이고 `StartTransferSagaUseCase`/`TransferSagaOrchestrator`는 컨트롤러가 없어 테스트에서만 호출되는 상태였음. 이 사실을 사용자에게 먼저 알리고 설계 결정에 반영.

**설계 결정**

1. **배치 — 전용 애그리게이트(`TransferApprovalRequest`) + 승인 완료 시 `TransferMoneyUseCase` 트리거**: "바뀌는 이유가 다르면 분리한다"는 기존 SRP 판단 기준(과제 8 `InterestPolicy`/`EodSnapshot` 분리, 과제 16 `LedgerEntry`/`Account` 분리와 동일 논리)을 적용 — 승인 정책이 바뀌는 이유와 이체 실행 메커니즘이 바뀌는 이유는 다름. `StartTransferSagaUseCase`가 아닌 실제 프로덕션 경로인 `TransferMoneyUseCase`를 트리거 대상으로 선택(위 "배경"의 발견 사항 반영).
2. **승인 조건 — 금액 threshold + 도메인 정책 객체(`ApprovalPolicy` record)**: `InterestPolicy`와 동일하게 도메인 계층에 정책을 두되, threshold 값 자체는 `application.yaml`(`approval.threshold`)에서 `ApprovalPolicyProperties`(`@ConfigurationProperties`)로 읽어 `ApprovalConfig`가 `ApprovalPolicy` 빈으로 조립.
3. **신원 표현 — makerId/checkerId 단순 String + 자기승인 방지는 도메인 모델 내부**: 이 프로젝트가 계좌번호도 항상 raw String으로 표현하는 컨벤션과 일치시킴. `TransferApprovalRequest.approve()/reject()` 내부에서 `checkerId.equals(makerId)`를 검증해 `SelfApprovalNotAllowedException`을 던짐 — `LedgerEntry.transferPair`가 두 다리에 동일 `Money` 인스턴스를 강제해 불변식을 원천 차단하는 것과 동일한 invariant-by-construction 사고방식.
4. **거절 처리 — `rejectionReason` 필드 추가(필수)**: 해시체인 감사로그(과제 15)·OpenTelemetry(과제 17) 등 감사 추적성에 강하게 투자해온 프로젝트 컨벤션과 일치, 4-eyes principle 자체가 규제 대응 목적이라 "왜 거절했는지" 없는 감사 기록은 실효성이 떨어짐.

**부가 판단 (짧게 언급 후 구현)**

- 승인 완료 후 트리거 방식: 직접 호출(동기) 선택 — 이 프로젝트에는 유스케이스 간 체이닝용 내부 이벤트 버스가 없고(Kafka는 Outbox를 통한 외부 발행 전용), 이 시점에 이벤트 기반 비동기 체이닝을 도입할 근거가 없어 YAGNI.
- 동시성: 기존 `@Version` 낙관적 락 패턴을 그대로 재사용(`TransferSaga`/`Account`와 동일), `ObjectOptimisticLockingFailureException`(하위) → `DataAccessException`(상위) catch 순서 컨벤션도 동일하게 적용.

**구현 내용**

- `ApprovalStatus`(domain.model, enum) — PENDING→{APPROVED, REJECTED}만 허용하는 단순 상태 머신, `SagaStatus.canTransitionTo()`와 동일한 Switch Expression 패턴.
- `TransferApprovalRequest`(domain.model) — `request()`/`reconstruct()` 이원화, `id`/`version` nullable 소유(Account/TransferSaga와 동일 컨벤션). `approve()`/`reject()`가 자기승인 차단 → 상태 전이 → 필드 갱신 순으로 처리.
- `ApprovalPolicy`(domain.model record) — `requiresApproval(Money amount)`.
- 신규 도메인 예외 7종(`domain.exception`): `InvalidApprovalTransitionException`, `SelfApprovalNotAllowedException`, `RejectionReasonRequiredException`, `ApprovalNotRequiredException`, `ApprovalRequestNotFoundException`, `ConcurrentApprovalModificationException`, `ApprovalPersistenceException`.
- Port In 4종(`RequestTransferApprovalUseCase`, `ApproveTransferUseCase`, `RejectTransferUseCase`, `GetPendingApprovalsUseCase`) — `CreateAccountService`/`GetAccountService`처럼 유스케이스 1개당 서비스 1개 컨벤션을 그대로 따라 구현체도 4개로 분리(`RequestTransferApprovalService`, `ApproveTransferService`, `RejectTransferService`, `GetPendingApprovalsService`).
- Port Out 2종(`SaveApprovalRequestPort`, `LoadApprovalRequestPort`) + 영속성 어댑터 4종(`TransferApprovalRequestJpaEntity`(`@Version` 보유), package-private `TransferApprovalRequestJpaRepository`, public `ApprovalRequestMapper`, `ApprovalPersistenceAdapter`) — `SagaPersistenceAdapter`와 동일한 catch 순서 컨벤션.
- `TransferApprovalController`(`/api/v1/transfer-approvals`) — 승인 요청 생성(POST), 대기 목록 조회(GET /pending), 승인(POST /{id}/approve), 거절(POST /{id}/reject) 4개 엔드포인트.
- `GlobalExceptionHandler`에 신규 예외 6종 매핑 추가(`INVALID_TRANSACTION` 그룹에 `InvalidTransferAmountException` 추가 포함) — 기존에 `InvalidSagaTransitionException`처럼 매핑이 누락된 채 500으로 흘러가던 갭을 신규 기능에서는 처음부터 만들지 않음.

**테스트**

- 신규 27개(`TransferApprovalRequestTest` 8, `ApprovalPolicyTest` 3, `RequestTransferApprovalServiceTest` 2, `ApproveTransferServiceTest` 3, `RejectTransferServiceTest` 3, `ApproveTransferConcurrencyTest` 1, `TransferApprovalControllerTest` 7).
- `ApproveTransferConcurrencyTest`: 두 Checker가 동시에 같은 요청을 승인 시도 시 정확히 1건만 성공하고 나머지는 `ConcurrentApprovalModificationException`으로 실패하는지 실제 Postgres(`@SpringBootTest`) 기반으로 검증 — `TransferConcurrencyTest`(과제 1-2)와 동일한 CountDownLatch 패턴.
- 전체 테스트(`./gradlew test`) 107개 통과(스킵 1건은 기존 `FbrlBackendApplicationTests`, 본 작업과 무관), `./gradlew spotlessCheck` 통과.

**심각한 게이트 누락 발견 및 수정 (사용자 리뷰에서 지적됨)**

- 최초 구현이 `TransferApprovalController`(신규 승인 워크플로)만 추가했을 뿐, 기존 `TransferMoneyController`(`/api/v1/transfers`)는 전혀 수정하지 않아 threshold 이상 금액도 승인 절차 없이 그대로 직접 이체가 가능했음 — Maker-Checker가 사실상 아무것도 강제하지 못하는 상태로 리뷰에서 지적됨.
- `ApprovalRequiredException`(domain.exception) 신규 추가, `TransferMoneyController`에 `ApprovalPolicy`를 주입해 `assertApprovalNotRequired()` 가드를 요청 진입 시점에 적용 — threshold 이상이면 400 반환, `TransferMoneyUseCase.transfer()` 호출 자체를 막음.
- 게이트를 `TransferMoneyService`(application 계층)가 아닌 컨트롤러(`adapter.in.web`)에 둔 이유: `TransferMoneyUseCase`는 `TransferMoneyController`(직접 이체)와 `ApproveTransferService`(승인 후 트리거) 양쪽에서 공유하는 단일 인터페이스라, 서비스 계층에 게이트를 두면 정상 승인 흐름까지 막힘. `ApproveTransferService`는 컨트롤러를 거치지 않고 `TransferMoneyUseCase`를 직접 호출하므로, 게이트를 HTTP 진입점에만 둠으로써 컨트롤러를 우회하는 내부 호출 경로는 구조적으로 영향받지 않음.
- 검증: `TransferControllerTest`에 threshold 이상 직접 요청 시 400 + `transferMoneyUseCase.transfer()` 미호출 검증 테스트 추가. `ApproveTransferBypassesWebGateIntegrationTest`(신규, `@SpringBootTest`)로 threshold 이상 금액도 승인 완료 후에는 실제 계좌 잔액이 이동하는지 Mock 없이 실물 DB로 검증 — 신규 2개 포함 전체 테스트 109개 통과, `spotlessCheck` 통과.

### 과제 20: 거절 사유 조회 API (완료)

브랜치: `feat/transfer-approval-rejection-reason-api` → `develop` (PR #44)

**배경**

- `TransferApprovalRequest.rejectionReason`은 과제 19(Maker-Checker)에서 이미 DB에 저장되고 있었지만, 이를 조회하는 API가 없는 상태였음. `GET /api/v1/transfer-approvals/{requestId}` 단건 조회 API 추가.

**Port Out 재사용 판단**

- 착수 전 조사에서 requestId(경로변수, 문자열 비즈니스 식별자)로 `TransferApprovalRequest`를 조회하는 재사용 가능한 Port Out이 이미 존재하는지 먼저 확인 — `LoadApprovalRequestPort.loadByRequestId(String requestId)`가 `ApproveTransferService`/`RejectTransferService`에서 동일한 목적으로 이미 쓰이고 있어 **신규 Port Out을 만들지 않고 그대로 재사용**.
- 같은 논리로 "존재하지 않는 requestId" 예외도 `domain.exception.ApprovalRequestNotFoundException`(이미 `loadByRequestId` 실패 시 던져지고 `GlobalExceptionHandler`에 404로 매핑되어 있음)을 재사용 — 최초 작업지시서는 신규 예외 생성을 예시로 들었으나, 동일 목적의 예외를 중복 생성하지 않는 쪽을 택함.

**구현 내용**

- `application.port.in.GetApprovalRequestUseCase`(`TransferApprovalRequest getByRequestId(String requestId)`) / `application.service.GetApprovalRequestService` — `GetPendingApprovalsUseCase`/`GetPendingApprovalsService`와 동일하게 도메인 모델을 그대로 반환하는 조회 전용 유스케이스 컨벤션을 따름.
- `adapter.in.web.dto.TransferApprovalDetailResponse`(record) — `TransferApprovalRequest` 전체 필드가 아니라 조회 목적에 필요한 필드만 선택(정보 노출 최소화, `TransferCompletedEvent`/`PendingApprovalResponse`와 동일한 원칙). PK `id`/`version`은 응답에 노출하지 않음.
- 신규 컨트롤러를 만들지 않고 기존 `TransferApprovalController`에 `GET /{requestId}` 엔드포인트로 추가(같은 리소스는 한 컨트롤러에 모으는 기존 컨벤션 유지).

**테스트**

- `GetApprovalRequestServiceTest`(정상 조회/404 2종), `TransferApprovalControllerTest`에 2종 추가(정상 조회 200, 존재하지 않는 requestId 404).
- 전체 테스트(`./gradlew test`) 통과 확인.

### 과제 21: 룰 기반 이상거래 탐지(FraudCheckPort) 도입 (완료)

브랜치: `feat/fraud-check-rule-engine` → `develop` (PR #46)

**배경**

- 단건 금액이 threshold를 넘으면 이상거래로 의심해 이체를 즉시 차단하는 최소 스코프 룰 엔진 도입. 기존 메모("Saga 흐름 중 어디에 끼울지가 쟁점")는 전제가 낡아 있어, 착수 전 실제 코드로 라이브 이체 경로부터 재확인 — `TransferSagaOrchestrator`/`StartTransferSagaUseCase`는 여전히 컨트롤러가 없어 웹에 연결되지 않았고, 라이브 경로는 `TransferMoneyController → TransferMoneyService`(과제 19와 동일 결론).
- `TransferMoneyController`에 걸린 Maker-Checker 승인 게이트(`assertApprovalNotRequired()`)를 `ApproveTransferService`가 컨트롤러를 거치지 않고 `TransferMoneyUseCase`를 직접 호출해 우회하는 구조(과제 19 "심각한 게이트 누락 발견 및 수정" 사례)를 먼저 파악. 이상거래 탐지는 반대로 승인 경로에서도 빠짐없이 적용돼야 하므로(과제 19의 승인 게이트와 정반대 요구사항), 이 점을 설계 기준에 반영.

**설계 결정**

1. **위치 — `TransferMoneyService.transfer()` 내부(`assertNotReservedAccount`와 같은 위치대)**: 컨트롤러/AOP 옵션도 함께 제시했으나, 두 이체 진입점(`TransferMoneyController`, `ApproveTransferService`)이 유일하게 공유하는 지점이 이 메서드뿐이라 여기 두어야만 승인 경로도 구조적으로 커버됨. 이미 `@DistributedLock(key = "#command.senderAccountNumber")`가 걸려 있어, 향후 "짧은 시간 내 다건" 같은 카운팅 룰을 추가해도 신규 락 없이 기존 락에 편승 가능하다는 점도 이 위치를 선택한 근거.
2. **판정 실패 처리 — 도메인 예외(`SuspiciousTransferException`) 즉시 던지기**: 별도 상태(심사 대기)로 전이하는 옵션도 제시했으나, 최소 스코프에서는 기존 도메인예외+`GlobalExceptionHandler` 컨벤션과 완전히 일치하는 예외 방식을 채택. 오탐 대응이 필요해지면 Maker-Checker의 `PENDING` 패턴을 재사용해 확장하는 것으로 미룸(YAGNI).
3. **룰 스코프 — 단건 금액 임계치만**: "짧은 시간 내 동일 계좌 다건 이체" 같은 카운팅 룰은 상태 조회·동시성 설계가 추가로 필요해 범위가 커지므로 이번 스코프에서 제외.
4. **형태 — `FraudCheckPort`(application.port.out)로 감싸기**: 순수 도메인 정책 객체만으로 충분한지 Port로 감쌀지 옵션을 제시했고, 사용자가 "지금은 단순 임계치 비교지만 향후 어댑터를 외부 룰엔진/ML 기반으로 교체할 가능성"을 이유로 Port 방식을 확정. `DepositParticipantPort`/`WithdrawalParticipantPort`와 동일하게 도메인 엔티티 전체가 아닌 최소 파라미터(계좌번호, `Money`)만 받는 시그니처로 설계.

**구현 내용**

- `domain.exception.SuspiciousTransferException` — `InvalidTransferAmountException`과 동일한 컨벤션.
- `application.port.out.FraudCheckPort` — `boolean isSuspicious(String accountNumber, Money amount)`. Result 객체가 아닌 `boolean`을 택한 이유: Deposit/WithdrawalParticipantPort의 `Result(boolean, failureReason)` 패턴은 "예외를 오케스트레이터로 전파하지 않고 Result로 수렴시켜야 하는" Saga 참여자 특유의 제약(과제 6) 때문인데, 이번 설계 결정 2번(예외 던지기)은 그 전제가 다르므로 Result 래핑은 불필요한 추상화.
- `global.config.FraudPolicyProperties`(`@ConfigurationProperties(prefix="fraud")`) / `global.config.FraudConfig` — `ApprovalPolicyProperties`/`ApprovalConfig`와 완전히 동형.
- `adapter.out.fraud.RuleBasedFraudCheckAdapter`(신규 패키지) — `FraudCheckPort` 구현체.
- `TransferMoneyService`에 `FraudCheckPort` 생성자 주입 + `assertNotSuspicious()` 가드클로즈 추가(`assertNotReservedAccount` 바로 다음, 계좌 조회 이전).
- `GlobalExceptionHandler`에 `SuspiciousTransferException` → 400(`SUSPICIOUS_TRANSFER`) 매핑 추가.
- `application.yaml`: `fraud.threshold: 50000000` 추가(기존 `approval.threshold: 10000000`과 별개 값).

**FraudPolicy 도메인 계층 누락 및 재작업 (사용자 리뷰에서 지적됨)**

- 설계안에서는 `ApprovalPolicy`와 동형인 `domain.model.FraudPolicy`로 시작하기로 했으나, 최초 구현은 임계치 비교 로직(`amount.isGreaterThanOrEqual(threshold)`)을 `RuleBasedFraudCheckAdapter`(adapter.out.fraud, 인프라 계층)에 직접 작성 — 사전 설계안과 diff를 대조한 사용자 리뷰로 지적됨(과제 19 "심각한 게이트 누락 발견 및 수정"과 같은 종류의 사례).
- "무엇이 의심거래인가"(임계치 룰)라는 업무 규칙이 어댑터 소유가 되면, 나중에 어댑터를 외부 룰엔진으로 교체할 때 이 규칙까지 같이 사라짐. `FraudConfig`가 `Money` 타입 자체를 Spring 빈으로 노출한 것도, 향후 다른 `Money` 타입 빈이 추가되면 `NoUniqueBeanDefinitionException`으로 이어질 수 있는 잠재 결함으로 함께 지적됨.
- 수정: `domain.model.FraudPolicy(Money threshold)`(record, `isSuspicious(Money amount)`) 신설 — `RuleBasedFraudCheckAdapter`는 이제 이 정책 객체에 위임만 함. `FraudConfig`도 `Money` 빈 대신 `FraudPolicy` 빈을 등록하도록 교체.

**테스트**

- `FraudPolicyTest`(도메인 순수 단위, `ApprovalPolicyTest`와 동형) — 임계치 이상/동일/미만 3케이스.
- `RuleBasedFraudCheckAdapterTest` — `@ConfigurationProperties` 실바인딩 검증 1건 + `FraudPolicy` 위임 검증 1건.
- `TransferMoneyServiceTest` — `FraudCheckPort` Mock 추가, 의심거래 시 `SuspiciousTransferException` + 원장/아웃박스 등 어떤 포트도 호출되지 않음(`verifyNoInteractions`) 검증.
- **`ApproveTransferTriggersFraudCheckIntegrationTest`(신규, `@SpringBootTest`) — 과제 19의 `ApproveTransferBypassesWebGateIntegrationTest`와 동일한 패턴으로, `ApproveTransferService.approve()` 경유 승인 후 트리거 경로에서도 threshold 이상 금액이면 `SuspiciousTransferException`이 실제로 발생하고 잔액이 이동하지 않는지 Mock 없이 실물 DB로 검증** — 과제 19에서 승인 경로가 게이트를 우회했던 사고와 반대 방향(우회되면 안 되는 로직)의 재발 방지 확인.
- 전체 테스트(`./gradlew test`) 통과 확인, `./gradlew spotlessCheck` 통과.

### 과제 22: EOD 정산 대사(Reconciliation) 엔진 도입 (완료)

브랜치: `feat/reconciliation-engine` → `develop` (PR #49)

**배경**

- 과제 10(복식부기 원장)에서 이미 "시스템 전체 `LedgerEntry` 차변합=대변합" 검증(`VerifyTrialBalanceService`/`TrialBalanceVerificationTasklet`)이 `eodSettlementJob`의 `trialBalanceVerificationStep`으로 존재해, 착수 전 이 기능과의 중복 여부부터 코드로 직접 확인 — trial balance는 시스템 전체 스칼라 2개만 비교하고 `EodSnapshot`을 전혀 참조하지 않는 반면, Reconciliation은 "`AccountBalanceCalculator`가 상시 사용하는 앵커(`EodSnapshot`) 캐시가 원장 원본과 실제로 일치하는가"라는 계좌 단위의 다른 질문이라 중복 아님으로 판단.
- 계좌별 "진실 소스" 후보(레거시 마이그레이션 시딩 데이터, 다른 배치 산출물 등)도 조사했으나, 이 프로젝트엔 라이브 외부 소스가 없고(과제 10에서 `accounts.balance` 컬럼 자체가 제거됨) 유일한 SSOT는 `LedgerEntry`, `EodSnapshot`은 그 파생 캐시임을 확인.

**설계 결정 (옵션 제시 → 사용자 확정, 여러 라운드에 걸쳐 진행)**

1. **대사 대상 — 계좌별 `EodSnapshot`(앵커) vs genesis부터 `LedgerEntry` 전량 재계산**: 계좌별 잔액 합계를 시스템 전체 trial balance와 재대조하는 옵션은 `transferPair`의 구조적 보장과 수학적으로 거의 항상 같아 새 결함을 못 잡을 것으로 판단해 기각.
2. **스냅샷 특정 — 정확히 오늘 날짜(`settlementDate`)만, 없으면 `NO_SNAPSHOT`으로 명시 분류**: "가장 최근" 스냅샷을 날짜 무관하게 쓰는 옵션은, EOD가 며칠 실패해도 예전 스냅샷이 "존재한다"는 우연한 사실만으로 계속 통과시키는 문제가 있어(과제 10 `ReservedAccountException`과 대칭되는 "우연히 통과" 사례) 기각.
3. **불일치 처리 — 별도 write-once 테이블(`ReconciliationDiscrepancy`), `MISMATCH`/`NO_SNAPSHOT`만 저장, `@Version` 없음**: trial balance처럼 예외로 배치 전체를 실패시키는 옵션은, Reconciliation이 계좌 단위 부분 실패라 계좌 1건 때문에 EOD 정산 전체를 막는 게 과함. 운영자 "확인 처리" 워크플로는 이 프로젝트에 그런 기능이 전혀 없어 YAGNI로 이번 스코프에서 명시 제외.
4. **배치 위치 — `eodSettlementJob`과 완전히 분리된 별도 Job(`reconciliationJob`), EOD 이후 시각 스케줄**: 대사 비용이 EOD 크리티컬 패스(이자 계산·스냅샷 저장)를 지연시키지 않도록 분리. Job 레벨 사전 게이트(`JobExplorer`로 EOD 완료 확인)는 계좌별 `NO_SNAPSHOT` 분류만으로도 "EOD가 안 돌았다"는 사실이 이미 드러나므로 이번엔 넣지 않고, 알림이 너무 많이 쌓이면 그때 추가(YAGNI).

**청크 배치 쿼리 재설계 (사용자 지적으로 2차 수정)**

- 최초 설계는 `ItemProcessor`가 계좌 1건마다 스냅샷/원장 델타를 개별 쿼리하는 구조 → 청크 크기(1000)만큼 청크당 최대 1000번 왕복하는 문제를 사용자가 지적. 무거운 조회를 `ItemProcessor`가 아니라 청크 전체(`List<Account>`)를 한 번에 받는 `ItemWriter`(`EodSnapshotItemWriter`가 이미 쓰던 것과 동일한 위치)로 이동 — `LoadEodSnapshotByDatePort`/`LoadLedgerBalanceDeltasPort` 모두 청크당 1쿼리로 수렴.
- 원장 델타 조회도 처음엔 `List<LedgerEntry>` 원본 행을 배치 `IN` 조회하는 형태였으나, 계좌당 거래 건수가 무제한이라 거래량 많은 계좌가 청크에 섞이면 결과 크기가 다시 unbounded해지는(`findAll()` 금지와 같은 유형) 문제를 사용자가 지적 — SQL `GROUP BY account_number`로 계좌별 신용/차변 합계까지 미리 집계해 반환하도록 재설계, 결과 크기가 청크당 계좌 수로 bounded됨.
- 순델타(credit-debit)를 SQL에서 미리 빼서 단일 `Money`로 반환하지 않고 신용/차변을 분리 반환(`LedgerBalanceDelta`)하는 이유: `Money`가 음수를 금지하는데(`Money.validate()`), 순델타는 원장 불변식이 실제로 깨졌을 때 음수가 될 수 있어 그대로 `Money`로 감싸면 생성자가 예외를 던져 대사 배치 자체가 죽음.

**구현 내용**

- Port 3종(`application.port.out`): `LoadEodSnapshotByDatePort`, `LoadLedgerBalanceDeltasPort`, `SaveReconciliationDiscrepancyPort`.
- `domain.model.ReconciliationDiscrepancy`(write-once record)/`ReconciliationStatus`(`MISMATCH`/`NO_SNAPSHOT`)/`LedgerBalanceDelta`(`creditTotal`/`debitTotal` 둘 다 `Money`).
- `adapter.out.persistence.ReconciliationDiscrepancyJpaEntity`(`(account_number, settlement_date)` 유니크 제약, `@Version` 없음) + package-private `*JpaRepository`/public `*Mapper`/`*PersistenceAdapter`(`DataIntegrityViolationException` → `DuplicateReconciliationDiscrepancyException` 번역).
- `adapter.in.batch.ReconciliationJobConfig`/`ReconciliationItemWriter`(reader는 `AccountItemReader` 재사용) + `adapter.in.scheduler.ReconciliationScheduler`(`${reconciliation.batch.cron:0 0 3 * * *}`, ShedLock).
- `ReconciliationScheduler`의 `asOf`(트리거 시각) 잡 파라미터는 `JobParametersBuilder.addString(key, value, false)`로 **non-identifying** 지정 — 매 실행마다 값이 달라지므로 identifying으로 두면 Job 인스턴스가 매번 새로 취급되어 EOD와 동일하게 갖고 있어야 할 "당일 재실행 스킵" 보호가 무력화됨.

**expectedBalance 계산 버그 발견 및 수정 (커밋 전 사용자 확인 중 발견)**

- 최초 구현은 `expectedBalance`로 `EodSnapshot.totalBalance()`(`closingBalance + interestAmount`)를 사용. `AccountInterestItemProcessor`가 계산하는 이자는 스냅샷 필드에만 기록될 뿐 `LedgerEntry`로 전혀 적립되지 않는데(grep으로 확인, `transferPair`/`SaveLedgerEntryPort` 사용처 어디에도 이자 적립 로직 없음), `actualBalance`는 원장만 재계산한 값이라 이자가 빠져 있어, 이자가 0이 아닌 사실상 모든 계좌가 매일 `MISMATCH`로 오탐되는 버그였음.
- 안 걸린 이유: 초기 `ReconciliationItemWriterTest`/`ReconciliationJobConfigTest`가 이자를 전부 `Money.ZERO`로 고정하거나 스냅샷 자체를 생성하지 않아(`NO_SNAPSHOT` 경로만 실행) 이 시나리오를 검증한 적이 없었음.
- 수정: `expectedBalance`를 이자 제외 `EodSnapshot.closingBalance()`로 교체. 이자가 0이 아닌 값(500원/300원)으로 MATCH/MISMATCH 단위 테스트를 재작성하고, `ReconciliationJobConfigTest`에도 스냅샷을 실제로 시딩해 MISMATCH 종단 경로(이자 5,000원 포함 스냅샷 vs 원장 재계산 불일치)를 추가.

**테스트**

- `LedgerEntryPersistenceAdapterTest`(신규) — `loadBalanceDeltasUntil`이 활동 없는 계좌도 `Money.ZERO`로 채워 결과 Map을 요청 계좌 전원에 대해 완전하게 반환하는지, `until` 이후 원장은 델타에서 제외되는지 검증.
- `ReconciliationItemWriterTest`(신규) — 이자가 붙어 있어도 재계산이 `closingBalance`와 일치하면 MATCH(미저장) / 불일치하면 MISMATCH(저장) / 스냅샷 없으면 NO_SNAPSHOT(저장, 델타 조회 자체를 안 함) 3케이스.
- `ReconciliationDiscrepancyPersistenceAdapterTest`(신규) — `(account_number, settlement_date)` 유니크 제약 위반 시 `DuplicateReconciliationDiscrepancyException` 번역 검증.
- `ReconciliationJobConfigTest`(신규) — NO_SNAPSHOT/MISMATCH 두 경로 모두 Job이 `BatchStatus.COMPLETED`로 끝나고 `ReconciliationDiscrepancy`가 정확히 기록되는지 종단 검증.
- 전체 테스트(`./gradlew test`) 128개 통과(스킵 1건은 기존 `FbrlBackendApplicationTests`, 본 작업과 무관), `./gradlew spotlessCheck` 통과.

### 과제 23: 승인 상태(status)와 이체 집행 결과(executionStatus) 분리 (완료)

브랜치: `fix/decouple-approval-status-from-execution-result` → `develop` (PR #53)

**배경**

- 포트폴리오 캡처 세션 중 실제로 재현: 이상거래 임계치(5천만원) 이상 금액을 Maker-Checker로 승인해도, `ApproveTransferService.approve()` 내부에서 `transferMoneyUseCase.transfer()`가 `SuspiciousTransferException`으로 실패하면 승인 요청의 `status`는 `APPROVED`로 남고 실제 자금은 이동하지 않는 상태가 확인됨.
- 원인 조사: `request.approve()` + 저장(`saveApprovalRequestPort.save()`, Spring Data JPA `save()`가 자체 트랜잭션으로 즉시 커밋)과, `transfer()`(`@DistributedLock` → `AopForTransaction`의 `REQUIRES_NEW`로 완전히 별도 트랜잭션)가 서로 롤백 연결고리 없이 분리되어 있어, `transfer()`가 어떤 예외(이상거래뿐 아니라 `InsufficientBalanceException` 등도 동일 구조)로 실패하든 이미 커밋된 승인 상태는 그대로 남는 구조적 문제로 확인.

**설계 결정**

- 승인 워크플로 상태(`status`)는 그대로 두고, 별도 `executionStatus`(`NOT_APPLICABLE`/`EXECUTED`/`FAILED`) + `executionFailureReason` 필드로 "승인 행위 자체는 유효했다"와 "그 승인의 집행이 실패했다"를 분리 — 감사 관점에서 체커가 승인한 사실 자체를 지우지 않는 쪽을 택함(대안으로 `approve()` 전체를 하나의 트랜잭션으로 묶어 실행 실패 시 승인 상태까지 롤백하는 방안도 검토했으나, 그 경우 승인 행위의 흔적 자체가 사라져 기각).
- `executionStatus` 갱신 저장에 `REQUIRES_NEW`/`AopForTransaction`은 불필요 — `saveApprovalRequestPort.save()`가 호출하는 Spring Data JPA `save()` 자체가 이미 독립 트랜잭션이고, `approve()`엔 애초에 감쌀 외부 트랜잭션이 없어 분리할 대상이 없음.
- 재시도 API(집행 실패 건을 다시 실행시키는 것)는 이번 스코프에서 의도적으로 제외(YAGNI) — 아래 "다음 작업" 참고.

**구현 내용**

- `domain.model.ExecutionStatus`(신규 enum) / `TransferApprovalRequest.markExecuted()`·`markExecutionFailed(String reason)` — 기존 `approve()`/`reject()`와 동일한 캡슐화 패턴(메서드로만 상태 전이).
- `ApproveTransferService.approve()` — `transfer()` 호출을 try-catch로 감싸 성공 시 `markExecuted()`, 실패 시 `markExecutionFailed(e.getMessage())` 저장 후 원래 예외를 그대로 rethrow(호출자에게 보이는 예외 타입/메시지는 기존과 동일하게 유지). `executionStatus` 저장 자체가 실패하는 2차 예외는 로그만 남기고 삼킴 — 원래 예외를 덮어쓰지 않음.
- `TransferApprovalRequestJpaEntity`/`ApprovalRequestMapper` — `execution_status`(`NOT NULL`)/`execution_failure_reason`(nullable) 컬럼 매핑 추가. 이 프로젝트는 Flyway/Liquibase 미사용이라 배포 시 수동 DDL 필요(`docs/DEPLOYMENT.md` "스키마 변경이 포함된 배포" 섹션에 반영).

**테스트**

- `TransferApprovalRequestTest`(도메인 단위) — PENDING/REJECTED 상태에서 `executionStatus`가 `NOT_APPLICABLE`로 유지되는지, `markExecuted()`/`markExecutionFailed()` 각각의 상태 전이 4건 추가.
- `ApproveTransferTriggersFraudCheckIntegrationTest` — 이상거래로 막힌 건이 `status=APPROVED` + `executionStatus=FAILED`로 남는지 검증 추가(기존 예외 발생 검증은 유지).
- `ApproveTransferBypassesWebGateIntegrationTest` — 성공 건이 `status=APPROVED` + `executionStatus=EXECUTED`로 남는지 검증 추가.
- 전체 테스트(`./gradlew test`) 132개 통과(기존 128 + 신규 4), `./gradlew spotlessCheck` 통과.

### 과제 24: Swagger(springdoc-openapi) 도입 (완료)

브랜치: `feat/add-swagger-openapi` → `develop` (PR #54)

**배경**

- API 문서화 수단이 전혀 없어 Postman으로 직접 캡처해가며 API를 파악해야 했음. Postman 캡처를 대체할 인터랙티브 API 문서로 Swagger UI 도입.

**구현 내용**

- `build.gradle`에 `springdoc-openapi-starter-webmvc-ui:3.1.0` 추가 — Spring Boot 4.0.7과 호환되는 최신 버전(Maven Central `maven-metadata.xml`로 직접 확인, 2026-08-01 릴리스).
- `global.config.OpenApiConfig` — 최소한의 `OpenAPI` 빈(title/version)만 등록. 엔드포인트별 `@Operation`/`@Schema` 문서화는 이번 스코프 아님.
- Spring Security가 아직 프로젝트에 없어 별도 `permitAll` 설정 없이 `/swagger-ui/**`, `/v3/api-docs/**`가 바로 열림 — 추후 Security 도입 시 허용 목록에 추가 필요.

**검증**

- `./gradlew compileJava`/`spotlessCheck` 통과, 로컬 `bootRun`으로 실기동 확인(`GET /v3/api-docs` → 200, `GET /swagger-ui/index.html` → 200).

### 과제 25: makerId/checkerId를 인증 컨텍스트로 연결 (완료)

브랜치: `feat/wire-authenticated-principal-to-approval-workflow` → `develop`

**배경**

- 과제 19(Maker-Checker)에서 승인 워크플로를 도입했을 때는 아직 인증 인프라가 없어 `makerId`/`checkerId`를 요청 본문에서 그대로 받는 구조였음(호출자가 아무 문자열이나 넣을 수 있어 자기승인 방지도 신뢰할 수 없었음). 그 사이 관리자 인증 인프라(AdminUser/JWT, `feat/auth-infrastructure`)가 먼저 들어오면서, 승인 워크플로 3개 엔드포인트가 여전히 요청 본문으로 신원을 받는 게 인증 인프라 도입 취지와 어긋나는 상태로 남아 있었음.

**구현 내용**

- `RequestTransferApprovalRequest`/`RejectTransferRequest`에서 makerId/checkerId 필드 제거, `toCommand()`가 인증된 사용자명을 파라미터로 받도록 변경. `ApproveTransferRequest`는 필드가 checkerId뿐이라 빈 DTO만 남아 삭제 — `approve()`는 `@RequestBody` 없이 `Authentication`만 받음.
- `TransferApprovalController`의 `requestApproval()`/`approve()`/`reject()` 세 메서드 모두 `Authentication` 파라미터를 추가해 `authentication.getName()`(JWT `sub` = `AdminUser.username`)으로 Command를 채움. `ApproveTransferCommand`/`RejectTransferCommand`/`RequestTransferApprovalCommand` 시그니처 자체는 유지 — application/domain 계층 변경 최소화.
- `OpenApiConfig`에 `bearerAuth` `SecurityScheme` + 전역 `SecurityRequirement` 등록, Swagger UI에서 인증 필요 엔드포인트가 자물쇠 아이콘으로 표시되도록 함.

**테스트**

- `TransferApprovalControllerTest`를 mock 기반 standalone 테스트에서 `@SpringBootTest`+`@AutoConfigureMockMvc`로 전환 — `@WithMockUser`는 이 프로젝트 필터 체인에 반영되지 않는다는 게 이미 확인된 사실(`IdempotencyIntegrationTest` 사례)이라, `LoginIntegrationTest`처럼 실제 `/api/v1/auth/login`으로 발급받은 JWT를 `Authorization` 헤더에 실어 검증.
- 자기승인 방지를 "손으로 다르게 넣은 문자열 비교"가 아니라 같은 로그인 세션으로 기안 후 그 세션 그대로 승인/거절을 시도해 `SelfApprovalNotAllowedException`(400)이 재현되는지 검증(`approveBySameLoginSession_isRejectedAsSelfApproval`, `rejectBySameLoginSession_isRejectedAsSelfApproval`). 서로 다른 두 관리자 계정으로 기안/승인·거절 시 정상 처리되고 `checkerId`/`rejectionReason`이 실제로 저장되는지도 함께 검증.
- `ApproveTransferBypassesWebGateIntegrationTest`/`ApproveTransferTriggersFraudCheckIntegrationTest`는 컨트롤러를 거치지 않고 `ApproveTransferService`를 직접 호출하는 구조라 Authentication과 무관 — Command 시그니처가 유지되므로 변경 없음.
- 전체 테스트(`./gradlew test`) 145개 통과, `./gradlew spotlessCheck` 통과.

**설계 결정**: Command의 makerId/checkerId가 "인증된 신원"이라는 보장이 컨트롤러(웹 어댑터)에서만 성립하고 application/domain 계층엔 이를 강제하는 코드가 없다는 점을 `ARCHITECTURE.md` 결정 15번으로 기록 — 자세한 내용은 [`ARCHITECTURE.md`](./ARCHITECTURE.md) 참고.

### 과제 26: Security 에러 응답 UTF-8 인코딩 수정 (완료)

브랜치: `chore/fix-error-response-charset` → `develop` (PR #62)

**배경**

- 관리자 조회 API 배치1의 curl 검증 도중, 인증 실패(401) 응답의 한글 메시지가 `?`로 깨져 나오는 걸 우연히 발견. `SecurityConfig.writeErrorResponse()`(401 `authenticationEntryPoint`/403 `accessDeniedHandler`가 공유하는 메서드)가 `response.setContentType()`만 호출하고 `setCharacterEncoding()`을 호출하지 않아, 서블릿 컨테이너가 플랫폼 기본 인코딩(ISO-8859-1)으로 응답 바디를 써서 한글이 깨졌음. `GlobalExceptionHandler`의 다른 에러 응답들은 `ResponseEntity` + Jackson `HttpMessageConverter` 경로라 이 문제와 무관.

**구현 내용**

- `writeErrorResponse()`에 `response.setCharacterEncoding("UTF-8")`을 `setContentType()`보다 먼저 호출하도록 추가. 401/403 두 핸들러가 이 메서드 하나를 공유하므로 한 줄 수정으로 둘 다 해결됨(403은 이 프로젝트에 역할 기반 인가 자체가 없어 실제로 트리거되지는 않지만 — 결정 16번 — 같은 코드 경로이므로 구조적으로 함께 고쳐짐).

**테스트**

- `LoginIntegrationTest`에 `protectedEndpoint_withoutToken_returnsUtf8EncodedErrorBody` 추가 — 응답 `Content-Type`에 `charset=UTF-8` 포함 여부와 한글 메시지 원문을 검증. 수정 전 코드로 되돌려 이 테스트가 실제로 실패하는 것까지 확인한 뒤 재적용(회귀 테스트가 실제로 회귀를 잡는지 검증).
- `bootRun` 실기동 후 `xxd`로 raw 바이트 직접 확인 — 수정 전 `3f 3f 3f`(`?`)였던 자리가 수정 후 유효한 UTF-8 멀티바이트 시퀀스로 바뀜.
- 전체 테스트 146개 통과.

### 과제 27: 관리자 조회 API 배치1 — 승인이력/Reconciliation목록/원장조회 (완료)

브랜치: `feat/admin-query-apis-batch1` → `develop` (PR #63)

**배경**

- 관리자 프론트엔드가 필요로 하는 조회(읽기 전용) API 6종을 1단계 조사 문서로 먼저 설계 확정한 뒤, 공통 패턴(페이지네이션/필터/날짜 타입/인증)을 하나로 정하고 6개 중 3개를 이번 배치에서 구현.

**구현 내용**

- `application.port.out.PagedResult<T>`(items, totalElements)/`adapter.in.web.dto.PageResponse<T>`(content/totalElements/page/size/totalPages) 신설 — `Pageable`/`Page<T>`는 `adapter.out.persistence` 내부로만 한정하고, Port/응답 계층엔 프레임워크 타입을 노출하지 않음(자세한 내용은 `ARCHITECTURE.md` 결정 17번 참고).
- ① 승인 요청 이력: `LoadApprovalRequestPort.search()` 추가(기존 `loadByStatus`는 유지), `GET /api/v1/transfer-approvals`(status 선택 필터 + from/to `Instant` + page/size).
- ② Reconciliation 불일치 목록: `LoadReconciliationDiscrepancyPort` 신규, `ReconciliationDiscrepancyController` 신규, `GET /api/v1/reconciliation-discrepancies`(status 선택 필터 + from/to `LocalDate`).
- ③ 계좌별 원장 조회: `LoadLedgerEntriesPort.loadByAccountNumberAndPeriod()` 추가(기존 `loadByAccountNumberSince`는 Reconciliation 배치가 그대로 사용 중이라 유지), `AccountController`에 `GET /{accountNumber}/ledger-entries` 추가.
- 전부 `SecurityConfig`의 기존 `anyRequest().authenticated()` 원칙 그대로 — 역할 세분화 없이 로그인 여부만 검사(`ARCHITECTURE.md` 결정 16번, 이번 과제에서 함께 기록).

**테스트**

- Port 구현체별로 필터 조합/기간 범위/페이지네이션 경계(전체 건수보다 큰 페이지 요청 시 빈 content) 검증.
- 컨트롤러는 실제 로그인 세션(JWT)으로 200을 확인하고 인증 없이 호출 시 401도 함께 검증.
- 전체 테스트 162개 통과.

### 과제 28: 관리자 조회 API 배치2 — EOD 스냅샷 조회 (완료)

브랜치: `feat/admin-query-apis-batch2` → `develop`

**배경**

- 배치1과 동일한 공통 패턴으로 6종 중 EOD 스냅샷 조회 2개(계좌별 히스토리 / 날짜별 전체 계좌)를 구현.

**구현 내용**

- `LoadEodSnapshotHistoryPort` 신규(`byAccountNumber`/`byDate`) — 기존 `LoadLatestEodSnapshotPort`(계좌 1개의 최신 스냅샷 1건, 잔액 계산 앵커용)와 `LoadEodSnapshotByDatePort`(계좌 목록 + 정확한 날짜 1개, Reconciliation 배치 전용 벌크 조회, `Map` 반환)는 이름은 비슷하지만 목적이 전혀 달라 손대지 않음.
- `AccountController`에 `GET /{accountNumber}/eod-snapshots`(계좌 하위 자원 — 배치1의 `ledger-entries`와 동일 패턴으로 기존 컨트롤러에 귀속), 신규 `EodSnapshotController`에 `GET /api/v1/eod-snapshots?date=`(어떤 기존 프리픽스에도 속하지 않는 독립 최상위 자원 — 배치1의 `ReconciliationDiscrepancyController` 신설과 동일한 논리로 컨트롤러 분리).
- nullable `LocalDate` 파라미터가 `IS NULL` 단독 위치에서 Postgres가 파라미터 타입을 추론하지 못하는 문제(`could not determine data type of parameter`)를 실제 테스트로 발견 — `cast(:param as date)`를 명시적으로 추가해 해결(배치1에서 같은 nullable-OR 패턴을 쓴 `ApprovalStatus`는 캐스트 없이도 통과했던 것과 대비됨, 트러블슈팅 섹션에 원인 기록).

**테스트**

- Port 구현체 테스트(전체 조회/기간 범위/`byDate`), 컨트롤러는 실제 로그인 세션 통합 테스트 + 인증 없이 401, 페이지네이션 경계.
- 전체 테스트 174개 통과.

### 과제 29: 관리자 조회 API 배치3(배치 Job 이력/Outbox 이벤트) — 6종 완료, JobRepository 실제 영속화로 전환 (완료)

브랜치: `feat/admin-query-apis-batch3` → `develop`

**배경**

- 1단계 조사에서 확정한 관리자 조회 API 6종 중 마지막 2개(배치 Job 실행 이력, Outbox 감사로그 이벤트 목록)를 구현. 이번 배치를 마지막으로 6종 전부 완료.

**중대 발견 — JobRepository가 실제로는 Postgres에 영속화되지 않고 있었음**: 배치 Job 이력 조회 기능을 테스트하던 중, 이 프로젝트의 `JobRepository`가 Spring Boot 4.0의 `spring-boot-batch` 모듈이 기본 제공하는 `ResourcelessJobRepository`(필드 하나에 "가장 최근 실행된 Job 인스턴스 1개"만 기억하는 인메모리 스텁)였다는 걸 확인했다. `application.yaml`의 `spring.batch.jdbc.initialize-schema: always`는 Boot 4에서 이 기능을 제공하던 스키마 초기화 빈 자체가 `spring-boot-batch` 모듈에서 빠지면서 아무 효과가 없었고, Postgres에 `BATCH_*` 테이블이 아예 없었다(`BatchAutoConfiguration` 클래스 자체에 "Auto-configuration for Spring Batch **using an in-memory store**"라고 명시돼 있음). `DefaultBatchConfiguration.jobRepository()`가 `new ResourcelessJobRepository()`를 하드코딩해서 반환하기 때문에, DataSource가 있어도 자동으로 JDBC 기반으로 승격되지 않음. EOD/Reconciliation Job은 "당일 1회만 실행"을 단일 테스트 메서드 안에서만 검증해왔기 때문에 이 제약이 지금까지 드러나지 않았을 뿐, **`JobInstanceAlreadyCompleteException` 기반 재실행 방지도 지금까지는 앱을 재시작하면 무력화되는 상태**였다(부수적으로 함께 발견된 기존 잠재 버그).

**해결**: 사용자 확인 후 JobRepository를 실제로 Postgres에 영속화되도록 전환(1개 옵션 제시 후 채택 — 대안은 "이번 배치에서 배치 Job 이력 기능 보류"였음).
- `src/main/resources/db/batch-schema-postgresql.sql` — Spring Batch 공식 `schema-postgresql.sql`(spring-batch-core jar 내장)을 그대로 가져오되, 모든 `CREATE TABLE`/`CREATE SEQUENCE`에 `IF NOT EXISTS`를 추가해 매 기동마다 재실행해도 안전하게(idempotent) 만듦. 이 프로젝트는 Flyway/Liquibase를 쓰지 않아 JPA 엔티티는 `ddl-auto`로 관리하지만, Spring Batch 스키마는 `@Entity`가 아니라 순수 JDBC 테이블이라 그 메커니즘 밖에 있음 — 대신 `spring.sql.init.schema-locations`로 이 파일을 지정하고 `spring.sql.init.mode: always`로 매 기동 시 실행되게 함(효과 없던 `spring.batch.jdbc.initialize-schema` 설정은 제거).
- `global.config.BatchRepositoryConfig`(`DefaultBatchConfiguration` 상속) — `jobRepository()` 빈을 오버라이드해 `JdbcJobRepositoryFactoryBean`으로 앱의 실제 `DataSource`/`PlatformTransactionManager`를 사용하도록 교체. 이 클래스가 `DefaultBatchConfiguration` 타입 빈으로 등록되는 순간 Boot의 `BatchAutoConfiguration`(`@ConditionalOnMissingBean(value = DefaultBatchConfiguration.class, ...)`)이 자동으로 물러남.
- 부수 효과: EOD/Reconciliation Job의 재실행 방지 보호도 이제 앱 재시작 후에도 실제로 유지됨(위 잠재 버그 해결).

**구현 내용**

- `BatchJobExecutionSummary`(`application.port.out`, `domain.model` 아님) — 은행 업무 도메인이 아니라 Spring Batch 인프라 메타데이터라 `domain.model`에 두지 않고, `GetAccountUseCase.AccountDetail`처럼 Port 전용 결과 레코드를 Port 근처에 두는 기존 전례를 따름. `status` 필드는 `BatchStatus`(프레임워크 타입)를 그대로 안 쓰고 `String`으로 변환해 어댑터 밖으로 프레임워크 타입이 새지 않게 함.
- `LoadBatchJobExecutionHistoryPort`/`adapter.out.batch.BatchJobExecutionHistoryAdapter`(신규 패키지) — `JobRepository`(`JobExplorer` 아님 — Batch 6.0부터 `@Deprecated(forRemoval=true)`)를 감싸 `JobExecution`/`JobInstance` 같은 프레임워크 타입이 어댑터 밖으로 새지 않게 번역. `getJobInstances(jobName, start, count)`로 페이지를 가져오고 `getJobExecutions(instance)`로 실행 이력을 펼침, `getJobInstanceCount(jobName)`으로 totalElements 계산(`NoSuchJobException`은 "한 번도 안 돈 Job"으로 보고 0 처리).
- 신규 `BatchJobExecutionController` — `GET /api/v1/batch-jobs/{jobName}/executions`.
- `LoadOutboxEventsPort`(신규, `loadPage`) — 기존 `LoadAllOutboxEventsPort.loadAllOrderedById()`(무제한 List, `/verify` 전용 해시체인 전량 검증에는 정당)는 손대지 않음. `OutboxEventJpaRepository`에 `Pageable` 오버로드 추가.
- 기존 `AuditController`에 `GET /api/v1/audit/events` 추가(새 컨트롤러 만들지 않음 — `/verify`와 같은 리소스군). `OutboxEventResponse`는 `aggregateType`/`aggregateId`/`eventType`/`createdAt`만 노출 — `entryHash`/`previousHash`는 해시체인 무결성 검증(`/verify`)의 내부 계산값이라 목록 조회에는 노출하지 않음(최소 필드 원칙).

**테스트**

- `BatchJobExecutionHistoryAdapterTest` — `eodSettlementJob`/`reconciliationJob`을 실제로 각각 launch해 실행 이력을 만든 뒤, jobName으로 조회하면 서로 섞이지 않고 정확히 해당 Job 것만 반환되는지 검증(이 기능의 핵심 정합성). 배치 메타데이터 테이블은 `JdbcTemplate`으로 직접 `TRUNCATE`해 격리(`JobRepositoryTestUtils.removeJobExecutions()`는 실행(Execution) 없이 인스턴스만 있는 행을 정리하지 못하는 gap이 있어 이 프로젝트의 `deleteAllInBatch()` 컨벤션과 동일하게 직접 초기화하는 쪽을 택함).
- `BatchJobExecutionControllerTest`/`AuditControllerTest` — 실제 로그인 세션 통합 테스트 + 인증 없이 401, 페이지네이션 경계.
- `OutboxPersistenceAdapterTest`에 `loadPage()` 단위 테스트 추가.
- 전체 테스트(`./gradlew test`) 183개 통과, `./gradlew spotlessCheck` 통과.

**문서**: `ARCHITECTURE.md`에 JobRepository 실제 영속화 결정(18번)과 관리자 조회 API 6종이 배치 처리량/락 경합과 무관하다는 결정(19번)을 기록. `README.md`에 6종(엔드포인트 기준 7개) 전체 목록 표 추가.

### 과제 30: Redisson Azure Cache for Redis 인증/TLS 지원 (완료)

브랜치: `feat/redisson-auth-tls` → `develop` (PR #73)

Azure Cache for Redis는 비밀번호 인증과 TLS가 기본 강제되는데 `RedissonConfig`에 해당 설정 필드가 없어 연결이 실패했다. 비밀번호/SSL 값이 비어있으면 기존 로컬 docker-compose Redis와 동일하게 동작하도록 설계해 로컬 무변경 호환성을 유지했다. `ShedLockConfig`가 쓰는 Boot 자동구성 `RedisConnectionFactory`도 동일하게 password/ssl을 반영하는지 별도 테스트로 검증.

### 과제 31: 듀얼 DataSource + 듀얼 JobRepository 배관 구축 — 데모 랩 인프라 착수 (완료)

브랜치: `feat/demo-datasource-infrastructure` → `develop` (PR #75)

공개 데모 프론트엔드가 운영 데이터에 전혀 영향을 주지 않도록, 운영 DB는 기존 코드 그대로 두고 `adapter.out.persistence.demo` 패키지만 별도 `DataSource`/`EntityManagerFactory`/`TransactionManager`로 물리 격리했다. `AbstractRoutingDataSource`(런타임 조건 분기, 실수 위험)가 아니라 `@Qualifier` 정적 배선(컴파일 타임에 "이 코드가 운영 DB를 건드릴 수 있는가"가 이미 결정됨)을 채택. 배치 메타데이터도 크로스 DB 트랜잭션 원자성 문제를 피하려 운영/데모 `JobRepository`를 완전히 분리했다. `demoSmokeTestJob`으로 데모 `JobRepository`가 실제로 데모 DB의 `BATCH_*` 테이블에 읽고 쓰고 운영에는 영향이 없는지 검증. 후속 커밋에서 `demoSmokeTestStep`의 `demoTransactionManager` 명시 배선, `ddl-auto`가 `EntityManagerFactoryBuilder` 공유로 데모 EMF에도 동일 적용되는지, `ddl-auto: validate` + `sql.init.mode: never` 조합의 실제 기동까지 재확인하고 `db/demo-schema-postgresql.sql`을 신설(`DEPLOYMENT.md`가 서술 대신 이 파일을 가리키도록 갱신). 자세한 내용은 `ARCHITECTURE.md` 결정 20번 참고.

### 과제 32: 데모 EOD 정산 Job + 온디맨드 트리거 (완료)

브랜치: `feat/demo-eod-ondemand-trigger` → `develop` (PR #76)

기존 `EodSettlementJobConfig`를 템플릿으로 `demoEodSettlementJob`을 구성 — Step은 `demoTransactionManager`로 명시 배선하고 Reader/Processor/Writer 전부 데모 전용 포트를 주입받는다. `AccountBalanceCalculator`/`VerifyTrialBalanceService`는 unqualified `@Transactional`이라 그대로 재사용하면 운영 트랜잭션 매니저가 딸려 들어가는 문제가 있어 `DemoAccountBalanceCalculator`/`DemoVerifyTrialBalanceService`를 별도로 신설해 데모 트랜잭션 매니저로 고정했다. 온디맨드 트리거(`POST /api/v1/demo/batch-jobs/eod/trigger`)는 크론과 동일한 `JobParameters` 생성 로직을 공유해 "당일 1회" 보호를 그대로 상속받고, `JobInstanceAlreadyCompleteException`은 409로 변환. `ddl-auto: validate` 검증 시 `bootRun` 태스크가 환경변수를 덮어써 검증이 안 되는 것을 확인하고 `java -jar` 직접 실행으로 재검증(과제 29 이전부터 반복되는 함정, `DEPLOYMENT.md`에 명시).

### 과제 33: 데모 Reconciliation Job + 온디맨드 트리거 — EOD와 동일 템플릿 (완료)

브랜치: `feat/demo-reconciliation-ondemand-trigger` → `develop` (PR #77)

기존 `ReconciliationJobConfig`를 템플릿으로 `demoReconciliationJob` 구성, 과제 32와 동일한 패턴(Step `demoTransactionManager` 명시, 데모 전용 Reader/Writer). Reader는 "Job마다 별도 빈"이라는 기존 컨벤션을 따라 `demoAccountItemReader`를 재사용하지 않고 `demoReconciliationAccountItemReader`를 새로 만듦. `SaveReconciliationDiscrepancyPort`는 신규 `DemoReconciliationDiscrepancyPersistenceAdapter`가 필요했고, `@Primary` 없이 먼저 테스트를 돌려 운영 `ReconciliationJobConfigTest`가 `NoUniqueBeanDefinitionException`으로 실패하는 걸 실측한 뒤 운영 어댑터에 `@Primary`를 추가(이 세션에서 반복된 "먼저 실측, 그다음 수정" 검증 원칙 재확인). 데모 EOD를 안 돌리고 Reconciliation만 트리거하면 `NO_SNAPSHOT`이 자연스럽게 재현되는 것을 실제 curl로 확인.

### 과제 34: 데모 이체 + 해시체인 감사로그 — Redisson 락 인프라 전체 복제(Option C) (완료)

브랜치: `feat/demo-transfer-concurrency-and-audit-chain` → `develop` (PR #78)

사전 조사에서 "Option D"(`Account.@Version` 낙관적 락 의존, 인프라 복제 없이 간소화)를 주 후보로 제안했으나, 사용자가 3가지 재검증을 요구해 확인한 결과 `TransferMoneyService.transfer()`가 이체 경로에서 `accountRepositoryPort.save()`를 한 번도 호출하지 않아 `@Version`이 발동할 여지가 없고(간소화가 아니라 동시성 제어 자체가 빠지는 회귀), `LockComparisonService`(과제 1-2) 벤치마크도 실제 `Account`가 아닌 별도 앵커 엔티티를 대상으로 하며, no-lock 베이스라인 측정 이력 자체가 없다는 것까지 확인 후 **Option C(운영과 동일하게 Redisson 락 + REQUIRES_NEW 트랜잭션 인프라를 데모 전용으로 완전 복제)**로 뒤집었다. `DemoDistributedLockAspect`는 락 키에 `"DEMO-LOCK:"` 접두사를 써서 운영 `"LOCK:"` 네임스페이스와 계좌번호가 겹쳐도 구조적으로 충돌하지 않게 했고, `RedissonClient`는 키 네임스페이스만으로 이미 분리되어 운영과 동일 인스턴스 재사용. `DemoTransferMoneyService`는 로직을 간소화하지 않고 운영과 완전히 동일(예약 계좌 검증/이상거래 판정/잔액 검증/LedgerEntry 쌍 저장/Outbox 발행) 구성. `outbox_chain_tail` 해시체인도 운영과 동일 구조로 데모 DB에 복제, `SaveOutboxEventPort` 구현체가 두 번째로 생기며 운영 `OutboxPersistenceAdapter`에 `@Primary` 추가. `DemoTransferConcurrencyTest`는 잔액을 초과하는 100건 동시 요청 중 정확히 잔액만큼(50건)만 성공함을 실측 — Option D였다면 성립하지 않았을 결과. 자세한 내용은 `ARCHITECTURE.md` 결정 21번 참고.

### 과제 35: TransferSagaOrchestrator — `sagaStateWriter.save()` 반환값 무시 버그 수정 (완료)

브랜치: `fix/saga-state-writer-return-value` → `develop` (PR #79)

인프라 팀(김준희)이 카오스 엔지니어링 시나리오 검증 중 발견. `TransferSaga.id`/`version`이 `final`이라 최초 저장 후 실제 값은 `save()`의 반환값에만 있는데, `TransferSagaOrchestrator`의 6번의 `save()` 호출 전부 반환값을 버리고 원래 `saga` 변수를 계속 참조 — 두 번째 저장부터 `id=null`로 INSERT를 시도해 `saga_id` 유니크 제약 위반이 나며 출금은 되고 입금/보상은 시도조차 안 된 채 크래시. `ApproveTransferService`가 반환값을 받아쓰는 것과 동일한 패턴으로 `saga = sagaStateWriter.save(saga)` 재할당. 재할당으로 `saga`가 effectively final이 아니게 되어 람다가 참조하던 필드들을 메서드 앞부분에서 한 번만 추출한 지역 `final` 변수로 교체.

**실제 JPA 테스트가 드러낸 2차 버그**: mock 없이 실제 JPA로 검증하라는 요구에 따라 통합 테스트를 짜자, 1차 수정만으로는 세 번째 `save()`가 `ObjectOptimisticLockingFailureException`으로 새롭게 실패 — `SagaStateWriter.save()`가 `@Transactional(REQUIRES_NEW)`라 `SagaPersistenceAdapter.save()`의 매핑 코드가 실제 flush(Hibernate가 버전 증가를 메모리에 반영하는 시점)보다 먼저 실행되어, 반환된 도메인 객체의 `version`이 DB 반영값보다 한 스텝 뒤처져 있었음. `transferSagaJpaRepository.save()` → `saveAndFlush()`로 교체해 해결(`ApproveTransferService`는 `REQUIRES_NEW` 래퍼가 없어 repository 레벨 트랜잭션이 그 자리에서 바로 커밋되므로 이 문제 자체가 없었음). `TransferSagaOrchestratorIntegrationTest` 작성 중 `@MockitoBean`으로 `Withdrawal/DepositParticipantPort`를 오버라이드하면 별도 `@SpringBootTest` 컨텍스트가 하나 더 생겨(컨텍스트마다 자체 HikariCP 풀) 전체 스위트를 한 번에 돌릴 때만 간헐적 커넥션 풀 고갈이 나는 것도 발견 — 실제 어댑터를 그대로 타도록 바꿔 기본 컨텍스트를 재사용하는 쪽으로 해결. 자세한 내용은 `ARCHITECTURE.md` 결정 25번 참고.

### 과제 36: 데모 승인/거절/기안 워크플로 (완료)

브랜치: `feat/demo-approval-workflow` → `develop` (PR #80)

`ApproveTransferService`/`RejectTransferService`/`RequestTransferApprovalService`는 클래스 레벨 `@Transactional`/AOP가 없어 포트만 데모로 갈아끼워 그대로 복제. `DemoApproveTransferService`는 `request.approve()` 저장 → transfer 실행 → `markExecuted()`/`markExecutionFailed()` 흐름을 운영과 완전히 동일한 `TransferApprovalRequest` 도메인 메서드로 재사용(도메인 모델 공유, 자기승인 방지도 별도 구현 없이 자동 상속). `ApprovalPolicy`/`FraudCheckPort`는 stateless라 데모 버전 없이 운영 빈 공유. `DemoApprovalRequestEntity` 등은 운영 스키마와 컬럼 단위로 동일 구성, `SaveApprovalRequestPort`/`LoadApprovalRequestPort` 두 번째 구현체가 생기며 운영 `ApprovalPersistenceAdapter`에 `@Primary` 추가. `DemoTransferApprovalController`는 4개 엔드포인트(기안/승인/거절/단건조회)만 미러링(목록조회/이력검색은 스코프 제외). 테스트는 자기승인 방지(승인·거절 둘 다), 다른 계정 정상 승인·거절 시 실제 데모 이체 실행/사유 저장, 이상거래 임계치 이상 승인 시 `status=APPROVED` 유지 + `executionStatus=FAILED`(과제 23 로직 재사용 확인), 데모 결과가 운영 DB엔 전혀 없음, 인증 없이 401까지 실제 MockMvc+JPA로 검증.

### 과제 37: DEMO 역할 도입 + 엔드포인트 분리, JWT 인가 하드코딩 버그 수정 (완료)

브랜치: `feat/demo-role-authorization` → `develop` (PR #81)

`AdminRole`에 `DEMO` 추가, `SecurityConfig`가 `/api/v1/demo/**`는 `hasAnyRole("DEMO","ADMIN")`, 그 외 `anyRequest()`는 `hasRole("ADMIN")`으로 강제하도록 재구성(기존 `anyRequest().authenticated()`에서 역할 체크로 강화 — 과제 27 결정을 뒤집음, `ARCHITECTURE.md` 결정 16번 amendment 참고).

**사전 확인이 잡아낸 버그**: `AdminUserDetailsService`(로그인 시점)는 실제 역할을 정확히 반환하지만, `JwtAuthenticationFilter`(매 요청 인증 경로)는 토큰의 실제 역할과 무관하게 항상 `"ROLE_ADMIN"`을 하드코딩해서 부여하고 있었다. `JwtTokenAdapter.issueToken()`은 처음부터 JWT에 `"role"` claim을 심고 있었는데 필터가 전혀 읽지 않았던 것 — 역할이 `ADMIN` 하나뿐이던 시절엔 결과적으로 항상 맞았지만, `DEMO`를 추가하는 순간 DEMO 계정도 전부 `ROLE_ADMIN`을 받아 이 배치의 목적(운영 엔드포인트 403 차단) 자체가 성립하지 않는 상태였다. `TokenPort.extractRole(token)` 추가(자체 서명 검증 포함 — `validateToken()` 없이 단독 호출해도 위조 토큰을 안 믿음)로 해결.

`DemoAccountSeeder`는 `AdminUserSeeder`와 동일한 idempotent 패턴(env var 둘 다 비어있으면 스킵, username 이미 존재하면 스킵)으로 복제, 서로 다른 username·설정 프리픽스라 두 `ApplicationRunner`는 실행 순서 무관하게 독립적. `LoginResponse`/`LoginUseCase.LoginResult`에 `role` 필드 추가.

**부수 발견**: `admin_users.role` DB CHECK 제약이 여전히 `'ADMIN'` 하나만 허용(`ddl-auto: validate`가 컬럼 존재만 검증하고 CHECK 내용은 검증 안 함)해 DEMO 계정 저장이 `DataIntegrityViolationException`으로 막히는 것을 발견 — 로컬 DB 제약 갱신, `DEPLOYMENT.md`에 배포용 DDL 추가. `AdminUserPersistenceAdapter.save()`가 이 CHECK 위반을 `DuplicateAdminUsernameException`으로 오역하는 기존 버그도 함께 문서화. `DEMO_ACCOUNT_USERNAME`/`PASSWORD`는 공개 노출이 의도된 설계임을 `ADMIN_INITIAL_*`(silent-breach)와 구분해 명시(`ARCHITECTURE.md` 결정 23번).

### 과제 38: 데모 데이터 리셋(DemoDataResetService) + 온디맨드 배치 423 락 체크 (완료)

브랜치: `feat/demo-data-reset` → `develop` (PR #82)

`DemoDataResetService.reset()` 단일 `@Transactional("demoTransactionManager")`로: 데모 6개 테이블 전체 삭제 → `BATCH_JOB_INSTANCE` 이하 6개 테이블을 `demoEodSettlementJob`/`demoReconciliationJob` job명 한정으로 FK 역순 삭제 → `DEMO-AUDIT-DEMO`/`DEMO-AUDIT-COUNTERPARTY` 계좌+원장 시딩 → outbox_event 3건을 기존 `chainedWith()` 자동 체이닝으로 적재 → 마지막 1건만 JPQL bulk UPDATE로 payload 직접 변조(entryHash는 원본 payload 기준 그대로 남아 재계산 시 불일치) → Redis에 리셋 완료 시각 기록. 변조는 방문자가 실시간으로 트리거하는 API가 아니라 리셋 시퀀스 안에 사전 구성으로만 존재 — 프로덕션 코드에 "감사로그를 깨는 엔드포인트"를 남기지 않기 위함(`ARCHITECTURE.md` 결정 24번).

헥사고날 경계를 지키려 포트 5개 신설: `DemoDataWipePort`(6개 데모 어댑터의 `deleteAllInBatch`를 오케스트레이션), `DemoBatchJobHistoryPort`(`BATCH_*`가 JPA 엔티티가 아니라 `@PersistenceContext(unitName="demo")` 네이티브 SQL 필요 — 이 프로젝트 최초의 EntityManager 직접 사용), `DemoOutboxTamperPort`, `DemoResetLockPort`/`DemoResetStatusPort`(Redis 기반, 신규 `adapter.out.lock` 패키지). `DemoResetLockPort`는 ShedLock이 락 보유 여부 조회 공개 API를 제공하지 않아 `RedisLockProvider`의 실제 내부 키 포맷(`"job-lock:" + environment + ":" + lockName`)을 직접 재구현.

`DemoDataResetScheduler`는 `@Scheduled(cron="${demo.reset.cron:0 */30 * * * *}")` + `@SchedulerLock(name="demoDataReset")`. Demo EOD/Reconciliation 트리거 컨트롤러 둘 다 `jobOperator.start()` 호출 전 락 보유 여부를 확인해 보유 중이면 423 반환. `GET /api/v1/demo/reset-status`는 Redis에 기록된 마지막 리셋 시각과 `CronExpression.parse(cron).next(now)`로 계산한 다음 리셋 예정 시각을 반환.

동시성 테스트로 리셋 트랜잭션 진행 중 반복 조회해도 리셋 전/후 개수만 관측되고 중간값이 노출되지 않음을 실측(Postgres READ COMMITTED + 단일 트랜잭션이면 당연히 보장되는 성질이지만 직접 관측). 변조 판정 테스트는 outbox_event 3건 중 앞 2건은 `entryHash == recomputeEntryHash()`, 마지막 1건만 불일치함을 확인. 배치 메타데이터 테스트는 EOD 트리거를 당일 2회 호출해 409를 재현한 뒤 `reset()`을 호출하고 3번째 호출이 다시 200으로 성공함을 확인해 "데모 Job 한정 삭제"가 실제로 동작함을 증명.

### 과제 39: 감사로그 해시체인 필드 노출 + 승인 실행 실패 사유 방어 (완료)

브랜치: `feat/expose-audit-hashes-and-execution-failure-reason` → `develop` (PR #89)

프론트엔드 팀 요청으로 두 응답 DTO에 도메인 모델엔 있으나 누락됐던 필드를 반영.

1. `OutboxEventResponse`에 `previousHash`/`entryHash`/`traceId`/`spanId` 추가 — `/api/v1/audit/events` 목록도 `/verify`와 동일하게 해시체인 검증용 값을 노출하도록 확장. 착수 전 검토에서 과제 29의 "최소 필드 원칙"(entryHash/previousHash를 목록 API에서 의도적으로 제외) 결정과 충돌함을 확인했으나, 이번 요청이 명시적으로 승인한 범위라 그대로 반영(사용자 재확인 후 진행).
2. `TransferApprovalDetailResponse`에 `executionFailureReason` 추가. 반영 전 `TransferMoneyService.transfer()`가 호출하는 포트 5종을 전수 확인한 결과, `AccountRepositoryPort`/`SaveLedgerEntryPort`는 인프라 예외를 도메인 예외로 번역하지만 `SaveOutboxEventPort`(`OutboxPersistenceAdapter`)는 과제 4 결정(트랜잭션 롤백 보장을 위한 의도적 미번역)에 따라 raw 예외를 그대로 전파함을 확인 — 이 raw 메시지가 `ApproveTransferService.approve()`의 catch 블록을 통해 `executionFailureReason`에 그대로 저장될 위험이 있음을 구현 전에 보고. `OutboxPersistenceAdapter`의 기존 설계(예외 미번역)는 그대로 두고, catch 지점에서 `com.fbrl.domain.exception` 패키지 소속 예외만 원본 메시지를 저장하고 그 외(인프라 예외)는 고정 문구로 대체하는 방어(`ApproveTransferService.safeExecutionFailureReason()`)를 신설.

검증: `AuditControllerTest`(목록에 해시 필드 노출 확인, 기존 "entryHash 미노출" 단정 제거), `TransferApprovalControllerTest`(승인 성공 시 executionStatus 확인 추가), `ApproveTransferServiceTest`(인프라 예외 시 고정 문구 저장 확인 신규 케이스), 기존 `ApproveTransferTriggersFraudCheckIntegrationTest`(도메인 예외 경로는 원본 메시지 그대로 유지됨을 재확인)까지 전체 239건 통과.

### 과제 40: 데모 데이터 리셋 대차평형 버그 수정 (완료)

브랜치: `fix/demo-reset-trial-balance` → `develop`

프론트엔드 팀이 `DemoDataResetService.reset()`이 `DEMO-AUDIT-DEMO` 계좌 초기 잔액(100만원)을 상대편 DEBIT 없는 단일 `LedgerEntry.of(..., CREDIT, ...)`로 저장하고 있음을 발견 — 원장 전체 차변/대변 합이 리셋마다 100만원씩 어긋나 `demoTrialBalanceVerificationStep`(`demoEodSettlementJob`)이 항상 실패하는 구조였음.

`OpeningBalanceMigrationService.seedOpeningBalances()`가 쓰는 것과 동일한 패턴 — `SystemAccounts.OPENING_BALANCE_SOURCE`(실제 계좌 row 없는 sentinel)를 상대 계좌로 삼는 `LedgerEntry.transferPair(...)` — 로 교체해 DEBIT/CREDIT 페어로 시딩하도록 수정.

수정 전 코드로 실제 버그를 재현: 리셋 후 원장 전체 총 차변 100,000.00 KRW vs 총 대변 1,100,000.00 KRW(정확히 100만원 어긋남), `demoJobOperator.start(demoEodSettlementJob, ...)`가 `TrialBalanceViolationException`으로 `BatchStatus.FAILED` 종료됨을 확인 — "리셋 → EOD 트리거" 조합이 이 버그가 존재하는 한 한 번도 COMPLETED로 끝난 적이 없었음을 실증. 수정 재적용 후 동일 테스트 통과.

신규 `DemoDataResetTrialBalanceIntegrationTest` — 리셋 후 원장 DEBIT/CREDIT 합계를 `ledger_entries` 테이블에 직접 SQL로 대조(`demoTrialBalanceVerificationStep`과 동일한 로직 재현), 리셋→데모 EOD Job 온디맨드 트리거→`BatchStatus.COMPLETED` 종단 테스트 추가. 기존 `DemoDataResetIntegrationTest`(계좌 개수/outbox 개수 등 raw 쿼리 검증) 6건도 회귀 없이 통과. 전체 241건 통과.

## 🚧 다음 작업

- (보류) 승인은 됐으나 집행(실제 이체) 실패한 건의 재시도 정책 — 과제 23에서 `TransferApprovalRequest.executionStatus`(NOT_APPLICABLE/EXECUTED/FAILED)로 "승인 행위"와 "집행 결과"를 분리했지만, `executionStatus=FAILED`로 남은 건을 재시도시킬 API/운영 절차는 이번 스코프에서 의도적으로 제외(YAGNI). 재시도 API 필요 시: 같은 요청을 다시 집행할지, 아니면 신규 승인 요청을 처음부터 다시 만들게 할지부터 결정 필요.
- CI 파이프라인 부재 — GitHub Actions로 PR마다 `./gradlew test` 자동 실행하는 게 없어, 지금까지는 사람이 매번 로그를 직접 요구해서 확인. "재현 가능한 품질 관리"를 위해 다음 세션 우선순위로 권장.
- 엔드포인트별 Swagger `@Operation`/`@Schema` 문서화 — 과제 24에서 최소 설정만 도입, 상세 문서화는 제외(YAGNI).
- 트랙 3(장애 복구 & 카오스 엔지니어링) 두 과제(Kafka DLQ, Resilience4j) 완료 — 3개 트랙(실시간 트랜잭션/EOD 배치/장애복구) 모두 핵심 구현 최소 1개 이상 완료.
- (협업 필요) Chaos Mesh 인프라 결함 주입 — 노션 "프로젝트 개요"상 Infra(김준희) 담당 업무. 백엔드가 처음부터 CRD/클러스터까지 다 짜는 게 아니라, "어떤 장애 시나리오로 무엇(서킷 브레이커/재시도 등)을 검증할지"를 먼저 정의해 인프라 담당자와 공유하고, 실제 장애 주입 후 애플리케이션 반응을 검증하는 역할 분담으로 진행할 것
- (보류) 실제 Kafka 브로커 기반 재시도 토픽 → DLT 라우팅 통합 테스트 (과제 11에서 범위 분리)
- (보류) 실제 Kafka 브로커 E2E 수동 검증
- (보류) Debezium EventRouter SMT의 `table.fields.additional.placement`(trace_id/span_id 헤더 라우팅)를 커버하는 자동화된 통합 테스트 — 위 두 항목과 같은 이유(실제 Kafka 브로커 필요)로 보류, 현재는 로컬 수동 검증으로만 확인됨(과제 17 참고)
- (보류) Testcontainers 기반 통합 테스트 재검증 — 프로젝트 전체가 docker-compose 기반 통합 테스트 컨벤션을 일관되게 쓰고 있어 현재는 도입 보류로 결정(Testcontainers는 이 컨벤션과 공존 시 일관성이 깨짐, YAGNI)
- (권장) 실제 배포 대상 Postgres에 `accounts.balance` 컬럼 등 orphan 컬럼이 남아있다면 `ALTER TABLE ... DROP COLUMN`으로 별도 정리 필요(과제 16 참고, 이 프로젝트는 Flyway/Liquibase 미사용)
- (보류) Reconciliation Job 레벨 사전 게이트(당일 `eodSettlementJob` 완료 여부를 확인 후 스킵, 구현 시 `JobExplorer`가 아니라 `JobRepository` 사용 — 과제 29에서 `JobRepository`가 실제로 Postgres에 영속화되도록 이미 전환됨) — 계좌별 `NO_SNAPSHOT` 분류만으로도 "EOD가 안 돌았다"는 사실이 이미 드러나므로 이번 스코프에서는 제외(YAGNI). 알림이 너무 많이 쌓여 노이즈가 문제되면 추가(과제 22 참고)

## 🤖 AI 에이전트(Claude Code) 활용 방침

- 현재는 학습 목적상 위임 범위를 반복적·정형화된 작업(예: package-private 접근제어자 전수 수정, 테스트 코드의 정형화된 반복 패턴 작성)으로 의도적으로 제한 중
- 3개 트랙의 핵심 개념을 충분히 체득한 이후에는, 위임 범위를 넓혀 기능 리팩토링 및 신규 기능 추가까지 AI 에이전트에게 맡길 계획
- 다만 위임 범위가 넓어져도 이번 세션까지 확립된 검증 워크플로는 계속 유지: (1) 작업지시서에 판단 기준과 금지 규칙을 명시 → (2) git diff/파일 트리를 직접 검증 → (3) 기존 컨벤션과 다른 결과물은 근거를 들어 반려 후 재작성 요청

## 📌 트러블슈팅 / 지켜야 할 원칙

- Jackson 3 패키지 경로: `tools.jackson.databind.ObjectMapper`
- `@AutoConfigureMockMvc` 패키지 경로: `org.springframework.boot.webmvc.test.autoconfigure`
- AOP self-invocation 금지 → REQUIRES_NEW는 반드시 별도 스프링 빈으로 분리 (동일 원리가 `@CircuitBreaker`에도 적용됨 — 프록시로 감싸인 빈을 거쳐야만 동작)
- 멀티스레드 테스트 초기화 시 `deleteAllInBatch()` 사용 (`deleteAll()` 금지)
- ShedLock(Redis) 상태도 테스트 간 격리 대상 — `lockAtLeastFor`로 인해 Job 완료 후에도 락이 일정 시간 유지되므로, 해당 스케줄러를 검증하는 테스트는 `@BeforeEach`에서 락 키를 선제적으로 삭제할 것 (JPA의 `deleteAllInBatch()`와 동일한 논리를 Redis 상태에도 적용)
- 낙관적 락 사용 시 `@Version` 값이 도메인 매퍼에서 누락되지 않도록 주의
- 통합 테스트(`@SpringBootTest`)는 Docker(MariaDB/Redis/Kafka)가 떠 있어야 함 → `docker compose up -d` 먼저 확인
- Jackson 3: `ObjectMapper` 대신 불변(immutable) `JsonMapper`가 권장 진입점, Spring Boot가 자동 빈 등록
- `private` 생성자만 가진 불변 VO(예: `Money`)를 Jackson (역)직렬화 대상으로 쓰려면 정적 팩토리에 `@JsonCreator`/`@JsonProperty`를 반드시 지정할 것 — 안 붙이면 serialize(쓰기, getter만 필요)는 성공하고 deserialize(읽기)만 조용히 실패하는 비대칭 버그가 생기고, Mock으로 `PayloadDeserializerPort`를 대체한 테스트로는 절대 못 잡음. Jackson 3에서도 `@JsonCreator`/`@JsonProperty`는 `com.fasterxml.jackson.annotation`(구 패키지) 그대로 사용(과제 18 참고)
- Mockito `@InjectMocks`는 `@Mock` 안 된 생성자 파라미터에 null을 채워 넣으므로, 서비스 생성자 파라미터가 늘어나면 관련 단위 테스트의 `@Mock` 필드도 반드시 같이 추가
- JPA 전용 락(`@Lock(LockModeType...)`)을 다루는 클래스는 `adapter.out.persistence`에 위치
- package-private으로 좁힌 interface는 이를 참조하던 테스트 파일도 같은 패키지로 함께 이동
- Spring Boot 4.0 Kafka 모듈 분리: `KafkaAutoConfiguration`, `KafkaProperties`가 `org.springframework.boot.kafka.autoconfigure` 패키지로 이동. 순수 spring-kafka만 추가하면 이 자동설정 모듈이 클래스패스에 없어 오토와이어링 실패 → 반드시 `spring-boot-starter-kafka(-test)` 사용
- `KafkaProperties.buildProducerProperties()`는 Boot 3.4~3.x대에 SslBundles 인자를 받다가 Boot 4.0에서 다시 무인자로 환원됨 (버전별 시그니처 변경 주의, 도입 시 공식 API 문서 재확인 필수)
- Boot 4.0이 자동생성하는 `KafkaTemplate`은 `<Object,Object>` 타입 고정 — 원하는 제네릭 타입(`<String,String>`)을 쓰려면 `@ConditionalOnMissingBean`을 활용해 직접 빈 정의 필요
- Kafka Producer는 key가 파티셔닝 기준이 되므로, 순서 보장이 필요한 단위(계좌 등 aggregateId)를 key로 반드시 지정
- Kafka 토픽은 브로커 auto-create 기본값에 맡기지 말고 `NewTopic` 빈으로 파티션 수/복제 계수를 명시적으로 생성 (재시도/DLT 토픽도 동일 원칙 — `RetryTopicConfiguration`의 `autoCreateTopics()`로 명시적 생성)
- Kafka Consumer 재시도 예외 분류: 재시도해도 결과가 달라지지 않는 결정론적 실패는 반드시 `NonRetryableEventProcessingException`을 상속해야 하며, 그래야 `KafkaRetryTopicConfig` 재수정 없이 자동으로 즉시 DLT 라우팅 대상에 포함됨(OCP). 상속하지 않은 일반 `RuntimeException`은 기본적으로 재시도 대상으로 간주됨
- Kafka Consumer에서 역직렬화/처리 실패 시 예외를 컨슈머 메서드 내부에서 절대 삼키지 말 것(try-catch로 흡수 금지) — `RetryTopicConfiguration`이 재시도/DLT 여부를 판단하는 유일한 근거가 "리스너 메서드가 던진 예외 타입"이므로, 삼키면 메시지가 정상 처리로 오인되어 에러 로그 없이 조용히 유실됨
- 금액을 다루는 모든 도메인/포트 시그니처는 `BigDecimal`이 아닌 `Money` VO로 통일 (Saga 작업 중 위반 발견 후 전체 전환)
- 도메인 전용 Exception은 `domain.model`이 아닌 `domain.exception` 패키지 소속
- 감사(리뷰) AI가 PROGRESS.md 등 프로젝트 히스토리를 모르는 상태로 점검하면 오탐이 섞일 수 있음 → 리포트는 항상 과거 기록과 대조해서 검증할 것
- package-private 인터페이스 컨벤션: `*JpaRepository`(순수 JPA 구현 세부사항)는 package-private, `*Mapper`(변환 로직)는 public. 신규 리포지토리 생성 시 AI 에이전트가 관성적으로 public interface를 만드는 경우가 있으니 git diff에서 반드시 확인
- 낙관적 락 예외 catch 순서: `ObjectOptimisticLockingFailureException`은 `DataAccessException`의 하위 타입이므로, 어댑터에서 반드시 하위 타입을 먼저 catch할 것 — 순서가 바뀌면 더 구체적인 예외 분기가 영원히 발동하지 않음
- Saga 참여자 어댑터 실패 처리 원칙: 오케스트레이터로는 예외를 절대 전파하지 않고 항상 Result 객체로 수렴 — 알려진 도메인 예외뿐 아니라 예기치 못한 `RuntimeException`까지 잡지 않으면 실패 처리 경로가 두 갈래로 갈라져 saga 정합성이 깨짐
- 터미널 diff 확인 시 잘림 주의: `git diff`/`git status`가 pager나 터미널 버퍼로 인해 일부만 보이는 경우가 있음 — 전체 파일 수(`--stat` 마지막 줄의 "N files changed")와 실제 나열된 파일 수가 일치하는지 항상 대조하고, 의심되면 GitHub 웹의 커밋/PR file tree에서 최종 확인할 것. 페이저(less)에 멈췄을 때는 `git --no-pager diff`로 우회
- Spring Batch 6.0 패키지 재구성: `Job`→`core.job.Job`, `Step`→`core.step.Step`, `JobExecution`→`core.job.JobExecution`로 이동. `JobParameters`/`JobParametersBuilder`→`core.job.parameters`로 이동. `RepeatStatus`는 core 밑도 아닌 완전히 새 모듈 `org.springframework.batch.infrastructure.repeat.RepeatStatus`로 이동. `ItemReader`/`ItemProcessor`/`ItemWriter`/`ExecutionContext` 등 구 spring-batch-infrastructure 모듈 전체가 `org.springframework.batch.infrastructure.*`로 이동. 반대로 `BatchStatus`/`JobRepository`/`JobBuilder`/`StepBuilder`(단, `chunk(size, tm)` 오버로드는 deprecated → `chunk(size).transactionManager(tm)` 사용)/`Tasklet`/`JobOperator`는 패키지 그대로 — 클래스마다 다르므로 6.x 공식 문서(그것도 마일스톤이 아닌 GA 버전 문서인지 확인)로 매번 재확인 필요
- 테스트 유틸 세대교체: `JobLauncherTestUtils`(6.0 deprecated, 6.2+ 제거 예정) → `JobOperatorTestUtils`, `launchJob()` → `startJob()` 원칙이나, `startJob(JobParameters)`에 `@StepScope` 파라미터 전달 버그가 있어(spring-batch#5216) 그 경우엔 예외적으로 상속받은 `launchJob(JobParameters)` 사용
- IntelliJ가 `@SpringBatchTest`로 런타임에 동적 등록되는 빈(`JobOperatorTestUtils` 등)을 정적 분석으로 못 쫓아가 "오토와이어링 불가" 오탐을 낼 수 있음 — 에디터 빨간줄보다 실제 테스트 실행 결과를 우선 신뢰할 것
- 프로덕션 코드는 `JobOperator.start(Job, JobParameters)` 사용(JobLauncher는 6.0부터 deprecated, 6.2+ 제거 예정). 관련 예외(`JobInstanceAlreadyCompleteException` 등)는 `org.springframework.batch.core.launch` 패키지(JobOperator와 동일 패키지)
- ShedLock 공식 프로바이더 중 Redisson 전용은 없음 — Redisson을 쓰는 프로젝트라도 spring-boot-starter-data-redis가 자동 구성해주는 `RedisConnectionFactory` 기반 `shedlock-provider-redis-spring`을 사용
- `TransferEventConsumer`가 Kafka 헤더의 `trace_id`/`span_id`로 W3C traceparent를 조립할 때 sampled flag를 `"01"`(항상 샘플됨)로 하드코딩(`SAMPLED_TRACE_FLAGS`) — 현재 `management.tracing.sampling.probability: 1.0`(100% 샘플링)이라 드러나지 않지만, 나중에 샘플링 확률을 낮추면 producer 쪽에서 "샘플링 안 함"으로 결정한 trace도 consumer가 무조건 "샘플됨"으로 강제 복원하게 됨. 샘플링 확률을 조정할 때는 이 하드코딩도 함께 수정 필요(sampled 여부를 별도 컬럼/헤더로 전달하거나 span context의 실제 sampled 상태를 반영하도록 변경)
- Mockito 가짜 객체도 원본 인터페이스의 checked exception 시그니처를 그대로 물려받음 — `verify(mock).method()`에서도 그 예외 처리가 필요할 수 있음
- K8s Lease API의 리더 선출도 낙관적 락(resourceVersion) 기반 — JPA `@Version` 충돌 처리와 동일한 사고방식으로 접근 가능. 다만 Split-Brain(옛 리더가 죽은 게 아니라 응답만 지연된 경우 짧게 리더가 둘로 보이는 상황) 위험은 DB 유니크 제약 같은 최후 방어선과는 별개로 반드시 고려해야 함 — 유니크 제약은 저장 단계의 중복만 막을 뿐, 그 전 단계(읽기/계산)의 자원 낭비는 못 막음
- `@Bean` 팩토리 메서드가 unchecked exception(`IllegalStateException` 등)을 던질 수 있음 — 특정 checked exception(`IOException` 등)만 잡도록 catch를 좁게 설계하면 실제 라이브러리가 던지는 예외를 못 잡을 수 있으니, "외부 인프라 감지 후 폴백"처럼 의도가 명확한 방어 로직에서는 `Exception`으로 넓게 잡는 것이 오히려 올바른 설계일 수 있음(무분별한 예외 은폐와는 구분할 것 — 로그를 남기고 명시적으로 대체 경로로 전환하는 경우에 한함)
- AI 에이전트(Claude Code)에게 diff를 승인하기 전, 이 프로젝트의 기존 컨벤션(예: 상태 정리는 `@BeforeEach`)과 다른 방식을 제안하면 근거를 들어 반려하고 재작성을 요청할 것 — 에이전트의 해결책이 "동작은 하지만 컨벤션과 다른" 경우가 있으므로 기능적 정합성뿐 아니라 컨벤션 일치 여부도 함께 검증
- Resilience4j 실패 판정: 어댑터가 예외를 던지는 컨벤션이면 `recordExceptions`만으로 충분하지만, boolean 등 반환값으로 성공/실패를 번역하는 컨벤션이면 `CircuitBreakerConfig.Builder.recordResult(Predicate)`로 별도 보강 필요 — 감싸인(wrapped) 예외 타입만 `recordExceptions`에 등록해야 함(원인 예외 타입을 나열해봤자 밖으로 실제로 던져지는 타입이 아니면 무의미)
- Boot 4.0: `spring-boot-starter-aop` → `spring-boot-starter-aspectj`로 리네임(#42948), `@MockBean`/`@SpyBean` 완전 제거 → `@MockitoBean`/`@MockitoSpyBean`(`org.springframework.test.context.bean.override.mockito`) 사용
- 이 프로젝트는 1인 개발이 아니라 Backend(본인)/Infra·SRE(김준희) 2인 협업 프로젝트임 — Chaos Mesh, K8s 클러스터 운영, GitOps/관측성 구축은 Infra 담당 영역이므로, 이런 영역을 "혼자 다 해야 하는지" 판단할 때는 먼저 노션 "프로젝트 개요"의 팀원 역할표를 확인할 것
- Flyway/Liquibase 없이 `ddl-auto: update`만 쓰는 프로젝트에서 엔티티 필드를 제거해도 물리 컬럼은 DROP되지 않고 NOT NULL 제약만 orphan으로 남아 INSERT가 깨질 수 있음 — 로컬 DB에 이전 브랜치/이전 스키마 잔재가 없는지 항상 의심할 것(과제 16)
- 두 값(예: DEBIT/CREDIT 쌍)의 불변식을 지키려면 "따로 만들고 나중에 검증"(validate-after)보다 "애초에 어긋난 값을 만들 수 없는 시그니처"(invariant-by-construction, 예: `LedgerEntry.transferPair`가 두 다리에 동일 `Money` 인스턴스를 강제)가 더 신뢰도 높음
- "존재하지 않아서 우연히 막히는" 방어(예: sentinel 계좌번호가 실제 row가 없어서 조회 실패로 차단됨)는 나중에 그 전제가 깨지면 조용히 무력화되므로, 알아챈 즉시 명시적 가드 클로즈 + 전용 도메인 예외로 전환할 것(과제 16, `ReservedAccountException`)
- JPQL에서 `:param is null or column = :param` 패턴으로 nullable 필터를 구현할 때, `@Query`의 `countQuery`는 항상 명시적으로 같이 작성할 것(과제 27) — Spring Data의 자동 count 쿼리 유도가 조건절이 복잡해질수록 실패하거나 성능이 나빠질 수 있음. 또한 파라미터 타입에 따라 이 패턴 자체가 Postgres에서 깨질 수 있음: `LocalDate`처럼 `IS NULL` 단독 위치에서 타입을 추론할 문맥이 없는 파라미터는 `could not determine data type of parameter` 오류가 나므로 `cast(:param as date)`를 명시해야 함 — 반면 `@Enumerated(STRING)` enum은 같은 패턴이 캐스트 없이도 통과함(과제 28)
- Spring Batch 6.0부터 `org.springframework.batch.core.repository.explore.JobExplorer`는 `@Deprecated(forRemoval = true)`(6.2+ 제거 예정) — 읽기 기능은 전부 `org.springframework.batch.core.repository.JobRepository`가 흡수(`JobRepository extends JobExplorer`). 배치 Job 이력을 다루는 신규 코드는 `JobExplorer`가 아니라 `JobRepository`를 참조할 것(과제 29에서 `BatchJobExecutionHistoryAdapter`에 적용)
- Spring Boot 4.0의 `spring-boot-batch` 모듈은 기본적으로 `JobRepository`를 **인메모리 전용**(`ResourcelessJobRepository`)으로 구성한다(`BatchAutoConfiguration` 클래스 javadoc에 명시). `spring.batch.jdbc.initialize-schema` 프로퍼티는 이 버전에서 대응하는 스키마 초기화 빈이 없어 아무 효과가 없음 — 실제로 Postgres에 영속화하려면 `DefaultBatchConfiguration`을 상속한 자체 `@Configuration`에서 `jobRepository()` 빈을 `JdbcJobRepositoryFactoryBean`으로 오버라이드하고, Spring Batch 공식 스키마(`schema-postgresql.sql`, spring-batch-core jar 내장)를 직접 적용해야 함(과제 29, `BatchRepositoryConfig`). `ResourcelessJobRepository`는 필드 하나에 최근 실행 1건만 기억해 `JobInstanceAlreadyCompleteException` 기반 재실행 방지도 앱 재시작 시 무력화됨 — EOD/Reconciliation Job처럼 이미 이 저장소를 쓰던 기존 Job에도 해당되는 잠재 버그였음(과제 29에서 함께 해결)
- **로그인 시점 인증(`UserDetailsService`)과 매 요청 인증(JWT 필터) 경로는 서로 다른 코드라 역할/권한 로직이 양쪽에 각각 존재할 수 있음** — 한쪽만 고치고 다른 쪽을 놓치기 쉬운 함정. 이 프로젝트는 `AdminUserDetailsService`(로그인 시점, `SimpleGrantedAuthority("ROLE_" + role.name())`로 실제 역할 반영)는 처음부터 정확했지만, `JwtAuthenticationFilter`(매 요청, 과제 37 이전까지)는 항상 `"ROLE_ADMIN"`을 하드코딩하고 있었음 — 역할이 하나뿐이던 동안은 우연히 항상 맞아서 드러나지 않았다. 역할을 2종 이상으로 늘리는 작업을 할 때는 반드시 "인증이 발생하는 모든 지점"을 grep해서 하드코딩된 권한 문자열이 없는지 전수 확인할 것(과제 37)
- ShedLock은 "이 락이 지금 보유 중인가"를 조회하는 공개 API를 제공하지 않음 — 필요하면 `shedlock-provider-redis-spring`의 내부 키 포맷(`InternalRedisLockProvider.buildKey()` = `keyPrefix(기본 "job-lock") + ":" + environment + ":" + lockName`)을 직접 재구현해 `StringRedisTemplate.hasKey()`로 조회해야 함(과제 38, `ShedLockRedisStatusAdapter`) — 라이브러리 내부 구현 세부사항 의존이라 ShedLock 버전을 올릴 때는 이 키 포맷이 안 바뀌었는지 재확인 필요
- Spring Batch의 `BATCH_*` 테이블은 JPA `@Entity`가 아닌 순수 JDBC 테이블이라, 특정 Job명 한정으로 이력을 삭제하는 등 Repository로 표현 불가능한 작업은 네이티브 SQL이 필요함 — `@PersistenceContext(unitName = "...")`로 얻은 `EntityManager`의 `createNativeQuery()`는 같은 트랜잭션(같은 `PlatformTransactionManager`)에 자연스럽게 참여하므로, 별도 `DataSource`로 `JdbcTemplate`을 새로 만드는 것(트랜잭션이 분리되어 원자성이 깨짐)보다 안전함(과제 38, `DemoBatchJobHistoryResetAdapter`)
- `@SpringBootTest` + `@MockitoBean`으로 특정 Port를 오버라이드하면, 그 오버라이드 조합이 기존 어떤 테스트 클래스와도 다르면 Spring이 완전히 새로운 ApplicationContext를 띄운다 — 컨텍스트마다 각자 HikariCP 커넥션 풀(운영+데모 두 DataSource 몫)을 새로 열기 때문에, 이미 서로 다른 오버라이드 조합의 컨텍스트가 여러 개 쌓인 상태에서 하나를 더 추가하면 전체 테스트 스위트를 한 번에 돌릴 때만(단독 실행은 항상 통과) 무관한 다른 테스트가 커넥션 풀 고갈로 간헐적으로 실패할 수 있음 — 가능하면 실제 어댑터를 그대로 쓰거나 이미 존재하는 오버라이드 조합을 재사용해 컨텍스트 캐시를 늘리지 말 것(과제 35)
- 트랜잭션 롤백 보장을 위해 예외를 의도적으로 번역하지 않고 그대로 전파하는 어댑터(`OutboxPersistenceAdapter`, 과제 4)가 있으면, 그 raw 예외가 호출 스택을 타고 올라가 사용자 응답 필드(예: `executionFailureReason`)에 그대로 저장될 수 있다는 점을 별개로 점검할 것 — "예외 번역 안 함"과 "그 예외 메시지를 외부에 노출 안 함"은 서로 다른 문제라 한쪽을 지켰다고 다른 쪽이 자동으로 지켜지지 않음(과제 39, `ApproveTransferService.safeExecutionFailureReason()`으로 도메인 예외 패키지 여부를 기준으로 방어)
- 복식부기 원장 초기 잔액 시딩은 반드시 `LedgerEntry.transferPair(...)`(상대계정 있는 페어)로만 할 것 — `LedgerEntry.of(...)` 단독 호출로 단일 다리만 넣으면 시스템 전체 대차평형이 깨지고, 이 버그는 EOD trial balance 검증 스텝을 실제로 실행해봐야만 드러남(단위 테스트만으로는 안 잡힘). 운영 코드(`OpeningBalanceMigrationService`, 과제 16)는 처음부터 이 규칙을 지켰지만 데모 시딩 코드(`DemoDataResetService`, 과제 38)에서 동일한 실수가 반복됨(과제 40) — 신규 원장 시딩 코드를 작성/리뷰할 때마다 `LedgerEntry.of()` 단독 호출이 없는지 확인할 것

---

마지막 업데이트: 2026-08-23
