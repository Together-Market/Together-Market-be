# Google Cloud 배포 준비 1단계: 실행 이미지

2026-11-15 기준. 이번 단계는 로컬에서 실행 JAR과 Java 21 컨테이너 이미지를 준비하는 작업이다. Cloud SQL 연결, DB 세션, Cloud Run CPU·최소 인스턴스 설정, 실제 배포는 다음 단계다. Google Cloud 무료 체험 계정 준비만으로 애플리케이션 배포가 완료되는 것은 아니다.

## 파일과 실행 방식

- `Dockerfile`: Eclipse Temurin Java 21 JRE로 실행하며 사용자 UID/GID 10001:10001을 사용한다. 빌드된 실행 JAR 하나만 `/app/app.jar`로 복사한다. JVM을 직접 실행하므로 종료 신호가 JVM에 전달된다.
- `.dockerignore`: 모든 파일을 기본 제외하고 Dockerfile·제외 규칙·실행 JAR만 빌드 대상으로 허용한다.
- `.gcloudignore`: 나중에 Google Cloud CLI로 업로드할 때도 같은 범위만 허용한다. `.env`, IDE 설정, Git 이력, DB 덤프, 로그, 테스트 결과, 소스 폴더 자체는 업로드하지 않는다.
- `application.properties`: `0.0.0.0`에서 요청을 받고, `PORT`가 있으면 해당 포트, 없으면 기존 8080을 사용한다. Cloud Run이 PORT를 제공한다. `EXPOSE 8080`은 설명용 기본 포트이며 실제 PORT를 고정하지 않는다.
- 프로필·키·DB 비밀번호는 이미지에 넣지 않는다. 컨테이너 실행 환경에서 주입한다. 기본 프로필을 demo/prod로 자동 변경하지 않는다.

JAR에는 애플리케이션 리소스 및 기존 빌드가 포함하는 시연 카탈로그가 들어간다. 자동 카탈로그 입력은 기본 OFF이며 배포에서도 `DEMO_CATALOG_ENABLED=false`를 유지한다. 업로드 제외 규칙은 이미 JAR 안에 들어간 파일을 제거하지 않으므로, 비밀값을 src/main/resources 등에 넣으면 안 된다. 현재 설정은 환경변수 참조를 사용한다.

## 1. IntelliJ 터미널 열기

IntelliJ에서 `C:\Together-Market-be` 프로젝트를 연다. 아래쪽 Terminal을 클릭한다. 보이지 않으면 View → Tool Windows → Terminal을 누른다. 아래 명령은 Windows PowerShell 기준이다.

```powershell
cd C:\Together-Market-be
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
```

현재 PC에서 확인한 JDK 21 경로다. 다른 PC에서는 설치 경로를 확인해서 바꾼다.

## 2. 기존 설정과 분리해서 빌드·테스트

아래 명령은 이 터미널 프로세스에서만 환경변수를 제거한다. IntelliJ에 저장한 개인 실행 설정이나 시스템 환경변수를 수정하지 않으며 비밀값을 출력하지 않는다. 실제 시연 DB를 테스트용 DB로 사용하지 않는다.

```powershell
foreach ($testVariable in @('DB_URL','DB_USERNAME','DB_PASSWORD','MYSQL_TEST_URL','MYSQL_TEST_USERNAME','MYSQL_TEST_PASSWORD','CSV_DEMO_TEST_URL','KAKAO_REST_API_KEY','GEMINI_API_KEY','OPENAI_API_KEY','SPRING_PROFILES_ACTIVE')) {
    Remove-Item -LiteralPath "Env:$testVariable" -ErrorAction SilentlyContinue
}
.\gradlew.bat clean spotlessCheck build --no-daemon
```

`BUILD SUCCESSFUL`을 확인한 뒤 다음 단계로 간다. 의존성 캐시가 이미 있는 이 PC에서는 `--offline`을 추가할 수 있지만, 처음 빌드하는 PC에서는 다운로드가 필요하다. 실제 MySQL 조건부 테스트는 별도 테스트 DB를 사용하는 CI에서 확인한다.

생성 파일은 `build/libs/together-market-0.0.1-SNAPSHOT.jar`이다. `-plain.jar`는 컨테이너에서 실행할 파일이 아니다. 코드 수정 후에는 반드시 다시 빌드한다. 버전이나 JAR 이름을 변경하면 Dockerfile과 두 제외 파일의 JAR 경로도 함께 변경해야 한다.

## 3. Docker 이미지 만들기

Windows 시작 메뉴에서 Docker Desktop을 찾아 연다. Linux 컨테이너 엔진이 실행 중인지 확인한다. 터미널에서 다음을 실행한다.

```powershell
docker version
docker build --pull --platform linux/amd64 -t together-market-be:container-check .
```

끝의 `.`은 현재 프로젝트 폴더를 뜻한다. Dockerfile이 소스를 컴파일하지 않으므로 2단계에서 만든 최신 JAR이 반드시 있어야 한다. 이미지 이름 `together-market-be:container-check`는 로컬 이름이며 아직 Google Cloud에 업로드하지 않는다.

`COPY ... not found`가 나오면 먼저 Gradle 빌드 성공 및 실행 JAR 경로를 확인한다. Docker 엔진 연결 오류가 나면 Docker Desktop의 실행 상태와 Linux 컨테이너 사용 여부를 확인한다.

## 4. 비밀값 없는 이미지 확인

```powershell
docker image inspect together-market-be:container-check --format '{{.Os}}/{{.Architecture}} user={{.Config.User}}'
docker run --rm --network none --entrypoint java together-market-be:container-check -version
```

첫 명령은 `linux/amd64 user=10001:10001`, 두 번째는 Java 21을 기대한다. 이 명령은 애플리케이션 서버를 부팅하지 않으며 DB나 외부 API에 연결하지 않는다. 컨테이너를 정상 시작할 수 있다는 확인과 실제 백엔드 부팅·DB 연결 성공은 구분한다.

## 5. Git에 포함할 파일

현재 사용자가 수정한 `.gitignore`에는 `.dockerignore`를 제외하는 규칙이 있다. 그 변경은 보존했다. `.dockerignore`는 비밀정보가 아니라 안전한 빌드 범위를 정하는 공유 설정이므로 이번 변경을 커밋할 때에는 다음처럼 명시적으로 추가한다. 기존 사용자 변경인 `.gitignore`는 이 명령에 포함하지 않는다.

```powershell
git add Dockerfile .gcloudignore src/main/resources/application.properties README.md docs/cloud-run-container.md
git add -f .dockerignore
git diff --cached --name-only
```

여섯 파일이 대상이다. JAR·`.env`·개인 설정은 커밋하지 않는다. 커밋·푸시·이슈·PR·병합은 사용자가 직접 진행하며, 이전 PR이 이미 병합됐다면 후속 작업용 이슈와 브랜치를 준비한다.

## 6. 이후 Cloud Build 업로드 준비

Google Cloud CLI 설치·로그인과 프로젝트·Artifact Registry 준비 후, 실제 업로드 전에 프로젝트 루트에서 다음 명령으로 파일 목록만 확인한다.

```powershell
gcloud meta list-files-for-upload
```

예상 파일은 Dockerfile, `.dockerignore`, `.gcloudignore`, 실행 JAR뿐이다. `.gcloudignore`는 Git의 build/ 제외 규칙을 가져오지 않는다. JAR은 Git에는 추가하지 않지만 이미지 빌드에는 필요하기 때문이다.

이 방식은 로컬에서 검증한 JAR을 포장하는 방식이다. GitHub 저장소만 연결하는 자동 배포에는 JAR이 없으므로, 추후 CI에서 Gradle 빌드 → 이미지 생성 순서를 별도로 구성해야 한다. 지금 단계에서 GitHub 연결만으로 배포가 된다고 가정하지 않는다.

## 실제 배포 전 남은 작업

이번 로컬 검증: Java 21에서 `clean spotlessCheck build --offline --no-daemon` 성공, 자동 테스트 329개 통과·54개 조건부 생략·실패 0개. 실제 Docker 이미지 빌드 성공, `linux/amd64`·사용자 `10001:10001`·Java 21.0.13 실행 및 이미지 안 JAR과 빌드 JAR의 SHA-256 일치를 확인했다. 네트워크를 차단한 일회성 컨테이너만 실행했다. 실제 애플리케이션 부팅·DB 연결·Cloud Run 배포는 아직 검증하지 않았다. 현재 Google Cloud CLI가 설치되어 있지 않아 `gcloud meta list-files-for-upload` 실행은 후속 단계에서 확인한다.

1. Cloud SQL 연결 설정 및 실행 서비스 계정 권한.
2. DB 기반 세션 저장과 세션 테이블 마이그레이션.
3. 추천 작업·예약 만료 처리를 위한 CPU 할당 및 최소 인스턴스 설정.
4. Secret Manager 값은 사용자 직접 입력, 기존 DB 백업·복원 및 데이터 수 검증.
5. CORS·HTTPS 쿠키·CSRF와 프런트 브라우저 연동 확인.

기존 로컬 실행 인자와 데이터는 유지한다. 현재 이미지에 실제 키를 넣거나 원본 DB에 연결해서 시험하지 않는다.

## 공식 근거

- [Cloud Run 실행 포트·컨테이너 계약](https://docs.cloud.google.com/run/docs/container-contract)
- [Docker 빌드 컨텍스트와 .dockerignore](https://docs.docker.com/build/concepts/context/)
- [Google Cloud 업로드 제외 규칙](https://docs.cloud.google.com/sdk/gcloud/reference/topic/gcloudignore)
- [Eclipse Temurin 공식 이미지](https://hub.docker.com/_/eclipse-temurin)
