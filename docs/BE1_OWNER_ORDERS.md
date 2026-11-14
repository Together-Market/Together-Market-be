# BE1 점주 주문·예약 결과 처리

작업 이슈 #22. 예약 생성 PR #21과 인증 PR #19에 의존합니다.

| API | 권한·동작 |
| --- | --- |
| GET /owner/stores/{store_id}/orders | 본인 점포 주문 목록 |
| GET /owner/stores/{store_id}/orders/{order_id} | 해당 점포의 주문 상세 |
| POST /owner/stores/{store_id}/orders/{order_id}/approve | 점주 승인, 본문 없음 |
| POST /owner/stores/{store_id}/orders/{order_id}/reject | 점주 거절, rejection_reason 필수 |

OWNER 세션과 점포 소유권을 확인합니다. 주문도 경로의 해당 점포에 속해야 합니다. 다른 점포·주문은 404이며 다른 점포의 상품·주문·고객 정보는 응답에 포함하지 않습니다. 변경 요청은 X-CSRF-Token이 필요합니다.

## 목록·응답

목록은 page=1, size=20 기본, size 최대 100입니다. approval_status와 reservation_status는 AND 필터입니다. 최신 예약 생성일시·예약 ID·주문 ID 순으로 정렬합니다. 응답은 {data:{items,page,size,total_count}}입니다.

주문 응답에는 명세의 기존 주문 필드와 reservation_status, failure_code, approval_deadline_at, customer_name, customer_phone, customer_note, can_respond가 포함됩니다. 다른 점포의 주문 배열은 반환하지 않습니다. ID는 문자열, JSON은 snake_case, 시간은 +09:00입니다.

can_respond는 전체 PENDING·개별 PENDING·승인 기한 이전일 때만 true입니다. 개별 APPROVED만으로 고객에게 예약 확정을 안내하면 안 됩니다.

## 승인·거절과 동시 요청

- 전체 예약 행을 먼저 잠그고 자식 주문을 읽습니다. 같은 예약의 두 점포 응답·고객/점주 조회의 만료 처리·백그라운드 만료는 이 잠금에 따라 직렬화합니다.
- 잠금을 얻은 뒤 서버 시각으로 기한을 확인합니다. 기한과 같은 시각부터 만료입니다.
- 한 점포 승인: 개별 APPROVED, 전체 PENDING.
- 두 점포 모두 승인: 전체 CONFIRMED.
- 한 점포 거절: 개별 REJECTED, 전체 FAILED / STORE_REJECTED. 이미 다른 점포가 승인했다면 그 기록은 유지합니다. 나머지 개별 PENDING도 원래 기록으로 유지하지만 can_respond는 false입니다.
- 기한 만료: 전체 FAILED / APPROVAL_EXPIRED, 남은 개별 PENDING은 EXPIRED. 기존 승인·거절 기록은 보존합니다.
- 새 승인·거절은 전체·개별 PENDING인 경우에만 허용합니다. 예약 당시 단가·상품·수령 스냅샷을 사용하고 가격을 다시 바꾸거나 재고를 차감하지 않습니다. 최종 판매·준비 가능 여부는 점주가 확인합니다.
- 늦은 새 동작은 409 RESERVATION_EXPIRED입니다. 이 409 때문에 만료 갱신이 롤백되지 않도록 해당 예외만 트랜잭션 커밋 대상입니다. 그 외 처리 오류는 일반적인 롤백 규칙을 따릅니다.

거절 본문 예: {"rejection_reason":"해당 시간에 상품 준비가 어렵습니다."}. 공백만 있는 내용은 거절하며 길이는 1000자 이내입니다. 입력 사유를 임의로 trim하거나 덮어쓰지 않습니다.

## 재요청: 팀 합의 필요 기준안

이미 승인한 주문에 같은 승인 요청, 이미 거절한 주문에 정확히 같은 거절 사유를 보낸 요청은 **현재 주문·예약 결과로 200**을 반환합니다. responded_at이나 거절 사유를 변경하지 않습니다. 전체 예약이 이후 실패했더라도 성공했던 개별 응답 기록의 재조회로 취급하므로 전체 FAILED와 can_respond=false를 그대로 표시합니다.

새로운 승인·거절, 반대 동작, 이미 저장한 거절 사유 변경은 종료된 예약을 다시 열지 않으며 409를 반환합니다. 주요 코드는 RESERVATION_NOT_PENDING, ORDER_ALREADY_RESPONDED, RESERVATION_EXPIRED입니다. 이 재응답 정책과 오류 코드표는 팀 공유·합의 대상입니다.

## 백그라운드 만료

기본적으로 30초 간격, 최대 100건씩 기한이 지난 PENDING 예약 ID를 선택합니다. 각 예약은 별도 트랜잭션으로 잠근 뒤 현재 상태·기한을 다시 확인합니다. 여러 서버가 같은 대상을 선택해도 이미 처리된 예약은 건너뛰며 확정 예약을 실패로 바꾸지 않습니다. 잠금 충돌로 한 건이 실패해도 나머지를 처리하고 다음 주기에 재시도합니다.

운영 설정:

- reservation.expiry-scheduler.enabled: 기본 true, 테스트·수동 점검 시 false 가능
- reservation.expiry-scheduler.delay: 기본 PT30S
- reservation.expiry-scheduler.batch-size: 기본 100, 허용 1~1000

스케줄러 지연과 무관하게 고객·점주 조회와 승인/거절에서도 만료를 확인합니다. 목록은 만료 상태를 저장한 뒤 상태 필터와 개수를 계산합니다. 예약 승인 마감 산정은 기존 reservation.approval-window 설정을 그대로 사용하며 숫자 기본값은 여전히 합의 전입니다. 스케줄러 실행 간격은 승인 허용시간과 별개입니다.

Flyway 번호 충돌을 피하기 위해 이번 작업에는 스키마 변경이 없습니다. 운영 데이터량이 늘면 상태·마감시간 조회 인덱스는 BE2와 마이그레이션 번호를 조율해 추가할 수 있습니다.

## 검증

로컬: ./gradlew build spotlessCheck.

CI MySQL 8.4 테스트는 기존 예약 검증에 두 점포 동시 승인, 같은 주문의 승인·거절 경쟁, 기한 만료와 최종 승인 경쟁, 늦은 요청의 409 이후 만료 커밋, 점포 소유권·주문 소속, AND 상태 필터, 반복 만료·확정 보존을 추가했습니다. 테스트 환경에서는 자동 실행을 끄고 고정 시각의 작업자를 직접 호출합니다. 별도 로컬 테스트로 스케줄러 등록과 비활성화 설정도 확인합니다.
