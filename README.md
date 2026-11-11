# Together Market BE

Java 21 · Spring Boot 4.1.2 · Gradle(Groovy) · MySQL 8.4 · JPA · Flyway

## 로컬 실행

MySQL에 together_market DB를 생성하고 DB_URL, DB_USERNAME, DB_PASSWORD를 환경변수로 설정합니다.

```sh
./gradlew bootRun
```

ERD는 아직 초안이므로 초기 마이그레이션과 도메인 엔티티는 포함하지 않았습니다. 인증 방식도 협의 대기 상태입니다.

```sh
./gradlew spotlessApply
./gradlew spotlessCheck build
```

현재 context 테스트는 DB 자동 설정을 제외한 기본 애플리케이션 부팅만 확인합니다. MySQL 연동·재고 동시성 검증은 향후 통합 테스트가 필요합니다.

## 협업

- dev: 기본 브랜치 및 작업 PR 대상, main: 배포 기준
- 이슈 먼저 생성 → dev에서 타입/이슈번호-영문-작업명 브랜치 생성
- 타입: feat, fix, refactor, chore, docs, test
- 예: feat/12-create-reservation
- 커밋: feat: 통합 예약 생성 구현
- PR 제목: feat: 통합 예약 생성 (#12)
- PR 본문: 변경 내용, 확인 방법, 관련 이슈(Closes #12) 필수
- 작업 PR: Squash merge 후 브랜치 삭제
- PR 필수, 필수 승인 0명, 강제 푸시·브랜치 삭제 차단 예정
- CI 필수 검사: build-test-format, pr-validation
- 이슈는 필수 입력 폼 사용. 본문 의미의 정확성은 리뷰로 확인합니다.
- PR 자동 검사는 필수 섹션·내용·이슈 연결·제목 형식을 검사합니다.
- dev → main 병합 방식, 배포 환경, 로그인 인증 방식은 미정

## 코드 구조

도메인 auth, user, store, product, recommendation, reservation별로 controller/service/repository/entity/dto(request,response)를 구성합니다. 공통 예외·설정은 global에 둡니다. 실제 코드가 생길 때 패키지를 생성합니다.

## API 합의

Notion에서 설계하고 springdoc Swagger UI는 추후 연동합니다.
/api/v1, 복수 명사·kebab-case URL, camelCase JSON, 성공 {data}, 실패 {code,message,errors}, 적절한 HTTP 상태코드를 사용합니다.
날짜·시간은 시간대 포함 ISO 8601, 금액은 원 단위 정수, 페이지는 0부터 size 기본 20입니다.
예약 생성에는 Idempotency-Key를 사용합니다. 상세 정책은 별도 API 명세에서 확정합니다.
