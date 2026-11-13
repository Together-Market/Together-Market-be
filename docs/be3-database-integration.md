# BE3 공통 코드 연결

공통 도메인(PR #6)과 추천 내부 로직(PR #8)을 연결한다. 구매요청 저장·조회, 후보 생성·재검증, 비동기 결과 저장을 공유 Entity·Repository와 상품 가능 여부 검증 서비스에 연결한다. 공개 HTTP API와 인증 연결은 상위 작업 #4에서 계속 진행한다.

## 연결된 부분

| 범위 | 구현 |
| --- | --- |
| 구매목적 | PurposeCategoryRepository → 기존 분류 조회 서비스. 공유 스키마에는 활성·표시순서가 없어 전체 등록 분류를 활성으로, ID 순으로 제공 |
| 구매요청 생성 | 세션에서 전달받은 고객 ID의 CUSTOMER 역할 재검사, 답변 검증, KST 일시·JSON 답변 스냅샷 저장, 커밋 후 전달 |
| 구매요청 조회 | Entity의 BigDecimal·LocalDateTime·JSON을 정수 원·+09:00·답변 DTO로 변환 |
| 상품 카탈로그 | 상품·점포·목적 태그를 각각 배치 조회하고 기존 후보 생성기에 전달 |
| 공통 검증 | ProductAvailabilityService.check(productId, pickupInstant, checkedInstant)를 직접 사용하며 사유 코드를 보존 |
| 추천 조회 | 저장된 후보·상품을 배치 조회해 목록/상세/대체 후보 서비스에 연결. 예약의 purchase_request_id는 선택 후보를 통해 확인 |
| 추천 계산 | 저장된 구매조건·답변·태그로 서버 후보 생성 → 기존 AI 평가·학습 가중치 순위화. 계산 전체에 트랜잭션을 유지하지 않음 |
| 비동기 저장 | 행 잠금으로 선점·완료·실패·만료를 직렬화. 후보·구성상품·READY를 하나의 별도 트랜잭션에 저장 |

공개 컨트롤러·세션/CSRF·공통 응답·공통 예외는 BE1 연결이 필요하다. 위 내부 서비스가 존재한다고 10개 HTTP API가 모두 완성된 것은 아니다.

## 시간과 금액

팀 합의에 따라 **DB DATETIME은 KST**로 연결했다. KoreanDateTime이 Instant ↔ KST LocalDateTime ↔ API +09:00 변환을 담당한다. 서버 JVM의 기본 시간대에 의존하지 않는다. 기존 DATETIME은 초 정밀도이므로 저장 전에 소수초를 버리고, 수령시각이 그 결과 과거/현재가 되면 입력을 거절한다. 제한시간은 DB에 저장된 생성시각부터 계산한다.

공유 스키마가 허용하는 0원 상품·추천 단가를 Java 추천 경로에서도 허용한다. 음수는 계속 거절하고 예산은 1원 이상이다. 예산을 정확히 사용하는 유료 상품+0원 상품 조합도 허용한다. DB 금액은 `longValueExact()`로 변환해 소수·범위 오류를 숨기지 않는다. Java 추론 특징 계산은 0원 조합을 처리한다. 학습 노트북·데이터·가중치 파일은 이 변경에 포함하지 않는다.

## V3 마이그레이션

`V3__add_recommendation_job_claim.sql`을 추가했다. V1/V2는 수정하지 않았다.

- purchase_requests.recommendation_claim_token: 선점 UUID
- purchase_requests.recommendation_deadline_at: KST 제한시각
- 두 필드가 함께 존재하거나 함께 null이어야 하는 CHECK
- 상태·제한시각·생성시각 조회 인덱스

공유 PurchaseRequest에는 두 필드와 제한된 선점/종료 메서드를 추가했다. 다른 도메인 Entity나 예약 상태 전이는 변경하지 않았다. 현재 dev에 V3가 없음을 확인한 뒤 사용한 번호지만, 팀과 합치기 전에 새 마이그레이션 번호 충돌을 다시 확인해야 한다. 격리된 MySQL 8.4.13 테스트 DB에 V1~V3를 적용하고 Hibernate 스키마 검증을 통과했다. 서비스 DB에는 적용하지 않았다.

JobStore는 REQUIRES_NEW 프록시 트랜잭션을 사용하고 PESSIMISTIC_WRITE로 구매요청 행을 잠근다. 잠금을 얻은 다음 새 Clock 시각과 DB에 저장된 토큰·기한을 검사한다. 호출 인자의 오래된 now나 임의 Lease.deadline으로 통과시키지 않는다. 후보 insert 중 실패하면 후보·구성상품·상태 변경을 모두 롤백한다. insert 후에도 제한시간을 다시 검사하며 초과하면 롤백하고 runner가 별도 실패 처리를 수행한다. 실제 MySQL의 동시 선점과 저장 실패 시 전체 롤백은 아래 통합 테스트로 확인했다.

## 저장 직전 재검증

AI 응답 이후 결과를 저장하는 짧은 트랜잭션에서 상품을 다시 배치 조회하고 BE2 공통 서비스로 판매·준비·영업·수령 조건을 확인한다. 같은 상품의 공통 검증은 한 번만 실행한다. 현재 합계가 예산을 넘거나 조건을 통과하지 못하는 후보는 제외하고 남은 순위를 연속으로 저장한다. 모두 제외되어도 READY와 빈 결과다.

가격만 바뀌고 현재 합계가 예산 안이면 후보를 유지하며 현재 단가를 스냅샷으로 저장한다. 추천 이유와 상대 순서는 AI 평가 당시 결과를 유지하고 재호출하지 않는다. 현재 순위는 가격 변경 후 재학습·재평가한 순위가 아니다. 조회와 예약 단계에서도 최신 조건을 검증해야 하며 추천 저장은 재고 확보를 의미하지 않는다.

## 활성화 설정

읽기/저장 어댑터는 명시적으로 켠다. 기본 상태에서는 기존 DB 없는 context 테스트와 학습 작업에 영향을 주지 않는다.

```properties
recommendation.persistence.enabled=true
```

구매요청 생성과 실제 추천 실행까지 연결하려면 추가 설정이 필요하다.

```properties
recommendation.execution.enabled=true
recommendation.ai.enabled=true
recommendation.max-products=200
# 승인된 질문 문구를 실제 분류 ID별로 제공한다. 아래 한 줄은 설정 형태 예시다.
recommendation.questions.preference-labels.11=선호하는 상품 종류를 알려주세요.
```

OPENAI_API_KEY/OPENAI_MODEL은 기존 AI 설정대로 환경변수로 제공한다. 학습 모델 연결은 기존 `recommendation.ranker.mode`/`model-path` 설정을 따른다. 승인된 질문 문구 설정이 없으면 실행 구성을 시작하지 않으며 초안 resource를 자동으로 승인된 데이터처럼 쓰지 않는다. 지원할 중분류의 문구를 모두 입력해야 한다.

실행 설정은 worker 2개·대기열 32개·전체 40초·복구 1초·배치 100건을 사용한다. `recommendation.execution.enabled`는 요청 전달뿐 아니라 기존 RECOMMENDING 만료 복구도 시작하므로, DB 연결·질문·AI 설정과 팀 적용 범위를 확인한 환경에서 켠다.

현재 DB 사전 조회는 ON_SALE·예산 이하 상품을 ID 순으로 최대 200개 가져온다. 이후 공통 시간 검증과 목적 태그·거리·조합 다양성으로 최대 12개를 선택한다. 이 제한은 작은 시연 카탈로그를 위한 구현이며 규모가 커지면 상권·카테고리·위치 조건과 사전 조회 정렬을 개선해야 한다. 텍스트 선호의 별도 사전 점수는 아직 만들지 않고, 원문 답변을 기존 AI 평가기에 전달한다.

## 검증과 남은 작업

단위 테스트는 KST 날짜 경계, 초 정밀도, 답변 배열/라벨, 상품·태그 배치 조회, 0원 조합, CUSTOMER 권한, 커밋 후 전달, 선점 토큰·오래된 시각·만료 경합을 확인한다. 설정 테스트는 기본 비활성, 읽기만 활성, 질문 미설정 실패, 실행 구성 조립을 확인한다.

MySQL 통합 테스트는 `RecommendationPersistenceIntegrationTest`에 추가했다. 실제 두 트랜잭션의 선점 경쟁, 후보/item/READY 저장, 두 번째 상품 insert 실패 시 후보·상품·상태 전체 롤백, 빈 추천, 누락 작업 만료를 검사한다. 기존 스키마 테스트는 V3를 포함해 SQL 이력 3개를 확인하도록 수정했다.

2026-11-13 별도 폴더의 MySQL 8.4.13을 localhost 전용으로 실행해 실제 DB 통합 검증을 수행했다. V1~V3 적용, 두 트랜잭션의 선점 경쟁, 결과의 원자적 저장·롤백, 빈 추천·만료, 구매요청 커밋 후 별도 트랜잭션 전달과 롤백 시 미전달을 확인했다. 가격·판매 상태·수령 일정이 AI 평가 중 바뀌는 경우도 DB에서 재현했다. 테스트는 외부 AI를 호출하지 않으며 라이브 AI 품질과 HTTP 인증 연결은 별도 검증 대상이다. 재실행 시 전용 테스트 DB의 MYSQL_TEST_URL/MYSQL_TEST_USERNAME/MYSQL_TEST_PASSWORD를 설정한다.

```powershell
.\gradlew.bat spotlessCheck build
.\gradlew.bat test --tests '*DatabaseSchemaIntegrationTest' --tests '*RecommendationPersistenceIntegrationTest' --rerun-tasks
```

남은 연결은 BE1 세션/권한 전달·응답/예외·컨트롤러, 승인된 분류별 질문 문구, 대체 추천 기준 수령시각과 공개 응답 사유/집계 계약이다. 고객 상품·점포 상세 HTTP API도 아직 필요하다. 현재 가격과 조건의 최종 예약 검증은 BE1 책임을 유지한다.
