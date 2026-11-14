# BE1 세션 인증 연동

작업 이슈: #18. 기존 BE2 경로에 맞춰 현재 `/auth`를 사용합니다. `/api/v1` 적용 여부는 팀 합의 후 일괄 변경합니다.

## 요청 순서

1. `GET /auth/csrf` → `{ "data": { "csrf_token": "..." } }`. 세션 쿠키를 보관합니다.
2. `POST /auth/login`: `{ "email": "...", "password": "..." }`, `X-CSRF-Token` 헤더와 쿠키를 함께 전송합니다.
3. 로그인 성공 후 세션 ID와 CSRF 토큰이 변경됩니다. `GET /auth/csrf`를 다시 호출합니다.
4. `GET /auth/me`는 `{ "data": { "id": "1", "email": "...", "name": "...", "role": "CUSTOMER" } }`를 반환합니다.
5. 변경 요청에는 새 CSRF 토큰을 보냅니다. `POST /auth/logout`은 세션과 토큰을 폐기하고 `{ "data": { "logged_out": true } }`를 반환합니다. 이후 로그인하려면 CSRF부터 다시 조회합니다.

로그인 실패는 이메일 존재 여부를 구분하지 않고 `401 INVALID_CREDENTIALS`로 반환합니다. 비밀번호는 trim하지 않으며 DB에는 BCrypt 해시가 필요합니다. 회원가입 API는 이번 범위에 없습니다.

## 팀원 사용법

- 컨트롤러에서 `@AuthenticationPrincipal LoginUser user`로 받고 `user.userId()`, `user.role()`을 사용합니다. ID는 세션에서 추출하며 요청의 고객 ID나 역할을 신뢰하지 않습니다.
- `OwnerAccess.requireOwner(request)`는 기존 BE2 인터페이스 그대로 연결됩니다. CSRF는 Security 필터에서 검사하고 점포 소유권은 기존 서비스에서 검사합니다.
- `/owner/**`는 OWNER만 허용합니다. 공개 기능이 합의되지 않은 나머지 경로는 기본적으로 로그인이 필요합니다. CSRF 조회·로그인·로그아웃은 비로그인 요청도 가능하나 POST에는 CSRF가 필요합니다.
- 공통 성공 응답은 `ApiResponse<T>`, 예외는 `ApiException`, 오류는 `{code,message,errors}`입니다. 기존 BE2 응답 및 예외 처리는 유지합니다.
- 기존 BE2와 맞춰 `UNAUTHORIZED`, `FORBIDDEN`, `VALIDATION_ERROR`를 사용하고 인증 전용으로 `INVALID_CREDENTIALS`, `CSRF_INVALID`를 추가했습니다. 최종 오류 코드표는 명세와 팀 합의 후 통일합니다.

## 합의 필요

- 백엔드 HTTPS 배포 주소와 MySQL 실행 위치. 프런트는 `http://localhost:5173` 로컬 실행으로 확정
- 세션 만료시간, 다중 서버 운영 시 세션 공유 방식
- 비로그인 공개 조회 범위, API 접두사, 최종 오류 코드표

교차 출처 허용은 `CORS_ALLOWED_ORIGINS` 환경변수로 지정합니다. 현재 프런트 주소인 `http://localhost:5173`을 넣고 서버를 재시작합니다. 쉼표로 여러 출처를 지정할 수 있고, 빈 값이면 동일 출처만 허용합니다. 와일드카드·경로·끝의 `/`는 거부합니다. 쿠키 포함 요청과 `X-CSRF-TOKEN`, `Idempotency-Key`를 허용하며 preflight OPTIONS를 인증 전에 처리합니다. 실제 요청의 세션·역할·CSRF 검사는 유지됩니다. CORS 거부는 공통 JSON 형식의 `403 CORS_REJECTED`이며 브라우저는 허용되지 않은 출처에 오류 본문도 공개하지 않을 수 있습니다.

프런트의 모든 요청은 fetch `credentials: "include"` 또는 Axios `withCredentials: true`를 사용합니다. 로컬 프런트에서 배포 HTTPS 백엔드를 직접 호출할 때는 배포 서버에 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`, `SERVER_SERVLET_SESSION_COOKIE_SAME_SITE=none`을 설정합니다. 로컬 HTTP 백엔드에는 이 배포용 쿠키 설정을 적용하지 않습니다. 브라우저의 서드파티 쿠키 차단은 별도로 검증해야 합니다. 상세 설정·프록시 대안은 [배포 안내](kakao-setup-deployment.md)를 참고합니다.

로컬 `demo`에서는 세션이 서버 메모리에 저장되어 재시작 시 로그아웃됩니다. 배포용 `demo,cloudsql`에서는 [JDBC 세션 설정](jdbc-session-deployment.md)으로 DB에 저장합니다. HttpOnly와 쿠키 전용 세션 전달을 적용했습니다. 실제 브라우저·배포 DB 확인 전 운영 배포 준비 완료로 간주하지 않습니다.

## 검증

`./gradlew build spotlessCheck`

DB 없이 MockMvc + 실제 Security 필터로 세션 로그인, 세션 ID 교체, CSRF 갱신/누락/구 토큰 거부, 로그아웃, 잘못된 입력·비밀번호, 고객의 점포 접근 거부, 점주의 기존 점포 API 연결을 확인합니다. CORS는 허용 출처의 preflight·세션 로그인, 다른 호스트/포트·허용하지 않은 헤더 거부, 인증·CSRF 유지, 비정상 허용 목록의 시작 실패를 확인합니다. 실제 MySQL 검증은 기존 `MYSQL_TEST_URL` 등 환경변수를 설정해야 실행됩니다.

## 공통 오류 응답 보완

필드 오류는 기존 BE2·BE3와 동일하게 `{field, reason}`을 사용합니다. field는 Jackson의 실제 JSON 이름을 따르며 `customer_name`, `pickups[0].requested_pickup_at`처럼 중첩 경로와 배열 위치를 보존합니다. HTTP 405는 METHOD_NOT_ALLOWED, 415는 UNSUPPORTED_MEDIA_TYPE과 공통 오류 본문을 반환하며 Allow 등 표준 헤더를 유지합니다.
