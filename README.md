# N블로그 포스터 (nclaude)

네이버 블로그 자동 포스팅 + SNS 교차 공유 안드로이드 앱.

## 기능
- 2계정 전환 (dicajohn / macdcross) — 쿠키 스냅샷으로 두 로그인 동시 유지
- 본문 전체 자동 입력 + 가독성 서식(소제목 볼드/색상, 핵심문장 하이라이트)
- 제목 자동 생성 — 첫 줄 + 마지막 줄 결합, 중복 단어 삭제/유사어 치환
- 갤러리 다중 사진 선택 → 에디터 사진 업로드 자동 호출
- SNS 후킹 요약 + 글 URL → 페북/링크드인/인스타/스레드/X 개별·일괄 공유

## 빌드 (GitHub Actions)
`android/**` 푸시 또는 Actions 수동 실행 → `nclaude-debug-apk` 아티팩트(app-debug.apk) 다운로드.
로컬 SDK 불필요. (mclaude 와 동일 파이프라인: JDK17 + Android 34 + Gradle 8.2 `assembleDebug`)

## 사용 흐름
1. 계정 선택 → 본문 붙여넣기(제목 자동 생성) → 사진 선택 → **블로그 포스팅**
2. WebView 가 PC 스마트에디터를 열고 제목·본문·서식·사진을 채움 (미로그인 시 1회 로그인)
3. 내용 검토 후 에디터의 **발행** 클릭 → 게시 URL 자동 캡처
4. SNS 버튼(개별) 또는 **전체 SNS 일괄 포스팅**

## 기기에서 조정이 필요할 수 있는 부분
스마트에디터/네이버 구조 변경 시 아래만 손보면 된다 (미드저니 앱의 EXTRACT_JS 와 동일한 성격):
- `EditorJs.kt` 의 셀렉터(`.se-documentTitle`, 본문 문단, 사진 버튼)와 주입 지연(2.5s)
- `MainActivity.isEditorUrl / isPublishedUrl` 의 URL 패턴
- 서식(색상/볼드)은 에디터 모델 반영이 기기별로 다를 수 있어 best-effort
