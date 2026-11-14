# 카카오 설정·로컬 확인·배포 준비

2026-11-15 기준. 사용자가 카카오맵 사용 설정을 ON으로 저장했음을 확인했다. 프런트는 `http://localhost:5173`에서 실행하며 **백엔드만 배포**한다. 백엔드 배포 서비스와 MySQL 실행 위치는 아직 미정이다. 실제 키·비밀번호·토큰·쿠키는 사용자만 본인 화면에서 입력하고 이 문서나 채팅에 붙여 넣지 않는다.

이번 구현은 카카오 **실시간 미리보기**와 공공데이터 좌표 보완이다. 카카오 결과 영구 저장은 사용자 결정에 따라 차단되어 있다. 키를 설정해도 자동 좌표 저장이 활성화되지 않는다. [변경 API 명세](kakao-geocoding-api.md), [데이터 보완 순서](coordinate-backfill.md)를 함께 참고한다.

## 1. 카카오 앱과 키 설정

1. 브라우저에서 [카카오 개발자 사이트](https://developers.kakao.com/)에 직접 로그인한다. 상단 `앱`에서 같이팔장에 사용하는 앱을 선택한다.
2. 해당 앱의 `카카오맵` → `사용 설정`에서 상태가 `ON`인지 확인한다. 이미 ON으로 저장했다고 확인했으므로 다시 앱을 만들거나 키를 재발급할 필요는 없다.
3. 앱 정보에 `카카오맵 무료 쿼터` 배지가 있는지 확인한다. 최신 안내는 개발자 계정에서 처음 활성화한 앱에 무료 쿼터를 제공한다. 두 번째 앱/무료량 초과의 유료 설정을 임의로 켜지 말고 팀 사용 계획부터 확인한다. 사용량은 `통계` → `쿼터`에서 확인한다. [카카오맵 공식 설정·쿼터 안내](https://developers.kakao.com/docs/ko/kakaomap/common).
4. 왼쪽 `앱` → `플랫폼 키` → `REST API 키`로 이동한다. 이 키는 **백엔드**용이다. 복사 버튼으로 본인이 복사하고 아래 IntelliJ 환경변수 칸에만 입력한다.
5. 이 주소 변환은 REST 키 헤더 인증을 사용하므로 카카오 로그인 활성화, 리다이렉트 URI 등록, 로그인용 클라이언트 시크릿 변경은 필요하지 않다. 기존 로그인 관련 설정을 바꾸지 않는다.
6. `호출 허용 IP 주소`를 사용한다면 카카오가 보는 백엔드의 외부 통신 IP가 대상이다. 입력란에 해당 공인 IP를 입력하고 `저장`한다. `localhost`, `127.0.0.1`, 프런트 URL을 여기에 넣는 것이 아니다. 배포 서버의 고정 외부 IP는 배포 방식 결정 후 확인한다.
7. 지도용 키는 `앱` → `플랫폼 키` → `JavaScript 키`로 이동해 해당 키의 `JavaScript SDK 도메인` 편집 화면을 연다.
8. 도메인 입력란에 확정된 프런트 주소 `http://localhost:5173`을 입력하고 `저장`한다. API 경로나 끝의 `/`는 붙이지 않는다. 기존 목록에 있다면 중복 추가하지 않는다. 프런트 브라우저도 이 주소로 연다.
9. 이번에는 백엔드만 배포하므로 이 목록에 프런트 배포 도메인을 추가할 작업은 없다. 백엔드 배포 주소를 지도용 도메인 대신 등록하지 않는다. 나중에 프런트 주소를 바꾸면 그때 실제 사용하는 주소를 추가한다.

키 관리 화면과 도메인·IP 규칙은 [카카오 앱 설정 공식 문서](https://developers.kakao.com/docs/ko/app-setting/app), 주소 API 인증은 [공식 REST 문서](https://developers.kakao.com/docs/ko/local/dev-guide#address-coord)를 기준으로 한다. 키 값은 공유 채팅·Git에 넣지 않는다.

## 2. IntelliJ에서 백엔드 REST 키 입력

1. IntelliJ에서 `C:\Together-Market-be` 프로젝트를 연다.
2. 상단 `File` → `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Gradle`에서 `Gradle JVM`을 JDK 21로 선택하고 `Apply` → `OK`를 누른다. 코드의 toolchain도 Java 21이다.
3. 상단 `Run` → `Edit Configurations...`를 누르고 기존 서버용 **Gradle** 실행 설정을 선택한다. 설정이 없다면 왼쪽 `+` → `Gradle`, Name은 `Together Market demo`, Gradle project는 `C:\Together-Market-be`로 지정한다.
4. `Run`/`Tasks and arguments` 칸에는 기존과 같이 아래 내용을 입력한다.

```text
bootRun --args="--spring.profiles.active=demo --demo.catalog.enabled=false --reservation.approval-window=PT30M"
```

5. `Environment variables` 칸 오른쪽 `...`를 클릭한다. 칸이 보이지 않으면 `Modify options`에서 환경변수 표시 옵션을 켠다.
6. 환경변수 편집 창의 `+`를 누르고 Name에 정확히 `KAKAO_REST_API_KEY`를 입력한다. Value에는 **본인이 복사한 REST API 키**를 직접 붙여 넣는다. `KakaoAK ` 접두사·따옴표를 붙이지 않는다. 코드는 접두사를 헤더에 붙인다.
7. 기존 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, 시연에 사용하는 AI 설정은 본인이 유지한다. 값이 여러 개라면 새 행으로 추가한다. 키를 프로그램 인자나 Gradle VM 옵션에 넣지 않는다.
8. `Store as project file`은 체크하지 않는다. 환경변수 창 `OK` → 실행 설정 창 `Apply` → `OK`를 누른다. 해당 개인 설정 파일을 Git에 추가하지 않는다.
9. 실행 중 서버가 있으면 Run 창의 빨간 정지 버튼을 누른 다음 초록 실행 버튼으로 재시작한다. 환경변수 변경은 이미 실행 중인 프로세스에 자동 반영되지 않는다.
10. 키 값을 콘솔로 출력해 확인하지 말고 다음 API 동작으로 확인한다. `.env` 파일을 만들어도 이 프로젝트가 자동으로 읽는 설정은 추가하지 않았다.

코드 연결: `KAKAO_REST_API_KEY` → `kakao.local.rest-api-key` → `GeocodingConfiguration` → `KakaoAddressGeocoder`. 누락된 상태에서도 서버는 부팅하고 해당 API만 `503 GEOCODE_NOT_CONFIGURED`를 반환한다. [IntelliJ Gradle 실행 설정 공식 안내](https://www.jetbrains.com/help/idea/run-debug-gradle.html).

로컬 프런트와 로컬 백엔드를 먼저 연결하려면 `Run` → `Edit Configurations...` → 같은 Gradle 설정 → `Environment variables` 오른쪽 `...` → `+`에서 **Name**에 `CORS_ALLOWED_ORIGINS`, **Value**에 `http://localhost:5173`을 입력한다. `OK` → `Apply` → `OK` 후 서버를 재시작한다. 이 값은 비밀정보가 아니다. 로컬 백엔드가 HTTP일 때 아래 배포용 Secure/SameSite 설정은 추가하지 않는다. 프런트의 요청에는 로컬 연결에서도 `credentials: "include"`가 필요하다.

## 3. Postman에서 직접 확인

기존에 검증한 점주 로그인/CSRF 요청 묶음을 그대로 사용한다. 비밀값을 이 문서 예시로 바꾸거나 응답 전체를 채팅에 붙여 넣지 않는다.

1. 서버 시작 후 Postman을 연다. 기존 컬렉션에서 OWNER 계정의 로그인 흐름을 실행한다. 고객 계정은 이 API에 403이다.
2. 새 요청 탭 `+`를 열고 메서드를 `GET`으로 선택한다.
3. 주소 입력 칸에 `http://localhost:8080/owner/stores/geocode`를 넣는다. 서버 포트가 다르면 실제 포트로 바꾼다.
4. 아래 `Params` 탭에서 Key에 `address`, Value에 본인이 확인하려는 완전한 도로명 주소(건물번호 포함)를 입력한다. 인코딩이 필요한 경우 Value의 주소 전체를 선택하고 우클릭 → `EncodeURIComponent`로 한 번 인코딩한다. 이미 `%`로 인코딩된 값에는 반복 적용하지 않는다. Body는 `none`으로 둔다. [Postman 공식 파라미터 안내](https://learning.postman.com/docs/use/send-requests/create-requests/parameters/).
5. Authorization에 카카오 REST 키를 넣지 않는다. 이 요청은 우리 백엔드 세션으로 인증하며 카카오 키는 서버가 사용한다. 기존 로그인 요청과 같은 호스트 이름을 사용해 Postman의 쿠키 관리를 유지한다.
6. `Send`를 누른다. 성공이면 200과 `data.latitude`, `data.longitude`, `coordinate_source=KAKAO`, `storage_allowed=false`가 반환된다. 이 좌표를 DB 저장용으로 복사하지 않는다.
7. 401이면 점주 로그인 세션, 403이면 계정 역할을 확인한다. 503 `GEOCODE_NOT_CONFIGURED`면 환경변수 이름/재시작/REST 키 종류/앱 ON/호출 IP를 본인 화면에서 확인한다. 422는 주소를 구체화한다. 429에 해당하는 내부 코드는 `503 GEOCODE_RATE_LIMITED`이며 연속 Send를 멈추고 카카오 `통계` → `쿼터`를 확인한다.
8. 기존 점포를 수정해 확인할 경우 먼저 GET `/owner/stores`로 본인 점포를 확인한다. 기존 시연 데이터의 주소를 시험 삼아 바꾸지 말고, 쓰기 테스트는 별도 테스트 점포/테스트 DB에서 한다.
9. 새 테스트 점포에 저장 가능한 좌표를 제공하면 OWN-02 201, 좌표 없는 등록/카카오 출처 등록은 503을 기대한다. 주소만 변경한 PATCH도 503이며 기존 주소·좌표·전화번호가 유지되어야 한다. POST/PATCH는 기존 CSRF 토큰 사용 절차를 따른다.
10. CUSTOMER 세션에서 기존 추천의 REC-03을 호출한다. 좌표 누락이면 200 + `location_status=UNAVAILABLE`, `distance_meters=null`; 좌표가 모두 있으면 `AVAILABLE`과 숫자다. API 조회가 카카오 쿼터를 소비하지 않는다.

로그인 사용자 ID/비밀번호/CSRF 값/쿠키를 이 작업에 전달할 필요가 없다. 오류를 알려줄 때는 HTTP 상태와 `code`만으로 먼저 진단할 수 있다.

## 4. 기존 데이터 보완

사용자가 직접 확인한 현재 DB 결과는 `total_stores=598`, `missing_pair_count=0`, `usable_pair_count=598`, `zero_pair_count=0`이다. **현재 데이터에는 좌표 보완 실행이 필요 없다.** 이 수치는 좌표 쌍·범위 검증 결과이며 각 주소와 좌표의 실제 일치까지 증명하지는 않는다. REC-03의 두 점포 좌표가 같아서 거리 0인 경우는 정상일 수 있다. 카카오 주소 조회의 `storage_allowed=false` 응답도 사용자가 확인했다.

앞으로 누락 데이터가 생길 때만 [데이터 보완 안내](coordinate-backfill.md)의 백업→읽기 SQL→10개 미리보기→같은 ID 범위 적용→재조회 순서대로 직접 진행한다. 공공데이터 기반 실행은 카카오 키 없이 가능하다. 미리보기로 확인된 좌표가 없는 경우 데이터 초기화/삭제/카탈로그 재입력을 해결책으로 사용하지 않는다.

이번부터 카탈로그 자동 입력은 기본 OFF다. 새 시연 DB를 만드는 별도 작업에서만 `demo.catalog.enabled=true`를 명시한다. 기존 서버 실행 인자의 `--demo.catalog.enabled=false`도 계속 유지한다.

## 5. 배포 서비스 결정 후 입력할 구조

배포 서비스가 미정이므로 특정 업체의 콘솔 클릭 위치나 DB 주소를 임의로 정하지 않았다. 지금은 [값 없는 환경변수 예시](deployment-environment.example)를 검토할 수 있다. 이 파일은 자동으로 로드되지 않는다. 배포 서비스가 정해지면 해당 서비스의 서버 실행 환경변수/Secret 설정 화면에 같은 이름을 사용자가 직접 입력해야 한다.

| 이름 | 입력 주체/용도 |
| --- | --- |
| DB_URL | 사용자가 배포 MySQL 주소·스키마·시간대 옵션 입력 |
| DB_USERNAME / DB_PASSWORD | 사용자가 해당 DB 계정 값 입력 |
| KAKAO_REST_API_KEY | 사용자가 카카오 REST 키 입력 |
| GEMINI_API_KEY | 기존 demo AI 추천에 필요한 경우 사용자가 기존 방식으로 입력 |
| SPRING_PROFILES_ACTIVE | 해커톤 시연 설정을 그대로 사용할 때 `demo` |
| DEMO_CATALOG_ENABLED | 기존 DB에는 `false` |
| RESERVATION_APPROVAL_WINDOW | 기존 시연 합의 `PT30M` |
| CORS_ALLOWED_ORIGINS | 확정된 프런트 출처 `http://localhost:5173` |
| SERVER_SERVLET_SESSION_COOKIE_SECURE | HTTPS 배포에서 `true`; HTTP localhost에는 설정하지 않음 |
| SERVER_SERVLET_SESSION_COOKIE_SAME_SITE | 로컬 프런트에서 배포 HTTPS 백엔드를 직접 호출할 때 `none` |

`demo`에는 추천 API/실행/고객 조회와 시연용 질문·랭커 선택이 포함된다. 현재 별도 `prod` 설정은 준비되지 않았으므로 프로필 이름만 `prod`로 바꾸면 같은 기능이 모두 켜진다고 가정하면 안 된다. 실제 운영 전환 시 시연 정책과 기능 활성화 값을 별도로 확정한다.

백엔드는 HTTPS로 접근할 수 있게 배포한다. 브라우저는 `http://localhost:5173`, API 요청은 **배포 백엔드의 HTTPS 기본 주소 + 기존 API 경로**를 사용한다. 예를 들어 기본 주소에 `/auth/csrf`를 붙인다. `/api/v1`을 추가하지 않는다. 프런트 팀원에게 기본 주소 설정을 바꿔 달라고 전달한다. 프런트 빌드 도구를 아직 확인하지 않았으므로 특정 환경변수 이름을 가정하지 않는다.

환경변수 `CORS_ALLOWED_ORIGINS`에 정확한 프런트 출처를 넣으면 해당 출처의 쿠키 포함 요청을 허용한다. 값이 비어 있으면 같은 출처 요청만 허용한다. 여러 출처가 필요한 경우 쉼표로 구분한다. `*`, 경로, 끝의 `/`는 허용하지 않는다. `http://127.0.0.1:5173`은 현재 허용 목록에 없으므로 프런트는 확정된 `localhost` 주소로 연다. preflight OPTIONS는 로그인 없이 처리하며 실제 요청에는 기존 세션·권한·CSRF 검사가 적용된다. 거부된 CORS 요청은 403 + 공통 오류 형식 `CORS_REJECTED`다. 허용되지 않은 출처의 JavaScript에는 브라우저가 오류 본문을 공개하지 않을 수 있다.

프런트 팀원은 모든 백엔드 fetch 요청에 `credentials: "include"`를 넣는다. Axios라면 공통 인스턴스의 `withCredentials: true`다. 인증 순서는 다음과 같다.

1. `GET /auth/csrf`로 CSRF 값을 받아 메모리에 보관한다. 최초 요청부터 credentials 옵션을 적용해야 한다.
2. `POST /auth/login`에 JSON 로그인 입력과 `X-CSRF-TOKEN` 헤더를 보낸다.
3. 로그인 성공 후 `GET /auth/csrf`를 다시 호출해 갱신된 값을 보관한다.
4. `GET /auth/me`가 200인지 확인한다. 이후 POST/PUT/PATCH/로그아웃에 새 CSRF 헤더를 보낸다. 예약의 `Idempotency-Key`도 CORS에서 허용한다.
5. 쿠키는 브라우저가 처리한다. JavaScript가 쿠키 값을 직접 읽거나 `Cookie` 헤더를 만들지 않는다. CSRF 값과 쿠키를 콘솔·공유 화면에 출력하지 않는다.

로컬 프런트와 배포 백엔드는 서로 다른 사이트이므로 직접 호출하는 배포 구성에는 `SameSite=None; Secure`가 필요하다. 이 두 속성은 **HTTPS 배포 백엔드**에 설정한다. 브라우저의 서드파티 쿠키 차단은 CORS와 별개로 세션을 막을 수 있어 실제 프런트에서 로그인 유지 검증이 필요하다. Postman 성공만으로 브라우저 연결 완료로 판단하지 않는다. [쿠키 속성](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie), [CORS와 서드파티 쿠키](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS).

브라우저 정책으로 직접 연결이 막히면 프런트 개발 서버에서 배포 백엔드로 프록시하는 구성을 팀원과 맞춘다. 이 경우에도 프런트 배포는 필요 없지만 프록시의 Origin 처리·쿠키 전달·속성을 함께 검증해야 한다. Vite를 사용한다면 [공식 server.proxy 안내](https://vite.dev/config/server-options.html#server-proxy)를 참고한다. 현재 프런트 소스는 수정하지 않았다. JavaScript 키 도메인 등록은 CORS/세션 설정을 대신하지 않는다. 배포용 `cloudsql` 프로필은 [DB 세션](jdbc-session-deployment.md)을 사용하며 로컬 `demo`는 메모리 세션을 사용한다.

MySQL도 배포 백엔드가 접근할 수 있는 위치를 정해야 한다. 배포 서버의 `localhost`는 사용자 PC가 아니라 **배포 서버 자신**을 뜻한다. 클라우드 DB 또는 백엔드와 같은 서버의 MySQL 등 실제 구조가 정해진 뒤 `DB_URL`을 입력한다. 기존 로컬 DB의 백업을 별도 배포 DB로 복원하는 작업은 사용자가 직접 수행한다. 현재 598개 점포와 상품·태그·일정, Flyway 이력을 함께 보존하고 복원 후 수를 비교한다. 원본 DB에 복원 덤프를 다시 실행하지 않는다.

현재 빌드 산출물은 `build/libs/together-market-0.0.1-SNAPSHOT.jar`다. `together-market-0.0.1-SNAPSHOT-plain.jar`는 실행 JAR과 구분한다. Java 21 실행 환경에서 사용한다. 현재 로컬 빌드는 성공했지만 배포 서버·HTTPS·실제 키·실제 DB 검증까지 끝난 것은 아니다.

## 6. 배포 전 확인 순서

1. 사용자 본인이 변경 내용을 검토하고 저장소 규칙대로 이슈/작업 브랜치를 준비한다. 기존 `dev` 작업 트리 변경분을 유지한 채 브랜치를 만들 수 있다. 커밋·푸시·PR·병합은 사용자가 한다.
2. 테스트 전용 DB에서 MySQL 조건부 테스트/CI 결과를 확인한다. 실제 시연 DB 주소를 `MYSQL_TEST_URL`로 사용하지 않는다.
3. 현재 누락 0건이므로 좌표 보완은 건너뛴다. 배포 DB로 이전한다면 백업·복원 후 점포 598개와 상품·태그·일정 수가 보존되는지 확인한다.
4. 코드·테스트·보안 점검 결과를 검토한 뒤 사용자가 커밋·푸시하고 PR/CI 결과를 확인한다. 배포 전에 Git 반영과 점검을 완료한다.
5. 백엔드 배포 서비스와 MySQL 위치를 정한 뒤 해당 업체 콘솔의 구체적 입력 단계, DB 네트워크 연결, HTTPS를 확정하고 백엔드를 먼저 배포한다. 기존 검증 흐름(구매요청·추천·양 점포 승인·한 점포 거절·대체 예약·멱등성)을 배포 환경에서 확인한다. 승인 기한 만료 추가 시연은 기존 일정상 보류 결정을 유지한다.
6. 백엔드 배포 후 프런트에 nullable 거리/저장 차단/예약 거리 오류 계약을 반영한다. 프런트 주소는 `http://localhost:5173`으로 유지한다. CSRF 조회→로그인→CSRF 재조회→내 정보→지도/위치 조회와 주소 변경 실패 시 원본 유지를 확인한다. Swagger 연동도 백엔드 우선 배포 이후 작업으로 분리한다.

카카오 좌표 영구 저장은 차단 상태를 유지한다. 최종 신규 예약 생성 시점의 500m 재검증은 로컬 코드에 추가되었으며 [예약 API](BE1_RESERVATIONS.md)에 오류·재시도 계약을 정리했다. 코드 구현과 실제 배포 완료는 구분한다.
