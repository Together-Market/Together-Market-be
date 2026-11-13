# BE3 구매조건·조회 기반 구현

후속 DB 연결·KST 변환·V3 저장소와 최신 활성화 설정은 [공통 코드 연결](be3-database-integration.md)을 참고하세요. 아래는 초기 내부 로직의 설계와 연결 경계 기록입니다.

2026-11-13 팀 회신과 BE3 API 명세를 기준으로, 공유 DB 연결과 분리해 독립적으로 검증할 수 있는 부분을 구현했다. 이 문서는 내부 로직 구현 범위이며 API 배포나 DB 연동 완료를 뜻하지 않는다.

추가로 구현한 후보 생성, 현재 조건 재검증, 추천 목록·상세·대체 후보 조회 로직은 [추천 후보 구현 문서](be3-candidate-preparation.md)에 정리했다.

## 구현 범위

| 대상 | 준비된 코드 | 아직 필요한 연결 |
| --- | --- | --- |
| PUR-01 | 활성 분류 트리, 표시 순서·ID 정렬, 문자열 ID DTO | 공유 Repository → PurposeCategorySource, 인증·컨트롤러 |
| PUR-02 | 질문 정의, 입력 유형·필수 여부·길이·예산 제약 DTO | 22개 중분류의 마지막 질문 문구 확정 |
| PUR-03 | 엄격한 JSON 타입, 예산·수령시간·질문 답변 검증, 서버 질문 문구 스냅샷 | 세션 고객, 공유 Entity 저장, 202 응답, 커밋 이후 작업 접수 |
| PUR-04 | 본인 소유권 검사, 상태·실패 코드·답변 스냅샷 조회 DTO | 공유 Repository → PurchaseRequestSource, 공통 응답·예외 매핑 |
| REC-03 | 유효한 WGS84 좌표의 미터 단위 반올림 직선거리 | 후보 소유권·점포 조회, 좌표 없음 HTTP 응답 계약 |

새 HTTP 엔드포인트나 Spring Bean은 자동 등록하지 않았다. BE1의 세션·권한·CSRF·공통 응답이 준비되지 않은 상태에서 비인증 API를 열지 않기 위해서다. 클래스는 명시적으로 조립할 수 있다. 전역 JSON 설정, SecurityConfig, 공유 Entity·Repository·enum·Flyway SQL은 추가하지 않았다.

## 연결 예시

```java
// 아래 source는 공유 Repository에 연결하는 BE3 읽기 어댑터다.
var categoryService = new PurposeCategoryService(categorySource);
var questionService = new PurposeQuestionService(categoryService, approvedPreferenceLabels);
var validator = new PurchaseConditionValidator(questionService, clock);

var conditions = validator.validate(createRequest);
// sessionCustomerId는 BE1이 인증한 CUSTOMER principal에서 가져온다.
// 공유 Entity에 조건과 conditions.answers()의 label 스냅샷을 함께 저장한다.
// 저장 커밋 이후에만 추천 작업을 시작하고, 공통 data 래퍼로 HTTP 202를 반환한다.

var queryService = new PurchaseRequestQueryService(purchaseRequestSource);
var payload = queryService.get(requestId, sessionCustomerId);
// payload를 BE1 공통 data 응답으로 감싼다.
```

학습/추천 모듈에는 `purposeCategoryName()`, `budgetAmount()`, `preferredPickupAt()`, `answerValues()`를 변환해서 넘긴다. 구매조건 검증은 특정 AI 평가기나 학습 파일에 의존하지 않는다.

### 읽기 어댑터 계약

- `PurposeCategorySource.findAll()`은 비활성 항목도 포함한 완전한 2단계 트리를 반환한다. 누락·중복 ID나 부모 연결 오류는 설정/데이터 오류로 처리한다. 상위 분류가 비활성인 경우 하위 분류도 사용하지 않는다.
- `PurchaseRequestSource.findById(Long)`는 저장 당시 답변과 질문 문구, 현재 처리 상태를 함께 읽는다. 조회할 때 최신 질문 문구로 바꿔 끼우지 않는다.
- Java 내부 ID는 Long이고 응답 DTO에서만 문자열로 직렬화한다. 가격은 숫자다. DTO는 `data` 내부 payload이며 공통 래퍼를 재정의하지 않는다.
- 시간 값은 오프셋을 유지하고, 미래 여부는 주입한 Clock의 시각과 Instant로 비교한다. DB 저장·변환 정책은 BE1 기준에 맞춰 어댑터에서 결정한다.
- 조회 서비스는 고객 ID만으로 역할을 판정하지 않는다. CUSTOMER 역할 검사와 세션 신뢰성은 호출 전에 BE1 인증 계층에서 보장해야 한다.

### 검증 및 공통 예외 연결

`CreatePurchaseRequest`에는 customer_id·label·추천 결과 필드가 없다. JSON 생성자는 숫자 ID, 문자열/소수/지수 표기의 예산, 알 수 없는 필드와 잘못된 답변 타입을 거절한다. 전역 ObjectMapper coercion 정책을 변경하지 않는다.

`PurchaseConditionValidator`는 다음을 검증한다.

- 활성 중분류, 예산 1~999999999999원, 시간대 포함 미래 수령일시
- TEXT 질문 코드의 중복·누락·알 수 없는 코드
- 필수 답변의 공백과 1000 Unicode code point 길이 제한
- budget 질문은 answers에 넣지 않고 budget_amount로만 받기
- 선택 질문 excluded_products는 생략 또는 빈 문자열 가능. null 답변은 불가

`PurchaseInputException`은 BE3 내부의 code·field·reason 전달용이다. 공통 HTTP 예외 처리나 응답 타입을 새로 만든 것이 아니다. BE1의 예외 처리에서 아래를 연결한다. JSON 생성 단계의 검증 예외는 Jackson 매핑 예외로 감싸질 수 있으므로 원인 예외도 처리해야 한다.

| 내부 코드 | 명세상 HTTP 매핑 |
| --- | --- |
| VALIDATION_ERROR, INVALID_PURPOSE_CATEGORY | 400 |
| UNAUTHORIZED | 401 |
| FORBIDDEN | 403 |
| PURPOSE_CATEGORY_NOT_FOUND, PURCHASE_REQUEST_NOT_FOUND | 404 |

질문 설정 누락, 읽기 어댑터의 잘못된 데이터는 내부 오류다. 원문 예외를 고객에게 노출하지 않는다. FAILED 상태 자체는 조회 실패가 아니며 PUR-04의 정상 200 payload로 반환한다. failure_code는 FAILED일 때만 값을 갖고 나머지는 명시적 null이다.

## 분류와 질문 참고 데이터

`src/main/resources/recommendation/purchase-reference.json`에는 명세의 대분류 3개·중분류 22개와 분류별 마지막 질문 문구 초안이 있다. `DRAFT_REFERENCE_NOT_DATABASE_SEED`로 표시했고 실행 시 DB에 넣거나 기본 Bean으로 자동 등록하지 않는다.

초기 스키마 담당자에게 분류 목록·표시 순서를 전달하고 실제 ID를 맞춘다. 질문 문구가 확정되면 승인된 Map을 PurposeQuestionService에 주입한다. 서비스는 질문이 누락된 분류에 임의의 공통 문구를 대신 제공하지 않는다. 질문 정의 하나를 조회와 답변 검증에서 함께 사용한다.

## 공통 코드 도착 후 작업

1. BE1 사용자 객체, CUSTOMER 권한, CSRF, 공통 응답·예외에 컨트롤러 연결.
2. 공유 Entity·Repository로 두 읽기 어댑터와 구매요청 저장 트랜잭션 구현. 초기 분류 데이터가 공유 SQL에 포함되는지 확인.
3. [비동기 처리 기반](be3-async-recommendation.md)의 커밋 후 전달·제한시간·복구 로직에 영속 저장소를 연결. 후보 저장과 READY 변경을 DB에서 원자적으로 수행.
4. BE2의 상품 ID·수령 희망일시·검증 기준시각 → 가능 여부/사유 Service 연결.
5. 구현된 CandidateRevalidator·RecommendationReadService를 실제 조회 어댑터와 공개 DTO에 연결. 현재 가격·예산과 BE2의 ON_SALE/SOLD_OUT/PAUSED 검증 결과를 반영하고 같은 구매요청의 기존 대체 후보만 재검증.

이 준비 작업은 실제 DB 저장·세션·CSRF·HTTP 통합·외부 AI 호출을 검증하지 않는다. 이 연결이 끝나기 전에는 10개 API 구현 완료로 표시하지 않는다.

## 로컬 검증

백엔드 저장소에서 Java 21로 실행한다.

```powershell
.\gradlew.bat test --tests 'com.togethermarket.recommendation.service.purchase.*' --tests 'com.togethermarket.recommendation.service.StoreDistanceCalculatorTest'
.\gradlew.bat spotlessCheck build
```

테스트는 DB와 외부 API를 호출하지 않는다. 활성 분류·질문 계약, 엄격한 JSON 타입, 예산·문자 길이·시간 경계, 소유권·질문 스냅샷 유지, 문자열 ID·금액·시간 직렬화, 좌표 누락과 직선거리를 확인한다.
