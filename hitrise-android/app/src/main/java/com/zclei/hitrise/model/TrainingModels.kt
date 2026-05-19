package com.zclei.hitrise.model

enum class TrainingMode(val durationSeconds: Int, val label: String) {
    Seconds30(durationSeconds = 30, label = "30 sec"),
    Seconds60(durationSeconds = 60, label = "60 sec"),
    Burst10(durationSeconds = 10, label = "10 sec burst"),
    Burst15(durationSeconds = 15, label = "15 sec burst"),
}

enum class AppLanguage(val storageValue: String) {
    Chinese(storageValue = "zh"),
    English(storageValue = "en"),
    French(storageValue = "fr"),
    Thai(storageValue = "th");

    companion object {
        fun fromStorage(value: String?): AppLanguage =
            entries.firstOrNull { it.storageValue == value } ?: Chinese
    }
}

enum class TrainingPhase {
    Idle,
    Countdown,
    Running,
    Finished,
    Error,
}

enum class BeatScore {
    Perfect,
    Good,
    Miss,
}

data class RoundConfig(
    val id: String,
    val label: String,
    val workSeconds: Int,
    val restSeconds: Int = 0,
    val rounds: Int = 1,
) {
    companion object {
        fun forMode(mode: TrainingMode): RoundConfig =
            RoundConfig(
                id = mode.name,
                label = mode.label,
                workSeconds = mode.durationSeconds,
            )
    }
}

data class PunchEvent(
    val id: String,
    val sessionId: String,
    val forceN: Double,
    val deviceTs: Long,
    val systemTs: Long,
    val beatOffsetMs: Int? = null,
    val beatScore: BeatScore? = null,
)

data class ComboEvent(
    val type: String,
    val detectedAtMs: Long,
    val punchCount: Int,
    val peakForceN: Double,
)

data class RhythmSummary(
    val perfectCount: Int = 0,
    val goodCount: Int = 0,
    val missCount: Int = 0,
) {
    val totalJudged: Int
        get() = perfectCount + goodCount + missCount

    val accuracy: Float
        get() = if (totalJudged == 0) 0f else (perfectCount + goodCount * 0.5f) / totalJudged
}

data class TrainingRoundReport(
    val roundIndex: Int,
    val totalRounds: Int,
    val durationSeconds: Int,
    val totalHits: Int,
    val caloriesBurned: Float,
    val fatBurnedGrams: Float,
    val peakForceN: Float,
    val avgForceN: Float,
    val avgBpm: Float,
    val rhythmAccuracy: Float,
    val endedAtEpochMs: Long,
)

data class TrainingReport(
    val mode: TrainingMode,
    val totalHits: Int,
    val averageFrequency: Float,
    val bestBurstCount: Int,
    val bestBurstStartSec: Float,
    val endedAtEpochMs: Long,
    val durationSeconds: Int = mode.durationSeconds,
    val completedRounds: Int = 1,
    val totalRounds: Int = 1,
    val caloriesBurned: Float = 0f,
    val fatBurnedGrams: Float = 0f,
    val avgBpm: Float = 0f,
    val peakForceN: Float = 0f,
    val avgForceN: Float = 0f,
    val comboSummary: Map<String, Int> = emptyMap(),
    val rhythmAccuracy: Float = 0f,
    val rhythmSummary: RhythmSummary = RhythmSummary(),
    val roundConfig: RoundConfig? = null,
    val roundReports: List<TrainingRoundReport> = emptyList(),
    val playMode: String = "free",
    val soundPackId: String = "sfx_gym",
)

data class TrainerUiState(
    val phase: TrainingPhase = TrainingPhase.Idle,
    val selectedMode: TrainingMode = TrainingMode.Seconds30,
    val countdownValue: Int? = null,
    val hitCount: Int = 0,
    val remainingMillis: Long = 0L,
    val latestReport: TrainingReport? = null,
    val reportHistory: List<TrainingReport> = emptyList(),
    val errorMessage: String? = null,
    val isBusy: Boolean = false,
)
