"""Open-Meteo 다중 모델 예보 조회와 파싱."""
import json
import time
import urllib.parse
import urllib.request
from datetime import datetime

from .series import ModelSeries

BASE_URL = "https://api.open-meteo.com/v1/forecast"

# 응답 변수 이름 → 공용 이름
VAR_MAP = {
    "temperature_2m": "temp",
    "apparent_temperature": "apparent",
    "dew_point_2m": "dewpoint",
    "precipitation": "precip",
    "snowfall": "snowfall",
    "precipitation_probability": "pop",
    "wind_gusts_10m": "gust",
    "soil_temperature_0cm": "surface_temp",
}


class OpenMeteoError(RuntimeError):
    pass


def build_url(lat: float, lon: float, models: list[str]) -> str:
    params = {
        "latitude": f"{lat:.4f}",
        "longitude": f"{lon:.4f}",
        "hourly": ",".join(VAR_MAP),
        "models": ",".join(models),
        "past_days": 2,
        "forecast_days": 2,
        "timezone": "Asia/Seoul",
        "wind_speed_unit": "ms",
    }
    return BASE_URL + "?" + urllib.parse.urlencode(params)


def parse_response(body: dict, models: list[str]) -> list[ModelSeries]:
    hourly = body["hourly"]
    times = [datetime.fromisoformat(t) for t in hourly["time"]]
    out = []
    for model in models:
        values = {}
        for src, name in VAR_MAP.items():
            key = f"{src}_{model}"
            vals = hourly.get(key)
            values[name] = list(vals) if vals is not None else [None] * len(times)
        if all(v is None for v in values["temp"]):
            continue
        out.append(ModelSeries(model, times, values))
    return out


def fetch_point(lat: float, lon: float, models: list[str], timeout: int = 30) -> list[ModelSeries]:
    url = build_url(lat, lon, models)
    last_err = None
    for attempt in range(2):
        try:
            with urllib.request.urlopen(url, timeout=timeout) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            if "hourly" not in body:
                raise OpenMeteoError(f"응답에 hourly 없음: {body.get('reason', '')}")
            return parse_response(body, models)
        except Exception as e:  # 네트워크·파싱 오류 모두 1회 재시도
            last_err = e
            if attempt == 0:
                time.sleep(5)
    raise OpenMeteoError(str(last_err))
