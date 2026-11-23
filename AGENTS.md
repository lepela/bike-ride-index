# bike-ride-index — 프로젝트 헌장

평일 출퇴근(08:30·18:30) 바이크 주행 지수를 계산·게시하고 안드로이드 앱·위젯으로 보여 준다.

- 이 저장소가 소유하는 것: 지수 계산 파이프라인(`pipeline/`), 기준값과 공용 테스트 케이스(`rules/`), GitHub Actions 워크플로, 게시 폴더(`docs/` → GitHub Pages), 안드로이드 앱(`android/`)
- 소유하지 않는 것: 집·회사 좌표와 인증키 값(Secrets·환경변수에만 둔다), 봄봄 메뉴 파이프라인(`lepela/bombom-menu`)

설계: `docs/superpowers/specs/2026-11-23-bike-ride-index-design.md`
계획: `docs/superpowers/plans/2026-11-23-bike-ride-index.md`

## 구조

| 경로 | 내용 |
|---|---|
| `rules/rules.json` | 기준값 단일 원본. Python·Kotlin이 모두 읽는다. 게시 때 `docs/rules.json`으로 복사한다 |
| `rules/test_vectors/` | Python·Kotlin 규칙 엔진이 같은 결과를 내는지 보는 공용 케이스 |
| `pipeline/bri/` | Python 파이프라인(표준 라이브러리만). CLI `python -m bri prepare|finalize` |
| `pipeline/prompt.md` | Actions에서 Claude가 읽는 지시문 |
| `docs/` | GitHub Pages. `latest.json`, `rules.json`, `history/` |
| `android/` | 안드로이드 앱(Kotlin, Compose, Glance) |

## 명령

- Python 테스트: `cd pipeline && python -m unittest discover -s tests`
- 안드로이드 테스트·빌드(PowerShell):
  `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; cd android; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
  - Git Bash: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :app:testDebugUnitTest`
  - `android/local.properties`에 `sdk.dir=C:/Users/<사용자>/AppData/Local/Android/Sdk`가 필요하다(커밋하지 않음).
  - 저장소 경로에 한글이 있으면 빌드 출력이 `%TEMP%\bike-ride-index-build\`로 간다. APK는 `%TEMP%\bike-ride-index-build\app\outputs\apk\debug\app-debug.apk`. 한글 경로의 build 폴더에서는 단위 테스트 워커가 클래스를 찾지 못한다(`android/build.gradle.kts` 참고).

## 비밀값 위치

- 기상청 단기예보 인증키(디코딩 값): 이 PC는 사용자 환경변수 `KMA_SERVICE_KEY`, GitHub Actions는 같은 이름의 Secret
- Claude 구독 토큰: Secret `CLAUDE_CODE_OAUTH_TOKEN`
- 좌표: Secret `HOME_LAT`, `HOME_LON`, `WORK_LAT`, `WORK_LON`
- 값은 파일·커밋·채팅·게시물에 쓰지 않는다. `docs/`와 Claude 입력 파일에도 좌표를 넣지 않는다.

## 협업

- 작업 브랜치에서 한다. 커밋 메시지는 한국어, 끝에 `Agent: <도구>` trailer.
- 리뷰는 구현 담당과 다른 회사의 모델이 맡는다.
