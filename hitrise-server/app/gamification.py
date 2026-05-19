from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class TierDefinition:
    level: int
    key: str
    min_best_hits: int


@dataclass(frozen=True)
class AchievementDefinition:
    key: str
    metric: str
    goal: int
    sort_order: int


TIERS: tuple[TierDefinition, ...] = (
    TierDefinition(level=1, key="beginner", min_best_hits=0),
    TierDefinition(level=2, key="prospect", min_best_hits=40),
    TierDefinition(level=3, key="contender", min_best_hits=50),
    TierDefinition(level=4, key="striker", min_best_hits=60),
    TierDefinition(level=5, key="challenger", min_best_hits=70),
    TierDefinition(level=6, key="elite", min_best_hits=80),
    TierDefinition(level=7, key="master", min_best_hits=90),
    TierDefinition(level=8, key="legend", min_best_hits=100),
    TierDefinition(level=9, key="champion", min_best_hits=120),
)


ACHIEVEMENTS: tuple[AchievementDefinition, ...] = (
    AchievementDefinition("duration_5m", "total_training_seconds", 300, 10),
    AchievementDefinition("duration_15m", "total_training_seconds", 900, 20),
    AchievementDefinition("duration_30m", "total_training_seconds", 1800, 30),
    AchievementDefinition("duration_60m", "total_training_seconds", 3600, 40),
    AchievementDefinition("hits_100", "total_hits", 100, 50),
    AchievementDefinition("hits_500", "total_hits", 500, 60),
    AchievementDefinition("hits_1000", "total_hits", 1000, 70),
    AchievementDefinition("hits_5000", "total_hits", 5000, 80),
    AchievementDefinition("peak_force_50", "best_peak_force_n", 50, 90),
    AchievementDefinition("peak_force_100", "best_peak_force_n", 100, 100),
    AchievementDefinition("peak_force_150", "best_peak_force_n", 150, 110),
    AchievementDefinition("peak_force_200", "best_peak_force_n", 200, 120),
    AchievementDefinition("avg_force_30", "best_avg_force_n", 30, 130),
    AchievementDefinition("avg_force_60", "best_avg_force_n", 60, 140),
    AchievementDefinition("avg_force_90", "best_avg_force_n", 90, 150),
    AchievementDefinition("avg_force_120", "best_avg_force_n", 120, 160),
    AchievementDefinition("calories_30", "total_calories_burned", 30, 170),
    AchievementDefinition("calories_100", "total_calories_burned", 100, 180),
    AchievementDefinition("calories_300", "total_calories_burned", 300, 190),
    AchievementDefinition("calories_600", "total_calories_burned", 600, 200),
    AchievementDefinition("fat_5", "total_fat_burned_grams", 5, 210),
    AchievementDefinition("fat_15", "total_fat_burned_grams", 15, 220),
    AchievementDefinition("fat_40", "total_fat_burned_grams", 40, 230),
    AchievementDefinition("fat_80", "total_fat_burned_grams", 80, 240),
)


def tier_for_best_hits(best_hits: int) -> TierDefinition:
    current = TIERS[0]
    for tier in TIERS:
        if best_hits >= tier.min_best_hits:
            current = tier
        else:
            break
    return current


def tier_for_level(level: int) -> TierDefinition:
    for tier in TIERS:
        if tier.level == level:
            return tier
    return TIERS[0]


def tier_snapshot(best_hits: int) -> dict[str, Any]:
    tier = tier_for_best_hits(best_hits)
    next_tier = next((candidate for candidate in TIERS if candidate.level == tier.level + 1), None)
    return {
        "level": tier.level,
        "key": tier.key,
        "best_hits": best_hits,
        "next_level": next_tier.level if next_tier else None,
        "next_key": next_tier.key if next_tier else None,
        "next_hits": next_tier.min_best_hits if next_tier else None,
        "progress_hits": max(0, best_hits - tier.min_best_hits),
        "progress_target_hits": (next_tier.min_best_hits - tier.min_best_hits) if next_tier else 0,
    }


def achievement_progress_rows(metrics: dict[str, int]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for item in ACHIEVEMENTS:
        progress = int(metrics.get(item.metric, 0))
        rows.append(
            {
                "key": item.key,
                "metric": item.metric,
                "goal": item.goal,
                "progress": max(0, progress),
                "unlocked": progress >= item.goal,
                "sort_order": item.sort_order,
            }
        )
    return rows
