# BE1 고객 예약 API

작업 이슈 #20. 인증 PR #19에 의존합니다. 경로는 기존 구현과 동일하게 접두사 없이 사용합니다.

| API | 동작 |
| --- | --- |
| POST /reservations | 세션 고객의 추천으로 통합 예약 1건과 점포 주문 2건 생성 |
| GET /reservations | 본인 예약 목록, page=1·size=20 기본, status 선택 |
| GET /reservations/{id} | 본인 예약 상세, 타인 예약·없는 예약은 404 |

모든 API는 CUSTOMER 세션이 필요합니다. 생성은 CSRF와 UUID 형식의 Idempotency-Key가 필요합니다. 응답은 기존 명세의 snake_case, 문자열 ID, 숫자 금액, +09:00 시간 형식을 사용합니다.

## 생성 요청 예시

```json
{
  "recommendation_candidate_id": "200",
  "customer_name": "김고객",
  "customer_phone": "010-0000-0000",
  "customer_note": null,
  "pickups": [
    {"store_id": "3", "requested_pickup_at": "2026-11-20T15:00:00+09:00"},
    {"store_id": "4", "requested_pickup_at": "2026-11-20T15:15:00+09:00"}
  ],
  "expected_total_amount": 55000
}
```

추천 소유권·READY 상태, 상품 2개 각 수량 1·다른 점포 2곳을 확인합니다. 점포별 pickup은 추천의 점포 집합과 일치해야 합니다. BE2 ProductAvailabilityService로 현재 판매·영업·휴무·수령·준비시간을 검사합니다. 현재 가격 합계가 expected_total_amount와 다르면 PRICE_CHANGED, 구매 예산 초과이면 BUDGET_EXCEEDED로 409를 반환합니다. 재고 수량 차감·확보는 하지 않습니다.

단가·상품명·수령 주소·수령시간은 예약 시점 스냅샷입니다. store_name은 현재 점포명입니다. 추천 스냅샷 가격은 예약 금액으로 사용하지 않습니다. 목록·상세 total_amount는 주문 스냅샷 합계입니다.

## 저장과 동시 요청

- 고객 행을 먼저 잠가 같은 고객의 예약 생성을 직렬화합니다. 미생성 키도 이 잠금으로 보호하고 기존 (customer_id, request_key) UNIQUE를 유지합니다.
- 기존 키 검사는 상품·판매 조건 재검증보다 먼저 합니다. 동일 요청이면 기존 예약의 현재 상태로 200, 신규 저장이면 201, 다른 요청이면 409 IDEMPOTENCY_KEY_REUSED입니다.
- 해시는 pickup을 점포 ID 순으로 정렬하고 날짜를 Instant로 정규화합니다. JSON 필드·pickup 순서나 같은 시각의 다른 시간대 표기는 같은 요청으로 봅니다. note null과 빈 문자열은 구분합니다.
- 고객 → 기존 예약 또는 점포 ID 오름차순 → 상품 ID 오름차순으로 잠급니다. 상품은 잠금 후 새로 읽어 최신 가격·판매 상태를 사용합니다. 영업시간 변경은 BE2의 동일 점포 행 잠금과 직렬화합니다.
- 예약과 두 주문을 하나의 READ_COMMITTED 트랜잭션으로 저장합니다. 주문 저장 실패 시 전체 롤백됩니다. 실패한 요청의 키는 별도로 보관하지 않습니다.
- 동시 요청은 먼저 진행 중인 트랜잭션을 기다린 뒤 기존 예약을 반환합니다. DB 잠금 타임아웃·교착으로 실패하면 409 REQUEST_IN_PROGRESS이며 같은 키로 재시도합니다.

DB DATETIME은 KST의 LocalDateTime으로 기록합니다. 입력은 오프셋을 포함해야 하고 초 단위로 제한합니다. API 출력은 Asia/Seoul의 +09:00입니다. timestamp를 DB 서버의 NOW()나 서버 기본 시간대로 만들지 않습니다.

## 마감·만료: 팀 합의 필요

승인 마감시간의 숫자 기본값을 확정하지 않았으므로 `reservation.approval-window`를 지정하지 않으면 **신규 생성은 503 RESERVATION_POLICY_NOT_CONFIGURED**입니다. 기존 예약 조회·동일 키 재응답은 가능합니다.

현재 구현 기준안은 `min(생성시각 + 설정 승인시간, 두 상품 중 가장 이른 수령시각 - 준비시간)`입니다. 생성시각보다 늦은 마감시간을 확보할 수 없으면 409 APPROVAL_WINDOW_UNAVAILABLE입니다. 시간과 산정식 모두 팀 합의가 필요합니다. 로컬 테스트나 시연에서 팀이 정한 값으로 아래처럼 설정할 수 있습니다. PT30M은 테스트에만 쓰는 예시입니다.

```shell
RESERVATION_APPROVAL_WINDOW=PT30M ./gradlew bootRun
```

고객 상세·목록·동일 키 재응답 시 기한이 지난 PENDING 예약은 parent 예약 행을 잠근 상태에서 FAILED / APPROVAL_EXPIRED로 갱신합니다. 개별 PENDING 주문은 EXPIRED로 변경하고 기존 APPROVED/REJECTED 기록은 보존합니다. 목록은 갱신 후 status 필터·total_count를 계산합니다. 점주 승인·거절 API와 백그라운드 만료 스케줄러는 #22에서 동일한 예약 행 잠금과 만료 전이로 연결했습니다. 상세 규칙은 [점주 주문 문서](BE1_OWNER_ORDERS.md)를 참고합니다.

## 추가 합의 필요

- UUID 키 형식, 신규 201·재응답 200, 성공 키 무기한 보관·실패 키 미보관 정책
- 고객명 100자·연락처 30자(DB 한도), 메모 1000자 제한 및 연락처 허용 형식
- 점포별 다른 수령시간 허용 여부와 store_name 현재값 반환
- 조회 시 만료 갱신 및 개별 EXPIRED 사용 정책, 최종 오류 코드표

## 검증

로컬: `./gradlew build spotlessCheck`.

실제 MySQL 테스트는 `MYSQL_TEST_URL`, `MYSQL_TEST_USERNAME`, `MYSQL_TEST_PASSWORD` 설정 후 `./gradlew test --tests '*ReservationDatabaseIntegrationTest'`로 실행합니다. **전용 테스트 DB**를 사용해야 합니다. 테스트는 임시 트리거로 두 번째 주문 저장 실패를 유도한 뒤 트리거를 제거합니다.

CI의 reservation-mysql 작업은 별도 MySQL 8.4에서 동시 동일 키 생성, 두 번째 주문 실패 시 전체 롤백과 재시도, 가격 변경 후 스냅샷 유지, 타인 접근 차단, 만료 처리 후 상태 필터를 검사합니다. 로컬에서 DB를 구동하지 못한 경우 이 CI 결과로 별도 확인합니다.
