# 배포 세션 저장 — 3단계

`cloudsql` 프로필에서만 `app.session.jdbc.enabled=true`로 Spring Session JDBC를 활성화한다. starter의 전역 자동 설정 대신 명시적 설정을 사용하므로 로컬 `demo`는 기존 서버 메모리 세션을 유지한다. 기존 로그인·로그아웃·CSRF API 구조는 동일하다.

## DB 변경과 적용 시점

새 Flyway V5는 `app_sessions`, `app_sessions_ATTRIBUTES` 두 테이블과 인덱스만 추가한다. 기존 점포·상품·예약 테이블의 데이터는 변경하지 않는다. 세션 테이블의 UUID는 내부 구현이며 기존 API의 BIGINT 문자열 ID 규칙과 별개다.

실제 DB에는 아직 적용하지 않았다. 다음 서버 시작 시 Flyway가 실행되는 모든 프로필에서 V5가 적용될 수 있으므로, 사용자 본인이 먼저 DB를 백업한다. 클라우드 DB에는 기존 데이터와 flyway_schema_history를 먼저 복원하고 새 JAR을 시작한다. Spring Session 자체의 별도 스키마 자동 초기화는 사용하지 않는다. 기존 마이그레이션을 수정하거나 DB를 초기화하지 않는다.

## 로그인·쿠키

인증 정보와 CSRF가 세션 속성으로 DB에 저장된다. 같은 DB를 사용하는 다른 서버 인스턴스에서도 읽을 수 있다. 쿠키 이름은 JSESSIONID, 경로 /, HttpOnly다. JDBC 세션 설정의 기본은 Secure=true, SameSite=None이며 기존 SERVER_SERVLET_SESSION_COOKIE_SECURE / SAME_SITE 설정을 반영한다. Cloud Run HTTPS와 localhost 프런트 조합의 브라우저 정책은 실제 연동으로 확인해야 한다.

유휴 만료는 30분이다. 이는 예약 승인 제한 30분과 별개다. 만료 세션 정리는 Spring Session의 기본 주기(매분)로 실행되므로 백그라운드 CPU 설정은 후속 단계에서 적용한다. 마감 이후의 요청은 정리 작업 이전에도 세션 만료로 처리한다.

기존 메모리 세션은 DB로 이전되지 않으므로 전환 배포 후 다시 로그인한다. 첫 GET /auth/csrf → POST /auth/login → GET /auth/csrf 재조회 순서를 유지한다. 로그아웃은 DB 세션도 제거한다. DB 장애가 나면 메모리 세션으로 우회하지 않는다. 세션 내용은 직렬화되어 저장되므로 DB 백업도 민감정보로 취급하고 Git에 올리지 않는다.

## 사용자 직접 확인 순서

1. 기존 DB 백업과 새 Cloud SQL 복원을 완료한다.
2. 배포 프로필 demo,cloudsql과 DB 권한을 확인한다. 실행 계정에는 V5 생성 및 세션 읽기·쓰기·삭제 권한이 필요하다.
3. 나중에 새 버전을 배포한 뒤 Flyway V5 성공 여부를 확인한다. 쿠키·CSRF·세션 내용은 출력하거나 공유하지 않는다.
4. 프런트에서 새로 로그인하고 /auth/me가 200인지 확인한다.
5. 같은 앱 버전 재배포 후 기존 브라우저에서 /auth/me가 계속 200인지 확인한다. 유휴 만료되지 않은 세션으로 검사한다.
6. 로그아웃 후 /auth/me가 401인지 확인한다.

Java 21에서 `spotlessCheck build` 검증을 통과했다. 전체 390개 중 336개 통과, 실제 MySQL 등 별도 환경이 필요한 54개는 건너뛰었으며 실패는 0개다. 새 테스트는 로컬 프로필 비활성화, JDBC 필터 등록, 두 저장소 사이 인증·CSRF 속성 복원, 세션 삭제 및 만료 처리를 확인한다. 세션 삭제 테스트는 저장소 수준 검증이며 실제 브라우저 로그아웃 검증을 대체하지 않는다.

실제 MySQL V5 적용, Cloud SQL 연결 및 브라우저 재배포 후 로그인 유지 검증은 아직 수행하지 않았다. 위 직접 확인 순서로 배포 후 검증한다.

[Spring Session JDBC 공식 설명](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)
