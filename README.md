# Together Market BE

Java 21 · Spring Boot 4.1.2 · Gradle(Groovy) · MySQL 8.4 · JPA · Flyway

## 로컬 실행

4단계 [Cloud Run 백그라운드 실행 설정](docs/cloud-run-background.md): 예약 만료·추천 작업을 위해 인스턴스 기반 과금과 서비스 최소 1개를 사용합니다. 콘솔 입력 순서와 배포 후 검증 항목을 정리했으며 실제 클라우드 적용은 아직입니다.

주소 미리보기와 기존 좌표 보완: [API 명세·프런트 전달](docs/kakao-geocoding-api.md), [키 설정·배포 준비](docs/kakao-setup-deployment.md), [데이터 보완 실행](docs/coordinate-backfill.md). 카카오 결과 영구 저장은 공식 이용 조건에 따라 차단하며 공공데이터 좌표를 우선 사용합니다.

백엔드 우선 배포 준비: [점검·보안 검토 결과](docs/predeployment-review.md), [사용자 커밋·푸시·PR 순서](docs/git-release-guide.md). 최종 신규 예약도 현재 좌표로 직선거리 500m 조건을 검사합니다.

Google Cloud 배포 준비 1단계: [Java 21 컨테이너 빌드 안내](docs/cloud-run-container.md). 먼저 Gradle로 검증한 실행 JAR을 만들고 Docker 이미지에 포장합니다. Cloud SQL 연결·DB 세션·백그라운드 작업 설정은 후속 단계입니다.

2단계 [Cloud SQL 연결 준비](docs/cloud-sql-setup.md): `cloudsql` 프로필을 명시할 때만 Google Java Connector를 사용합니다. 해커톤 배포는 `demo,cloudsql`, 기존 로컬 실행은 `demo`를 유지합니다. 실제 Cloud SQL 접속·데이터 복원·세션 저장·배포 검증은 별도입니다.

팀 공유 CSV 점포와 시연 상품 데이터를 설치하려면 [CSV 시연 DB 준비](docs/csv-demo-data.md)를 참고하세요. CSV 원문·상품/가격 스냅샷·입력 스크립트를 포함하며, 새 MySQL DB에서도 동일한 카탈로그를 구성할 수 있습니다.

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

배포 세션 저장: [JDBC 세션 적용·확인 안내](docs/jdbc-session-deployment.md). `cloudsql`에서 활성화하며 V5가 세션용 테이블을 추가합니다. 다음 서버 시작 전 백업이 필요합니다. 로컬 메모리 세션은 유지됩니다.

`GET /purpose-categories`는 로그인한 CUSTOMER와 OWNER 모두 조회할 수 있습니다. 응답은 `data.items`의 대분류와 `children`의 중분류이며 ID는 문자열입니다. 상품 등록·수정의 `purpose_category_ids`에는 중분류 ID 문자열 배열을 전달합니다. 맞춤 질문 조회와 구매요청 API는 기존처럼 CUSTOMER 전용입니다.

Notion에서 설계하고 springdoc Swagger UI는 추후 연동합니다.
URL은 /api/v1 접두사 없이 기존 명세의 경로를 사용합니다. JSON 및 쿼리 파라미터는 snake_case, Java 필드는 camelCase를 사용합니다. 성공 {data}, 실패 {code,message,errors} 형식을 따릅니다. DB ID는 BIGINT, Java는 Long, API ID는 문자열입니다. 공통 응답·변환 설정은 BE1 구현에 맞춥니다.
날짜·시간은 오프셋 포함 ISO 8601, 금액은 원 단위 정수입니다. 외부 API page는 1부터, size 기본값은 20이며 목록은 items/page/size/total_count를 사용합니다. JPA 페이지 번호는 내부에서 page - 1로 변환합니다. DB DATETIME은 팀 합의에 따라 KST로 저장하며 BE3 변환 계층에서 Asia/Seoul과 API +09:00 오프셋을 적용합니다.
예약 생성에는 Idempotency-Key를 사용합니다. 상세 정책은 별도 API 명세에서 확정합니다.

구매요청은 202 Accepted, 상태는 RECOMMENDING/READY/FAILED입니다. 후보 없음은 READY와 빈 목록, 시스템 실패는 FAILED와 failure_code로 구분합니다. 구매요청에는 별도 Idempotency-Key를 적용하지 않습니다.

상품 이미지는 image_url로 등록·수정하며 업로드 API는 이번 범위에 없습니다. 상품 삭제·점포 탈퇴는 제외하고 판매 중지는 PAUSED로 처리합니다.

CSV 시연 데이터 자동 입력은 기본 OFF입니다. 새 시연 DB에 입력할 때만 `local` 또는 `demo` 프로필과 `--demo.catalog.enabled=true`를 명시합니다. 기존 시연 DB의 서버 실행에는 `--demo.catalog.enabled=false`를 유지하세요. 자세한 설정은 [CSV 시연 데이터 안내](docs/csv-demo-data.md)를 참고하세요.
