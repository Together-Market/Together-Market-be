# OWN-01~03 점포 관리

Notion OWN-01~03의 경로/정상 응답을 기준으로 구현했다.

- GET /owner/stores: 본인 점포만 id 내림차순. page=1, size=20 기본, size 1~100.
- POST /owner/stores: HTTP 201. owner_id는 서버 인증 결과에서 결정. body에는 받을 수 없다.
- PATCH /owner/stores/{store_id}: HTTP 200. 본인 점포만 변경. 다른 소유자/없는 점포는 동일 404.
- DB 금액·날짜 정책이나 기존 마이그레이션 변경 없음.

## 입력 및 반환

등록 필드: name, business_type, address, latitude, longitude, phone. 좌표 없는 경우 두 필드 모두 null.
PATCH는 위 필드 중 하나 이상. 생략은 유지, 필수 문자열 null/공백 금지. 좌표는 명시적 null로 삭제할 수 있으나 변경 후 두 좌표 모두 null 또는 모두 값이어야 한다.
좌표 범위는 위도 -90~90, 경도 -180~180, 소수점 7자리 이하. 문자열 길이는 DB 컬럼과 일치.
알 수 없는 필드(특히 owner_id/id)는 400. JSON 숫자를 문자열로 전달한 좌표도 400.
응답 ID는 문자열, JSON snake_case. owner_id는 등록 응답에만 포함. Entity 직접 노출 없음.
수정은 소유권 조건과 비관적 잠금을 함께 사용해 다른 PATCH의 변경 유실을 방지한다.

## BE1 연결 필수

OwnerAccess.requireOwner(HttpServletRequest)를 구현하는 Spring Bean을 제공한다.
BE1은 이 Bean에서 인증된 서버 세션으로 사용자 ID를 조회하고 OWNER 역할, 변경 요청의 CSRF 검증을 수행해야 한다(역할은 Service에서도 DB 기준 재확인).
세션 key, 가짜 사용자, 클라이언트 owner_id를 임의로 도입하지 않았다.
어댑터가 없으면 기본 설정이 모든 OWN-01~03 요청을 HTTP 503 AUTHENTICATION_NOT_CONFIGURED로 차단한다. 따라서 실제 로그인 기반 E2E는 BE1 연결 전 완료되지 않았다.
Bean 탐색 순서 문제를 피하려면 BE1 설정에서 OwnerAccess Bean을 명시하고 기본 fallback 설정 제거/대체를 함께 확인한다.
현재 컨트롤러 전용 응답 wrapper/ExceptionHandler는 공통 코드 준비 전 사용한다. BE1 공통 wrapper/advice 연결 시 중복을 제거하고 기존 JSON을 유지한다.

## 검증

OwnerStoreControllerTest: 실제 Controller+Service를 연결한 MockMvc 테스트. 저장소와 인증만 대체한다. 요청/응답, 역할/소유권, PATCH, 잘못된 입력, 페이지 정렬을 검증한다.
OwnerAccessConfigurationTest: 인증 어댑터 누락 시 차단, 제공 시 교체 검증.
MySQL 조건부 통합 테스트는 기존 환경변수 방식으로 실행한다.

## 실행 결과 (2026-11-13)

- spotlessCheck 및 전체 build 통과.
- MySQL 8.4에서 전체 25개 테스트 통과, 실패/건너뜀 없음.
- 새 테스트 12개: HTTP Controller/Service 8개, 인증 fallback 2개, MySQL 점포 등록·목록·수정·소유권 검증 2개.
- 실제 인증/CSRF 어댑터 연결 E2E는 아직 대기 중이며 테스트에서만 인증을 대체했다.