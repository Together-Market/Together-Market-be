# 변경 검토·커밋·푸시·배포 순서

사용자가 커밋·푸시·병합을 직접 하기로 확인했다. 이 문서는 수행 순서이며 자동 실행 스크립트가 아니다. 현재 브랜치는 dev, 기존 변경은 미커밋 상태다. 실제 DB·개인 설정·비밀값을 Git에 추가하지 않는다.

## 1. 먼저 검토

1. IntelliJ에서 C:\Together-Market-be를 연다. 왼쪽 Commit 도구 창 또는 상단 Git → Commit을 연다. 이 단계에서는 Commit 버튼을 누르지 않는다.
2. 변경 파일 목록에서 소스와 문서를 선택해 차이를 확인한다. 이번 범위는 카카오 미리보기·좌표 정책·REC-03·공공데이터 보완·CORS·예약 거리 검증이다. 개인 .env/.idea/키 파일·DB 백업 파일이 보이면 포함하지 않는다.
3. [검토 결과](predeployment-review.md)와 [예약 API 변경](BE1_RESERVATIONS.md)을 확인한다. 테스트 324개 통과·54개 조건부 생략이며 실제 DB/배포 검증은 별도다.
4. 개인 설정이나 비밀값이 있는 화면·파일의 내용을 채팅에 붙여 넣지 않는다.

## 2. 저장소 규칙에 맞는 이슈·작업 브랜치 준비

README는 이슈 → 작업 브랜치 → PR(dev 대상) 순서를 요구한다. 이번 전체 변경을 포괄하는 기존 이슈가 있으면 그것을 사용한다. 없다면 사용자가 [GitHub 저장소](https://github.com/Together-Market/Together-Market-be) → Issues → New issue → 작업 이슈 양식을 연다.

- 작업 유형: feat
- 제목 제안: 카카오 주소 미리보기·점포 좌표 정책·예약 거리 검증 보완
- 작업 목적: 주소 미리보기와 안전한 좌표 처리를 제공하고, 추천 이후 위치 변경으로 거리 조건을 벗어난 신규 예약을 막는다.
- 작업 내용: 주소 조회 API, 등록·수정 좌표 정책, REC-03 nullable 응답, 공공데이터 dry-run 보완, CORS, 최종 예약 거리 검사, 명세·테스트 갱신.
- 완료 기준: 로컬 빌드/포맷 통과, PR CI 확인, 카카오 저장 차단 유지, 누락/500m 초과 시 예약 미저장, 기존 멱등성 유지.

Submit new issue로 생성한 후 화면의 실제 이슈 번호를 확인한다. 번호를 임의로 만들지 않는다.

IntelliJ 하단 Terminal을 누른 뒤 아래 명령을 한 줄씩 실행한다. 오류가 나면 다음 줄을 실행하지 않는다. Reset, Clean, Discard, force push는 사용하지 않는다.

```powershell
Set-Location -LiteralPath 'C:\Together-Market-be'
git status --short
git branch --show-current
$releaseIssue = Read-Host '방금 확인한 실제 GitHub 이슈 번호(숫자만)'
if ($releaseIssue -notmatch '^[1-9][0-9]*$') { throw '실제 이슈 번호를 숫자로 입력해 주세요.' }
git switch -c ('feat/{0}-geocoding-reservation-distance' -f $releaseIssue)
```

dev에서 새 브랜치를 만들면 현재 작업 트리의 변경이 그대로 따라간다. 이 명령은 원격 최신화를 하지 않는다. 이미 같은 이름의 브랜치가 있거나 현재 상태가 예상과 다르면 삭제·강제 전환하지 말고 그 상태를 먼저 확인한다.

## 3. 파일 선택 후 직접 커밋·푸시

1. Commit 창으로 돌아가 이번 작업의 소스·테스트·문서만 체크한다. 다른 작업이 섞여 있으면 파일/변경 단위로 분리한다. 이 가이드는 `git add .`나 강제 추가를 지시하지 않는다.
2. 커밋 메시지 제안: `feat: 카카오 주소 미리보기와 예약 거리 검증 보완`.
3. Commit을 누른다. Commit and Push가 별도 버튼이면 먼저 Commit으로 확정한 뒤 푸시 내용을 검토한다.
4. Terminal에서 `git status --short`로 남은 변경을 확인하고 `git log -1 --oneline`으로 방금 커밋을 확인한다. 남은 변경이 있으면 이유를 확인한 뒤 다음 단계로 진행한다.
5. 상단 Git → Push에서 대상이 방금 만든 작업 브랜치인지 확인하고 Push를 누른다. dev/main 직접 푸시나 Force Push를 선택하지 않는다. GitHub 로그인이 필요하면 본인이 인증 화면에서 진행한다. 인증값을 문서·채팅에 넣지 않는다.

## 4. PR과 CI 확인

1. GitHub 저장소 → Pull requests → New pull request를 누른다. base는 dev, compare는 방금 푸시한 작업 브랜치로 선택한다.
2. 제목은 `feat: 카카오 주소 미리보기와 예약 거리 검증 보완 (#실제이슈번호)` 형식으로 실제 번호를 넣는다.
3. 저장소 템플릿의 변경 내용·확인 방법·관련 이슈를 채운다. 아래 내용은 붙여 넣기용이며 마지막 이슈 번호는 실제 번호로 바꿔야 한다.

```markdown
## 변경 내용
- OWNER 주소→좌표 미리보기와 환경변수 주입, 외부 오류·시간 초과 처리
- 카카오 결과 저장 차단, 점포 등록·수정의 완전한 좌표 쌍 검증
- 공공데이터 기반 좌표 보완의 dry-run·재실행·정상 데이터 보존
- REC-03의 nullable 거리·location_status 및 localhost 프런트 CORS
- 신규 예약 저장 전 최신 좌표로 직선거리 500m 검증, 기존 멱등 재응답 유지

## 확인 방법
- Java 21: spotlessApply spotlessCheck build --offline --no-daemon 성공
- 테스트 324개 통과, 54개 조건부 생략, 실패 0개
- git diff --check 통과
- 외부 HTTP는 모의 호출. 실제 DB·배포 브라우저 검증은 미실시
- .env·개인 IDE 설정은 열지 않았으며 Git/JAR 파일명 점검에서 개인 파일 경로 없음
- 카카오 좌표 저장은 미제공. 상세 검토: docs/predeployment-review.md

## 관련 이슈
Closes #실제이슈번호
```

4. Create pull request를 누른다. feature 브랜치에 push만 해서는 현재 CI의 push 조건(dev/main)에 걸리지 않으므로 PR을 열어 검사 결과를 확인한다.
5. PR의 Checks 탭에서 build-test-format, reservation-mysql, csv-demo-mysql, pr-validation을 확인한다. 실패하면 병합하지 않고 해당 job의 실패 단계부터 확인한다. 실제 비밀값이 있을 수 있는 전체 로그를 채팅에 복사하지 않는다.
6. 저장소 합의에 따라 사용자가 Squash merge한다. README상 배포 기준은 main이며 dev→main 반영 방식은 팀 합의를 확인한다. 이 가이드는 main 병합을 자동 승인하거나 실행하지 않는다.

## 5. 그다음 백엔드 우선 배포

백엔드와 MySQL 배포 위치가 아직 미정이다. 서비스를 정하면 해당 콘솔의 환경변수·DB·HTTPS 입력 화면 순서를 구체화한다. 기존 598개 점포와 상품·태그·일정의 백업·이전은 사용자 직접 작업으로 진행한다. [배포 설정 안내](kakao-setup-deployment.md)의 비밀값 없는 환경변수 구조를 사용한다.

프런트는 배포하지 않고 http://localhost:5173을 유지한다. 프런트 연결·화면 변경과 Swagger 작업은 백엔드 배포 후 진행한다.
