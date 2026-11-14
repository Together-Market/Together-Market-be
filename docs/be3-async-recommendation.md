# BE3 비동기 추천 상태 처리

후속 DB 연결·KST 변환·V3 저장소와 최신 활성화 설정은 [공통 코드 연결](be3-database-integration.md)을 참고하세요. 아래는 초기 내부 로직의 설계와 연결 경계 기록입니다.

공통 코드 없이 검증할 수 있는 상태 전이, 비동기 실행, 커밋 후 전달, 만료 복구를 구현했다. 공유 Entity·Repository·Flyway는 수정하지 않았다. 실제 DB 어댑터가 없으므로 현재 애플리케이션에 자동으로 등록되거나 실행되지는 않는다.

## 처리 흐름

1. 구매요청 저장 트랜잭션에서 상태를 RECOMMENDING, failure_code를 null로 저장한다. 같은 트랜잭션 안에서 `RecommendationAfterCommit.register(requestId)`를 호출한다.
2. 커밋 성공 시에만 작업을 전달한다. 롤백 시 전달하지 않는다. 커밋 후 전달 중 예외가 발생해도 이미 저장된 구매요청을 롤백했다고 응답하지 않는다. 전달되지 않은 요청은 만료 복구 대상이다.
3. `RecommendationJobStore.claim`에서 DB의 RECOMMENDING·미선점 조건을 원자적으로 확인하고 토큰과 제한시각을 저장한다. 같은 요청에 대한 중복 전달, 여러 인스턴스의 경합에서는 하나만 작업을 시작한다.
4. 제한된 스레드 수·대기열을 가진 executor가 계산한다. 구매조건 조회 → 서버 후보 생성 → AI 평가·순위화는 `RecommendationComputation`으로 연결한다. 계산이나 외부 AI 호출 전체를 DB 트랜잭션으로 감싸지 않는다.
5. 저장 시 DB에서 선점 토큰·현재 상태·제한시각을 다시 확인한다. 후보·상품 스냅샷·이유·순위 저장과 READY 전이는 한 트랜잭션이다. 빈 결과도 READY이며 오류가 아니다.
6. 실행 오류는 FAILED와 안전한 failure_code로 전환한다. READY/FAILED는 다른 완료·실패·만료 결과가 덮어쓰지 않는다.
7. 시작 시와 주기적으로 기한이 지난 RECOMMENDING을 FAILED로 정리한다. 이 조회에는 작업 전달이 누락된 요청과 다른 서버 인스턴스가 선점한 요청도 포함해야 한다. 복구 과정에서 AI를 재호출하지 않는다.

`RecommendationJobState`는 불변 상태 전이 규칙이고 공유 JPA Entity나 공통 상태 enum을 대신하지 않는다. `RecommendationJobRunner`는 실행 조정, `RecommendationJobRuntime`은 유한한 대기열과 복구 스케줄러를 제공한다. 메모리 저장소는 테스트 소스에만 있다.

## BE3가 선택한 실행 정책

- 전체 제한시간 기본값은 **구매요청 생성시각부터 40초**다. 대기열에 머무는 시간도 포함하며 선점·중복 전달로 연장하지 않는다. 만료 경계에서는 성공보다 만료가 우선한다.
- 기존 외부 AI 어댑터의 요청당 15초·일부 일시 오류에 한해 1회 재시도는 유지한다. 이번 작업에서 외부 API 호출 코드는 변경하지 않았다. 비동기 작업 전체를 자동 재실행하지 않는다.
- 복구는 기본 한 번에 100건까지 처리한다. worker 수·대기열 크기·복구 주기는 런타임 생성 시 명시한다. 초기 연결 예시는 worker 2개, 대기열 32개, 복구 주기 1초다. 대기열 포화 시 요청 스레드에서 AI를 실행하지 않고 DISPATCH_REJECTED로 실패 처리한다.
- 실행 중인 작업의 인터럽트는 최선의 중단 요청이다. 외부 라이브러리가 인터럽트를 무시할 수 있으므로 안전성은 DB의 토큰·상태·제한시각 검사로 보장해야 한다. 중단 불응 작업이 worker를 계속 점유할 경우 대기열 상한과 거절 정책이 추가 요청을 제한한다.
- 시간 초과 후 상태 정리는 복구 주기와 배치 처리에 따라 지연될 수 있다. DB가 계속 불가하면 FAILED 저장도 불가능하다. DB가 복구되면 다음 만료 조회가 처리한다. 제한시각 이후 결과 저장은 DB 어댑터에서도 거절해야 한다.
- 정상 종료에서는 로컬 작업을 중단하고 WORKER_INTERRUPTED로 정리한다. 프로세스 강제 종료처럼 종료 처리가 실행되지 않는 경우에는 재시작 후 만료 처리한다. 서버 재시작 직후 아직 40초가 지나지 않은 선점 요청은 남은 제한시간까지 기다린다.

## failure_code

아래는 BE3 코드에서 선택한 값이며 FE 공개 문서·공유 스키마와 연결할 때 전달할 목록이다. 예외 메시지, AI 응답 본문, 사용자 답변은 failure_code나 로그에 넣지 않는다.

| 코드 | 의미 |
| --- | --- |
| RECOMMENDATION_TIMEOUT | 대기·계산·저장을 포함한 전체 제한시간 초과, 누락 작업 만료 |
| DISPATCH_REJECTED | executor 종료 또는 대기열 포화로 전달 거절 |
| AI_TIMEOUT | 계산 어댑터가 명시적으로 보고한 외부 AI 시간 초과 |
| AI_RESPONSE_INVALID | 계산 어댑터가 명시적으로 보고한 AI 응답 검증 실패 |
| AI_UNAVAILABLE | 계산 어댑터가 명시적으로 보고한 AI 서비스 오류 |
| RECOMMENDATION_FAILED | 분류되지 않은 계산 오류 또는 null 결과 |
| RESULT_PERSISTENCE_FAILED | 후보/상태 저장 트랜잭션 오류 |
| WORKER_INTERRUPTED | 종료·인터럽트에 따른 중단 |

계산 어댑터는 구분 가능한 실패를 `RecommendationComputation.Failure(code)`로 전달할 수 있다. 직접 발생한 HttpTimeoutException/TimeoutException도 AI_TIMEOUT으로 변환한다. **현재 OpenAiCandidateEvaluator는 일부 원인을 일반 예외로 감싸므로 세분화하지 못한 오류는 RECOMMENDATION_FAILED가 된다.** 예외 메시지를 파싱해 원인을 추측하지 않는다. 구체적인 AI 코드 연결은 평가기에서 구조화된 실패를 제공할 때 보완한다. 전체 기한이 지났으면 원래 오류보다 RECOMMENDATION_TIMEOUT이 우선한다.

## 후속 DB 연결에 필요한 계약

`RecommendationJobStore`의 모든 메서드는 짧고 독립적인 트랜잭션이어야 한다. 특히 Spring afterCommit 콜백에서는 기존 트랜잭션 리소스가 남아 있을 수 있으므로 **store 구현을 별도 프록시 Bean의 REQUIRES_NEW 트랜잭션으로 실행**한다. 자기 호출로 트랜잭션 프록시를 우회하지 않는다.

- 선점 토큰(UUID)과 제한시각을 영속화할 위치를 초기 스키마 담당자와 맞춘다. purchase_requests의 추가 컬럼 또는 별도 작업 저장소가 필요할 수 있다. 현재는 SQL/컬럼을 임의 추가하지 않았다.
- claim: 행 잠금 또는 조건부 갱신으로 상태·미선점을 확인하고 저장한다. 아직 커밋되지 않은 구매요청을 읽지 않는다. 생성시각+40초가 지났으면 즉시 FAILED로 정리한다.
- complete: DB에서 읽은 토큰·기한을 기준으로 판정한다. 호출 인자의 Lease.deadline만 신뢰하지 않는다. 잠금 대기 후에도 트랜잭션 안에서 현재 시각을 다시 읽어 기한을 검사한다. 인자로 받은 now는 테스트/조정용 기준이며 긴 대기 이후의 확정시각으로 고정해서는 안 된다.
- 후보 insert와 READY 변경은 하나의 트랜잭션이다. insert 중 오류가 나면 후보 일부와 상태 변경 모두 롤백한다. 중복 결과를 막을 UNIQUE 또는 조건부 전이도 함께 검증한다.
- 저장이 실제로 커밋됐는데 응답만 유실된 경우 추가 실패 처리로 READY를 덮어쓰지 않는다. fail도 토큰과 RECOMMENDING을 조건으로 갱신한다.
- expire: 미선점 요청을 포함해 만료된 RECOMMENDING을 가장 오래된 기한부터 제한 건수만 처리한다. 상태·토큰 경쟁은 DB에서 원자적으로 판정하고 필요 인덱스를 검토한다. 같은 정책과 시간 기준을 모든 인스턴스에 적용한다.
- 날짜 저장·변환은 팀 합의에 따라 DB DATETIME은 KST로 저장하고 변환 계층에서 Asia/Seoul 기준을 적용한다. DB 시각 또는 서버 Clock 중 어떤 기준으로 비교할지 일관되게 정하고, 커넥션·쿼리 제한시간도 설정한다.

메모리 테스트는 이 계약을 지키는 저장소에서의 동작을 검증한다. 실제 MySQL의 잠금, UNIQUE, 트랜잭션 롤백, 여러 프로세스 경합은 아직 검증하지 않았다.

## 조립 예시

```java
// 애플리케이션 수명주기로 관리하며 종료 시 runtime.close()를 호출한다.
var runtime = new RecommendationJobRuntime(
    durableJobStore, computationAdapter, clock,
    RecommendationJobRunner.Policy.defaults(), 2, 32, Duration.ofSeconds(1));
var afterCommit = new RecommendationAfterCommit(runtime::afterCommit);

// 구매요청 생성 서비스의 쓰기 트랜잭션 내부:
var request = sharedRepository.save(newPurchaseRequest);
afterCommit.register(request.getId());
// 세션 고객 ID와 공통 202 응답은 기존 BE1 계약에 연결한다.
```

공개 상태는 기존 RECOMMENDING / READY / FAILED를 유지한다. 선점·대기열 상태를 API 상태로 추가하지 않았다. 구매요청 POST의 Idempotency-Key를 도입한 것도 아니다. 여기서 막는 중복은 **동일 구매요청의 추천 작업과 결과 저장**이다.

## 검증 범위

- 미커밋·롤백 시 미전달, 커밋 이후 전달 오류 처리
- 실제 별도 스레드에서의 실행, 대기열 포화, 두 실행자의 동시 선점
- READY+빈 목록, 중복 전달·완료, 잘못된 토큰, 정확한 시간 경계
- 대기 중 만료, 계산 후 늦은 완료, 인터럽트를 무시하는 작업의 늦은 결과
- 유실된 전달·재시작·배치 만료, 복구 스케줄러의 오류 후 재실행
- 저장 오류·커밋 응답 유실·DB 오류 후 복구, 종료 처리

외부 AI·실제 DB·실제 HTTP API는 호출하지 않는다. 공통 코드 연결 후 해당 통합 검증이 필요하다.

```powershell
.\gradlew.bat spotlessCheck build
```
