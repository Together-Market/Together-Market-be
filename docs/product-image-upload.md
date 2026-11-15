# 상품 이미지 파일 업로드

## API

- `POST /owner/stores/{store_id}/product-images`
- 점주 로그인 세션, 본인 점포, CSRF 토큰 필요. `/api/v1` 접두사 없음.
- `Content-Type: multipart/form-data`의 `file` 필드에 JPEG 또는 PNG 파일 1개를 보낸다. 클라이언트는 multipart boundary를 직접 지정하지 않는다.
- 최대 5MB, 최대 4096×4096 픽셀. SVG, GIF, WebP, 빈 파일, 이미지로 읽히지 않는 파일은 거부한다.
- 성공: HTTP 201, `{ "data": { "image_url": "https://<백엔드 주소>/product-images/<파일명>" } }`.
- 반환된 `image_url`을 기존 상품 등록/수정의 `image_url`에 그대로 넣는다. 업로드만 하고 상품에 연결하지 않으면 고아 파일이 남을 수 있으므로 시연 후 정리한다.
- 인증 실패 401, 점주 역할 아님 403, 다른 점포/없는 점포 404, 잘못된 이미지 400 `INVALID_IMAGE`, 저장소 미설정/오류 503 `IMAGE_STORAGE_UNAVAILABLE`.
- 이미지 표시: `GET /product-images/{file_name}`. 상품 이미지가 공개 콘텐츠인 현재 서비스에 맞게 인증 없이 조회되며, Cloud Storage 객체 자체는 비공개다. 없는 이미지는 404.

프런트는 먼저 `GET /auth/csrf`로 토큰과 세션 쿠키를 받고, `credentials: 'include'`(Axios는 `withCredentials: true`)로 업로드한다. 요청 헤더 `X-CSRF-Token`에는 해당 토큰을 넣는다. 로그인 직후 CSRF 토큰이 바뀌므로 다시 `/auth/csrf`를 호출한다.

## 배포 설정

Cloud Storage에 **상품 이미지만 보관할 별도 비공개 버킷**을 만든다. 기존 Cloud Build 소스 버킷과 혼용하지 않는다. 공개 액세스 방지는 유지한다. Cloud Run 실행 서비스 계정 `together-market-runtime`에는 그 버킷에서 객체 생성과 조회에 필요한 권한만 부여한다(예: Storage Object Creator + Storage Object Viewer). 버킷을 `allUsers`에 공개하지 않는다.

Cloud Run 환경변수 이름과 값의 형태:

- `PRODUCT_IMAGE_BUCKET`: 새 상품 이미지 버킷 이름
- `PRODUCT_IMAGE_PUBLIC_BASE_URL`: `https://...run.app` 형태의 현재 Cloud Run 서비스 URL. 뒤에 `/`를 붙이지 않는다.

키 파일이나 Google 자격 증명을 환경변수에 넣지 않는다. Cloud Run 실행 서비스 계정의 권한으로 Cloud Storage에 접근한다. 두 변수가 없으면 업로드는 503이며 기존 상품 API는 계속 동작한다. 새 버전 배포 전 버킷과 IAM을 준비하고, 실제 파일 업로드/표시/상품 연결을 점주 계정으로 확인한다. 테스트는 저장소를 대체하며 실제 버킷이나 키를 쓰지 않는다.
