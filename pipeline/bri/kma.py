"""기상청 단기예보(getVilageFcst) 조회와 파싱.

실패(키 오류, 해외 IP 차단, 시간 초과, XML 오류 응답)는 예외 대신 None을 돌려준다.
호출하는 쪽은 이 출처만 빼고 계속 진행한다.
"""
import json
import math
import re
import urllib.parse
import urllib.request
from datetime import datetime, timedelta

from .series import ModelSeries

BASE_URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst"
RELEASE_HOURS = (2, 5, 8, 11, 14, 17, 20, 23)

# 카테고리 → 공용 이름
CATEGORY_MAP = {"TMP": "temp", "PCP": "precip", "SNO": "snowfall", "PTY": "pty", "POP": "pop", "WSD": "wind"}
# 이 출처가 주지 않는 변수
MISSING_VARS = ("dewpoint", "apparent", "surface_temp", "gust")


def latlon_to_grid(lat: float, lon: float) -> tuple[int, int]:
    """기상청 동네예보 람베르트 정각원추도법 격자 변환."""
    re_km, grid = 6371.00877, 5.0
    slat1, slat2, olon, olat = 30.0, 60.0, 126.0, 38.0
    xo, yo = 43, 136
    deg = math.pi / 180.0
    re_ = re_km / grid
    s1, s2 = slat1 * deg, slat2 * deg
    o_lon, o_lat = olon * deg, olat * deg
    sn = math.tan(math.pi * 0.25 + s2 * 0.5) / math.tan(math.pi * 0.25 + s1 * 0.5)
    sn = math.log(math.cos(s1) / math.cos(s2)) / math.log(sn)
    sf = math.tan(math.pi * 0.25 + s1 * 0.5)
    sf = math.pow(sf, sn) * math.cos(s1) / sn
    ro = math.tan(math.pi * 0.25 + o_lat * 0.5)
    ro = re_ * sf / math.pow(ro, sn)
    ra = math.tan(math.pi * 0.25 + lat * deg * 0.5)
    ra = re_ * sf / math.pow(ra, sn)
    theta = lon * deg - o_lon
    if theta > math.pi:
        theta -= 2.0 * math.pi
    if theta < -math.pi:
        theta += 2.0 * math.pi
    theta *= sn
    x = int(ra * math.sin(theta) + xo + 0.5)
    y = int(ro - ra * math.cos(theta) + yo + 0.5)
    return x, y


def pick_base(now_kst: datetime) -> tuple[str, str]:
    """발표 10분 뒤부터 조회된다. now 이하인 가장 최근 발표분."""
    for days_back in (0, 1):
        day = (now_kst - timedelta(days=days_back)).date()
        for h in reversed(RELEASE_HOURS):
            available = datetime(day.year, day.month, day.day, h, 10)
            if available <= now_kst.replace(tzinfo=None):
                return day.strftime("%Y%m%d"), f"{h:02d}00"
    raise ValueError("발표 시각을 찾지 못함")


def _first_number(s: str) -> float:
    return float(re.findall(r"\d+(?:\.\d+)?", s)[0])


def parse_pcp(s: str) -> float:
    s = s.strip()
    if s in ("강수없음", "-", "0"):
        return 0.0
    if "미만" in s:
        return 0.5
    if "~" in s:
        a, b = s.replace("mm", "").split("~")
        return (float(a) + float(b)) / 2
    return _first_number(s)


def parse_sno(s: str) -> float:
    s = s.strip()
    if s in ("적설없음", "-", "0"):
        return 0.0
    if "미만" in s:
        return 0.5
    if "~" in s:
        a, b = s.replace("cm", "").split("~")
        return (float(a) + float(b)) / 2
    return _first_number(s)


def _to_number(category: str, value: str) -> float | None:
    try:
        if category == "PCP":
            return parse_pcp(value)
        if category == "SNO":
            return parse_sno(value)
        return float(value)
    except (ValueError, IndexError):
        return None


def parse_items(body: str, model_name: str) -> ModelSeries | None:
    try:
        data = json.loads(body)
        resp = data["response"]
        if resp["header"]["resultCode"] != "00":
            return None
        items = resp["body"]["items"]["item"]
    except (ValueError, KeyError, TypeError):
        return None

    by_time: dict[datetime, dict[str, float | None]] = {}
    for it in items:
        name = CATEGORY_MAP.get(it.get("category"))
        if name is None:
            continue
        t = datetime.strptime(it["fcstDate"] + it["fcstTime"], "%Y%m%d%H%M")
        by_time.setdefault(t, {})[name] = _to_number(it["category"], str(it["fcstValue"]))
    if not by_time:
        return None

    times = sorted(by_time)
    values = {name: [by_time[t].get(name) for t in times] for name in CATEGORY_MAP.values()}
    for name in MISSING_VARS:
        values[name] = [None] * len(times)
    return ModelSeries(model_name, times, values)


def build_url(service_key: str, base_date: str, base_time: str, nx: int, ny: int) -> str:
    query = urllib.parse.urlencode({
        "pageNo": 1, "numOfRows": 1000, "dataType": "JSON",
        "base_date": base_date, "base_time": base_time, "nx": nx, "ny": ny,
    })
    return f"{BASE_URL}?serviceKey={urllib.parse.quote(service_key, safe='')}&{query}"


def fetch_point(lat: float, lon: float, service_key: str, now_kst: datetime, model_name: str,
                timeout: int = 30) -> ModelSeries | None:
    if not service_key:
        return None
    nx, ny = latlon_to_grid(lat, lon)
    base_date, base_time = pick_base(now_kst)
    url = build_url(service_key, base_date, base_time, nx, ny)
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
    except Exception:
        return None
    return parse_items(body, model_name)
