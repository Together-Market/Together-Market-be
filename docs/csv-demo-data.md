# CSV 점포·시연 상품을 팀원 DB에 넣기

GitHub에는 CSV와 시연 데이터, 입력 스크립트를 보관한다. 실제 MySQL DB는 각 개발자 PC 또는 팀이 준비한 DB 서버에서 실행한다. GitHub에서 저장소를 내려받는 것만으로 DB가 자동 생성되지는 않는다.

## 포함된 데이터

| 항목 | 개수 |
| --- | ---: |
| 보정동 상가정보 CSV | 135행 |
| 보정동 와이페이 CSV | 132행 |
| 동백 상가정보 CSV | 2,077행 |
| 동백 와이페이 CSV | 921행 |
| 원문 합계 | 3,265행 |
| 반경·좌표 검증을 통과한 점포 | 2,224곳 |
| 시연 상품이 있는 점포 | 633곳 |
| 시연 상품 | 1,980개 |
| 판매 가능 / 품절 / 판매 중지 | 1,907 / 44 / 29개 |

`data/csv-demo/raw/`의 CSV 4개는 팀에서 공유한 파일 그대로다. 공공데이터 출처는 [용인시 와이페이 가맹점 API](https://www.data.go.kr/data/15097365/openapi.do)와 [소상공인시장진흥공단 상가정보 API](https://www.data.go.kr/data/15012005/openapi.do)다. 원문 출처 ID·행 번호·내보내기 당시 DB 번호는 보존하지만, 외부 DB 번호를 새 DB의 PK로 사용하지 않는다.

`catalog.json`은 CSV 점포에 연결한 **시연용 가상 상품·가격·영업/수령 일정**이다. 실제 점포의 메뉴·서비스 가입·영업·재고를 뜻하지 않는다. `[시연]` 이름과 상품 설명의 안내를 유지한다. 고객·구매요청·예약·로그인 비밀번호·API 키는 포함하지 않는다. 점주 계정은 실행 시 `.invalid` 이메일과 무작위 비밀번호 해시로 새로 생성하며 공통 로그인 암호를 제공하지 않는다.

보정동 카페거리 500m, 동백호수공원·쥬네브 주변 세 기준점 500m 합집합을 적용했다. 좌표 미확인 또는 범위 밖 CSV 행도 원문 테이블에 저장하되 참여 점포 연결은 비워 둔다. 중복 출처 행은 각 CSV 출처·원천 행 식별자를 유지한다. 전체 용인시 원문 아카이브는 기존 수집 환경에 보존되며 이 패키지는 지정된 4개 CSV만 포함한다.

## 1. 서버 실행으로 자동 입력

2026-11-15 안전 기본값 변경: 카탈로그 입력은 이제 명시적 opt-in이다. **이미 시연 데이터가 있는 DB는 이 절을 실행하지 않고 `demo.catalog.enabled=false`를 유지한다.** 기존 좌표 보완에는 [좌표 보완 전용 명령](coordinate-backfill.md)을 사용한다.

Java 21과 MySQL 8.4를 준비하고, 처음 한 번 빈 시연 DB를 만든다.

```sql
CREATE DATABASE together_market_csv_demo CHARACTER SET utf8mb4;
```

실행 환경변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 이 DB로 설정한다. URL 예시는 `jdbc:mysql://localhost:3306/together_market_csv_demo?connectionTimeZone=Asia/Seoul`이다. 비밀번호는 개인 실행 환경에만 보관한다. 애플리케이션은 MySQL 서버 자체나 데이터베이스를 생성하지 않는다.

저장소 루트에서 실행한다. Windows는 다음 명령, macOS/Linux는 `./gradlew`를 사용한다.

```powershell
./gradlew.bat bootRun --args="--demo.catalog.enabled=true --reservation.approval-window=PT30M --reservation.expiry-scheduler.enabled=false"
```

프로필을 별도로 지정하지 않으면 기본 `local` 프로필로 시작한다. 위 명령처럼 입력을 명시적으로 활성화한 경우에만 Flyway로 테이블·목적 분류를 준비한 뒤 점주·점포·상품·태그·일정·공공데이터를 입력한다. 별도 Python 실행이나 Gemini 키는 필요 없다. CSV와 카탈로그는 실행 JAR에도 포함되어 실행 위치와 무관하게 읽힌다.

로그에 `CSV_DEMO_INITIALIZED`가 표시되면 입력이 완료된 것이다. 첫 실행은 점포 2,224곳·상품 1,980개·공공데이터 3,265행 등을 추가하며, 같은 데이터로 재시작하면 `inserted={}`로 추가 입력이 없다. 이미 수정한 가격·판매 상태·일정·비밀번호는 유지한다. 점포 이름·주소 충돌 등 입력 오류는 전체 트랜잭션을 롤백하고 서버 시작을 실패시킨다. 두 서버가 동시에 시작해도 같은 DB의 입력 잠금으로 중복 생성을 방지한다.

입력은 `local` 또는 `demo` 프로필에서 `--demo.catalog.enabled=true` 또는 `DEMO_CATALOG_ENABLED=true`를 명시한 경우 작동한다. `prod`, `production`, `coordinate-backfill`이 함께 지정되면 작동하지 않는다. 기본 OFF이며 기존 DB에는 `--demo.catalog.enabled=false` 또는 `DEMO_CATALOG_ENABLED=false`를 유지한다. 입력 대상은 서버의 `DB_URL`이므로 새 시연 전용 DB를 지정한다.

GitHub 병합만으로 다른 PC의 DB에 데이터가 들어가는 것은 아니다. 반영된 코드를 내려받아 위 DB 설정으로 서버를 시작하면 자동 입력된다. 이 데이터 초기 입력은 테이블 구조를 변경하지 않는다.

## 2. 선택 사항: Python으로 직접 입력

서버 자동 입력 대신 기존 입력 스크립트를 사용할 수도 있다. 이 경우 Python 3.11 이상과 Flyway 적용이 필요하다.

```powershell
python -m pip install -r scripts/requirements-csv-demo.txt
python scripts/seed_csv_demo.py
```

두 번째 명령은 파일 체크섬·개수·연결 검증만 수행한다. `--apply`가 없으면 DB에 연결하지 않는다.

| 변수 | 기본값 / 의미 |
| --- | --- |
| `CSV_DEMO_DB_HOST` | `127.0.0.1` |
| `CSV_DEMO_DB_PORT` | `3306` |
| `CSV_DEMO_DB_NAME` | `together_market_csv_demo` |
| `CSV_DEMO_DB_USERNAME` | `DB_USERNAME`, 없으면 `root` |
| `CSV_DEMO_DB_PASSWORD` | `DB_PASSWORD`를 대신 사용할 수 있음 |

스크립트는 `DB_URL`을 해석하지 않으므로 서버 URL과 위 host·port·DB 이름을 맞춘다. 다른 이름은 `together_market_csv_demo_...` 또는 `together_market_csv_test_...` 형태만 허용하며 `prod`/`production` 프로필에서는 실행하지 않는다.

```powershell
python scripts/seed_csv_demo.py --apply
```

Java 자동 입력과 Python 입력은 같은 식별자·잠금을 사용한다. 어느 방법으로 먼저 입력했든 나중 실행에서 중복 생성하지 않는다. Python 실행 결과와 해당 DB의 추천 대상 점포 ID 목록은 Git에서 제외된 `data/csv-demo/runtime/`에 생성한다. 자동 입력만 사용한 경우 이 파일이 필요하면 Python 명령을 한 번 실행한다. DB 커밋 후 파일 기록에 실패하면 같은 명령을 재실행하면 된다. ID는 DB마다 다르므로 다른 DB로 복사하지 않는다.

## 3. 추천 API와 연결

이 변경은 현재 `dev` 스키마에 데이터를 준비하는 기능이다. 고객용 추천 API와 Gemini 시연 실행 코드는 별도 [PR #26](https://github.com/Together-Market/Together-Market-be/pull/26)에 있다. API 실행 기능과 이 데이터가 함께 반영된 브랜치에서 `DB_URL`을 위 DB로 설정해 추천을 실행한다. API 키는 각 개발자의 환경변수나 Git 제외 `.env`에서 제공한다.

현재 로컬에서 사용한 CSV 점포 제한(`csv-demo` 프로필)이 있는 코드와 연결할 때는 `RECOMMENDATION_CATALOG_SCOPE_PATH=data/csv-demo/runtime/candidate-store-ids.txt`를 지정하고 `demo,csv-demo` 프로필을 사용한다. 추천 범위를 제한하는 `csv-demo` 프로필 설정은 추천 기능 쪽에 있으며 이 데이터 PR에는 포함되지 않는다. 새 DB에는 선정된 점포만 들어가지만, 이후 다른 점포를 추가한 경우에는 추천 코드의 점포 제한도 함께 적용해야 한다.

## 검증 범위

CSV 점포·상품 구성은 실제 Gemini 호출 2회에서 추천 12개를 검증한 카탈로그를 기준으로 추출했다. 공공 점포와 시연 상품에 필요한 필드만 선택했으며 운영 계정·예약 DB 덤프가 아니다. 새 DB에 적용하면 내부 ID는 달라진다.

자동 검증은 기본 OFF, local/demo 명시적 활성화 및 운영/좌표보완 프로필·명시적 비활성화 차단, 원문 체크섬, 데이터 수량, 계정·요청 필드 제외, 손상된 파일 거부, 대상 DB·프로필 제한을 포함한다. 별도 빈 MySQL DB에서 Flyway 적용 후 첫 입력·재입력·수정 가격/상태 유지·실패 시 롤백을 검증한다. 실제 Gemini 재호출은 이 데이터 패키지 검증에 필요하지 않다.

2026-11-14 검증: Python 5개 통과. 독립 MySQL 테스트 DB에서 점주 2,224명·점포 2,224곳·상품 1,899개·태그 14,241개·일정 8,862개·원문 3,265행을 입력했다. 재실행 추가 행 0개, 수정 가격·판매 상태 보존, 중간 실패 전체 롤백을 확인했다. 구매요청·예약 행은 각각 0개다. 체크섬이 운영체제별 줄바꿈 변환으로 바뀌지 않도록 데이터 파일은 `.gitattributes`에서 바이트 보존을 지정했다.

서버 자동 입력 검증은 빈 MySQL에서 Flyway와 초기 입력, 반복 입력, 가격·판매 상태 보존, 동시 입력, 중간 실패 롤백을 포함한다. GitHub CI의 `csv-demo-mysql` 작업에서 같은 검사를 실행한다.

2026-11-15 자동 입력 검증: Java 21 `spotlessCheck build` 성공(225개 중 190개 통과, 환경 조건에 따라 35개 제외, 실패 0개). 별도 빈 MySQL에서 서버 시작 자동 입력·재입력·수정 보존·동시 입력·실패 롤백을 확인했고 Python 검사 5개도 통과했다.
