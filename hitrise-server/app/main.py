from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import json
import math
from pathlib import Path
import re
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse

from .config import load_settings
from .db import get_conn
from .gamification import achievement_progress_rows, tier_for_level, tier_snapshot
from .models import (
    ActivateRequest,
    CheckRequest,
    DeviceReactivationRequest,
    LeaderboardRequest,
    ResetRequest,
    TrainingHistoryRequest,
    TrainingSessionCreateRequest,
    UserBootstrapRequest,
    UserProfileUpdateRequest,
    UserStatisticsRequest,
)
from .security import make_activation_token, normalize_code, normalize_serial, verify_code


app = FastAPI(title="HitRise Activation Service", version="1.2.0")

SUPPORTED_LANGUAGES = {"zh", "en", "fr", "th"}
SUPPORTED_WINDOWS = {"all", "day", "week", "month"}
MIN_MODE_SECONDS = 1
MAX_MODE_SECONDS = 600
SUPPORTED_LEADERBOARD_KEYS = {
    "total_training_seconds",
    "total_hits",
    "peak_force_n",
    "avg_force_n",
    "calories_burned",
    "fat_burned_grams",
    "best_30_hits",
    "best_60_hits",
    "longest_streak",
}
HEX_COLOR_RE = re.compile(r"^#[0-9A-Fa-f]{6}$")
DEFAULT_BODY_WEIGHT_KG = 70.0
BASE_BOXING_MET = 7.0
FORCE_REFERENCE_N = 800.0
MIN_DYNAMIC_MET = 4.0
MAX_DYNAMIC_MET = 10.5
KCAL_PER_FAT_GRAM = 7.7
SFX_MANIFEST_NAME = "manifest.json"
MUSIC_MANIFEST_NAME = "manifest.json"


@dataclass
class RequestContext:
    serial: str
    install_id: str
    device_hash: str
    activation_token: str
    app_version: str | None
    ip: str
    license_row: dict[str, Any] | None
    activation_row: dict[str, Any] | None
    user_row: dict[str, Any]


APP_USER_SELECT_COLUMNS = """
id, serial, nickname, language_code, country_code, avatar_color,
total_sessions_cached, total_hits_cached, total_calories_cached,
total_fat_burned_grams_cached, best_score_cached,
best_30_hits_cached, best_60_hits_cached, best_burst_cached,
longest_streak_cached, active_days_cached,
current_tier, highest_tier, tier_updated_at,
created_at, updated_at, last_seen_at
"""


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "").strip()
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else ""


def utc_from_epoch_ms(value: int | None) -> datetime | None:
    if value is None or value <= 0:
        return None
    return datetime.fromtimestamp(value / 1000.0, tz=timezone.utc).replace(tzinfo=None)


def decimal_to_float(value: Any, digits: int = 3) -> float:
    if value is None:
        return 0.0
    if isinstance(value, Decimal):
        return round(float(value), digits)
    return round(float(value), digits)


def clamp(value: float, low: float, high: float) -> float:
    return min(high, max(low, value))


def calories_for_training(total_hits: int, duration_seconds: int | float, avg_force_n: float = 0.0) -> float:
    safe_hits = max(0, int(total_hits))
    safe_duration_seconds = max(0.0, float(duration_seconds or 0.0))
    if safe_hits <= 0 or safe_duration_seconds <= 0.0:
        return 0.0
    minutes = safe_duration_seconds / 60.0
    punches_per_minute = safe_hits / max(minutes, 1.0 / 60.0)
    frequency_factor = clamp(punches_per_minute / 60.0, 0.50, 1.60)
    force_factor = clamp(math.sqrt(max(0.0, float(avg_force_n or 0.0)) / FORCE_REFERENCE_N), 0.70, 1.35)
    intensity = 0.70 * frequency_factor + 0.30 * force_factor
    dynamic_met = clamp(BASE_BOXING_MET * intensity, MIN_DYNAMIC_MET, MAX_DYNAMIC_MET)
    calories = dynamic_met * 3.5 * DEFAULT_BODY_WEIGHT_KG / 200.0 * minutes
    return round(calories, 3)


def fat_grams_for_calories(calories: float) -> float:
    if calories <= 0:
        return 0.0
    return round(calories / KCAL_PER_FAT_GRAM, 3)


def _number_from(payload: dict[str, Any], *keys: str, default: float = 0.0) -> float:
    for key in keys:
        if key in payload and payload[key] is not None:
            try:
                return float(payload[key])
            except (TypeError, ValueError):
                return default
    return default


def _int_from(payload: dict[str, Any], *keys: str, default: int = 0) -> int:
    return int(max(0, _number_from(payload, *keys, default=default)))


def parse_round_reports_json(raw: str | None, *, total_rounds: int, fallback_ended_at: datetime) -> list[dict[str, Any]]:
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail="round_reports_json must be a JSON array") from exc
    if not isinstance(parsed, list):
        raise HTTPException(status_code=400, detail="round_reports_json must be a JSON array")
    if len(parsed) > 100:
        raise HTTPException(status_code=400, detail="round_reports_json has too many rounds")

    reports: list[dict[str, Any]] = []
    for item in parsed:
        if not isinstance(item, dict):
            continue
        round_index = _int_from(item, "round_index", "roundIndex")
        if round_index <= 0:
            continue
        cumulative_hits = _int_from(item, "total_hits", "totalHits", "cumulative_hits", "cumulativeHits")
        cumulative_duration = _int_from(item, "duration_seconds", "durationSeconds", "cumulative_duration_seconds", "cumulativeDurationSeconds")
        avg_force_n = round(_number_from(item, "avg_force_n", "avgForceN"), 3)
        cumulative_calories = _number_from(item, "calories_burned", "caloriesBurned", "cumulative_calories_burned", "cumulativeCaloriesBurned")
        if cumulative_calories <= 0 and cumulative_hits > 0:
            cumulative_calories = calories_for_training(cumulative_hits, cumulative_duration, avg_force_n)
        cumulative_fat = _number_from(item, "fat_burned_grams", "fatBurnedGrams", "cumulative_fat_burned_grams", "cumulativeFatBurnedGrams")
        if cumulative_fat <= 0 and cumulative_calories > 0:
            cumulative_fat = fat_grams_for_calories(cumulative_calories)
        reports.append(
            {
                "round_index": round_index,
                "total_rounds": max(1, _int_from(item, "total_rounds", "totalRounds", default=total_rounds) or total_rounds),
                "cumulative_duration_seconds": cumulative_duration,
                "cumulative_hits": cumulative_hits,
                "cumulative_calories_burned": round(cumulative_calories, 3),
                "cumulative_fat_burned_grams": round(cumulative_fat, 3),
                "peak_force_n": round(_number_from(item, "peak_force_n", "peakForceN"), 3),
                "avg_force_n": avg_force_n,
                "avg_bpm": round(_number_from(item, "avg_bpm", "avgBpm"), 3),
                "rhythm_accuracy": round(min(1.0, max(0.0, _number_from(item, "rhythm_accuracy", "rhythmAccuracy"))), 4),
                "ended_at": utc_from_epoch_ms(_int_from(item, "ended_at_epoch_ms", "endedAtEpochMs")) or fallback_ended_at,
            }
        )
    return sorted(reports, key=lambda row: row["round_index"])


def clamp_language(value: str | None, fallback: str = "zh") -> str:
    normalized = (value or "").strip().lower()
    return normalized if normalized in SUPPORTED_LANGUAGES else fallback


def clamp_window(value: str | None) -> str:
    normalized = (value or "all").strip().lower()
    if normalized not in SUPPORTED_WINDOWS:
        raise HTTPException(status_code=400, detail="window must be one of: all, day, week, month")
    return normalized


def normalize_leaderboard_key(board_key: str | None, mode_seconds: int | None) -> str:
    normalized = (board_key or "").strip().lower()
    if normalized in SUPPORTED_LEADERBOARD_KEYS:
        return normalized
    if mode_seconds == 60:
        return "best_60_hits"
    if mode_seconds == 30:
        return "best_30_hits"
    return "total_training_seconds"


def clamp_avatar_color(value: str | None, fallback: str) -> str:
    normalized = (value or "").strip()
    if HEX_COLOR_RE.fullmatch(normalized):
        return normalized.upper()
    return fallback


def leaderboard_cutoff(window: str) -> datetime | None:
    now = utc_now()
    if window == "day":
        return now - timedelta(days=1)
    if window == "week":
        return now - timedelta(days=7)
    if window == "month":
        return now - timedelta(days=30)
    return None


def default_nickname(serial: str) -> str:
    return f"Player-{serial[-4:]}"


def avatar_color_for_serial(serial: str) -> str:
    colors = ["#145DA0", "#0E8F6A", "#A73A54", "#D97D00", "#5C3D99", "#2C6E49"]
    return colors[int(serial[-2:]) % len(colors)]


def masked_serial(serial: str) -> str:
    return serial if len(serial) <= 4 else "*******" + serial[-4:]


def write_log(
    conn,
    *,
    serial: str | None,
    install_id: str | None,
    device_hash: str | None,
    event_type: str,
    result: str,
    reason: str | None,
    ip: str | None,
) -> None:
    return


def blocked(reason: str, message: str, status_code: int = 403) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"status": "blocked", "reason": reason, "message": message},
    )


def serialize_profile(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "user_id": row["id"],
        "serial": row["serial"],
        "serial_masked": masked_serial(row["serial"]),
        "nickname": row["nickname"],
        "language_code": row["language_code"],
        "country_code": row["country_code"],
        "avatar_color": row["avatar_color"],
        "current_tier": int(row.get("current_tier") or 1),
        "highest_tier": int(row.get("highest_tier") or 1),
        "best_score_cached": int(row.get("best_score_cached") or 0),
        "best_30_hits_cached": int(row.get("best_30_hits_cached") or 0),
        "best_60_hits_cached": int(row.get("best_60_hits_cached") or 0),
        "best_burst_cached": int(row.get("best_burst_cached") or 0),
        "total_calories_cached": decimal_to_float(row.get("total_calories_cached")),
        "total_fat_burned_grams_cached": decimal_to_float(row.get("total_fat_burned_grams_cached")),
        "longest_streak_cached": int(row.get("longest_streak_cached") or 0),
        "active_days_cached": int(row.get("active_days_cached") or 0),
        "created_at": row["created_at"].isoformat() if row.get("created_at") else None,
        "last_seen_at": row["last_seen_at"].isoformat() if row.get("last_seen_at") else None,
    }


def serialize_statistics(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "total_sessions": int(row.get("total_sessions") or 0),
        "total_hits": int(row.get("total_hits") or 0),
        "best_30_hits": int(row.get("best_30_hits") or 0),
        "best_60_hits": int(row.get("best_60_hits") or 0),
        "average_30_frequency": decimal_to_float(row.get("average_30_frequency")),
        "average_60_frequency": decimal_to_float(row.get("average_60_frequency")),
        "personal_best_hits": int(row.get("personal_best_hits") or 0),
        "best_burst_record": int(row.get("best_burst_record") or 0),
        "best_average_frequency": decimal_to_float(row.get("best_average_frequency")),
        "total_training_seconds": int(row.get("total_training_seconds") or 0),
        "total_calories_burned": decimal_to_float(row.get("total_calories_burned")),
        "total_fat_burned_grams": decimal_to_float(row.get("total_fat_burned_grams")),
        "best_avg_bpm": decimal_to_float(row.get("best_avg_bpm")),
        "best_peak_force_n": decimal_to_float(row.get("best_peak_force_n")),
        "best_avg_force_n": decimal_to_float(row.get("best_avg_force_n")),
        "total_rounds": int(row.get("total_rounds") or 0),
        "best_round_hits": int(row.get("best_round_hits") or 0),
        "average_round_hits": decimal_to_float(row.get("average_round_hits")),
        "best_round_peak_force_n": decimal_to_float(row.get("best_round_peak_force_n")),
        "best_round_avg_force_n": decimal_to_float(row.get("best_round_avg_force_n")),
        "average_round_calories_burned": decimal_to_float(row.get("average_round_calories_burned")),
        "active_days": int(row.get("active_days") or 0),
        "current_streak": int(row.get("current_streak") or 0),
        "longest_streak": int(row.get("longest_streak") or 0),
    }


def serialize_tier(payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "level": int(payload["level"]),
        "key": payload["key"],
        "best_hits": int(payload["best_hits"]),
        "next_level": payload.get("next_level"),
        "next_key": payload.get("next_key"),
        "next_hits": payload.get("next_hits"),
        "progress_hits": int(payload.get("progress_hits") or 0),
        "progress_target_hits": int(payload.get("progress_target_hits") or 0),
    }


def serialize_achievement(payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "key": payload["key"],
        "metric": payload["metric"],
        "goal": int(payload["goal"]),
        "progress": int(payload["progress"]),
        "unlocked": bool(payload["unlocked"]),
        "unlocked_at": payload.get("unlocked_at").isoformat() if payload.get("unlocked_at") else None,
        "sort_order": int(payload.get("sort_order") or 0),
    }


def serialize_history_row(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "session_id": row["id"],
        "mode_seconds": int(row["mode_seconds"]),
        "total_hits": int(row["total_hits"]),
        "duration_seconds": int(row.get("duration_seconds") or row.get("mode_seconds") or 0),
        "average_frequency": decimal_to_float(row["average_frequency"]),
        "best_burst_count": int(row["best_burst_count"]),
        "best_burst_start_sec": decimal_to_float(row["best_burst_start_sec"]),
        "calories_burned": decimal_to_float(row.get("calories_burned")),
        "fat_burned_grams": decimal_to_float(row.get("fat_burned_grams")),
        "avg_bpm": decimal_to_float(row.get("avg_bpm")),
        "peak_force_n": decimal_to_float(row.get("peak_force_n")),
        "avg_force_n": decimal_to_float(row.get("avg_force_n")),
        "rhythm_accuracy": decimal_to_float(row.get("rhythm_accuracy"), digits=4),
        "combo_summary_json": row.get("combo_summary_json"),
        "beat_score_counts_json": row.get("beat_score_counts_json"),
        "round_config_json": row.get("round_config_json"),
        "play_mode": row.get("play_mode"),
        "sound_pack_id": row.get("sound_pack_id"),
        "started_at": row["started_at"].isoformat() if row.get("started_at") else None,
        "ended_at": row["ended_at"].isoformat() if row.get("ended_at") else None,
    }


def serialize_round_row(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "round_index": int(row.get("round_index") or 0),
        "total_rounds": int(row.get("total_rounds") or 1),
        "round_duration_seconds": int(row.get("round_duration_seconds") or 0),
        "cumulative_duration_seconds": int(row.get("cumulative_duration_seconds") or 0),
        "round_hits": int(row.get("round_hits") or 0),
        "cumulative_hits": int(row.get("cumulative_hits") or 0),
        "round_calories_burned": decimal_to_float(row.get("round_calories_burned")),
        "cumulative_calories_burned": decimal_to_float(row.get("cumulative_calories_burned")),
        "round_fat_burned_grams": decimal_to_float(row.get("round_fat_burned_grams")),
        "cumulative_fat_burned_grams": decimal_to_float(row.get("cumulative_fat_burned_grams")),
        "peak_force_n": decimal_to_float(row.get("peak_force_n")),
        "avg_force_n": decimal_to_float(row.get("avg_force_n")),
        "avg_bpm": decimal_to_float(row.get("avg_bpm")),
        "rhythm_accuracy": decimal_to_float(row.get("rhythm_accuracy"), digits=4),
        "ended_at": row["ended_at"].isoformat() if row.get("ended_at") else None,
    }


def compute_statistics(conn, user_id: int) -> dict[str, Any]:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT
              COUNT(*) AS total_sessions,
              0 AS total_hits,
              0 AS best_30_hits,
              0 AS best_60_hits,
              0 AS average_30_frequency,
              0 AS average_60_frequency,
              COALESCE(MAX(total_hits), 0) AS personal_best_hits,
              COALESCE(MAX(best_burst_count), 0) AS best_burst_record,
              COALESCE(MAX(average_frequency), 0) AS best_average_frequency,
              0 AS total_training_seconds,
              0 AS total_calories_burned,
              0 AS total_fat_burned_grams,
              0 AS best_avg_bpm,
              0 AS best_peak_force_n,
              0 AS best_avg_force_n
            FROM training_sessions
            WHERE user_id = %s
            """,
            (user_id,),
        )
        row = cur.fetchone() or {}
        cur.execute(
            """
            SELECT
              COUNT(*) AS total_rounds,
              COALESCE(SUM(round_hits), 0) AS total_hits,
              COALESCE(MAX(round_hits), 0) AS best_30_hits,
              COALESCE(MAX(round_hits), 0) AS best_60_hits,
              COALESCE(AVG(CASE WHEN round_duration_seconds > 0 THEN round_hits / round_duration_seconds END), 0) AS average_30_frequency,
              COALESCE(AVG(CASE WHEN round_duration_seconds > 0 THEN round_hits / round_duration_seconds END), 0) AS average_60_frequency,
              COALESCE(MAX(cumulative_hits), 0) AS personal_best_hits,
              COALESCE(MAX(round_hits), 0) AS best_round_hits,
              COALESCE(AVG(round_hits), 0) AS average_round_hits,
              COALESCE(MAX(CASE WHEN round_duration_seconds > 0 THEN round_hits / round_duration_seconds END), 0) AS best_average_frequency,
              COALESCE(SUM(round_duration_seconds), 0) AS total_training_seconds,
              COALESCE(SUM(round_calories_burned), 0) AS total_calories_burned,
              COALESCE(SUM(round_fat_burned_grams), 0) AS total_fat_burned_grams,
              COALESCE(MAX(avg_bpm), 0) AS best_avg_bpm,
              COALESCE(MAX(peak_force_n), 0) AS best_peak_force_n,
              COALESCE(MAX(avg_force_n), 0) AS best_avg_force_n,
              COALESCE(MAX(peak_force_n), 0) AS best_round_peak_force_n,
              COALESCE(MAX(avg_force_n), 0) AS best_round_avg_force_n,
              COALESCE(AVG(round_calories_burned), 0) AS average_round_calories_burned
            FROM training_session_rounds
            WHERE user_id = %s
            """,
            (user_id,),
        )
        round_row = cur.fetchone() or {}
    row.update(round_row)
    return serialize_statistics(row)


def fetch_activity_dates(conn, user_id: int) -> list[date]:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT DISTINCT DATE(ended_at) AS active_date
            FROM training_session_rounds
            WHERE user_id = %s
            ORDER BY active_date DESC
            """,
            (user_id,),
        )
        rows = cur.fetchall() or []
    return [row["active_date"] for row in rows if row.get("active_date")]


def streak_summary(dates: list[date]) -> dict[str, int]:
    if not dates:
        return {"active_days": 0, "current_streak": 0, "longest_streak": 0}

    ordered = sorted(set(dates))
    longest = 1
    current = 1
    for index in range(1, len(ordered)):
        if (ordered[index] - ordered[index - 1]).days == 1:
            current += 1
            longest = max(longest, current)
        else:
            current = 1

    today = utc_now().date()
    date_set = set(ordered)
    if today in date_set:
        cursor = today
    elif (today - ordered[-1]).days == 1:
        cursor = ordered[-1]
    else:
        return {"active_days": len(date_set), "current_streak": 0, "longest_streak": longest}

    current_streak = 0
    while cursor in date_set:
        current_streak += 1
        cursor = cursor.fromordinal(cursor.toordinal() - 1)

    return {
        "active_days": len(date_set),
        "current_streak": current_streak,
        "longest_streak": longest,
    }


def sync_user_progress(conn, user_row: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]], bool]:
    statistics = compute_statistics(conn, user_row["id"])
    streaks = streak_summary(fetch_activity_dates(conn, user_row["id"]))
    statistics.update(streaks)

    metrics = {
        "total_sessions": statistics["total_sessions"],
        "total_hits": statistics["total_hits"],
        "personal_best_hits": statistics["personal_best_hits"],
        "total_training_seconds": statistics["total_training_seconds"],
        "best_peak_force_n": round(statistics["best_peak_force_n"]),
        "best_avg_force_n": round(statistics["best_avg_force_n"]),
        "total_calories_burned": round(statistics["total_calories_burned"]),
        "total_fat_burned_grams": round(statistics["total_fat_burned_grams"]),
    }
    tier = tier_snapshot(statistics["best_30_hits"])
    previous_tier = int(user_row.get("current_tier") or 1)
    promoted = tier["level"] > previous_tier

    achievement_rows = achievement_progress_rows(metrics)
    unlocked_map: dict[str, datetime | None] = {}
    valid_achievement_keys = {item["key"] for item in achievement_rows}
    now = utc_now()
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT achievement_key, unlocked_at
            FROM user_achievements
            WHERE user_id = %s
            """,
            (user_row["id"],),
        )
        for row in cur.fetchall() or []:
            unlocked_map[row["achievement_key"]] = row.get("unlocked_at")

        stale_keys = [key for key in unlocked_map if key not in valid_achievement_keys]
        if stale_keys:
            placeholders = ", ".join(["%s"] * len(stale_keys))
            cur.execute(
                f"""
                DELETE FROM user_achievements
                WHERE user_id = %s AND achievement_key IN ({placeholders})
                """,
                (user_row["id"], *stale_keys),
            )
            for key in stale_keys:
                unlocked_map.pop(key, None)

        for item in achievement_rows:
            existing_unlocked_at = unlocked_map.get(item["key"])
            if item["unlocked"]:
                unlocked_at = existing_unlocked_at or now
            else:
                unlocked_at = None
            if item["key"] in unlocked_map:
                cur.execute(
                    """
                    UPDATE user_achievements
                    SET unlocked_at = %s,
                        progress_value = %s,
                        goal_value = %s
                    WHERE user_id = %s AND achievement_key = %s
                    """,
                    (unlocked_at, item["progress"], item["goal"], user_row["id"], item["key"]),
                )
            else:
                cur.execute(
                    """
                    INSERT INTO user_achievements
                    (user_id, achievement_key, unlocked_at, progress_value, goal_value, created_at, updated_at)
                    VALUES (%s, %s, %s, %s, %s, %s, %s)
                    """,
                    (user_row["id"], item["key"], unlocked_at, item["progress"], item["goal"], now, now),
                )
            item["unlocked_at"] = unlocked_at

        cur.execute(
            """
            UPDATE app_users
            SET total_sessions_cached = %s,
                total_hits_cached = %s,
                total_calories_cached = %s,
                total_fat_burned_grams_cached = %s,
                best_score_cached = %s,
                best_30_hits_cached = %s,
                best_60_hits_cached = %s,
                best_burst_cached = %s,
                longest_streak_cached = %s,
                active_days_cached = %s,
                current_tier = %s,
                highest_tier = GREATEST(highest_tier, %s),
                tier_updated_at = %s,
                last_seen_at = %s
            WHERE id = %s
            """,
            (
                statistics["total_sessions"],
                statistics["total_hits"],
                statistics["total_calories_burned"],
                statistics["total_fat_burned_grams"],
                statistics["best_30_hits"],
                statistics["best_30_hits"],
                statistics["best_60_hits"],
                statistics["best_burst_record"],
                statistics["longest_streak"],
                statistics["active_days"],
                tier["level"],
                tier["level"],
                now,
                now,
                user_row["id"],
            ),
        )

    return statistics, tier, [serialize_achievement(item) for item in achievement_rows], promoted


def fetch_app_user(conn, user_id: int) -> dict[str, Any]:
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT {APP_USER_SELECT_COLUMNS}
            FROM app_users
            WHERE id = %s
            """,
            (user_id,),
        )
        return cur.fetchone()


def fetch_history(conn, user_id: int, limit: int) -> list[dict[str, Any]]:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT
              id,
              mode_seconds,
              duration_seconds,
              total_hits,
              average_frequency,
              best_burst_count,
              best_burst_start_sec,
              calories_burned,
              fat_burned_grams,
              avg_bpm,
              peak_force_n,
              avg_force_n,
              rhythm_accuracy,
              combo_summary_json,
              beat_score_counts_json,
              round_config_json,
              play_mode,
              sound_pack_id,
              started_at,
              ended_at
            FROM training_sessions
            WHERE user_id = %s
            ORDER BY ended_at DESC, id DESC
            LIMIT %s
            """,
            (user_id, limit),
        )
        rows = cur.fetchall() or []
    history = [serialize_history_row(row) for row in rows]
    session_ids = [int(item["session_id"]) for item in history]
    if not session_ids:
        return history
    placeholders = ", ".join(["%s"] * len(session_ids))
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT
              session_id,
              round_index,
              total_rounds,
              round_duration_seconds,
              cumulative_duration_seconds,
              round_hits,
              cumulative_hits,
              round_calories_burned,
              cumulative_calories_burned,
              round_fat_burned_grams,
              cumulative_fat_burned_grams,
              peak_force_n,
              avg_force_n,
              avg_bpm,
              rhythm_accuracy,
              ended_at
            FROM training_session_rounds
            WHERE session_id IN ({placeholders})
            ORDER BY session_id DESC, round_index ASC
            """,
            tuple(session_ids),
        )
        round_rows = cur.fetchall() or []
    round_map: dict[int, list[dict[str, Any]]] = {}
    for row in round_rows:
        round_map.setdefault(int(row["session_id"]), []).append(serialize_round_row(row))
    for item in history:
        item["round_reports"] = round_map.get(int(item["session_id"]), [])
    return history


def fetch_leaderboard(
    conn,
    *,
    board_key: str,
    window: str,
    limit: int,
    my_user_id: int | None,
) -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
    cutoff = leaderboard_cutoff(window)
    round_join_filter = ""
    params: list[Any] = []
    if cutoff is not None:
        round_join_filter = " AND r.ended_at >= %s"
        params.append(cutoff)
    score_expression = {
        "total_training_seconds": "COALESCE(SUM(r.round_duration_seconds), 0)",
        "total_hits": "COALESCE(SUM(r.round_hits), 0)",
        "peak_force_n": "COALESCE(MAX(r.peak_force_n), 0)",
        "avg_force_n": "COALESCE(MAX(r.avg_force_n), 0)",
        "calories_burned": "COALESCE(SUM(r.round_calories_burned), 0)",
        "fat_burned_grams": "COALESCE(SUM(r.round_fat_burned_grams), 0)",
        "best_30_hits": "COALESCE(MAX(r.round_hits), 0)",
        "best_60_hits": "COALESCE(MAX(r.round_hits), 0)",
        "longest_streak": "MAX(u.longest_streak_cached)",
    }.get(board_key, "COALESCE(SUM(r.round_duration_seconds), 0)")

    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT
              u.id AS user_id,
              u.nickname,
              u.serial,
              u.country_code,
              u.current_tier,
              {score_expression} AS score_value,
              COALESCE(SUM(r.round_hits), 0) AS total_hits,
              COALESCE(MAX(r.round_hits), 0) AS best_30_hits,
              COALESCE(MAX(r.round_hits), 0) AS best_60_hits,
              COALESCE(MAX(r.round_hits), 0) AS best_burst_count,
              0 AS best_burst_start_sec,
              COALESCE(MAX(CASE WHEN r.round_duration_seconds > 0 THEN r.round_hits / r.round_duration_seconds END), 0) AS best_average_frequency,
              COALESCE(MAX(r.ended_at), u.last_seen_at) AS ended_at,
              u.longest_streak_cached
            FROM app_users u
            LEFT JOIN training_session_rounds r ON r.user_id = u.id{round_join_filter}
            GROUP BY u.id, u.nickname, u.serial, u.country_code, u.current_tier, u.last_seen_at, u.longest_streak_cached
            HAVING score_value > 0
            ORDER BY score_value DESC, ended_at ASC, u.id ASC
            LIMIT 5000
            """,
            tuple(params),
        )
        rows = cur.fetchall() or []

    ranked_rows: list[dict[str, Any]] = []
    for row in rows:
        ranked_rows.append(row)

    top_entries: list[dict[str, Any]] = []
    my_entry: dict[str, Any] | None = None
    for index, row in enumerate(ranked_rows, start=1):
        tier_level = int(row.get("current_tier") or 1)
        entry = {
            "rank": index,
            "user_id": int(row["user_id"]),
            "nickname": row["nickname"],
            "serial_masked": masked_serial(row["serial"]),
            "country_code": row["country_code"],
            "tier_level": tier_level,
            "tier_key": tier_for_level(tier_level).key,
            "best_hits": int(row["score_value"] or 0),
            "score_value": decimal_to_float(row.get("score_value")),
            "total_hits": int(row.get("total_hits") or 0),
            "average_frequency": decimal_to_float(row.get("best_average_frequency")),
            "best_burst_count": int(row.get("best_burst_count") or 0),
            "best_burst_start_sec": decimal_to_float(row.get("best_burst_start_sec")),
            "ended_at": row["ended_at"].isoformat() if row.get("ended_at") else None,
            "is_me": my_user_id is not None and int(row["user_id"]) == my_user_id,
        }
        if index <= limit:
            top_entries.append(entry)
        if my_user_id is not None and int(row["user_id"]) == my_user_id:
            my_entry = entry

    return top_entries, my_entry


def ensure_app_user(
    conn,
    *,
    serial: str,
    preferred_language: str | None = None,
) -> dict[str, Any]:
    language_code = clamp_language(preferred_language, "zh")
    now = utc_now()
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT {APP_USER_SELECT_COLUMNS}
            FROM app_users
            WHERE serial = %s
            FOR UPDATE
            """,
            (serial,),
        )
        row = cur.fetchone()
        if row:
            cur.execute(
                """
                UPDATE app_users
                SET last_seen_at = %s,
                    language_code = COALESCE(NULLIF(%s, ''), language_code)
                WHERE id = %s
                """,
                (now, preferred_language, row["id"]),
            )
            cur.execute(
                f"""
                SELECT {APP_USER_SELECT_COLUMNS}
                FROM app_users
                WHERE id = %s
                """,
                (row["id"],),
            )
            return cur.fetchone()

        cur.execute(
            """
            INSERT INTO app_users
            (serial, nickname, language_code, avatar_color, created_at, updated_at, last_seen_at)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            """,
            (
                serial,
                default_nickname(serial),
                language_code,
                avatar_color_for_serial(serial),
                now,
                now,
                now,
            ),
        )
        user_id = cur.lastrowid
        cur.execute(
            f"""
            SELECT {APP_USER_SELECT_COLUMNS}
            FROM app_users
            WHERE id = %s
            """,
            (user_id,),
        )
        return cur.fetchone()


def authorize_request(
    conn,
    *,
    serial: str,
    install_id: str,
    device_hash: str,
    activation_token: str,
    app_version: str | None,
    ip: str,
    event_type: str,
) -> tuple[RequestContext | None, JSONResponse | None]:
    user_row = ensure_app_user(conn, serial=serial)

    return (
        RequestContext(
            serial=serial,
            install_id=install_id,
            device_hash=device_hash,
            activation_token=activation_token,
            app_version=app_version,
            ip=ip,
            license_row=None,
            activation_row=None,
            user_row=user_row,
        ),
        None,
    )


@app.get("/health")
def health() -> dict[str, Any]:
    settings = load_settings()
    return {"status": "ok", "service": settings.app_name}


def sound_effects_dir() -> Path:
    settings = load_settings()
    root = Path(settings.upload_dir)
    if not root.is_absolute():
        root = Path.cwd() / root
    return root / "sfx"


def background_music_dir() -> Path:
    settings = load_settings()
    root = Path(settings.upload_dir)
    if not root.is_absolute():
        root = Path.cwd() / root
    return root / "music"


def public_sound_asset_base_url(request: Request) -> str:
    scheme = request.headers.get("x-forwarded-proto") or request.url.scheme
    host = request.headers.get("host") or request.url.netloc
    return f"{scheme}://{host}/hitrise/assets/sfx"


def public_music_asset_base_url(request: Request) -> str:
    scheme = request.headers.get("x-forwarded-proto") or request.url.scheme
    host = request.headers.get("host") or request.url.netloc
    return f"{scheme}://{host}/hitrise/assets/music"


@app.get("/api/v1/sound-effects")
def sound_effects(request: Request) -> dict[str, Any]:
    return {
        "status": "ok",
        "version": 1,
        "feature_enabled": False,
        "message": "Cloud sound effect selection is disabled in the current HitRise app.",
        "items": [],
    }


@app.get("/assets/sfx/{filename}")
def sound_effect_asset(filename: str):
    safe_name = Path(filename).name
    if safe_name != filename or not safe_name.lower().endswith(".wav"):
        raise HTTPException(status_code=404, detail="sound effect not found")
    path = sound_effects_dir() / safe_name
    if not path.exists() or not path.is_file():
        raise HTTPException(status_code=404, detail="sound effect not found")
    return FileResponse(path, media_type="audio/wav", filename=safe_name)


@app.get("/api/v1/background-music")
def background_music(request: Request) -> dict[str, Any]:
    return {
        "status": "ok",
        "version": 1,
        "feature_enabled": False,
        "message": "Background music selection is disabled in the current HitRise app.",
        "items": [],
    }


@app.get("/assets/music/{filename}")
def background_music_asset(filename: str):
    safe_name = Path(filename).name
    if safe_name != filename or not safe_name.lower().endswith(".wav"):
        raise HTTPException(status_code=404, detail="background music not found")
    path = background_music_dir() / safe_name
    if not path.exists() or not path.is_file():
        raise HTTPException(status_code=404, detail="background music not found")
    return FileResponse(path, media_type="audio/wav", filename=safe_name)


@app.post("/api/v1/activate")
def activate(payload: ActivateRequest, request: Request):
    try:
        serial = normalize_serial(payload.serial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    with get_conn() as conn:
        try:
            ensure_app_user(conn, serial=serial)
            conn.commit()
            return {
                "status": "ok",
                "license_state": "not_required",
                "message": "User identity ready.",
                "serial": serial,
                "activation_token": "local",
                "product_code": "HTR01",
                "batch_no": None,
            }
        except Exception:
            conn.rollback()
            raise


@app.post("/api/v1/reactivate-by-device")
def reactivate_by_device(payload: DeviceReactivationRequest, request: Request):
    device_hash = payload.device_hash.strip()
    if not device_hash:
        raise HTTPException(status_code=400, detail="device_hash is required")
    serial = "".join(str(byte % 10) for byte in hashlib.sha256(device_hash.encode("utf-8")).digest())[:11]

    with get_conn() as conn:
        try:
            ensure_app_user(conn, serial=serial)
            conn.commit()
            return {
                "status": "ok",
                "license_state": "not_required",
                "message": "User identity ready.",
                "serial": serial,
                "activation_token": "local",
                "product_code": "HTR01",
                "batch_no": None,
            }
        except HTTPException:
            raise
        except Exception:
            conn.rollback()
            raise


@app.post("/api/v1/check")
def check(payload: CheckRequest, request: Request):
    try:
        serial = normalize_serial(payload.serial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    install_id = payload.install_id.strip()
    device_hash = payload.device_hash.strip()
    token = payload.activation_token.strip()
    app_version = (payload.app_version or "").strip() or None
    ip = client_ip(request)

    with get_conn() as conn:
        try:
            context, failure = authorize_request(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                activation_token=token,
                app_version=app_version,
                ip=ip,
                event_type="check",
            )
            if failure is not None:
                return failure

            write_log(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                event_type="check",
                result="ok",
                reason="active",
                ip=ip,
            )
            conn.commit()
            return {
                "status": "ok",
                "license_state": "not_required",
                "message": "User identity ready.",
                "serial": serial,
            }
        except Exception:
            conn.rollback()
            raise


@app.post("/api/v1/user/bootstrap")
def user_bootstrap(payload: UserBootstrapRequest, request: Request):
    try:
        serial = normalize_serial(payload.serial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    install_id = payload.install_id.strip()
    device_hash = payload.device_hash.strip()
    token = payload.activation_token.strip()
    app_version = (payload.app_version or "").strip() or None
    language_code = clamp_language(payload.language_code, "zh")
    ip = client_ip(request)

    with get_conn() as conn:
        try:
            context, failure = authorize_request(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                activation_token=token,
                app_version=app_version,
                ip=ip,
                event_type="bootstrap",
            )
            if failure is not None:
                return failure

            user_row = ensure_app_user(conn, serial=serial, preferred_language=language_code)
            statistics, tier, achievements, promoted = sync_user_progress(conn, user_row)
            user_row = fetch_app_user(conn, user_row["id"])
            history = fetch_history(conn, user_row["id"], 10)
            write_log(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                event_type="bootstrap",
                result="ok",
                reason="ready",
                ip=ip,
            )
            conn.commit()
            return {
                "status": "ok",
                "message": "User profile ready.",
                "profile": serialize_profile(user_row),
                "statistics": statistics,
                "history": history,
                "achievements": achievements,
                "tier": serialize_tier(tier),
                "promoted": promoted,
            }
        except Exception:
            conn.rollback()
            raise


@app.post("/api/v1/user/profile/update")
def update_user_profile(payload: UserProfileUpdateRequest, request: Request):
    try:
        serial = normalize_serial(payload.serial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    install_id = payload.install_id.strip()
    device_hash = payload.device_hash.strip()
    token = payload.activation_token.strip()
    app_version = (payload.app_version or "").strip() or None
    nickname = (payload.nickname or "").strip() or None
    language_code = clamp_language(payload.language_code, "zh")
    ip = client_ip(request)

    with get_conn() as conn:
        try:
            context, failure = authorize_request(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                activation_token=token,
                app_version=app_version,
                ip=ip,
                event_type="profile_update",
            )
            if failure is not None:
                return failure

            user_row = context.user_row or ensure_app_user(conn, serial=serial, preferred_language=language_code)
            country_code = (payload.country_code or "").strip().upper() or user_row["country_code"]
            avatar_color = clamp_avatar_color(payload.avatar_color, user_row["avatar_color"])
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE app_users
                    SET nickname = %s,
                        language_code = %s,
                        country_code = %s,
                        avatar_color = %s,
                        last_seen_at = %s
                    WHERE id = %s
                    """,
                    (
                        nickname or user_row["nickname"],
                        language_code,
                        country_code,
                        avatar_color,
                        utc_now(),
                        user_row["id"],
                    ),
                )
                cur.execute(
                    f"""
                    SELECT {APP_USER_SELECT_COLUMNS}
                    FROM app_users
                    WHERE id = %s
                    """,
                    (user_row["id"],),
                )
                updated_user = cur.fetchone()

            statistics, tier, achievements, promoted = sync_user_progress(conn, updated_user)
            updated_user = fetch_app_user(conn, updated_user["id"])
            history = fetch_history(conn, updated_user["id"], 10)
            write_log(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                event_type="profile_update",
                result="ok",
                reason="updated",
                ip=ip,
            )
            conn.commit()
            return {
                "status": "ok",
                "message": "Profile updated.",
                "profile": serialize_profile(updated_user),
                "statistics": statistics,
                "history": history,
                "achievements": achievements,
                "tier": serialize_tier(tier),
                "promoted": promoted,
            }
        except Exception:
            conn.rollback()
            raise


@app.post("/api/v1/user/statistics")
def user_statistics(payload: UserStatisticsRequest, request: Request):
    try:
        serial = normalize_serial(payload.serial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    install_id = payload.install_id.strip()
    device_hash = payload.device_hash.strip()
    token = payload.activation_token.strip()
    app_version = (payload.app_version or "").strip() or None
    ip = client_ip(request)

    with get_conn() as conn:
        try:
            context, failure = authorize_request(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                activation_token=token,
                app_version=app_version,
                ip=ip,
                event_type="statistics",
            )
            if failure is not None:
                return failure

            user_row = context.user_row or ensure_app_user(conn, serial=serial)
            statistics, tier, achievements, promoted = sync_user_progress(conn, user_row)
            write_log(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                event_type="statistics",
                result="ok",
                reason="ready",
                ip=ip,
            )
            conn.commit()
            return {
                "status": "ok",
                "message": "Statistics ready.",
                "statistics": statistics,
                "achievements": achievements,
                "tier": serialize_tier(tier),
                "promoted": promoted,
            }
        except Exception:
            conn.rollback()
            raise


@app.post("/api/v1/training/history")
def training_history(payload: TrainingHistoryRequest, request: Request):
    try:
        serial = normalize_serial(payload.serial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    install_id = payload.install_id.strip()
    device_hash = payload.device_hash.strip()
    token = payload.activation_token.strip()
    app_version = (payload.app_version or "").strip() or None
    ip = client_ip(request)

    with get_conn() as conn:
        try:
            context, failure = authorize_request(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                activation_token=token,
                app_version=app_version,
                ip=ip,
                event_type="history",
            )
            if failure is not None:
                return failure

            user_row = context.user_row or ensure_app_user(conn, serial=serial)
            statistics, tier, achievements, promoted = sync_user_progress(conn, user_row)
            history = fetch_history(conn, user_row["id"], payload.limit)
            write_log(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                event_type="history",
                result="ok",
                reason=f"limit_{payload.limit}",
                ip=ip,
            )
            conn.commit()
            return {
                "status": "ok",
                "message": "History ready.",
                "history": history,
                "statistics": statistics,
                "achievements": achievements,
                "tier": serialize_tier(tier),
                "promoted": promoted,
            }
        except Exception:
            conn.rollback()
            raise


@app.post("/api/v1/training/session")
def create_training_session(payload: TrainingSessionCreateRequest, request: Request):
    try:
        serial = normalize_serial(payload.serial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    if payload.mode_seconds < MIN_MODE_SECONDS or payload.mode_seconds > MAX_MODE_SECONDS:
        raise HTTPException(status_code=400, detail="mode_seconds must be between 1 and 600 seconds")

    install_id = payload.install_id.strip()
    device_hash = payload.device_hash.strip()
    token = payload.activation_token.strip()
    app_version = (payload.app_version or "").strip() or None
    ip = client_ip(request)
    started_at = utc_from_epoch_ms(payload.started_at_epoch_ms)
    ended_at = utc_from_epoch_ms(payload.ended_at_epoch_ms) or utc_now()
    duration_seconds = int(payload.duration_seconds or payload.mode_seconds)
    avg_bpm = float(payload.avg_bpm or 0.0)
    if avg_bpm <= 0.0 and payload.average_frequency > 0.0:
        avg_bpm = round(payload.average_frequency * 60.0, 3)
    peak_force_n = float(payload.peak_force_n or 0.0)
    avg_force_n = float(payload.avg_force_n or 0.0)
    calories_burned = float(payload.calories_burned or 0.0)
    if calories_burned <= 0.0 and payload.total_hits > 0:
        calories_burned = calories_for_training(payload.total_hits, duration_seconds, avg_force_n)
    fat_burned_grams = float(payload.fat_burned_grams or 0.0)
    if fat_burned_grams <= 0.0 and calories_burned > 0.0:
        fat_burned_grams = fat_grams_for_calories(calories_burned)
    rhythm_accuracy = float(payload.rhythm_accuracy or 0.0)
    play_mode = (payload.play_mode or "").strip() or None
    sound_pack_id = (payload.sound_pack_id or "").strip() or None
    round_reports = parse_round_reports_json(payload.round_reports_json, total_rounds=1, fallback_ended_at=ended_at)
    if not round_reports and (duration_seconds > 0 or payload.total_hits > 0):
        round_reports = [
            {
                "round_index": 1,
                "total_rounds": 1,
                "cumulative_duration_seconds": duration_seconds,
                "cumulative_hits": payload.total_hits,
                "cumulative_calories_burned": calories_burned,
                "cumulative_fat_burned_grams": fat_burned_grams,
                "peak_force_n": peak_force_n,
                "avg_force_n": avg_force_n,
                "avg_bpm": avg_bpm,
                "rhythm_accuracy": rhythm_accuracy,
                "ended_at": ended_at,
            }
        ]

    with get_conn() as conn:
        try:
            context, failure = authorize_request(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                activation_token=token,
                app_version=app_version,
                ip=ip,
                event_type="session_create",
            )
            if failure is not None:
                return failure

            user_row = context.user_row or ensure_app_user(conn, serial=serial)
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO training_sessions
                    (user_id, serial, mode_seconds, duration_seconds, total_hits, average_frequency, best_burst_count,
                     best_burst_start_sec, calories_burned, fat_burned_grams, avg_bpm, peak_force_n, avg_force_n,
                     rhythm_accuracy, combo_summary_json, beat_score_counts_json, round_config_json,
                     play_mode, sound_pack_id, started_at, ended_at, device_hash, app_version, created_at)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        user_row["id"],
                        serial,
                        payload.mode_seconds,
                        duration_seconds,
                        payload.total_hits,
                        payload.average_frequency,
                        payload.best_burst_count,
                        payload.best_burst_start_sec,
                        calories_burned,
                        fat_burned_grams,
                        avg_bpm,
                        peak_force_n,
                        avg_force_n,
                        rhythm_accuracy,
                        payload.combo_summary_json,
                        payload.beat_score_counts_json,
                        payload.round_config_json,
                        play_mode,
                        sound_pack_id,
                        started_at,
                        ended_at,
                        device_hash,
                        app_version,
                        utc_now(),
                    ),
                )
                session_id = cur.lastrowid
                previous_duration = 0
                previous_hits = 0
                previous_calories = 0.0
                previous_fat = 0.0
                for round_report in round_reports:
                    cumulative_duration = int(round_report["cumulative_duration_seconds"])
                    cumulative_hits = int(round_report["cumulative_hits"])
                    cumulative_calories = float(round_report["cumulative_calories_burned"])
                    cumulative_fat = float(round_report["cumulative_fat_burned_grams"])
                    round_duration = max(0, cumulative_duration - previous_duration)
                    round_hits = max(0, cumulative_hits - previous_hits)
                    round_calories = max(0.0, cumulative_calories - previous_calories)
                    round_fat = max(0.0, cumulative_fat - previous_fat)
                    cur.execute(
                        """
                        INSERT INTO training_session_rounds
                        (session_id, user_id, round_index, total_rounds, round_duration_seconds,
                         cumulative_duration_seconds, round_hits, cumulative_hits,
                         round_calories_burned, cumulative_calories_burned,
                         round_fat_burned_grams, cumulative_fat_burned_grams,
                         peak_force_n, avg_force_n, avg_bpm, rhythm_accuracy, ended_at, created_at)
                        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                        ON DUPLICATE KEY UPDATE
                          total_rounds = VALUES(total_rounds),
                          round_duration_seconds = VALUES(round_duration_seconds),
                          cumulative_duration_seconds = VALUES(cumulative_duration_seconds),
                          round_hits = VALUES(round_hits),
                          cumulative_hits = VALUES(cumulative_hits),
                          round_calories_burned = VALUES(round_calories_burned),
                          cumulative_calories_burned = VALUES(cumulative_calories_burned),
                          round_fat_burned_grams = VALUES(round_fat_burned_grams),
                          cumulative_fat_burned_grams = VALUES(cumulative_fat_burned_grams),
                          peak_force_n = VALUES(peak_force_n),
                          avg_force_n = VALUES(avg_force_n),
                          avg_bpm = VALUES(avg_bpm),
                          rhythm_accuracy = VALUES(rhythm_accuracy),
                          ended_at = VALUES(ended_at)
                        """,
                        (
                            session_id,
                            user_row["id"],
                            round_report["round_index"],
                            round_report["total_rounds"],
                            round_duration,
                            cumulative_duration,
                            round_hits,
                            cumulative_hits,
                            round(round_calories, 3),
                            round(cumulative_calories, 3),
                            round(round_fat, 3),
                            round(cumulative_fat, 3),
                            round_report["peak_force_n"],
                            round_report["avg_force_n"],
                            round_report["avg_bpm"],
                            round_report["rhythm_accuracy"],
                            round_report["ended_at"],
                            utc_now(),
                        ),
                    )
                    previous_duration = cumulative_duration
                    previous_hits = cumulative_hits
                    previous_calories = cumulative_calories
                    previous_fat = cumulative_fat

            statistics, tier, achievements, promoted = sync_user_progress(conn, user_row)
            user_row = fetch_app_user(conn, user_row["id"])
            history = fetch_history(conn, user_row["id"], 10)
            write_log(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                event_type="session_create",
                result="ok",
                reason=f"mode_{payload.mode_seconds}",
                ip=ip,
            )
            conn.commit()
            return {
                "status": "ok",
                "message": "Training session saved.",
                "session_id": session_id,
                "profile": serialize_profile(user_row),
                "statistics": statistics,
                "history": history,
                "achievements": achievements,
                "tier": serialize_tier(tier),
                "promoted": promoted,
            }
        except Exception:
            conn.rollback()
            raise


@app.post("/api/v1/leaderboard")
def leaderboard(payload: LeaderboardRequest, request: Request):
    try:
        serial = normalize_serial(payload.serial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    board_key = normalize_leaderboard_key(payload.board_key, payload.mode_seconds)
    window = clamp_window(payload.window)
    install_id = payload.install_id.strip()
    device_hash = payload.device_hash.strip()
    token = payload.activation_token.strip()
    app_version = (payload.app_version or "").strip() or None
    ip = client_ip(request)

    with get_conn() as conn:
        try:
            context, failure = authorize_request(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                activation_token=token,
                app_version=app_version,
                ip=ip,
                event_type="leaderboard",
            )
            if failure is not None:
                return failure

            user_row = context.user_row or ensure_app_user(conn, serial=serial)
            sync_user_progress(conn, user_row)
            user_row = fetch_app_user(conn, user_row["id"])
            top_entries, my_entry = fetch_leaderboard(
                conn,
                board_key=board_key,
                window=window,
                limit=payload.limit,
                my_user_id=user_row["id"],
            )
            write_log(
                conn,
                serial=serial,
                install_id=install_id,
                device_hash=device_hash,
                event_type="leaderboard",
                result="ok",
                reason=f"{board_key}_{window}",
                ip=ip,
            )
            conn.commit()
            return {
                "status": "ok",
                "message": "Leaderboard ready.",
                "board_key": board_key,
                "mode_seconds": 60 if board_key == "best_60_hits" else 30 if board_key == "best_30_hits" else 0,
                "window": window,
                "top": top_entries,
                "me": my_entry,
            }
        except Exception:
            conn.rollback()
            raise


@app.post("/api/v1/admin/reset")
def admin_reset(
    payload: ResetRequest,
    request: Request,
    x_admin_token: str | None = Header(default=None),
):
    settings = load_settings()
    if x_admin_token != settings.admin_token:
        raise HTTPException(status_code=401, detail="invalid admin token")

    try:
        serial = normalize_serial(payload.serial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    with get_conn() as conn:
        try:
            ensure_app_user(conn, serial=serial)
            conn.commit()
            return {
                "status": "ok",
                "message": "User authentication reset is no longer required.",
                "serial": serial,
            }
        except Exception:
            conn.rollback()
            raise
