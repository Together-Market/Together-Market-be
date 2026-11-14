# 백엔드 초기 구조 인수인계

## 구현 범위

- ERD의 12개 테이블, V1 초기 SQL, V2 분류 25개(대분류 3 + 중분류 22).
- password_hash, purchase_requests.failure_code, 기존 UNIQUE/FK, 수량·금액·일정 CHECK.
- 전체 Entity 및 기본 Repository, 공통 상품·수령시간 검증.
- 예약 상태 enum은 기존 API의 PENDING/CONFIRMED/FAILED, 주문은 PENDING/APPROVED/REJECTED/EXPIRED를 옮긴 것으로 상태 전이 구현은 BE1 담당.
- 추천 상태는 최신 협의의 RECOMMENDING/READY/FAILED 사용. 후보 없음은 READY + 빈 목록.

## Entity 사용 규칙

- FK는 우선 Long ID 필드로 매핑한다. FK 무결성은 Flyway가 생성한 DB 제약으로 보장한다.
- 이는 도메인 간 객체 그래프/순환 참조 및 주문의 복합 FK 중복 매핑을 피하기 위한 초기 구현 선택이다. 다른 Entity가 필요한 경우 Repository로 조회한다.
- StoreOrder.storeId는 stores.id, productId는 products.id이며 SQL의 (store_id, product_id) 복합 FK가 소속 점포 일치를 보장한다.
- 상품 목적 태그만 EmbeddedId 복합키를 사용한다. 나머지 단일 ID는 IDENTITY 자동 증가.
- Entity 생성자는 id 이외의 필드를 받는다. 기능별 변경 메서드는 각 담당자가 유효성/상태 전이와 함께 추가한다. 범용 setter는 제공하지 않는다.
- answers는 JSON 문자열로 매핑한다. BE3는 서버에서 검증한 답변을 직렬화해서 저장한다. API 응답에는 문자열을 그대로 반환하지 말고 DTO로 변환한다.
- Entity는 API에 직접 노출하지 않는다. ID 문자열, snake_case, 시간 오프셋은 API DTO/공통 설정에서 처리해야 한다.
- DATETIME은 LocalDateTime으로 구조만 매핑했다. UTC/KST 저장 선택, 자동 생성시각, JDBC 시간대는 BE1 합의 전 확정하지 않았다.

## 검증 인터페이스

`ProductAvailabilityService.check(Long productId, Instant pickupAt, Instant checkedAt)`

반환: `AvailabilityResult`의 available(), reasons(). 불가 사유는 AvailabilityReason 참고.
절대시각인 Instant를 받으므로 DB 저장 시간대 결정과 독립적이다. 영업 일정 비교 시에만 용인 점포의 Asia/Seoul 시간으로 변환한다.

- 판매 상태, 미래 수령시간, 준비시간, 영업시간 및 수령시간을 확인.
- 주간 요일은 1(월)~7(일), 같은 구분의 특정일 일정 우선.
- 시작 포함/종료 제외. 당일 및 전날 시작한 자정 넘김 일정 확인.
- 당일 유효 일정이 휴무이면 전날부터 이어진 일정도 허용하지 않는다.
- 필요한 일정이 없으면 불가. 영업/수령은 각각 별도 검증.
- 수량 재고, 가격 합계/예산, 서로 다른 두 점포, 추천 소유권은 이 Service 범위 밖.
- 상태를 바꾸거나 잠그지 않는 읽기 전용 검사다. BE1은 예약 트랜잭션 안에서 가격/상태 동시 변경과 재검증을 조율해야 한다.
- BE3는 대량 후보 전체에 호출하기보다 DB 조건으로 후보를 먼저 좁혀야 한다. 현재 인터페이스는 상품별 조회이므로 필요 시 배치 조회 확장.

## 검증 실행

`./gradlew spotlessCheck build`는 DB 없는 기본 테스트와 검증 로직 단위 테스트를 실행한다.

MySQL 통합 테스트는 별도 테스트 DB를 만든 뒤 MYSQL_TEST_URL, MYSQL_TEST_USERNAME, MYSQL_TEST_PASSWORD를 설정하고 다음을 실행한다.

`./gradlew test --tests '*DatabaseSchemaIntegrationTest' --rerun-tasks`

MYSQL_TEST_URL이 없으면 해당 테스트는 건너뛴다. 테스트 DB에도 Flyway가 스키마를 생성하므로 운영 DB URL을 사용하지 않는다. 테스트 데이터는 트랜잭션 롤백한다. Flyway 스키마와 분류 seed는 유지한다.

## 담당별 다음 작업

- BE1: 세션/CSRF/로그인 사용자 전달, 공통 응답·예외, DB 시간대 확정, 예약·주문 트랜잭션/만료/멱등성.
- BE2: OWN-01~09 Controller/DTO/Service, 입력 검증, 소유권 검사, 일정 원자적 교체, 태그 수정, 공공데이터 수집/매칭.
- BE3: 구매요청/추천 API, Q5 설정, AI 비동기 작업, failure_code 정의, 조합 예산 검증, 대체 추천.
- FE 협의 대기: 가격 변경 확인 흐름, 대체 추천의 서로 다른 수령시간 처리, 비로그인 화면 공개 범위.
- 기본 클래스를 만드는 것으로 API가 구현된 것은 아니다. 현재 로그인 계정 seed, REST endpoint, 이미지 업로드 기능은 추가하지 않았다.

## 검증 결과 (2026-11-13)

- Java 21, 프로젝트 Gradle Wrapper로 spotlessCheck 및 build 통과.
- 별도 MySQL 8.4 컨테이너에서 V1/V2 실행 및 전체 Entity 스키마 검증 통과.
- 총 13개 테스트 통과: 기본 부팅 1, 검증 Service 3, 운영 일정 4, MySQL 통합 5. 건너뛴 테스트 없음.
- MySQL 통합 검증: 25개 분류 seed, 이메일/예약 키 중복 금지, 잘못된 일정·음수 가격 차단, 주문 상품·점포 소속 FK, JSON/enum JPA 저장·조회.
- 실제 서비스용 DB는 변경하지 않았다. 검증용 컨테이너는 테스트 후 제거한다.
- Controller/DTO와 OWN API, 인증, 외부 API 연동은 아직 구현하지 않았다. 본 변경은 공통 기반과 읽기 전용 검증 Service까지다.