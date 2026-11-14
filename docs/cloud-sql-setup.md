# Cloud SQL 연결 준비 — 2단계

2026-11-15 기준. 이 단계는 연결 코드·설정 준비이며 실제 Cloud SQL 생성·접속·원본 DB 변경을 수행하지 않는다. 무료 체험 계정 준비와 DB 생성은 별개다. 세션 DB 저장 및 백그라운드 작업 설정은 다음 단계다.

## 현재 코드의 사용 방법

기존 IntelliJ 실행은 `demo` 프로필과 기존 DB_URL을 유지한다. Cloud SQL 배포에만 `SPRING_PROFILES_ACTIVE=demo,cloudsql`을 사용한다. `cloudsql`만 활성화하면 demo의 추천·AI 기능 설정은 켜지지 않는다. 클라우드용 설정을 로컬 서버 실행 설정에 복사하지 않는다.

공식 Java Connector 1.31.0을 추가했다. 배포에서는 JDBC URL을 DB_NAME으로 구성하고 Hikari 속성으로 인스턴스 연결 이름·SocketFactory를 전달한다. DB_PASSWORD는 별도 비밀 환경변수이며 URL에 포함하지 않는다. 기본 경로는 PUBLIC, 인증 정보 갱신은 lazy, 연결 풀은 인스턴스당 최대 5개·최소 유휴 0개·대기 제한 10초다. JDBC 연결 생성에도 connectTimeout 10초를 지정했지만 전체 Google API 인증·연결 과정의 총 소요시간을 10초로 보장하지 않는다.

Cloud Run 실행 서비스 계정의 ADC로 Google 연결 권한을 인증하며, MySQL 로그인은 DB_USERNAME/DB_PASSWORD를 사용한다. 자동 IAM DB 로그인 방식은 아니다. 서비스 계정 JSON 키 파일을 발급하거나 이미지에 넣을 필요가 없다. Google Cloud IAM 역할과 MySQL DB 권한은 별개다.

MySQL 8.4의 기본 `caching_sha2_password` 인증을 위해 Cloud SQL 프로필의 Connector/J 속성에 `allowPublicKeyRetrieval=true`를 설정한다. 이는 Cloud SQL Java Connector 경로에서 서버의 인증용 공개키를 받아오는 설정이며 비밀번호 값을 설정 파일에 넣지 않는다. [Cloud SQL 공식 MySQL 8.4 연결 안내](https://docs.cloud.google.com/sql/docs/mysql/connect-auth-proxy).

## 배포할 때 입력할 이름

| 이름 | 입력 내용 |
| --- | --- |
| SPRING_PROFILES_ACTIVE | `demo,cloudsql` |
| CLOUD_SQL_INSTANCE_CONNECTION_NAME | SQL 인스턴스 개요의 연결 이름. `프로젝트ID:리전:인스턴스ID` 형태 |
| DB_NAME | 복원한 데이터베이스 이름. 기존 스키마와 맞춰 `together_market` 사용 가능 |
| DB_USERNAME | 해당 데이터베이스 전용 MySQL 계정 이름. root를 기본 사용하지 않음 |
| DB_PASSWORD | 본인이 Secret Manager에 저장한 DB 비밀번호의 버전 참조 |
| CLOUD_SQL_IP_TYPES | 초기 구성 `PUBLIC`; PRIVATE는 별도 VPC 연결 필요 |
| DEMO_CATALOG_ENABLED | `false` 유지 |

예시는 [값 없는 환경변수 구조](cloud-sql-environment.example)에 있다. 이 파일은 자동으로 로드되지 않는다. `cloudsql` 프로필에서는 기존 DB_URL 대신 DB_NAME으로 URL을 만든다. DB_NAME·DB_USERNAME·DB_PASSWORD·연결 이름은 실제 배포 전에 반드시 설정한다. 커스텀 SPRING_DATASOURCE_URL 등 Spring 직접 재정의 변수를 함께 넣지 않는다.

## 사용자 직접 설정 순서 — 리소스 준비 시 실행

### 1. Cloud SQL Admin API 활성화

1. Google Cloud 콘솔 상단에서 같이팔장 프로젝트를 선택한다.
2. 상단 검색창에 `Cloud SQL Admin API`를 입력하고 해당 API 화면을 연다.
3. `사용 / Enable`을 누른다. 이미 사용 중이면 그대로 둔다.

### 2. 인스턴스 연결 정보 확인

1. SQL → 생성한 MySQL 인스턴스 → 개요(Overview)를 연다. 아직 인스턴스를 만들지 않았다면 별도 생성·요금 확인 단계부터 진행한다.
2. 연결 이름(Connection name)을 확인한다. DB의 IP 주소나 전체 https URL을 입력하는 칸이 아니다.
3. 연결(Connections) → 네트워킹(Networking)에서 이 구성에 필요한 Public IP 사용 여부를 확인한다.
4. Java Connector의 인증·암호화된 PUBLIC 경로를 사용하므로 누구나 접속 가능한 승인 네트워크 `0.0.0.0/0`을 추가하지 않는다. PRIVATE를 선택하려면 Cloud Run과 DB 사이 VPC 경로를 먼저 구성해야 한다.

이 방식은 앱 안의 Java Connector가 직접 연결한다. 별도 Cloud SQL Auth Proxy 사이드카나 `/cloudsql/...` Unix socket 마운트는 요구하지 않는다. Cloud Run의 Cloud SQL 연결 항목을 추가하는 것만으로 앱의 인스턴스 이름·권한·DB 로그인 설정이 완성되지는 않는다. 아웃바운드 네트워크를 제한한다면 Google API HTTPS 및 Connector TCP 3307 접근을 확인한다.

### 3. 실행 서비스 계정 준비

1. IAM 및 관리자 → 서비스 계정 → 서비스 계정 만들기를 누른다.
2. 표시 이름 예시 `together-market-runtime`을 입력하고 생성한다.
3. 프로젝트 권한 선택에서 `Cloud SQL Client`를 부여한다. Owner·Editor 권한은 필요하지 않다.
4. 키 탭에서 JSON 키를 만들지 않는다. Cloud Run에 이 계정을 연결해서 사용한다.
5. DB와 앱이 서로 다른 프로젝트라면 DB가 속한 프로젝트에도 이 실행 계정의 Cloud SQL Client 권한과 관련 API 활성화가 필요하다. 첫 배포는 같은 프로젝트 구성을 기준으로 한다.

### 4. 새 DB에 데이터 복원 및 전용 사용자 준비

1. 원본 로컬 DB를 백업한다. 비밀정보·개인정보가 포함될 수 있는 덤프를 Git에 올리지 않는다.
2. Cloud SQL의 새 데이터베이스에만 복원한다. 최초 앱 부팅 전에 점포 598개, 상품·태그·일정과 `flyway_schema_history` 보존을 확인한다.
3. 복원 완료 후 백엔드 전용 MySQL 계정을 준비한다. MySQL 8.4에서는 Cloud SQL 콘솔의 사용자 생성 기능이 기본 `cloudsqlsuperuser` 역할을 부여할 수 있으므로, 콘솔에서 새 사용자만 만들고 최소 권한이 되었다고 가정하지 않는다. 계정과 비밀번호는 사용자가 직접 관리한다.
4. MySQL 계정 권한은 SQL에서 서비스 스키마 범위로 명시적으로 제한·확인한다. 현재 앱 시작 시 Flyway 마이그레이션을 실행하므로 초기 실행 계정에는 해당 스키마의 필요한 DDL 및 Flyway 이력 읽기·쓰기 권한도 있어야 한다. 이후 마이그레이션 전용 계정을 분리하려면 별도 구성이 필요하다. [Cloud SQL MySQL 사용자 권한 안내](https://docs.cloud.google.com/sql/docs/mysql/users).

자동 초기화·중복 시딩으로 복원 문제를 해결하지 않는다. `ddl-auto=validate`, `DEMO_CATALOG_ENABLED=false`를 유지한다. Flyway 오류가 나면 원본 이력과 복원 결과부터 확인하고 임의로 baseline/clean을 실행하지 않는다.

### 5. Secret Manager와 Cloud Run 연결

1. Secret Manager → 보안 비밀 만들기에서 이름 예시 `together-market-db-password`를 입력한다. 비밀값은 본인이 직접 입력한다.
2. 해당 비밀의 권한(Permissions)에서 실행 서비스 계정에 `Secret Manager Secret Accessor` 역할을 부여한다. 프로젝트 전체 비밀 대신 해당 비밀에 대한 권한을 우선 사용한다.
3. Cloud Run → 서비스 → 새 버전 수정 및 배포(Edit & deploy new revision)를 연다. 최초 배포라면 서비스 생성 화면의 컨테이너·네트워킹·보안 설정을 연다.
4. 보안(Security)에서 위 실행 서비스 계정을 선택한다.
5. 변수 및 보안 비밀(Variables & Secrets)에서 표의 일반 환경변수를 입력한다.
6. `보안 비밀 참조 / Reference a secret`에서 환경변수 이름 `DB_PASSWORD`, 위 비밀, 사용할 고정 버전 번호를 선택한다. 비밀번호를 일반 변수·시작 인자·JDBC URL에 붙여 넣지 않는다.
7. KAKAO_REST_API_KEY 및 GEMINI_API_KEY도 각 Secret Manager 비밀을 참조한다. 실제 값을 채팅에 보내지 않는다.
8. 세션·백그라운드 처리 보완 및 배포 준비가 끝난 후 새 버전을 배포한다.

## 검증 범위와 오류 구분

설정 테스트에서는 실제 프로필을 읽고 HikariDataSource에 바인딩하되 연결 풀을 시작하지 않는다. 로컬 연결 유지, demo와 cloudsql 조합, PUBLIC/PRIVATE 선택, 연결 풀 제한 및 드라이버 로딩을 검사한다. 실제 Google 인증·DB 접속·Flyway 복원 성공은 실제 배포 환경에서 따로 확인한다.

로컬 Java 21 검증 결과: `spotlessCheck build` 성공, 전체 테스트 333개 통과·54개 조건부 생략·실패 0개. 신규 설정 테스트 4개를 포함한다. 실행 JAR에 cloudsql 프로필과 Connector 1.31.0, MySQL 드라이버가 포함됨을 확인했다. 실제 키·Google ADC·운영 DB는 사용하지 않았다.

- Google API 403: API 활성화, 실행 서비스 계정과 Cloud SQL Client 권한 확인.
- MySQL Access denied: 전용 DB 계정·비밀번호·해당 스키마 권한 확인.
- 연결 시간 초과: PUBLIC/PRIVATE 선택과 실제 네트워크 경로 확인.
- DB_NAME 등 누락: Cloud Run 환경변수 및 Secret 버전 연결 확인.
- Flyway 검증 오류: 백업·복원·이력 확인. 기존 데이터 초기화 금지.

로그를 DEBUG/TRACE로 올리거나 환경변수 전체를 출력하지 않는다. 공유할 때는 비밀값 없는 오류 코드와 원인 요약만 전달한다.

## 공식 근거

- [Java Connector JDBC 설정](https://github.com/GoogleCloudPlatform/cloud-sql-jdbc-socket-factory/blob/main/docs/jdbc.md)
- [Cloud Run에서 Cloud SQL 연결](https://docs.cloud.google.com/sql/docs/mysql/connect-run)
- [Cloud Run Secret 참조](https://docs.cloud.google.com/run/docs/configuring/services/secrets)
