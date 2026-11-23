# 출퇴근 바이크 주행 지수 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 평일 출근(08:30)·퇴근(18:30) 바이크 주행 지수를 Claude가 산출해 GitHub Pages에 게시하고, 안드로이드 앱·위젯이 이를 표시한다.

**Architecture:** GitHub Actions가 Open-Meteo 4개 모델과 기상청 단기예보를 받아 규칙 엔진으로 특성·기준 점수를 계산한다. 이어서 `claude-code-action`(구독 OAuth 토큰)이 지수를 산출하고, 코드가 안전 하한을 강제한 뒤 `docs/latest.json`으로 게시한다. 앱은 이 JSON을 받아 위젯에 표시한다. 게시값이 없으면 같은 규칙 엔진(Kotlin 포팅)으로 기기 안에서 대체 점수를 계산한다.

**Tech Stack:** Python 3.12 표준 라이브러리만(테스트는 `unittest`), GitHub Actions, `anthropics/claude-code-action@v1`, Kotlin, Jetpack Compose, Glance, WorkManager, DataStore, kotlinx.serialization, play-services-location

**Spec:** `docs/superpowers/specs/2026-11-23-bike-ride-index-design.md`

## Global Constraints

- 저장소: GitHub `lepela/bike-ride-index`, 공개. Pages 원본은 `main` 브랜치 `/docs`.
- Python은 표준 라이브러리만 쓴다. 외부 패키지를 설치하지 않는다(봄봄과 같은 원칙).
- 게시물(`docs/` 아래 모든 파일)과 Claude 입력 파일에 좌표·지명을 넣지 않는다.
- Secrets: `HOME_LAT`, `HOME_LON`, `WORK_LAT`, `WORK_LON`, `CLAUDE_CODE_OAUTH_TOKEN`, `KMA_SERVICE_KEY`(디코딩한 값). 코드가 요청할 때 URL 인코딩한다.
- 시간은 모두 KST(Asia/Seoul) 기준이다. Actions 서버 시간(UTC)을 그대로 쓰지 않는다.
- 실행 cron: `30 21 * * 0-4`(평일 06:30 KST, am), `0 8 * * 1-5`(평일 17:00 KST, pm).
- 등급: 80~100 좋음, 60~79 주의, 40~59 비추천, 0~39 타지 않음.
- 안전 하한: 적설 예보 모델 2개 이상 → 39, 결빙 높음 → 39, 결빙 낮음 또는 적설 예보 모델 1개 → 59.
- Claude 점수가 기준 점수와 15점 넘게 차이 나면 `deviation_reason`이 있어야 한다.
- 기상청 단기예보는 앱에서 호출하지 않는다(인증키를 앱에 넣지 않는다).
- 안드로이드: 패키지 `dev.lepela.rideindex`, minSdk 26, 라이브러리 버전은 빌드 시점의 최신 안정판.
- 협업 규칙(`~/.agents/AGENTS.md`): 작업은 `feat/mvp` 브랜치에서 한다. 커밋 메시지는 한국어로 쓰고 끝에 아래 두 줄을 붙인다. 리뷰는 다른 회사 모델(Codex 또는 Kimi)이 한다.

  ```
  Agent: claude-code
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

- 로컬 빌드 환경(2026-11-23 확인): Android Studio 설치됨, SDK `%LOCALAPPDATA%\Android\Sdk`, JDK는 `C:\Program Files\Android\Android Studio\jbr`. PATH에 `java` 없음 → Gradle 실행 전 `JAVA_HOME`·`ANDROID_HOME`을 설정한다. Python 3.12.10, `gh` 2.96.0.

## Review Focus

1. Open-Meteo 요청이 한 지점이라도 실패(시간 초과·5xx) → 한 번 재시도 후 실패하면 `prepare`가 0이 아닌 코드로 끝나고 `docs/`를 건드리지 않는다. 위젯은 기기 계산으로 넘어간다. (Task 6 `test_prepare_fails_without_touching_docs`)
2. 기상청이 `dataType=JSON`을 무시하고 XML 오류(`SERVICE_KEY_IS_NOT_REGISTERED_ERROR` 등)나 `resultCode != "00"`을 돌려줌 → 예외 없이 그 출처만 빠진다. (Task 3 `test_xml_error_body_returns_none`)
3. 모델이 특정 변수를 전부 `null`로 줌(JMA 돌풍, GFS·JMA 지표면 온도) → 중앙값에서 빠지고, 전부 없으면 특성이 `None`이 되며 계산이 멈추지 않는다. (Task 4 `test_missing_variables`)
4. Claude 출력이 어긋남(점수가 문자열, 슬롯 누락·추가, `deviation_reason` 누락) → 전체를 기준 점수로 대체하고 `source: "rule"`. (Task 5 `test_invalid_outputs_rejected`)
5. 아침 실행이 실패한 날의 오후 실행 → `latest.json`에 오늘 퇴근 슬롯만 있다. 앱은 출근 슬롯을 기기 계산이나 "어제 값"으로 채운다. (Task 6 `test_pm_without_today_am`, Task 12 `test_missing_slot_uses_device`)

---

## 파일 구조

```
bike-ride-index/
├─ AGENTS.md                         # 빌드·테스트 명령, 구조 (Task 1)
├─ rules/
│  ├─ rules.json                     # 기준값 단일 원본 (Task 1)
│  └─ test_vectors/*.json            # Python·Kotlin 공용 엔진 케이스 (Task 4)
├─ pipeline/
│  ├─ bri/
│  │  ├─ __init__.py
│  │  ├─ __main__.py                 # CLI: prepare / finalize (Task 6)
│  │  ├─ rules.py                    # rules.json 로드 (Task 1)
│  │  ├─ series.py                   # ModelSeries, 창 자르기 (Task 2)
│  │  ├─ openmeteo.py                # Open-Meteo 요청·파싱 (Task 2)
│  │  ├─ kma.py                      # 기상청 격자·발표시각·파싱 (Task 3)
│  │  ├─ engine.py                   # 특성·결빙·기준점수·하한·등급 (Task 4)
│  │  ├─ claude_io.py                # Claude 입력 작성·출력 검증 (Task 5)
│  │  └─ publish.py                  # 실행 판정·멱등·게시 JSON (Task 6)
│  ├─ prompt.md                      # Claude 지시문 (Task 5)
│  └─ tests/ (test_*.py, fixtures/)
├─ .github/workflows/ride-index.yml  # (Task 7)
├─ docs/                             # Pages: latest.json, rules.json, history/
└─ android/                          # Gradle 프로젝트 (Task 9~14)
   └─ app/src/main/java/dev/lepela/rideindex/
      ├─ model/Published.kt          # (Task 9)
      ├─ engine/RuleEngine.kt        # (Task 10)
      ├─ data/{PagesClient,OpenMeteoClient,SettingsStore,DeviceCalculator}.kt  # (Task 11)
      ├─ display/DisplayResolver.kt  # (Task 12)
      ├─ widget/{RideWidget,RideWidgetReceiver}.kt, work/{RefreshWorker,RefreshScheduler}.kt  # (Task 13)
      └─ ui/{MainActivity,MainScreen,SettingsScreen}.kt  # (Task 14)
```

공용 변수 이름(Python 키 = Kotlin 프로퍼티): `temp`, `apparent`, `dewpoint`, `precip`(mm), `snowfall`(cm), `pop`, `gust`(m/s), `wind`(m/s), `surface_temp`, `pty`.

---

## Part A — 클라우드 파이프라인

### Task 1: 저장소 골격과 rules.json

**Files:**
- Create: `AGENTS.md`, `.gitignore`, `rules/rules.json`, `pipeline/bri/__init__.py`, `pipeline/bri/rules.py`
- Test: `pipeline/tests/test_rules.py`

**Interfaces:**
- Produces: `load_rules(path: str | Path = DEFAULT_RULES_PATH) -> dict`, `DEFAULT_RULES_PATH`(저장소 루트 기준 `rules/rules.json`). 반환값은 아래 JSON 그대로의 dict.

- [ ] **Step 1: 로컬 저장소와 GitHub 저장소 만들기**

  사용자가 고른 폴더에 `bike-ride-index`를 만든다. `git init -b main` 후 `gh repo create lepela/bike-ride-index --public --source . --remote origin`. `main`에 빈 README로 첫 커밋을 하고 push한 다음, `git switch -c feat/mvp`로 브랜치를 만든다.

- [ ] **Step 2: `rules/rules.json` 작성** — 설계 5.4·5.5절 값을 그대로 옮긴다.

```json
{
  "version": 1,
  "openmeteo_models": ["ecmwf_ifs025", "gfs_seamless", "icon_seamless", "jma_seamless"],
  "kma_model_name": "kma_vilage",
  "weekdays": [1, 2, 3, 4, 5],
  "slots": {
    "commute_in":  {"time": "08:30", "points": {"home": ["08:00", "09:00"], "work": ["08:30", "09:30"]}},
    "commute_out": {"time": "18:30", "points": {"work": ["18:00", "19:00"], "home": ["18:30", "19:30"]}}
  },
  "wet_precip_mm": 0.1,
  "score": {
    "precip_steps": [[0.1, 0], [1.0, 10], [3.0, 25], [null, 45]],
    "snow_single": 30, "snow_multi": 60,
    "ice_low": 20, "ice_high": 50,
    "cold_apparent_c": 0.0, "cold_penalty": 10,
    "very_cold_apparent_c": -5.0, "very_cold_penalty": 25,
    "gust_ms": 15.0, "gust_penalty": 15
  },
  "ice": {
    "high_temp_max": 1.0, "high_surface_max": 0.0,
    "low_precip24_min": 1.0, "low_temp_max": 3.0,
    "frost_temp_max": 2.0, "frost_spread_max": 1.0
  },
  "floors": {"snow_multi": 39, "ice_high": 39, "ice_low": 59, "snow_single": 59},
  "grades": [[80, "좋음"], [60, "주의"], [40, "비추천"], [0, "타지 않음"]],
  "claude": {"max_deviation_without_reason": 15}
}
```

  `precip_steps`는 "값이 첫 수 미만이면 둘째 수만큼 감점"을 차례로 본다. `null`은 상한 없음이다. 창은 시작 시각을 시 단위로 내림한 시각부터 끝 시각을 내림한 시각까지 포함한다(08:30~09:30 → 08시·09시).

- [ ] **Step 3: 실패하는 테스트 작성**

```python
class TestRules(unittest.TestCase):
    def test_loads_default(self):
        r = load_rules()
        self.assertEqual(r["floors"]["ice_high"], 39)
        self.assertEqual(r["slots"]["commute_out"]["time"], "18:30")
        self.assertEqual(len(r["openmeteo_models"]), 4)
```

- [ ] **Step 4:** `cd pipeline && python -m unittest discover -s tests -v` → FAIL(`ImportError`)
- [ ] **Step 5:** `pipeline/bri/rules.py`에 `load_rules` 구현(`json.load`, UTF-8)
- [ ] **Step 6:** 같은 명령 → PASS
- [ ] **Step 7:** `AGENTS.md`에 테스트 명령(`cd pipeline && python -m unittest discover -s tests`)과 안드로이드 빌드 명령(Task 9에서 채움), 디렉터리 설명을 적는다. `.gitignore`에 `work/`, `android/.gradle/`, `android/build/`, `android/app/build/`, `local.properties`를 넣는다.
- [ ] **Step 8: 커밋** `chore: 저장소 골격과 기준값 rules.json 추가`

### Task 2: Open-Meteo 조회와 시계열

**Files:**
- Create: `pipeline/bri/series.py`, `pipeline/bri/openmeteo.py`, `pipeline/tests/fixtures/openmeteo_seoul.json`
- Test: `pipeline/tests/test_openmeteo.py`

**Interfaces:**
- Produces:
  - `@dataclass ModelSeries(model: str, times: list[datetime], values: dict[str, list[float | None]])` — `times`는 KST naive datetime
  - `window(series: ModelSeries, start: datetime, end: datetime) -> dict[str, list[float | None]]` — `start <= t <= end`인 시각만
  - `past_sum(series: ModelSeries, var: str, before: datetime, hours: int) -> float | None` — `[before - hours, before)` 구간 합. 값이 하나도 없으면 `None`
  - `build_url(lat: float, lon: float, models: list[str]) -> str`
  - `parse_response(body: dict, models: list[str]) -> list[ModelSeries]` — 기온이 전부 `None`인 모델은 뺀다
  - `fetch_point(lat: float, lon: float, models: list[str], timeout: int = 30) -> list[ModelSeries]` — 실패 시 1회 재시도 후 `OpenMeteoError`

- [ ] **Step 1: 고정 응답 기록** — 서울시청 좌표(37.5665, 126.978)로 아래 URL을 받아 fixture로 저장한다. 개인 좌표를 쓰지 않는다.

  `build_url`이 만들어야 하는 URL: `https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&hourly=temperature_2m,apparent_temperature,dew_point_2m,precipitation,snowfall,precipitation_probability,wind_gusts_10m,soil_temperature_0cm&models=<쉼표 목록>&past_days=2&forecast_days=2&timezone=Asia%2FSeoul&wind_speed_unit=ms`

  돌풍 단위의 기본값은 km/h다. `wind_speed_unit=ms`를 빠뜨리면 돌풍 감점 기준(15m/s)이 틀어진다.

- [ ] **Step 2: 실패하는 테스트 작성**

```python
def test_parse_fixture(self):
    body = json.load(open(FIX / "openmeteo_seoul.json", encoding="utf-8"))
    out = parse_response(body, ["ecmwf_ifs025", "gfs_seamless", "icon_seamless", "jma_seamless"])
    self.assertEqual([s.model for s in out], ["ecmwf_ifs025", "gfs_seamless", "icon_seamless", "jma_seamless"])
    self.assertEqual(len(out[0].times), 96)
    self.assertTrue(all(v is None for v in out[3].values["gust"]))   # JMA 돌풍 없음
    self.assertTrue(all(v is None for v in out[1].values["surface_temp"]))  # GFS 지표면 없음

def test_drops_all_null_model(self):
    body = {"hourly": {"time": ["2026-11-24T08:00"], "temperature_2m_kma_seamless": [None],
                       "temperature_2m_icon_seamless": [3.0]}}
    self.assertEqual([s.model for s in parse_response(body, ["kma_seamless", "icon_seamless"])], ["icon_seamless"])

def test_window_and_past_sum(self):
    s = ModelSeries("m", [datetime(2026,11,24,h) for h in range(6)], {"precip": [1, None, 2, 0, 0, 5]})
    self.assertEqual(window(s, datetime(2026,11,24,2), datetime(2026,11,24,3))["precip"], [2, 0])
    self.assertEqual(past_sum(s, "precip", datetime(2026,11,24,3), 3), 3.0)
    self.assertIsNone(past_sum(s, "precip", datetime(2026,11,24,0), 3))

def test_url_has_ms_unit(self):
    self.assertIn("wind_speed_unit=ms", build_url(37.5, 127.0, ["icon_seamless"]))
```

- [ ] **Step 3:** 테스트 실행 → FAIL
- [ ] **Step 4:** 구현. 응답 키 `<변수>_<모델>`을 공용 이름으로 바꾼다: `temperature_2m→temp`, `apparent_temperature→apparent`, `dew_point_2m→dewpoint`, `precipitation→precip`, `snowfall→snowfall`, `precipitation_probability→pop`, `wind_gusts_10m→gust`, `soil_temperature_0cm→surface_temp`. 요청은 `urllib.request`로 한다.
- [ ] **Step 5:** 테스트 → PASS
- [ ] **Step 6: 커밋** `feat: Open-Meteo 다중 모델 조회와 시계열 도구`

### Task 3: 기상청 단기예보

**Files:**
- Create: `pipeline/bri/kma.py`, `pipeline/tests/fixtures/kma_vilage_sample.json`, `pipeline/tests/fixtures/kma_error.xml`
- Test: `pipeline/tests/test_kma.py`

**Interfaces:**
- Consumes: `ModelSeries` (Task 2)
- Produces:
  - `latlon_to_grid(lat: float, lon: float) -> tuple[int, int]`
  - `pick_base(now_kst: datetime) -> tuple[str, str]` — `(base_date "YYYYMMDD", base_time "HHMM")`
  - `parse_pcp(s: str) -> float`, `parse_sno(s: str) -> float`
  - `parse_items(body: str, model_name: str) -> ModelSeries | None` — 응답 원문(str)을 받는다. JSON이 아니거나 `resultCode != "00"`이면 `None`
  - `fetch_point(lat: float, lon: float, service_key: str, now_kst: datetime, model_name: str, timeout: int = 30) -> ModelSeries | None` — 네트워크 오류도 `None`

- [ ] **Step 1: fixture 작성** — `kma_vilage_sample.json`은 실제 응답 형식(`response.header.resultCode`, `response.body.items.item[]`의 `fcstDate`·`fcstTime`·`category`·`fcstValue`)으로 손으로 만든다. 08·09시에 `TMP` "-1"·"0", `PCP` "1mm 미만"·"강수없음", `SNO` "적설없음"·"1cm 미만", `PTY` "0"·"3", `POP` "60"·"70", `WSD` "2.1"·"3.4"를 넣는다. `kma_error.xml`은 `<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg></cmmMsgHeader></OpenAPI_ServiceResponse>`이다.

- [ ] **Step 2: 실패하는 테스트 작성**

```python
def test_grid(self):
    self.assertEqual(latlon_to_grid(37.5665, 126.978), (60, 127))
    self.assertEqual(latlon_to_grid(35.1796, 129.0756), (98, 76))

def test_pick_base(self):
    self.assertEqual(pick_base(datetime(2026,11,24,6,30)), ("20261124", "0500"))
    self.assertEqual(pick_base(datetime(2026,11,24,17,0)), ("20261124", "1400"))
    self.assertEqual(pick_base(datetime(2026,11,24,17,12)), ("20261124", "1700"))
    self.assertEqual(pick_base(datetime(2026,11,24,1,0)), ("20261123", "2300"))

def test_parse_strings(self):
    for s, v in [("강수없음", 0.0), ("1mm 미만", 0.5), ("2.5mm", 2.5), ("30.0~50.0mm", 40.0), ("50.0mm 이상", 50.0)]:
        self.assertEqual(parse_pcp(s), v)
    for s, v in [("적설없음", 0.0), ("1cm 미만", 0.5), ("1.2cm", 1.2), ("5.0cm 이상", 5.0)]:
        self.assertEqual(parse_sno(s), v)

def test_parse_items(self):
    s = parse_items((FIX / "kma_vilage_sample.json").read_text(encoding="utf-8"), "kma_vilage")
    self.assertEqual(s.model, "kma_vilage")
    self.assertEqual(s.values["temp"], [-1.0, 0.0])
    self.assertEqual(s.values["snowfall"], [0.0, 0.5])
    self.assertEqual(s.values["pty"], [0.0, 3.0])
    self.assertEqual(s.values["wind"], [2.1, 3.4])
    self.assertTrue(all(v is None for v in s.values["dewpoint"]))

def test_xml_error_body_returns_none(self):
    self.assertIsNone(parse_items((FIX / "kma_error.xml").read_text(encoding="utf-8"), "kma_vilage"))
```

- [ ] **Step 3:** 실행 → FAIL
- [ ] **Step 4:** 구현. 격자 변환은 설계 5.3a의 상수를 쓰는 표준 기상청 공식이다(반올림 `int(x + 0.5)`). 발표 시각은 02·05·08·11·14·17·20·23시에 10분을 더한 시각이 `now` 이하인 것 중 최근 것이다. 요청 URL은 `getVilageFcst?serviceKey=<urllib.parse.quote(key, safe="")>&pageNo=1&numOfRows=1000&dataType=JSON&base_date=..&base_time=..&nx=..&ny=..`이다. 응답에 없는 변수(`dewpoint`, `apparent`, `surface_temp`, `gust`, `precip` 과거값)는 `None` 목록으로 채운다.
- [ ] **Step 5:** 실행 → PASS
- [ ] **Step 6: 커밋** `feat: 기상청 단기예보 조회와 격자 변환`

### Task 4: 규칙 엔진과 공용 테스트 케이스

**Files:**
- Create: `pipeline/bri/engine.py`, `rules/test_vectors/{a_dry_mild,b_light_rain_cold,c_ice_after_snow,d_snow_two_models}.json`
- Test: `pipeline/tests/test_engine.py`

**Interfaces:**
- Consumes: `window`, `past_sum` (Task 2), `load_rules` (Task 1)
- Produces:
  - `ModelWindow = dict` — 키: 공용 변수 이름별 창 값 목록 + `past24_precip: float | None`, `past48_snowfall: float | None`
  - `point_features(models: dict[str, ModelWindow], rules: dict) -> dict` — 키: `n_models, temp_c, apparent_c, spread_c, surface_c, precip_median, precip_min, precip_max, precip_models, snow_models, gust_ms, past24_precip_mm, past48_snow_cm`
  - `ice_risk(pf: dict, rules: dict) -> str` — `"없음" | "낮음" | "높음"`
  - `evaluate_slot(points: dict[str, dict[str, ModelWindow]], rules: dict) -> dict` — 키: `features`(지점별 합친 최악값), `ice_risk`, `rule_score: int`, `confidence`, `floor_cap: int | None`, `floor_label: str | None`
  - `apply_floor(score: int, floor_cap: int | None, floor_label: str | None) -> tuple[int, str | None]` — `score > cap`일 때만 내리고 라벨을 돌려준다
  - `grade(score: int, rules: dict) -> str`
  - `build_model_windows(series: list[ModelSeries], start: datetime, end: datetime) -> dict[str, ModelWindow]`

- [ ] **Step 1: 계산 규칙(구현자가 바꾸지 않는 부분)**

  - 모델별로 창 안의 값을 하나로 줄인다: `temp`·`apparent`·`surface_temp`는 최솟값, `precip`·`gust`는 최댓값, spread는 시간별 `temp - dewpoint`의 최솟값. 그다음 모델들의 **중앙값**을 낸다. 짝수 개면 가운데 두 값의 평균이고, `None`인 모델은 뺀다. 남는 값이 없으면 `None`이다.
  - `n_models`: 창 안에 `temp` 값이 하나라도 있는 모델 수
  - `precip_models`: 창 최대 강수 ≥ `wet_precip_mm`인 모델 수
  - `snow_models`: 창 안에 `snowfall > 0`이거나 `pty`가 2·3인 모델 수
  - 결빙(지점별): 높음 = `past48_snow_cm > 0` 그리고 (`temp_c ≤ 1.0` 또는 `surface_c ≤ 0.0`). 낮음 = (`past24_precip_mm ≥ 1.0` 그리고 `temp_c ≤ 3.0`) 또는 (`temp_c ≤ 2.0` 그리고 `spread_c ≤ 1.0`). 비교 대상이 `None`인 조건은 거짓이다.
  - 지점 합치기: 결빙은 나쁜 쪽, `precip_median`·`precip_max`·`snow_models`·`precip_models`·`gust_ms`는 큰 쪽, `apparent_c`·`temp_c`는 작은 쪽, `n_models`는 작은 쪽, `precip_min`은 작은 쪽
  - 기준 점수 = `clamp(100 - 감점합, 0, 100)`. 감점 항목은 `precip_steps`(`precip_median` 기준), 적설(1개 30, 2개 이상 60), 결빙(낮음 20, 높음 50), 추위(`apparent_c`, 없으면 `temp_c`. -5 이하 25, 0 이하 10), 돌풍(≥15 → 15)이다.
  - 신뢰도: `0 < snow_models < n_models` → 낮음, 아니면 `0 < precip_models < n_models` → 보통, 아니면 높음
  - 하한: 적설 ≥2 → (39, "snow"), 결빙 높음 → (39, "ice"), 결빙 낮음 → (59, "ice"), 적설 1 → (59, "snow"). 해당하는 것 중 가장 낮은 상한이고, 같으면 이 순서에서 앞선 것이다.

- [ ] **Step 2: 공용 테스트 케이스 작성** — 형식: `{"name", "points": {"home": {모델: ModelWindow}, "work": {...}}, "expected": {"ice_risk", "rule_score", "confidence", "floor_cap", "floor_label", "final_score", "grade"}}`. `final_score`는 `apply_floor(rule_score, ...)` 결과다. 모든 창은 2시간(값 2개)이다.

  | 파일 | 입력 요지 | expected |
  |---|---|---|
  | a_dry_mild | 4모델, 기온 5, 체감 3, 이슬점 0, 강수 0, 적설 0, 돌풍 5, 지표 6, 과거 0 | 없음, 100, 높음, null, null, 100, 좋음 |
  | b_light_rain_cold | 5모델(kma 포함), 창 최대 강수 [0.5, 0.8, 0.0, 0.3, 0.5], 체감 -1, 기온 4, 이슬점 0, 과거 0 | 없음, 80, 보통, null, null, 80, 좋음 |
  | c_ice_after_snow | home은 a와 같음. work는 4모델, past48 적설 2.0, 기온 0.5, 체감 -3, 강수 0 | 높음, 40, 높음, 39, "ice", 39, 타지 않음 |
  | d_snow_two_models | 5모델, ICON 적설 0.3·KMA `pty` 3, 나머지 적설 0, 강수 중앙값 0.5, 기온 -1, 체감 -6, 이슬점 -2 | 낮음, 0, 낮음, 39, "snow", 0, 타지 않음 |

  표에 적지 않은 값은 a와 같다(돌풍 5, 지표 6, 이슬점 0 등). 기상청 모델은 `dewpoint`·`apparent`·`surface_temp`·`gust`·과거값이 `null`이다.

  d의 `final_score`가 0인 이유: 하한은 점수가 상한보다 높을 때만 적용하므로 `floor_applied`는 `null`이 된다(Task 6이 그대로 게시).

- [ ] **Step 3: 실패하는 테스트 작성**

```python
VEC = ROOT / "rules" / "test_vectors"

def test_vectors(self):
    rules = load_rules()
    for p in sorted(VEC.glob("*.json")):
        with self.subTest(p.name):
            v = json.loads(p.read_text(encoding="utf-8"))
            r = evaluate_slot(v["points"], rules)
            e = v["expected"]
            self.assertEqual((r["ice_risk"], r["rule_score"], r["confidence"], r["floor_cap"], r["floor_label"]),
                             (e["ice_risk"], e["rule_score"], e["confidence"], e["floor_cap"], e["floor_label"]))
            final, _ = apply_floor(r["rule_score"], r["floor_cap"], r["floor_label"])
            self.assertEqual((final, grade(final, rules)), (e["final_score"], e["grade"]))

def test_missing_variables(self):
    w = {"temp": [None, None], "apparent": [None, None], "dewpoint": [None, None], "precip": [None, None],
         "snowfall": [None, None], "gust": [None, None], "surface_temp": [None, None], "pty": [None, None],
         "past24_precip": None, "past48_snowfall": None}
    ok = dict(w, temp=[3.0, 2.0], precip=[0.0, 0.0], snowfall=[0.0, 0.0])
    pf = point_features({"jma": ok, "dead": w}, load_rules())
    self.assertEqual(pf["n_models"], 1)
    self.assertIsNone(pf["gust_ms"]); self.assertIsNone(pf["surface_c"])
    self.assertEqual(ice_risk(pf, load_rules()), "없음")

def test_apply_floor(self):
    self.assertEqual(apply_floor(72, 59, "ice"), (59, "ice"))
    self.assertEqual(apply_floor(30, 39, "snow"), (30, None))
    self.assertEqual(apply_floor(72, None, None), (72, None))
```

- [ ] **Step 4:** 실행 → FAIL
- [ ] **Step 5:** `engine.py` 구현
- [ ] **Step 6:** 실행 → PASS. 케이스 기대값이 계산과 다르면 표의 입력·기대값을 다시 계산해서 맞춘다. 규칙은 바꾸지 않는다.
- [ ] **Step 7: 커밋** `feat: 규칙 엔진(결빙·기준 점수·안전 하한)과 공용 테스트 케이스`

### Task 5: Claude 입력과 출력 검증

**Files:**
- Create: `pipeline/bri/claude_io.py`, `pipeline/prompt.md`
- Test: `pipeline/tests/test_claude_io.py`

**Interfaces:**
- Consumes: `evaluate_slot` 결과 dict (Task 4)
- Produces:
  - `build_claude_input(date: str, run: str, slots: list[dict], rules: dict) -> dict` — `slots` 원소: `{"id", "time", "eval": evaluate_slot 결과, "hourly": {지점: {모델: {"hours": [...], "temp": [...], "precip": [...], "snowfall": [...], "pty": [...]}}}}`. 반환 dict에는 `date, run, rubric(rules의 score·ice·floors·grades·claude), slots[]`가 들어가고, 좌표는 들어가지 않는다.
  - `validate_claude_output(out: object, slot_ids: list[str], rule_scores: dict[str, int], rules: dict) -> dict[str, dict] | None` — 유효하면 `{id: {"score", "confidence", "summary", "reasons", "deviation_reason"}}`, 아니면 `None`

- [ ] **Step 1: 검증 규칙** — 최상위 `{"slots": [...]}`. 각 원소에 `id`(str), `score`(bool이 아닌 int, 0~100), `confidence`("높음"·"보통"·"낮음"), `summary`(1~60자 str), `reasons`(str 1~4개), `deviation_reason`(str 또는 null)가 있다. 다른 키는 허용하지 않는다. `id` 집합이 `slot_ids`와 정확히 같아야 한다. `|score - rule_score| > 15`이면 `deviation_reason`이 비어 있지 않은 str이어야 한다. 하나라도 어긋나면 `None`.

- [ ] **Step 2: 실패하는 테스트 작성**

```python
GOOD = {"slots": [{"id": "commute_in", "score": 70, "confidence": "보통", "summary": "약한 비",
                   "reasons": ["3개 모델 약한 비"], "deviation_reason": None}]}

def test_valid(self):
    r = validate_claude_output(GOOD, ["commute_in"], {"commute_in": 75}, load_rules())
    self.assertEqual(r["commute_in"]["score"], 70)

def test_invalid_outputs_rejected(self):
    bad = [
        {"slots": [dict(GOOD["slots"][0], score="70")]},
        {"slots": [dict(GOOD["slots"][0], score=True)]},
        {"slots": []},
        {"slots": [GOOD["slots"][0], dict(GOOD["slots"][0], id="extra")]},
        {"slots": [dict(GOOD["slots"][0], score=50)]},                 # 25점 차이, 이유 없음
        {"slots": [dict(GOOD["slots"][0], extra_key=1)]},
        "not json object",
    ]
    for b in bad:
        with self.subTest(b=b):
            self.assertIsNone(validate_claude_output(b, ["commute_in"], {"commute_in": 75}, load_rules()))

def test_input_has_no_coordinates(self):
    s = json.dumps(build_claude_input("2026-11-24", "am", [SAMPLE_SLOT], load_rules()), ensure_ascii=False)
    for word in ("lat", "lon", "nx", "ny"):
        self.assertNotIn(f'"{word}"', s)
```

- [ ] **Step 3:** 실행 → FAIL
- [ ] **Step 4:** 구현
- [ ] **Step 5:** 실행 → PASS
- [ ] **Step 6: `pipeline/prompt.md` 작성** — 들어갈 내용:
  1. 역할: `work/claude_input.json`을 Read로 읽고 `work/claude_output.json` 하나만 Write로 만든다. 다른 파일은 건드리지 않고 git 명령은 쓰지 않는다.
  2. 사용자 기준(설계 1절 그대로): 비는 강수량에 따라, 눈은 피함, 눈 뒤 1~2일 결빙 주의.
  3. `rubric`의 기준표대로 판단하고, `rule_score`를 참고값으로 쓴다. 15점 넘게 다르면 `deviation_reason`에 이유를 적는다.
  4. 결빙 위험도와 안전 하한은 코드가 다시 적용하므로 점수에 반영하되 바꾸려 하지 않는다.
  5. `summary`는 한국어 60자 이내 한 줄, `reasons`는 1~4개 한국어 문장이다. 사실만 적는다.
  6. Step 1의 출력 스키마(추가 키 금지)
- [ ] **Step 7: 커밋** `feat: Claude 입력 작성과 출력 검증, 지시문`

### Task 6: 실행 판정·게시와 CLI

**Files:**
- Create: `pipeline/bri/publish.py`, `pipeline/bri/__main__.py`, `docs/.nojekyll`
- Test: `pipeline/tests/test_publish.py`, `pipeline/tests/test_cli.py`

**Interfaces:**
- Consumes: Task 2~5 전부
- Produces:
  - `kst_now() -> datetime`, `resolve_run(now_kst: datetime, override: str | None) -> str` — 12시 이전 `"am"`, 이후 `"pm"`
  - `should_skip(docs: Path, date: str, run: str, now_kst: datetime, rules: dict, force: bool) -> str | None` — 건너뛸 이유(주말·이미 게시) 또는 `None`
  - `assemble_latest(date, run, generated_at, slot_results: list[dict], previous: dict | None, source: str, models_used: list[str]) -> dict` — 설계 5.6 스키마. 각 슬롯에 `weather_by_model`(`{지점: {모델: {"hours", "temp", "precip", "snowfall"}}}`)을 더한다. pm 실행이면 `previous`의 `commute_in`을 날짜가 같을 때만 이어 붙인다.
  - CLI: `python -m bri prepare --work work [--run am|pm] [--force]`, `python -m bri finalize --work work --docs docs`
    - prepare: 환경변수(좌표 4개, `KMA_SERVICE_KEY`)를 읽는다. `should_skip`이면 `$GITHUB_OUTPUT`에 `skip=true`를 쓰고 0으로 끝난다. 아니면 조회(Open-Meteo 실패 시 0이 아닌 코드)와 평가를 거쳐 `work/state.json`, `work/claude_input.json`을 쓰고 `skip=false`.
    - finalize: `work/claude_output.json`이 있으면 검증한다. 유효하면 Claude 점수, 아니면 기준 점수를 쓴다. 이어서 `apply_floor`와 `grade`를 적용한다. 결과를 `docs/latest.json`, `docs/history/<date>-<run>.json`에 쓰고 `rules/rules.json`을 `docs/rules.json`으로 복사한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
def test_resolve_run_and_kst_date(self):
    utc = datetime(2026, 11, 22, 21, 30, tzinfo=timezone.utc)          # 일요일 21:30 UTC
    k = utc.astimezone(ZoneInfo("Asia/Seoul"))
    self.assertEqual((k.date().isoformat(), resolve_run(k, None)), ("2026-11-23", "am"))

def test_skip_weekend_and_duplicate(self):
    sat = datetime(2026, 11, 28, 6, 30)
    self.assertIsNotNone(should_skip(self.docs, "2026-11-28", "am", sat, load_rules(), False))
    (self.docs / "history").mkdir(parents=True); (self.docs / "history" / "2026-11-24-am.json").write_text("{}")
    self.assertIsNotNone(should_skip(self.docs, "2026-11-24", "am", datetime(2026,11,24,6,30), load_rules(), False))
    self.assertIsNone(should_skip(self.docs, "2026-11-24", "am", datetime(2026,11,24,6,30), load_rules(), True))

def test_pm_without_today_am(self):
    prev = {"slots": [{"id": "commute_in", "date": "2026-11-23"}]}
    out = assemble_latest("2026-11-24", "pm", "2026-11-24T17:03:00+09:00", [PM_SLOT], prev, "claude", MODELS)
    self.assertEqual([s["id"] for s in out["slots"]], ["commute_out"])

def test_pm_carries_today_am(self):
    prev = {"slots": [dict(AM_SLOT, date="2026-11-24")]}
    out = assemble_latest("2026-11-24", "pm", "2026-11-24T17:03:00+09:00", [PM_SLOT], prev, "claude", MODELS)
    self.assertEqual([s["id"] for s in out["slots"]], ["commute_in", "commute_out"])

def test_latest_has_no_coordinates(self):
    s = json.dumps(assemble_latest(...), ensure_ascii=False)
    self.assertNotIn("lat", s); self.assertNotIn("lon", s)
```

`test_cli.py`는 `bri.openmeteo.fetch_point`·`bri.kma.fetch_point`를 `unittest.mock.patch`로 바꿔 끼운다.

```python
def test_prepare_fails_without_touching_docs(self):
    with patch("bri.openmeteo.fetch_point", side_effect=OpenMeteoError("timeout")):
        rc = main(["prepare", "--work", str(self.work), "--run", "am", "--force"])
    self.assertNotEqual(rc, 0)
    self.assertFalse((self.docs / "latest.json").exists())

def test_finalize_falls_back_to_rule(self):
    # prepare 성공(모킹) 후 claude_output.json 없이 finalize
    ...
    latest = json.loads((self.docs / "latest.json").read_text(encoding="utf-8"))
    self.assertEqual(latest["source"], "rule")
    self.assertEqual(latest["slots"][0]["score"], latest["slots"][0]["rule_score"])  # 하한 미적용 케이스

def test_kma_failure_drops_source(self):
    with patch("bri.kma.fetch_point", return_value=None): ...
    self.assertNotIn("kma_vilage", latest["models_used"])
```

- [ ] **Step 2:** 실행 → FAIL
- [ ] **Step 3:** 구현. 게시 슬롯 필드는 설계 5.6절 그대로이고 `weather_by_model`만 추가한다. `weather`의 값: `temp_c`=`features.temp_c`, `apparent_c`, `precip_mm_h`={`min`: `precip_min`, `max`: `precip_max`}, `snow_models`, `ice_risk`, `gust_ms`. `floor_applied`는 `apply_floor`가 돌려준 라벨이다.
- [ ] **Step 4:** 실행 → PASS
- [ ] **Step 5: 샘플 생성** — finalize 결과 하나를 `docs/sample/latest.sample.json`으로 저장한다. Task 9 테스트가 이 파일을 쓴다.
- [ ] **Step 6: 커밋** `feat: 실행 판정·멱등 게시와 prepare/finalize CLI`

### Task 7: 워크플로와 첫 실행

**Files:**
- Create: `.github/workflows/ride-index.yml`

**Interfaces:**
- Consumes: CLI (Task 6), `pipeline/prompt.md` (Task 5)

- [ ] **Step 1: 워크플로 작성** — 봄봄 `daily-menu.yml`과 같은 틀이다.
  - `on`: 두 cron(Global Constraints), `workflow_dispatch`(입력 `run`: am/pm/auto, `force`: bool), `pull_request`(드라이런)
  - `permissions: contents: write, id-token: write`, `concurrency: ride-index`
  - 단계: checkout → setup-python 3.12 → `cd pipeline && python -m unittest discover -s tests` → `python -m bri prepare`(id: prep, secrets를 env로 전달) → `if: steps.prep.outputs.skip == 'false'`일 때 `anthropics/claude-code-action@v1`(`continue-on-error: true`, `claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}`, `github_token: ${{ secrets.GITHUB_TOKEN }}`, `claude_args: --allowedTools "Read,Write,Glob,Grep,TodoWrite" --max-turns 15`, `prompt: "pipeline/prompt.md를 Read로 읽고 그대로 따르라."`) → `python -m bri finalize` → 커밋·push(`github-actions[bot]`, 메시지 `index: <date> <run>`)
  - `pull_request`일 때는 finalize 출력 폴더를 `work/docs-dry`로 바꾸고 커밋하지 않는다. 결과 JSON을 `$GITHUB_STEP_SUMMARY`에 붙인다.
- [ ] **Step 2: Secrets 등록** — 사용자에게 요청한다. 토큰과 키는 에이전트가 입력하지 않는다.
  - `claude setup-token`으로 구독 OAuth 토큰을 만든 뒤 `gh secret set CLAUDE_CODE_OAUTH_TOKEN`
  - `gh secret set KMA_SERVICE_KEY`(디코딩 값)
  - 좌표 4개는 앱 완성 전이면 지도에서 읽은 값으로 임시 등록하고, Task 14 이후 앱에서 등록한 값으로 바꾼다.
- [ ] **Step 3: PR 드라이런** — `feat/mvp`를 push하고 PR을 연다. Actions 요약에서 세 가지를 확인한다. (1) 테스트 통과 (2) `models_used`에 `kma_vilage` 포함 여부. 빠졌으면 해외 IP 차단 가능성을 사용자에게 알리고 설계 9절대로 4개 모델로 운영한다. (3) `source: "claude"`
- [ ] **Step 4: Pages 설정** — 머지 뒤 `gh api -X POST repos/lepela/bike-ride-index/pages -f "source[branch]=main" -f "source[path]=/docs"`
- [ ] **Step 5: 실제 실행** — `gh workflow run ride-index.yml -f run=am -f force=true` 후 `curl -s https://lepela.github.io/bike-ride-index/latest.json`이 오늘 날짜 슬롯 두 개를 주는지 확인한다.
- [ ] **Step 6: 커밋** `ci: 주행 지수 워크플로 추가`

### Task 8: 클라우드 루틴 이중화

- [ ] **Step 1:** 만들 루틴 내용을 사용자에게 보여 주고 승인을 받는다. 이름은 「바이크 지수 트리거 (평일 06:50 / 17:20 KST)」, cron은 `50 21 * * 0-4`와 `20 8 * * 1-5`다. 루틴 하나에 cron이 하나라면 루틴 두 개로 나눈다. 프롬프트는 봄봄 루틴 형식을 따르되 `lepela/bike-ride-index`의 `ride-index.yml`을 `ref: main`으로 한 번 실행하고, 90초 뒤 `latest.json`의 `generated_at`이 오늘인지 확인한다. 셸 명령은 최대 6회로 제한한다.
- [ ] **Step 2:** 승인 후 `RemoteTrigger create`로 만든다. 응답의 실행 시각과 URL을 사용자에게 전달한다.
- [ ] **Step 3:** `RemoteTrigger run`으로 한 번 돌린다. `list_runs`에서 성공을 확인한다. 이미 게시돼 있으면 워크플로가 `skip=true`로 끝나는 것도 확인한다.

## Part B — 안드로이드 앱

### Task 9: 프로젝트 골격과 게시 JSON 모델

**Files:**
- Create: `android/` Gradle 프로젝트(Kotlin DSL, 버전 카탈로그), `app/src/main/java/dev/lepela/rideindex/model/Published.kt`
- Test: `app/src/test/java/dev/lepela/rideindex/model/PublishedTest.kt`

**Interfaces:**
- Produces: `@Serializable data class Published(generatedAt, run, source, modelsUsed: List<String>, slots: List<PublishedSlot>)`, `PublishedSlot(id, date, time, score: Int, grade, confidence, summary, reasons: List<String>, ruleScore: Int, floorApplied: String?, weather: SlotWeather, weatherByModel: Map<String, Map<String, ModelHours>> = emptyMap())`. JSON 키는 `@SerialName`으로 snake_case에 맞춘다. `fun parsePublished(text: String): Published` (`ignoreUnknownKeys = true`)

- [ ] **Step 1: 골격 생성** — Empty Compose Activity 구성을 손으로 만든다. Gradle wrapper는 services.gradle.org의 공식 배포판으로 `gradle wrapper`를 실행해 만든다. 빌드 전에 `JAVA_HOME`과 `ANDROID_HOME`을 Global Constraints의 경로로 설정한다. `app/build.gradle.kts`에 `sourceSets["test"].resources.srcDirs("../../rules", "../../docs/sample")`를 넣어 공용 케이스와 샘플을 테스트 리소스로 쓴다.
- [ ] **Step 2: 실패하는 테스트**

```kotlin
@Test fun parsesSample() {
    val p = parsePublished(javaClass.classLoader!!.getResource("latest.sample.json")!!.readText())
    assertTrue(p.slots.isNotEmpty())
    assertTrue(p.slots.all { it.score in 0..100 })
}
@Test fun ignoresUnknownKeys() {
    val p = parsePublished("""{"generated_at":"x","run":"am","source":"rule","models_used":[],"slots":[],"new_key":1}""")
    assertEquals("rule", p.source)
}
```

- [ ] **Step 3:** `cd android && ./gradlew.bat :app:testDebugUnitTest` → FAIL
- [ ] **Step 4:** 구현
- [ ] **Step 5:** 같은 명령 → PASS, `./gradlew.bat :app:assembleDebug` 성공
- [ ] **Step 6:** `AGENTS.md`에 안드로이드 명령과 환경변수를 적는다.
- [ ] **Step 7: 커밋** `feat(android): 프로젝트 골격과 게시 JSON 모델`

### Task 10: Kotlin 규칙 엔진

**Files:**
- Create: `engine/RuleEngine.kt`, `engine/Rules.kt`
- Test: `engine/RuleEngineVectorsTest.kt`

**Interfaces:**
- Produces: `fun loadRules(json: String): Rules`. `object RuleEngine`의 함수는 `pointFeatures(models: Map<String, ModelWindow>, rules): PointFeatures`, `iceRisk(pf, rules): String`, `evaluateSlot(points: Map<String, Map<String, ModelWindow>>, rules): SlotEval`, `applyFloor(score: Int, cap: Int?, label: String?): Pair<Int, String?>`, `grade(score, rules): String`이다. 의미는 Task 4와 같고, `SlotEval` 필드는 Task 4 반환 키의 camelCase다.

- [ ] **Step 1: 실패하는 테스트** — `test_vectors/*.json`을 모두 읽어 Task 4 `test_vectors`와 같은 다섯 값과 `final_score`·`grade`를 비교한다. `test_missing_variables`도 같은 입력으로 옮긴다.
- [ ] **Step 2:** `./gradlew.bat :app:testDebugUnitTest --tests "*RuleEngine*"` → FAIL
- [ ] **Step 3:** Task 4 Step 1의 계산 규칙대로 구현한다(중앙값, `None`/`null` 처리, 비교 부등호 동일).
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5: 커밋** `feat(android): 규칙 엔진 포팅과 공용 케이스 통과`

### Task 11: 데이터 계층과 기기 계산

**Files:**
- Create: `data/PagesClient.kt`, `data/OpenMeteoClient.kt`, `data/SettingsStore.kt`, `data/DeviceCalculator.kt`
- Test: `data/OpenMeteoParseTest.kt`, `data/DeviceCalculatorTest.kt`

**Interfaces:**
- Consumes: `RuleEngine`, `Published` (Task 9·10)
- Produces:
  - `class PagesClient(baseUrl: String) { suspend fun latest(): Published?; suspend fun rules(): String? }` — 실패 시 `null`
  - `object OpenMeteo { fun buildUrl(lat, lon, models): String; fun parse(body: String, models): List<ModelSeries> }` — 형식은 Task 2와 같다(`wind_speed_unit=ms` 포함)
  - `class SettingsStore(context)` — DataStore 키: `pages_url`(기본 `https://lepela.github.io/bike-ride-index/`), `home_lat/lon`, `work_lat/lon`, `last_published`(JSON 원문), `last_device`(JSON 원문), `cached_rules`
  - `object DeviceCalculator { fun compute(today: LocalDate, home: LatLon, work: LatLon, series: Map<String, List<ModelSeries>>, rules: Rules): Published }` — `source = "device"`, 두 슬롯, `score = applyFloor(ruleScore)`, `summary`는 규칙 요약("강수 0.5mm/h · 결빙 없음" 형식)
- `rules.json`은 Pages에서 받아 캐시하고, 받기 전에는 APK에 동봉한 사본을 쓴다(`app/src/main/assets/rules.json`은 빌드 때 `../rules/rules.json`에서 복사).

- [ ] **Step 1: 실패하는 테스트** — `OpenMeteoParseTest`는 Task 2의 `openmeteo_seoul.json`을 테스트 리소스로 읽는다. 모델 4개, `times.size == 96`, JMA `gust` 전부 null을 확인한다. `DeviceCalculatorTest`는 a_dry_mild 입력으로 만든 시계열에서 두 슬롯 모두 `score == 100`, `source == "device"`임을 확인한다.
- [ ] **Step 2:** 실행 → FAIL
- [ ] **Step 3:** 구현. HTTP는 `HttpURLConnection`과 `Dispatchers.IO`를 쓴다.
- [ ] **Step 4:** 실행 → PASS
- [ ] **Step 5: 커밋** `feat(android): Pages·Open-Meteo 조회, 설정 저장, 기기 계산`

### Task 12: 표시값 선택

**Files:**
- Create: `display/DisplayResolver.kt`
- Test: `display/DisplayResolverTest.kt`

**Interfaces:**
- Produces: `data class DisplaySlot(id: String, time: String, score: Int?, grade: String?, summary: String, origin: Origin, stale: Boolean)`, `enum class Origin { CLAUDE, RULE, DEVICE, NONE }`, `data class WidgetDisplay(slots: List<DisplaySlot>, updatedAt: String?)`, `fun resolve(today: LocalDate, published: Published?, device: Published?, lastKnown: Published?): WidgetDisplay`

- [ ] **Step 1: 규칙(설계 6.2)** — 슬롯(`commute_in`, `commute_out`)마다 다음 순서로 고른다. (1) `published`에 그 슬롯이 있고 날짜가 오늘이면 그 값. `source`가 "claude"면 CLAUDE, "rule"이면 RULE. (2) 없으면 `device`의 오늘 슬롯, DEVICE. (3) 없으면 `lastKnown`의 같은 슬롯, `stale = true`. (4) 그것도 없으면 NONE, 점수 `null`.
- [ ] **Step 2: 실패하는 테스트**

```kotlin
@Test fun todayPublishedWins()      // published 오늘 → CLAUDE, stale false
@Test fun yesterdayUsesDevice()     // published 어제 + device 오늘 → DEVICE
@Test fun noDeviceUsesStale()       // published 어제 + device null → 어제 값, stale true
@Test fun test_missing_slot_uses_device() // published 오늘이지만 commute_out만 → commute_in은 DEVICE
@Test fun nothingGivesNone()        // 모두 null → NONE, score null
```

- [ ] **Step 3:** 실행 → FAIL
- [ ] **Step 4:** 구현
- [ ] **Step 5:** 실행 → PASS
- [ ] **Step 6: 커밋** `feat(android): 위젯 표시값 선택 규칙`

### Task 13: 위젯과 백그라운드 갱신

**Files:**
- Create: `widget/RideWidget.kt`, `widget/RideWidgetReceiver.kt`, `work/RefreshWorker.kt`, `work/RefreshScheduler.kt`, `res/xml/ride_widget_info.xml`
- Modify: `AndroidManifest.xml`(receiver, `INTERNET`)
- Test: `work/RefreshSchedulerTest.kt`

**Interfaces:**
- Consumes: `PagesClient`, `SettingsStore`, `DeviceCalculator`, `DisplayResolver`
- Produces: `object RefreshScheduler { fun nextRun(now: LocalDateTime): LocalDateTime; fun scheduleAll(context) }`. `RefreshWorker`는 published를 받아 `last_published`에 저장한다. 오늘 슬롯이 빠졌고 좌표가 있으면 기기 계산을 해서 `last_device`에 저장한다. 끝나면 `RideWidget().updateAll(context)`를 부르고 다음 1회성 작업을 예약한다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun nextRun() {
    assertEquals(LocalDateTime.of(2026,11,24,7,2), RefreshScheduler.nextRun(LocalDateTime.of(2026,11,24,6,0)))
    assertEquals(LocalDateTime.of(2026,11,24,17,32), RefreshScheduler.nextRun(LocalDateTime.of(2026,11,24,7,2)))
    assertEquals(LocalDateTime.of(2026,11,25,7,2), RefreshScheduler.nextRun(LocalDateTime.of(2026,11,24,17,32)))
}
```

- [ ] **Step 2:** 실행 → FAIL
- [ ] **Step 3:** 구현. 1회성 작업은 07:02·17:32에 unique 이름 `refresh-once`(REPLACE)로 건다. 주기 작업은 1시간 간격, unique 이름 `refresh-periodic`(KEEP)이고, 네트워크 연결을 조건으로 한다. 위젯 탭은 `actionRunCallback`으로 즉시 갱신한다. 위젯 배치는 설계 6.1 그림을 따른다. 점수·등급 색(초록·노랑·주황·빨강), `stale`이면 "어제 값", 출처 라벨(Claude·규칙·자체 계산)을 표시하고, 출근 시각이 지나면 출근 칸을 흐리게 한다.
- [ ] **Step 4:** 실행 → PASS, `assembleDebug` 성공
- [ ] **Step 5: 커밋** `feat(android): 홈 위젯과 출퇴근 전 자동 갱신`

### Task 14: 앱 화면과 집·회사 등록

**Files:**
- Create: `ui/MainActivity.kt`, `ui/MainScreen.kt`, `ui/SettingsScreen.kt`
- Modify: `AndroidManifest.xml`(`ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`)

**Interfaces:**
- Consumes: `SettingsStore`, `Published`, `RefreshScheduler`

- [ ] **Step 1:** MainScreen을 만든다. 슬롯 카드에는 점수, 등급, 신뢰도, `reasons`, 기준 점수와의 차이, `floor_applied`를 넣는다. 모델별 표는 `weatherByModel`의 시간별 강수·적설·기온을 지점별로 보여 준다.
- [ ] **Step 2:** SettingsScreen을 만든다. Pages 주소 입력, 「지금 위치를 집으로 등록」·「지금 위치를 회사로 등록」 버튼을 둔다. 버튼은 권한을 요청한 뒤 `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY)`로 위치를 받는다. 등록된 좌표는 소수 넷째 자리까지 보여 주고, 복사 버튼은 `HOME_LAT=..` 형식 네 줄을 클립보드에 넣는다.
- [ ] **Step 3:** 앱을 처음 시작할 때 `RefreshScheduler.scheduleAll`을 호출한다.
- [ ] **Step 4: 확인** — `assembleDebug` 후 실기기에 설치한다(`adb install -r app/build/outputs/apk/debug/app-debug.apk`). 위젯을 홈에 추가하고, 집 등록 후 좌표 복사, 위젯 탭 갱신을 실제로 해 본다. 확인한 것과 못 한 것을 기록한다.
- [ ] **Step 5:** 사용자가 앱에서 복사한 좌표로 Secrets 4개를 바꾸고 `gh workflow run ride-index.yml -f run=auto -f force=true`로 다시 게시한다.
- [ ] **Step 6: 커밋** `feat(android): 상세 화면과 GPS 집·회사 등록`

### Task 15: 마무리와 리뷰 요청

- [ ] **Step 1:** `cd pipeline && python -m unittest discover -s tests`와 `cd android && ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`가 모두 통과하는지 확인한다.
- [ ] **Step 2:** `git grep -nE "3[3-8]\.[0-9]{3,}|12[5-9]\.[0-9]{3,}" -- docs pipeline/tests rules` 결과에 서울시청 테스트 좌표 외의 좌표가 없는지 확인한다(실패 조건 3).
- [ ] **Step 3:** PR 설명에 리뷰 요청 메모를 쓴다. 메모에는 바꾼 것, 직접 실행해서 확인한 것(명령과 결과), 확인하지 못한 것(예: 해외 IP에서의 기상청 응답 안정성, cron 지연 폭, 실기기 위젯 갱신 주기)을 적는다.
- [ ] **Step 4:** 사용자에게 다른 회사 모델(Codex 또는 Kimi)의 리뷰를 요청하고, 리뷰가 끝나기 전에는 `feat/mvp`를 고치지 않는다.
