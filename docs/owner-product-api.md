# 상품 관리 OWN-06~09 (이슈 #15)

- GET /owner/stores/{store_id}/products: page=1,size=20 기본(size 1~100), id 내림차순. sale_status 생략 시 모든 판매 상태 포함. 상품 목록의 태그는 일괄 조회.
- POST 같은 경로: 상품+중분류 태그를 한 트랜잭션으로 생성(201).
- PATCH /{product_id}: 전달 필드만 변경. 태그 전달 시 전체 교체, 생략 시 유지. sale_status는 받지 않는다.
- PATCH /{product_id}/sale-status: sale_status만 허용, ON_SALE/SOLD_OUT/PAUSED. 반복해서 같은 상태를 설정해도 허용.
- 응답은 {data:...}, ID 문자열, snake_case. 명세의 상품 필드와 purpose_category_ids 반환. 태그 ID는 숫자 오름차순.

## 입력 및 권한

상품 등록은 name,description,image_url,price_amount,preparation_minutes,sale_status,purpose_category_ids 필수. 이미지가 없으면 null.
PATCH는 1개 이상 필드. 필수 문자열/숫자/태그는 null 불가, image_url은 명시적 null로 제거 가능.
이미지는 유효한 HTTPS URL만 저장(외부 다운로드/업로드 없음). 사용자 정보가 포함된 URL은 거부한다.
가격은 0~999999999999 원 단위 정수, 준비시간 0~2147483647 정수. DB TEXT는 UTF-8 65535바이트 길이 검사.
태그는 존재하는 중분류 ID 문자열 배열 1개 이상. 중복/숫자형 ID/대분류/없는 ID 거부.
모든 기능에서 OWNER 역할 및 본인 점포 여부 확인, 수정은 product_id와 store_id가 일치해야 함. 타인/없는 객체 404.
고객 예약 당시 snapshot은 변경하지 않는다. 재고 수량·상품 삭제·이미지 업로드는 추가하지 않았다.

## 트랜잭션 및 BE1 연동

동일 상품의 정보/태그/판매 상태 수정은 상품 행의 비관적 잠금으로 직렬화한다.
태그 교체 전 product 변경을 flush하고 bulk delete/clear 후 태그를 새로 저장한다. 이전 복합키 Entity가 영속성 컨텍스트에 남는 문제를 방지한다. 하나라도 실패하면 전체 롤백.
기존 OwnerAccess 및 점포 전용 오류 처리를 재사용. BE1의 실제 인증/CSRF 어댑터 연결 전 503 차단은 동일하다.
BE1은 예약 생성의 가격·판매 상태 경합을 별도로 처리해야 하며, 본 잠금이 추천·예약 로직까지 자동으로 보호하지는 않는다.
공통 응답·예외 처리 준비 후 wrapper와 전용 handler를 통합해야 한다.

## 검증

Controller+Service HTTP 테스트(인증·저장소 대체), 실제 MySQL의 상품·태그 round trip, 오류 롤백 및 동시 변경을 검사한다.
MySQL 테스트는 기존 MYSQL_TEST_URL/USERNAME/PASSWORD 방식으로 격리된 DB에서 실행한다.

## 검증 결과 (2026-11-13)

- spotlessCheck 및 전체 build --rerun-tasks 통과.
- 신규 HTTP 테스트 8개 및 실제 MySQL 8.4 통합 테스트 3개 모두 통과(건너뜀 없음).
- 같은 태그 재등록, 판매 필터, 태그 INSERT 실패 시 상품 생성/수정 전체 롤백, 동시 정보/판매 상태 변경 유지 확인.
- MYSQL_TEST_URL 설정을 바꿨을 때는 기존 Gradle 테스트 결과 재사용을 막기 위해 --rerun-tasks를 사용한다.
- 실제 로그인·CSRF 연결 E2E는 BE1 연결 후 별도로 확인해야 한다.