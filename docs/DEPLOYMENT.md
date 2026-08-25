# DEPLOYMENT

이 문서(특히 아래 표)는 배포 담당자에게 그대로 전달 가능한 계약입니다. 여기 없는 값은 애플리케이션 기본값(로컬 개발 기준)이 그대로 쓰인다는 뜻이며, 프로덕션에서 그게 맞는지는 이 표를 기준으로 판단하면 됩니다.

배포 대상 클라우드(Azure)의 구체적인 서비스 구성은 인프라 팀과 아직 미정이지만, 애플리케이션 쪽은 어떤 배포 방식이든 **환경변수 주입만으로 동작**하도록 맞춰져 있습니다. 이 문서는 배포 준비도 감사(설정 외부화 전수 조사, loud/silent 판정 기준 명시본)의 카테고리 1-4("체크리스트 문서 부재")를 채우기 위해 작성됐습니다.

## 환경변수 표기 규칙 확인

Spring Boot의 환경변수 relaxed binding은 `.`과 `-` 둘 다 단어 경계로 보고 `_`로 치환합니다. 예: `k8s.leader-election.namespace` → `K8S_LEADER_ELECTION_NAMESPACE`. 이 문서 작성 중 이 매핑 규칙을 실제로 스파이크 테스트로 검증했습니다(`K8S_LEADER_ELECTION_NAMESPACE`, `K8S_LEADERELECTION_NAMESPACE` 둘 다 정상 매핑되는 것까지 확인 — Boot가 두 형태 모두 별칭으로 인식). 아래 표는 Spring 공식 문서가 권장하는 표준 표기(하이픈→언더스코어)를 사용합니다.

## "loud" / "silent" 판정 기준

배포 준비도 감사 2차본에서 정의한 기준을 그대로 씁니다 — 이 값을 프로덕션에서 안 건드리고 배포했을 때:

- **loud**: 앱이 기동에 실패해 그 자리에서 드러남(배포가 막힘, 알아채기 쉬움)
- **silent**: 앱은 정상 기동하고, 잘못된 동작이 조용히 누적됨(알아채기 어려움 — 더 위험)
- **silent-breach**: 앱은 정상 기동하고 겉보기엔 정상 동작하지만, 보안 경계 자체가 뚫려 있는 상태 — silent보다 한 단계 더 위험함. silent는 "잘못된 결과가 쌓이는 것"이지만 silent-breach는 "인증/인가 자체가 무력화된 채로 정상처럼 보이는 것"이라, 로그나 모니터링에 이상 신호가 아예 안 남을 수 있음(침해가 일어나도 알아챌 단서 자체가 없음)
- **N/A**: 값이 틀려도 앱 동작 자체엔 영향 없음(업무 정책값이거나, 조건부로 비활성화된 기능)

## 필수 환경변수 체크리스트

| 환경변수명 | 대응 Spring 프로퍼티 | 기본값(로컬) | 필수 여부(프로덕션) | 실패 시 동작 | 설명 |
|---|---|---|---|---|---|
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | `jdbc:postgresql://localhost:5432/fbrl_db` | **필수** | loud | HikariCP가 기동 시 커넥션 풀 초기화 과정에서 실제 연결을 시도 — 실패하면 애플리케이션 컨텍스트 자체가 뜨지 않음 |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | `fbrl_user` | **필수** | loud | 상동 |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | `fbrlpassword` | **필수** | loud | 로컬 기본값은 docker-compose 시드값과 동일한 더미 — 프로덕션에서 반드시 실제 값으로 교체. 안 바꾸면 인증 실패로 기동 자체가 안 됨(loud) |
| `DEMO_DATASOURCE_URL` | `demo.datasource.url` | `jdbc:postgresql://localhost:5433/fbrl_demo_db` | **필수** | loud | 데모 랩 전용 2차 DataSource(`DemoDataSourceConfig`) — 운영 DB와 완전히 분리된 별도 Postgres. HikariCP가 기동 시 실제 연결을 시도하므로 실패하면 애플리케이션 컨텍스트 자체가 뜨지 않음(운영 DataSource와 동일한 실패 양상) |
| `DEMO_DATASOURCE_USERNAME` | `demo.datasource.username` | `fbrl_user` | **필수** | loud | 상동 |
| `DEMO_DATASOURCE_PASSWORD` | `demo.datasource.password` | `fbrlpassword` | **필수** | loud | 로컬 기본값은 docker-compose `postgres-demo` 서비스 시드값과 동일한 더미 — 프로덕션에서 반드시 실제 값으로 교체 |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `spring.jpa.hibernate.ddl-auto` | `validate`(2026-08-16 이번 변경으로 기본값 자체가 안전해짐) | 프로덕션 필수 아님 | (이번 수정 전) silent → (이번 수정 후) 안전 | 예전엔 기본값이 `update`라 안 건드려도 매 기동마다 조용히 스키마를 변경하는 것이 Critical 리스크였음. 기본값을 `validate`로 바꿔 프로덕션에서 이 값을 아예 신경 쓰지 않아도 스키마를 건드리지 않도록 함. **`update`로 절대 덮어쓰지 말 것**(마이그레이션 도구 도입 전까지) |
| `SPRING_SQL_INIT_MODE` | `spring.sql.init.mode` | `never`(로컬 `test`/`bootRun` 태스크만 `always`로 오버라이드) | 프로덕션 필수 아님(`never` 유지) | **loud, 배포 담당자가 수동 DDL을 깜빡하면 기동 시점이 아니라 첫 배치 실행/첫 관리자 API 호출 시점에 드러남** | Spring Batch 스키마(`BATCH_*` 6개 테이블, `db/batch-schema-postgresql.sql`)를 앱이 기동 시점에 자동 적용하지 않도록 `never`로 고정(과제 29). 원래 `always`(매 기동 시 `CREATE TABLE IF NOT EXISTS` 재적용)였으나, **완전히 빈 DB에 여러 replica가 동시에 최초 기동하면 Postgres MVCC 특성상 경쟁 조건이 실재함을 `pgbench` 8개 동시 커넥션으로 재현(30/30 라운드 전부 `ERROR: duplicate key value violates unique constraint "pg_type_typname_nsp_index"`)** — `ddl-auto: update → validate` 전환(위 항목)과 같은 이유로 "앱이 기동 시점에 스키마를 건드리지 않는다"는 원칙을 여기도 적용해 `never`로 전환. **배포 전 아래 "스키마 변경이 포함된 배포" 절의 수동 DDL을 먼저 적용할 것** — 이 테이블은 JPA 엔티티가 아니라 `ddl-auto`/`validate`의 자동 검증 대상이 아니므로, 안 하면 기동 자체는 정상적으로 되고 배치 Job이 처음 실행되거나 관리자가 배치 이력 조회 API를 처음 호출하는 시점에야 `relation "batch_job_instance" does not exist`류 에러로 뒤늦게 드러남(JPA `validate`보다 늦고 조용한 실패 시점이라는 게 이 전환의 트레이드오프) |
| `SPRING_DATA_REDIS_HOST` | `spring.data.redis.host` | `localhost` | **필수** | loud로 추정(미검증) | Redisson이 기동 시 실제 연결을 시도하는 것으로 일반적으로 알려져 있음. 이 코드베이스에서 직접 재현 검증한 것은 아님 |
| `SPRING_DATA_REDIS_PORT` | `spring.data.redis.port` | `6379` | **필수** | loud로 추정(미검증) | 상동 |
| `SPRING_DATA_REDIS_PASSWORD` | `spring.data.redis.password` | (빈 문자열 — 비밀번호 없이 연결) | **필수(Azure Cache for Redis 확정으로 이제 필수)** | loud | Azure Cache for Redis는 기본적으로 비밀번호(access key) 인증이 강제됨. 값이 비어있으면 `RedissonConfig`가 `setPassword()`를 아예 호출하지 않아 로컬 docker-compose Redis(비인증)와 동일하게 동작하지만, Azure Cache for Redis 대상으로 이 값 없이 배포하면 인증 실패로 Redisson이 기동 시점에 연결에 실패함(loud) |
| `SPRING_DATA_REDIS_SSL_ENABLED` | `spring.data.redis.ssl.enabled` | `false` | **필수(Azure Cache for Redis 확정으로 이제 필수)** | loud | Azure Cache for Redis는 TLS 전용 포트(6380)가 기본이고 비TLS 포트(6379)는 기본 비활성. `true`면 `RedissonConfig`가 주소 프로토콜을 `rediss://`로 전환(Redisson은 TLS를 boolean 세터가 아니라 주소 스킴으로 제어), `false`면 로컬과 동일하게 `redis://` 유지. 켜야 하는데 안 켜면(또는 그 반대) 프로토콜/포트 불일치로 Redisson이 기동 시점에 연결에 실패함(loud) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `spring.kafka.bootstrap-servers` | `localhost:9092` | **필수** | **미확정(loud/silent 둘 다 가능성 있음)** | Kafka Producer는 일반적으로 lazy 연결이라, 이 값이 틀리거나 없어도 앱이 정상 기동하고 이벤트 발행만 조용히 실패할 가능성이 있음(미검증). **배포 후 반드시 실제 이체 1건을 발행해 `transfer-events` 토픽 수신을 직접 확인할 것.** |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | `management.opentelemetry.tracing.export.otlp.endpoint` | `http://localhost:4318/v1/traces`(로컬 Jaeger) | 인프라 협의 대기 중 | silent | 배포 환경 OTLP Collector 엔드포인트가 아직 미정(PROGRESS.md에 이미 기록된 상태 그대로) — 안 바꾸면 트레이스 export만 조용히 실패, 앱 기능에는 영향 없음 |
| `CORS_ALLOWED_ORIGINS` | `cors.allowed-origins` | `http://localhost:5173`, `http://localhost:3000` | **필수** | silent | 관리자 프론트엔드가 호출을 허용받을 오리진 목록. 실제 관리자 프론트엔드 배포 도메인으로 교체 필요 — 안 바꾸면 앱은 정상 기동하지만, 등록 안 된 오리진에서의 요청은 서버 로그 없이 브라우저 단에서 CORS 에러로만 조용히 막힘. 리스트형 값이라 콤마 구분 문자열(`CORS_ALLOWED_ORIGINS=https://admin.example.com,https://admin2.example.com`)로 오버라이드 가능 — relaxed binding이 `List<String>`으로 정상 바인딩되는지 실제 프리플라이트 요청으로 검증 완료(env var에만 있는 오리진은 허용되고, yaml 기본값에만 있던 오리진은 env var가 있으면 사라짐 — merge가 아니라 override) |
| `JWT_SECRET` | `jwt.secret` | `local-dev-only-jwt-signing-secret-change-in-production-32bytes-min`(66바이트, 528비트 — HS256 최소 요구치인 256비트/32바이트는 충족하지만 이름 그대로 로컬 전용 더미값) | **필수** | **silent-breach** | 안 바꾸면 앱은 정상 기동하고 로그인도 정상 동작하는 것처럼 보이지만, 이 문자열이 공개 저장소에 커밋되어 있는 값이라 **누구나 같은 시크릿으로 유효한 관리자 JWT를 직접 위조해 서명할 수 있음** — 인증 자체가 사실상 없는 것과 동일한 상태가 됨. loud도 silent도 아닌 이유: silent는 "틀린 값 때문에 기능이 저하"되는 것이지만 이건 "값이 새어나가 있어서 인증 경계 자체가 무의미"해지는 것 — 겉보기엔 완벽하게 정상 동작해서 침해 여부를 앱 로그만 봐서는 절대 알 수 없음. **배포 전 반드시 별도로 생성한 고엔트로피 시크릿으로 교체할 것**(예: `openssl rand -base64 48`) |
| `ADMIN_INITIAL_USERNAME` / `ADMIN_INITIAL_PASSWORD` | `admin.initial.username` / `admin.initial.password` | 없음(yaml 기본값 미설정 — 둘 다 비어있으면 `AdminUserSeeder`가 계정 생성 자체를 스킵) | **필수(최초 배포 1회만)** | **silent-breach** | 로컬 개발 문서/README 등에 예시로 적어둔 값을 그대로 프로덕션에 써서 배포하면, 그 값이 곧 "알려진 관리자 계정"이 되어 누구나 로그인 가능 — 이것도 앱은 정상 기동/정상 동작하므로 겉보기엔 문제가 없어 보임. 최초 1회 생성 이후에는 이 값을 바꿔도 이미 만들어진 계정 자체는 안 바뀜(`AdminUserSeeder`는 idempotent — username이 이미 존재하면 skip)이므로, 초기 배포 시점에만 강한 값을 넣는 것으로 충분하지만 그 순간이 가장 중요함 |
| `DEMO_ACCOUNT_USERNAME` / `DEMO_ACCOUNT_PASSWORD` | `demo.account.username` / `demo.account.password` | 없음(yaml 기본값 미설정 — 둘 다 비어있으면 `DemoAccountSeeder`가 계정 생성 자체를 스킵) | 필수 아님(데모 환경만 해당) | N/A | **이 계정 정보는 데모 프론트엔드에 공개적으로 노출되는 것이 의도된 설계다** — `role=DEMO`로 생성되며 `/api/v1/demo/**` 외 운영 엔드포인트에는 `hasRole("ADMIN")`에 막혀 403으로 거부된다. 나중에 이 값이 그대로 공개된 걸 보고 "왜 비밀번호가 유출됐냐"고 오인하지 말 것 — `ADMIN_INITIAL_*`(위 항목)와 달리 silent-breach가 아니다. `AdminUserSeeder`와 동일하게 idempotent(username이 이미 존재하면 skip)이며, 두 시더는 서로 다른 username·설정 프리픽스를 쓰기 때문에 실행 순서와 무관하게 독립적으로 동작한다 |
| `PROMETHEUS_METRICS_USERNAME` / `PROMETHEUS_METRICS_PASSWORD` | `prometheus.metrics.username` / `prometheus.metrics.password` | 없음(yaml 기본값 미설정 — 둘 다 비어있으면 `SecurityConfig`의 `prometheusSecurityFilterChain`이 Basic Auth 사용자를 아예 등록하지 않아 `/actuator/prometheus`가 항상 401) | **필수(Prometheus 스크레이핑을 쓰는 환경)** | **loud** | `/actuator/prometheus`만 별도 `SecurityFilterChain`(`@Order(1)`, `securityMatcher("/actuator/prometheus")`)으로 분리해 Basic Auth로 보호 — 기존 JWT 기반 체인(`@Order(2)`)과 완전히 무관하며 `AdminUser`/`AdminRole`을 전혀 거치지 않는다. 값이 비어있으면(로컬 기본) `InMemoryUserDetailsManager`에 사용자가 0명 등록되어 어떤 Basic Auth 조합으로도 항상 401 — "silent"가 아니라 "loud"인 이유는 값을 안 채우면 Prometheus 스크레이핑이 매번 401로 즉시 실패해서 모니터링 대시보드에 바로 드러나기 때문(트래픽이 조용히 새거나 기능이 저하되는 게 아니라 눈에 보이게 끊김) |
| `APPROVAL_THRESHOLD` | `approval.threshold` | `10000000` | 필수 아님(업무 정책값) | N/A | Maker-Checker 승인이 필요해지는 금액 기준. 값이 틀려도 앱은 정상 동작, 업무 정책만 달라짐 |
| `FRAUD_THRESHOLD` | `fraud.threshold` | `50000000` | 필수 아님(업무 정책값) | N/A | 이상거래 탐지 임계치. 상동 |
| `EOD_BATCH_CRON` | `eod.batch.cron` | `"0 0 2 * * *"` | 필수 아님 | N/A | EOD 정산 배치 트리거 시각. 스테이징/프로덕션에서 다른 시각이 필요하면 이 값만 바꾸면 됨 |
| `DEMO_EOD_BATCH_CRON` | `demo.eod.batch.cron` | `"0 10 2 * * *"` | 필수 아님 | N/A | 데모 EOD 정산 배치 트리거 시각. 운영(`02:00`)과 겹치지 않도록 기본값을 `02:10`으로 분리해뒀음 — 운영과 겹치게 바꿔도 기동 실패는 아니지만(별도 JobRepository/DataSource라 서로 락 경합도 없음) 로그 상에서 두 Job이 동시에 도는 게 헷갈릴 수 있어 권장하지 않음 |
| `RECONCILIATION_BATCH_CRON` | `reconciliation.batch.cron` | `"0 0 3 * * *"` | 필수 아님 | N/A | 정산 대사 배치 트리거 시각. EOD 이후 시각으로 유지할 것 |
| `DEMO_RECONCILIATION_BATCH_CRON` | `demo.reconciliation.batch.cron` | `"0 20 3 * * *"` | 필수 아님 | N/A | 데모 정산 대사 배치 트리거 시각. 운영(`03:00`)과도, 데모 EOD(`02:10`)와도 겹치지 않도록 `03:20`으로 분리해뒀음 — 별도 JobRepository/DataSource라 겹쳐도 기동 실패나 락 경합은 없지만 로그가 헷갈릴 수 있어 권장하지 않음 |
| `DEMO_RESET_CRON` | `demo.reset.cron` | `"0 */30 * * * *"`(30분마다) | 필수 아님 | N/A | 데모 데이터 리셋 주기. 값이 틀려도 앱 동작엔 영향 없음, 리셋 주기만 달라짐 — 방문자 트래픽 패턴에 따라 조정 가능 |
| `K8S_LEADER_ELECTION_ENABLED` | `k8s.leader-election.enabled` | `false` | 필수 아님 | N/A | **Azure로 갈 경우 기본값 `false` 유지 권장** — K8s Lease API 기반 리더 선출은 실제 K8s 클러스터 환경(kind/AKS 등)이 전제. Azure 배포 대상이 확정되지 않은 현재는 건드리지 말 것 |
| `K8S_LEADER_ELECTION_NAMESPACE` | `k8s.leader-election.namespace` | `default` | 필수 아님 | N/A(`enabled=false`면 미사용) | `ENABLED=true`로 켤 때만 의미 있음 |
| `K8S_LEADER_ELECTION_LEASE_NAME` | `k8s.leader-election.lease-name` | `eod-settlement-leader` | 필수 아님 | N/A(`enabled=false`면 미사용) | 상동 |
| `K8S_LEADER_ELECTION_LEASE_DURATION_SECONDS` | `k8s.leader-election.lease-duration-seconds` | `15` | 필수 아님 | N/A(`enabled=false`면 미사용) | 상동 |
| `K8S_LEADER_ELECTION_RENEW_DEADLINE_SECONDS` | `k8s.leader-election.renew-deadline-seconds` | `10` | 필수 아님 | N/A(`enabled=false`면 미사용) | 상동 |
| `K8S_LEADER_ELECTION_RETRY_PERIOD_SECONDS` | `k8s.leader-election.retry-period-seconds` | `2` | 필수 아님 | N/A(`enabled=false`면 미사용) | 상동 |
| `SHEDLOCK_ENVIRONMENT` | `shedlock.environment` | `fbrl-backend` | **같은 Redis를 공유하는 인스턴스가 둘 이상이면 필수**(예: 메인/데모) | silent | ShedLock 락 키(`job-lock:{environment}:{jobName}`)의 네임스페이스 세그먼트. 메인/데모 서버가 이 값을 다르게 가져가야 함 — 안 그러면 같은 Redis 위에서 두 서버가 같은 락 네임스페이스를 공유해, 한쪽의 EOD/Reconciliation 크론이 다른 쪽 크론 락을 선점해버려 스케줄이 조용히 스킵될 수 있음(로그에는 남지만 기동 실패는 아님) |
| `KAFKA_CONSUMER_GROUP_ID` | `kafka.consumer.group-id` | `transfer-event-processor` | **같은 Kafka를 공유하는 인스턴스가 둘 이상이면 필수**(예: 메인/데모) | silent | `TransferEventConsumer`의 컨슈머 그룹. 메인/데모 서버가 이 값을 다르게 가져가야 함 — 안 그러면 Kafka가 두 서버를 같은 그룹의 컨슈머로 취급해 토픽 파티션을 나눠 배정하고, 어느 한쪽 이벤트를 다른 쪽 서버가 대신 소비하는 교차 소비가 발생할 수 있음. 다만 group-id만 분리해도 두 서버가 같은 토픽을 공유하는 한 서로의 이벤트를 각자 전부 받게 되므로(파티션이 갈리는 게 아니라 그룹 전체가 복제), 완전한 격리는 아래 `KAFKA_TOPIC_TRANSFER_EVENTS` 분리까지 필요 |
| `KAFKA_TOPIC_TRANSFER_EVENTS` | `kafka.topic.transfer-events` | `transfer-events` | 필수 아님(아직 인프라 쪽 커넥터가 분리되지 않음) | **silent — 단, 이 값만 단독으로 바꾸면 오히려 위험**(아래 참고) | `KafkaTopicConfig`/`KafkaRetryTopicConfig`/`TransferEventConsumer`가 공유하는 기준 토픽명. **주의**: 이벤트를 실제로 이 토픽에 채워 넣는 건 `debezium/outbox-connector.json`(Kafka Connect에 등록된 Debezium 커넥터)이고, 이 커넥터의 `route.topic.replacement`는 아직 고정값 `transfer-events`다. 즉 이 앱 쪽 값만 예를 들어 `demo-transfer-events`로 바꾸면, 커넥터는 여전히 옛 토픽에 이벤트를 쏘는데 컨슈머는 새 토픽을 구독하게 되어 **이체 이벤트를 영구히 아무도 소비하지 못하는 상태가 조용히 발생**한다. 인프라 팀이 커넥터를 메인/데모용으로 분리 등록한 뒤, 그 커넥터의 `route.topic.replacement`와 이 값을 반드시 함께 맞춰서 바꿀 것 |

## 스키마 변경이 포함된 배포

이 프로젝트는 Flyway/Liquibase 같은 마이그레이션 도구를 쓰지 않고, `ddl-auto: validate` 기본값을 전제로 스키마는 배포 담당자가 수동으로 맞춰야 합니다(로컬 `test`/`bootRun` 태스크만 `ddl-auto=update`로 오버라이드되어 있어 로컬에서는 자동으로 맞춰지지만, 이 오버라이드는 프로덕션에는 적용되지 않습니다). `feat/demo-datasource-infrastructure`(운영/데모 DataSource 분리)부터는 이 원칙이 **운영 DB와 데모 DB 양쪽에 각각 독립적으로** 적용됩니다 — `MainDataSourceConfig`/`DemoDataSourceConfig`가 각자 별도의 `EntityManagerFactory`를 구성하고, `spring.jpa.hibernate.ddl-auto`(및 로컬 `test`/`bootRun` 태스크의 `update` 오버라이드)는 두 EntityManagerFactory 모두에 동일하게 적용되는 공유 설정이라, 로컬에서는 두 DB 모두 자동으로 맞춰지지만 프로덕션에서는 두 DB 모두 수동 DDL이 필요합니다.

### 운영 DB

- **`fix/decouple-approval-status-from-execution-result`(승인 상태와 집행 결과 분리)** — `transfer_approval_requests` 테이블에 컬럼 2개 추가 포함:
  - `execution_status VARCHAR(255) NOT NULL` — 기존 행이 있는 테이블에 `NOT NULL` 컬럼을 한 번에 추가하면 실패하므로, 배포 시 아래 순서로 적용할 것:
    ```sql
    ALTER TABLE transfer_approval_requests ADD COLUMN execution_status VARCHAR(255) NOT NULL DEFAULT 'NOT_APPLICABLE';
    ALTER TABLE transfer_approval_requests ALTER COLUMN execution_status DROP DEFAULT;
    ```
  - `execution_failure_reason VARCHAR(255)` — nullable이라 단순 `ADD COLUMN`으로 충분:
    ```sql
    ALTER TABLE transfer_approval_requests ADD COLUMN execution_failure_reason VARCHAR(255);
    ```
  - 배포 전 이 DDL을 프로덕션 DB에 먼저 적용하지 않으면, 새 애플리케이션 버전은 `ddl-auto: validate`가 스키마 불일치를 즉시 감지해 기동 자체가 실패합니다(loud) — 데이터 정합성보다는 기동 실패로 먼저 드러나는 종류의 변경.

- **`feat/admin-query-apis-batch3`(배치 Job 이력 조회)** — `JobRepository`를 실제 Postgres에 영속화하도록 전환하면서 `BATCH_*` 테이블 6개가 신규로 필요해졌습니다(과제 29). `spring.sql.init.mode`가 `never`라 앱이 기동 시점에 이 테이블을 자동 생성하지 않으므로, **배포 전 프로덕션 DB에 아래 DDL을 먼저 1회 적용**할 것:
  ```sql
  -- src/main/resources/db/batch-schema-postgresql.sql 전체 내용을 그대로 실행
  -- (또는 컨테이너 안에서 직접 실행)
  -- docker exec -i <postgres-container> psql -U <user> -d <db> < src/main/resources/db/batch-schema-postgresql.sql
  ```
  이 파일은 전부 `CREATE TABLE IF NOT EXISTS`/`CREATE SEQUENCE IF NOT EXISTS`라 이미 적용된 환경에서 다시 실행해도 안전합니다(idempotent) — 여러 환경에 걸쳐 반복 적용해도 되고, 실수로 두 번 적용해도 무해합니다.
  - **이 DDL을 깜빡했을 때의 실패 시점 — 다른 스키마 변경과 다름**: 위 `execution_status` 컬럼 추가는 `ddl-auto: validate`가 즉시 잡아내 기동 자체가 loud하게 실패하지만, 이 테이블들은 JPA `@Entity`가 아니라서 `ddl-auto`/`validate` 검증 대상이 아닙니다. 앱은 정상 기동하고, **배치 Job이 처음 실행되거나 관리자가 `GET /api/v1/batch-jobs/{jobName}/executions`를 처음 호출하는 시점**에야 `relation "batch_job_instance" does not exist`(또는 유사한 SQL 에러)로 뒤늦게 드러납니다 — 배포 직후 반드시 이 엔드포인트를 한 번 호출해 확인할 것.

- **`feat/demo-role-authorization`(DEMO 역할 도입)** — `admin_users.role` 컬럼의 기존 CHECK 제약(`role = 'ADMIN'`)이 `AdminRole.DEMO` 신규 값을 막습니다. 배포 전 프로덕션 DB에 아래 DDL을 먼저 적용할 것:
  ```sql
  ALTER TABLE admin_users DROP CONSTRAINT admin_users_role_check;
  ALTER TABLE admin_users ADD CONSTRAINT admin_users_role_check CHECK (role IN ('ADMIN','DEMO'));
  ```
  이 컬럼은 `@Enumerated(EnumType.STRING)`이라 `ddl-auto: validate`가 컬럼 존재/타입만 검증하고 CHECK 제약 내용까지는 검증하지 않습니다 — 안 하면 앱은 정상 기동하지만, `DemoAccountSeeder`가 `role='DEMO'`로 계정을 저장하려는 첫 시도에서 `DataIntegrityViolationException`(CHECK 제약 위반, SQLState `23514`)이 나고, 현재 `AdminUserPersistenceAdapter.save()`는 이 예외를 `DuplicateAdminUsernameException`으로 잘못 번역해 로그만 봐서는 "이미 존재하는 계정"으로 오인하기 쉽습니다(실제로는 계정이 없는데도 발생) — 원인이 CHECK 제약이라는 걸 알아두면 헤맬 필요 없음. `admin_users` 테이블은 운영 DB에만 있고 데모 DB에는 없으므로(인증은 운영 DB 공유) 이 DDL은 운영 DB 1곳에만 적용하면 됩니다.

### 데모 DB

`feat/demo-datasource-infrastructure`부터 데모 DB(`DEMO_DATASOURCE_*`)에도 아래 두 가지가 운영 DB와 별개로 필요합니다. 운영 DB에 이미 적용했더라도 데모 DB는 물리적으로 다른 Postgres 인스턴스라 **반드시 별도로 적용**해야 합니다.

- **`BATCH_*` 테이블 6개** — `DemoBatchRepositoryConfig`의 `demoJobRepository`가 데모 DB에 자체 JDBC 기반 `JobRepository`를 구성합니다(운영 `JobRepository`와 완전히 분리 — 크로스 DB 트랜잭션 원자성 문제를 피하기 위한 의도적 설계). 운영 DB와 동일하게 `src/main/resources/db/batch-schema-postgresql.sql`을 데모 DB에도 1회 적용할 것:
  ```sql
  -- docker exec -i <postgres-demo-container> psql -U <user> -d <db> < src/main/resources/db/batch-schema-postgresql.sql
  ```
  안 하면 앱은 정상 기동하지만(이 테이블들은 JPA 엔티티가 아니라 `ddl-auto`/`validate` 대상이 아님), 데모 Job이 처음 실행되는 시점에야 `relation "batch_job_instance" does not exist`로 뒤늦게 드러납니다 — 운영 DB 쪽과 동일한 실패 시점 트레이드오프.
- **`accounts` / `ledger_entries` / `eod_snapshots` / `reconciliation_discrepancies` 테이블** — `DemoAccountEntity`/`DemoLedgerEntryEntity`/`DemoEodSnapshotEntity`/`DemoReconciliationDiscrepancyEntity`가 데모 DB의 동명 테이블에 매핑됩니다(운영 엔티티와 컬럼 구조는 동일, 완전히 별도의 물리 테이블). `feat/demo-eod-ondemand-trigger`(데모 EOD Job)에서 `ledger_entries`/`eod_snapshots` 2개, `feat/demo-reconciliation-ondemand-trigger`(데모 Reconciliation Job)에서 `reconciliation_discrepancies`가 추가됐습니다. 데모 DB는 운영 DB와 달리 이 테이블들을 자동으로 물려받지 않으므로, 최초 배포 시 `src/main/resources/db/demo-schema-postgresql.sql`을 1회 적용할 것:
  ```sql
  -- docker exec -i <postgres-demo-container> psql -U <user> -d <db> < src/main/resources/db/demo-schema-postgresql.sql
  ```
  이 파일도 `CREATE TABLE IF NOT EXISTS`라 이미 적용된 환경에서 다시 실행해도 안전합니다(idempotent). 안 하면 데모 `EntityManagerFactory` 초기화 시점에 스키마 불일치를 즉시 감지해 기동 자체가 실패합니다(loud) — 운영 DB의 JPA 엔티티 스키마 불일치와 동일한 실패 양상.
  - **검증 시 주의**: `SPRING_JPA_HIBERNATE_DDL_AUTO=validate ./gradlew bootRun`처럼 셸에서 환경변수를 얹어 `bootRun`을 띄워도 검증되지 않습니다 — `build.gradle`의 `tasks.named('bootRun')`이 `SPRING_JPA_HIBERNATE_DDL_AUTO`/`SPRING_SQL_INIT_MODE`를 각각 `update`/`always`로 무조건 덮어써서, 셸에서 지정한 값이 조용히 무시됩니다(실제로 `SPRING_JPA_HIBERNATE_DDL_AUTO=존재하지않는값`을 줘도 에러 없이 기동되는 것으로 확인). 실제 `validate` 동작을 확인하려면 `./gradlew bootJar`로 만든 산출물을 `java -jar build/libs/*.jar`로 직접 실행해야 함 — 이 DDL은 그 방식으로 재현 검증했습니다.

## 인증 실패 시 HTTP 상태 코드 (프론트엔드 참고)

- **401 Unauthorized** — 요청에 유효한 인증 정보가 아예 없는 경우. `Authorization` 헤더 자체가 없거나, 토큰이 만료/위조/형식 오류로 `TokenPort.validateToken()`이 실패한 경우 전부 여기에 해당. 응답 바디는 `{"code":"UNAUTHORIZED", "message":"...", "timestamp":"..."}`.
- **403 Forbidden** — 인증은 됐지만(유효한 토큰을 갖고 있지만) 해당 작업을 수행할 권한이 없는 경우. 응답 바디는 `{"code":"FORBIDDEN", "message":"...", "timestamp":"..."}`.
- 과제 37(DEMO 역할 도입)부터 403이 실제로 발생하는 경로가 있습니다 — `DEMO` 역할 토큰으로 `/api/v1/demo/**` 밖의 운영 엔드포인트(예: `POST /api/v1/accounts`, `GET /api/v1/batch-jobs/{jobName}/executions`)를 호출하면 403이 반환됩니다(`SecurityConfig`: `/api/v1/demo/**`는 `hasAnyRole("DEMO","ADMIN")`, 그 외는 `hasRole("ADMIN")`). `ADMIN` 역할은 운영/데모 엔드포인트 양쪽 다 여전히 접근 가능합니다.

## 인프라 팀과 협의 필요한 별도 항목

아래는 코드 수정 없이, 배포 준비도 감사에서 확인된 사실만 그대로 옮긴 목록입니다.

- **Kafka 인증 경로 부재** — `KafkaProducerConfig`에 SASL 설정 필드 자체가 없음. Event Hubs(또는 Azure 상의 Kafka 호환 서비스) 등 실제 대상이 정해지면 인증 설정 코드를 추가해야 함. (Redis는 Azure Cache for Redis로 확정되어 `SPRING_DATA_REDIS_PASSWORD`/`SPRING_DATA_REDIS_SSL_ENABLED` 추가로 해소됨 — 위 표 참고)
- **Kafka 재시도/DLT 토픽 replication factor=1** — 단일 장애점. 실제 브로커 구성(파티션/복제본 수)이 정해지면 그에 맞게 조정 필요.
- **Kafka 토픽 자체 분리(main/demo)** — `debezium/outbox-connector.json` 하나뿐이고 `route.topic.replacement`가 `transfer-events` 고정값. 메인/데모가 각자 DB를 갖는 이상, 토픽까지 분리하려면 이 커넥터를 복제해 `database.dbname`/`slot.name`/`route.topic.replacement`를 각각 다르게 지정한 두 번째 커넥터를 Kafka Connect에 새로 등록해야 함(위 `KAFKA_TOPIC_TRANSFER_EVENTS` 행 참고 — 앱 쪽은 이미 외부화되어 이 작업만 남음). Redisson 계좌 락 키 관련해서도 데모 계좌번호에 `DEMO-` 같은 접두를 강제하는 채번 로직이 아직 없어, 같은 Redis를 공유하는 동안은 계좌 락 키가 우연히 겹치지 않는 수준에 머물러 있음 — 이 역시 별도 채번 작업으로 해소 예정.

(해소됨 — 과제 37에서 `DEMO`/`ADMIN` 역할 기반 인가(`SecurityConfig`)가 도입되어, 데모 전용 엔드포인트는 `/api/v1/demo/**` 아래로 모이고 `DEMO` 역할로는 그 밖의 운영 엔드포인트를 호출할 수 없습니다. 위 "인증 실패 시 HTTP 상태 코드" 절 참고.)
