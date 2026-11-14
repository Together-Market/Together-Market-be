# Cloud Run 백그라운드 실행 준비 — 4단계

2026-11-15 코드와 공식 문서를 확인한 배포 안내다. 아직 클라우드에 적용하지 않았다. 아래 값은 소규모 시연용 시작 설정이며 부하 검증 결과가 아니다. 이번 단계에는 Java 코드나 DB 변경이 필요하지 않다.

## 확인한 코드

| 작업 | 현재 구현 |
| --- | --- |
| 예약 만료 | ReservationExpiryScheduler: 기본 활성화, 시작 후 30초부터 각 실행 종료 후 30초 간격, 한 번에 최대 100개 |
| 만료 상태 변경 | ReservationExpiryWorker: DB 행 잠금 후 PENDING 여부와 기한 재확인. APPROVAL_EXPIRED로 실패 처리 |
| 추천 계산 | RecommendationExecutionConfiguration: 작업 스레드 2개, 대기열 32개, 제한 시간 40초 |
| 중단된 추천 정리 | 시작 시 및 실행 종료 후 1초 간격으로 기한 초과 작업을 실패 처리. 계산을 자동 재실행하는 구조는 아님 |
| 세션 정리 | cloudsql 프로필의 Spring Session JDBC에서 기본 매분 만료 세션 정리 |

demo의 승인 기한은 30분이다. 기한과 정리 주기는 다르며 상태 반영은 정확히 기한 시각에 실행되지 않는다. 작업량·DB 장애·재시작으로 늦어질 수 있다. 기존 승인·만료 정책은 변경하지 않는다.

## 배포 설정값

| Cloud Run 항목 | 시연용 입력값 |
| --- | --- |
| Billing / 결제 방식 | Instance-based / 인스턴스 기반 |
| Service scaling / 서비스 확장 | 자동 확장 |
| Minimum number of instances / 최소 인스턴스 | 1 |
| Maximum number of instances / 최대 인스턴스 | 1 |
| CPU | 1 vCPU |
| Memory / 메모리 | 1 GiB |

인스턴스 기반 방식은 요청이 없는 동안에도 CPU를 할당한다. 최소 1은 서버가 0개로 줄어드는 것을 막는다. 최대 1은 시연 비용·DB 연결 수를 제한하려는 초기 선택이다. 최대 1이 분산 잠금이나 정확히 한 번 실행을 보장하지는 않으며 재배포 등에서 인스턴스가 겹칠 수 있다. DB 연결 풀은 인스턴스당 최대 5개로 준비되어 있다.

요청이 없어도 비용 또는 체험 크레딧이 사용되며 Cloud SQL 비용은 별도다. 최소 1도 개별 인스턴스의 재시작을 막지는 않는다. 시연 중 배포를 피하고, 추천이 중단되어 실패하면 새 구매요청으로 다시 시도한다. 종료 후 필요한 데이터를 백업하고 생성한 리소스를 정리한다.

## 콘솔에서 직접 설정하는 순서

이미지 업로드·Cloud SQL 복원·비밀값 준비 후 실제 배포할 때 적용한다. 지금 설정만 하려고 빈 서비스를 만들 필요는 없다.

1. Google Cloud Console에 로그인하고 맨 위 프로젝트 선택 상자에서 시연 프로젝트를 선택한다.
2. 상단 검색창에 `Cloud Run`을 입력하여 열고 왼쪽 `Services / 서비스`를 클릭한다.
3. 첫 배포라면 `Deploy container / 컨테이너 배포`를 클릭하고 후속 단계에서 업로드한 이미지와 서비스 기본값을 입력한다. 기존 서비스라면 서비스 이름을 클릭하고 `Scaling / 확장` 탭을 연다.
4. `Billing / 결제`에서 `Instance-based / 인스턴스 기반`을 선택한다. 이전 화면에서는 CPU를 항상 할당하는 옵션으로 표시될 수 있다.
5. `Service scaling / 서비스 확장`에서 자동 확장을 선택하고 최소 인스턴스 칸에 `1`, 최대 인스턴스 칸에 `1`을 입력한다. 서비스 수준 설정을 사용한다.
6. 새 서비스의 `Containers, Networking, Security / 컨테이너, 네트워킹, 보안`을 펼친다. 기존 서비스는 새 버전 수정 화면의 컨테이너 설정으로 이동한다. CPU `1`, 메모리 `1 GiB`를 선택한다. 화면 배치가 다르면 항목 이름을 기준으로 찾는다.
7. 환경변수는 [Cloud SQL 예시](cloud-sql-environment.example)를 따른다. `SPRING_PROFILES_ACTIVE=demo,cloudsql`, `DEMO_CATALOG_ENABLED=false`, `RESERVATION_APPROVAL_WINDOW=PT30M`을 확인한다. 예약 만료 스케줄러를 비활성화하는 설정은 추가하지 않는다. 실제 키와 비밀번호는 사용자가 Secret Manager에 직접 입력한다.
8. 이미지·DB 복원·권한·비밀값 연결까지 준비한 후 새 서비스는 `Create / 만들기`, 기존 서비스는 `View diff & redeploy / 변경사항 확인 및 재배포` → `Deploy changes / 변경사항 배포`로 적용한다. 설정 변경은 재배포를 일으킬 수 있다.
9. 서비스 상세 화면의 `Scaling / 확장`에서 결제 방식과 최소·최대를 다시 확인하고 컨테이너 설정에서 CPU·메모리를 확인한다. 비밀값이 포함될 수 있는 전체 서비스 설정은 출력하거나 공유하지 않는다.

## CLI 대응 옵션

다음은 후속 배포 명령에 붙일 옵션이며 독립 실행 명령이 아니다. 실제 프로젝트·서비스·이미지와 DB 설정 준비 후 사용한다.

```text
--no-cpu-throttling --min=1 --max=1 --cpu=1 --memory=1Gi
```

`--min`, `--max`는 서비스 수준 설정이다. 환경변수 입력칸에 넣는 값이 아니다. 기존 서비스를 갱신할 때 환경변수 전체를 덮어쓰거나 전체 서비스 YAML을 교체하지 않는다.

## 배포 후 확인

1. 배포 성공과 위 설정값을 확인한다.
2. 구매요청 생성 후 추천 결과가 정상 완료 또는 정의된 실패로 전환되는지 확인한다. 다른 방문자가 없어도 처리되는지 확인한다.
3. 예약 만료 실증은 이전에 보류한 테스트다. 수행할 때 전용 테스트 예약을 만들고 승인하지 않은 채 30분 기한과 정리 시간을 기다린 뒤 APPROVAL_EXPIRED를 확인한다. 프런트 자동 조회를 중지하고 대기하면 요청 없는 구간을 만들 수 있다. 조회 결과만으로 백그라운드 실행 경로까지 증명되지는 않으므로 필요 시 별도 테스트 환경에서 상태 변경 시점을 확인한다.
4. 이를 위해 운영 승인 시간을 임의로 줄이거나 기존 시연 예약을 수정하지 않는다. 테스트하지 않은 항목은 미검증으로 기록한다.
5. 재배포 후 로그인 유지는 [3단계 안내](jdbc-session-deployment.md)를 따른다. 세션 보존과 진행 중인 추천 작업 보존은 서로 다른 기능이다.

이번 단계는 코드 분석 및 배포 문서 준비만 수행했다. 클라우드 설정 적용, 유휴 상태 실행 및 예약 기한 만료 실증은 아직 수행하지 않았다.

## 공식 근거

- [CPU 할당과 과금 방식](https://docs.cloud.google.com/run/docs/configuring/billing-settings)
- [서비스 최소 인스턴스 설정](https://docs.cloud.google.com/run/docs/configuring/min-instances)
- [서비스 최대 인스턴스 설정](https://docs.cloud.google.com/run/docs/configuring/max-instances)
- [최대 인스턴스 제한의 동작](https://docs.cloud.google.com/run/docs/configuring/max-instances-limits)
