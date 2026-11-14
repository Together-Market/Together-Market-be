# Together Market BE

Java 21 · Spring Boot 4.1.2 · Gradle(Groovy) · MySQL 8.4 · JPA · Flyway

## 로컬 실행

MySQL에 together_market DB를 생성하고 DB_URL, DB_USERNAME, DB_PASSWORD를 환경변수로 설정합니다.

```sh
./gradlew bootRun
```

초기 DB 구조와 12개 Entity·Repository를 포함합니다. Flyway가 V1 테이블 생성, V2 구매 목적 분류 등록, V3 비동기 추천 선점 필드 추가를 실행합니다. 기존 테이블이 있는 DB에는 바로 적용하지 말고 이력을 먼저 확인하세요. 로그인은 이메일·비밀번호 및 서버 세션·쿠키 방식이며 구현은 BE1 담당입니다.

```sh
./gradlew spotlessApply
./gradlew spotlessCheck build
```

기본 context 테스트는 DB 없이 부팅을 확인합니다. 공통 검증 단위 테스트와 MYSQL_TEST_URL 설정 시 실행되는 MySQL 스키마 통합 테스트를 추가했습니다. 실행 방법과 담당별 남은 작업은 docs/backend-foundation.md를 참고하세요. 재고는 전 상품 점주 확인형이며 수량 차감·임시 확보는 구현하지 않습니다.

## BE3 추천 내부 로직

구매조건·소유권 검증, 서로 다른 두 점포의 후보 생성·재검증, 추천 평가·순위화와 비동기 상태 처리 로직을 포함합니다. [구매조건](docs/be3-purchase-preparation.md), [후보 생성·조회](docs/be3-candidate-preparation.md), [비동기 처리](docs/be3-async-recommendation.md) 문서에 구현 범위와 연결 계약을 정리했습니다.

공통 Repository·상품 검증 서비스와 연결하는 DB 어댑터 및 V3 추천 선점 필드를 포함합니다. 기본 비활성화 상태이며 읽기와 추천 실행을 별도 설정으로 켭니다. [DB 연결과 실행 설정](docs/be3-database-integration.md)을 참고하세요. 공개 HTTP API·세션 인증·공통 응답 연결은 후속 작업입니다. 실제 AI 호출에는 환경변수 설정이 필요합니다. API 키나 학습 완료 가중치는 포함하지 않습니다. 수동 기본 점수와 학습 가중치 모드를 구분하고, 실험용·버전 불일치 모델은 로더가 거부합니다.

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
- dev → main 병합 방식, 배포 환경은 미정. 세션·CSRF 세부 구현은 BE1 담당

## 코드 구조

도메인 auth, user, store, product, recommendation, reservation별로 controller/service/repository/entity/dto(request,response)를 구성합니다. 공통 예외·설정은 global에 둡니다. 실제 코드가 생길 때 패키지를 생성합니다.

## API 합의

Notion에서 설계하고 springdoc Swagger UI는 추후 연동합니다.
URL은 /api/v1 접두사 없이 기존 명세의 경로를 사용합니다. JSON 및 쿼리 파라미터는 snake_case, Java 필드는 camelCase를 사용합니다. 성공 {data}, 실패 {code,message,errors} 형식을 따릅니다. DB ID는 BIGINT, Java는 Long, API ID는 문자열입니다. 공통 응답·변환 설정은 BE1 구현에 맞춥니다.
날짜·시간은 오프셋 포함 ISO 8601, 금액은 원 단위 정수입니다. 외부 API page는 1부터, size 기본값은 20이며 목록은 items/page/size/total_count를 사용합니다. JPA 페이지 번호는 내부에서 page - 1로 변환합니다. DB DATETIME은 팀 합의에 따라 KST로 저장하며 BE3 변환 계층에서 Asia/Seoul과 API +09:00 오프셋을 적용합니다.
예약 생성에는 Idempotency-Key를 사용합니다. 상세 정책은 별도 API 명세에서 확정합니다.

구매요청은 202 Accepted, 상태는 RECOMMENDING/READY/FAILED입니다. 후보 없음은 READY와 빈 목록, 시스템 실패는 FAILED와 failure_code로 구분합니다. 구매요청에는 별도 Idempotency-Key를 적용하지 않습니다.

상품 이미지는 image_url로 등록·수정하며 업로드 API는 이번 범위에 없습니다. 상품 삭제·점포 탈퇴는 제외하고 판매 중지는 PAUSED로 처리합니다.
