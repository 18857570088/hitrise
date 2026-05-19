package com.zclei.hitrise.cloud

data class CloudUserProfile(
    val userId: Long,
    val serial: String,
    val serialMasked: String,
    val nickname: String,
    val languageCode: String,
    val countryCode: String?,
    val avatarColor: String,
    val currentTier: Int,
    val highestTier: Int,
    val bestScoreCached: Int,
    val best30HitsCached: Int,
    val best60HitsCached: Int,
    val bestBurstCached: Int,
    val longestStreakCached: Int,
    val activeDaysCached: Int,
    val createdAt: String?,
    val lastSeenAt: String?,
)

data class CloudUserStatistics(
    val totalSessions: Int,
    val totalHits: Int,
    val best30Hits: Int,
    val best60Hits: Int,
    val average30Frequency: Float,
    val average60Frequency: Float,
    val personalBestHits: Int,
    val bestBurstRecord: Int,
    val bestAverageFrequency: Float,
    val totalTrainingSeconds: Int = 0,
    val activeDays: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val totalCaloriesBurned: Float = 0f,
    val totalFatBurnedGrams: Float = 0f,
    val bestPeakForceN: Float = 0f,
    val bestAvgForceN: Float = 0f,
    val totalRounds: Int = 0,
    val bestRoundHits: Int = 0,
    val averageRoundHits: Float = 0f,
    val bestRoundPeakForceN: Float = 0f,
    val bestRoundAvgForceN: Float = 0f,
    val averageRoundCaloriesBurned: Float = 0f,
)

data class CloudTierProgress(
    val level: Int,
    val key: String,
    val bestHits: Int,
    val nextLevel: Int?,
    val nextKey: String?,
    val nextHits: Int?,
    val progressHits: Int,
    val progressTargetHits: Int,
)

data class CloudAchievementItem(
    val key: String,
    val metric: String,
    val goal: Int,
    val progress: Int,
    val unlocked: Boolean,
    val unlockedAt: String?,
    val sortOrder: Int,
)

data class CloudRoundReportItem(
    val roundIndex: Int,
    val totalRounds: Int,
    val roundDurationSeconds: Int,
    val cumulativeDurationSeconds: Int,
    val roundHits: Int,
    val cumulativeHits: Int,
    val roundCaloriesBurned: Float,
    val cumulativeCaloriesBurned: Float,
    val roundFatBurnedGrams: Float,
    val cumulativeFatBurnedGrams: Float,
    val peakForceN: Float,
    val avgForceN: Float,
    val avgBpm: Float,
    val rhythmAccuracy: Float,
    val endedAt: String?,
)

data class CloudTrainingHistoryItem(
    val sessionId: Long,
    val modeSeconds: Int,
    val durationSeconds: Int = modeSeconds,
    val totalHits: Int,
    val averageFrequency: Float,
    val bestBurstCount: Int,
    val bestBurstStartSec: Float,
    val caloriesBurned: Float = 0f,
    val fatBurnedGrams: Float = 0f,
    val avgBpm: Float = 0f,
    val peakForceN: Float = 0f,
    val avgForceN: Float = 0f,
    val rhythmAccuracy: Float = 0f,
    val playMode: String? = null,
    val soundPackId: String? = null,
    val roundReports: List<CloudRoundReportItem> = emptyList(),
    val startedAt: String?,
    val endedAt: String?,
)

data class CloudLeaderboardEntry(
    val rank: Int,
    val userId: Long,
    val nickname: String,
    val serialMasked: String,
    val countryCode: String?,
    val tierLevel: Int,
    val tierKey: String,
    val bestHits: Int,
    val scoreValue: Float = bestHits.toFloat(),
    val totalHits: Int = 0,
    val averageFrequency: Float,
    val bestBurstCount: Int,
    val bestBurstStartSec: Float,
    val endedAt: String?,
    val isMe: Boolean,
)

data class CloudBootstrapResult(
    val success: Boolean,
    val message: String,
    val reason: String? = null,
    val profile: CloudUserProfile? = null,
    val statistics: CloudUserStatistics? = null,
    val history: List<CloudTrainingHistoryItem> = emptyList(),
    val achievements: List<CloudAchievementItem> = emptyList(),
    val tier: CloudTierProgress? = null,
    val promoted: Boolean = false,
)

data class CloudSessionUploadResult(
    val success: Boolean,
    val message: String,
    val reason: String? = null,
    val sessionId: Long? = null,
    val profile: CloudUserProfile? = null,
    val statistics: CloudUserStatistics? = null,
    val history: List<CloudTrainingHistoryItem> = emptyList(),
    val achievements: List<CloudAchievementItem> = emptyList(),
    val tier: CloudTierProgress? = null,
    val promoted: Boolean = false,
)

data class CloudLeaderboardResult(
    val success: Boolean,
    val message: String,
    val reason: String? = null,
    val boardKey: String,
    val modeSeconds: Int,
    val window: String,
    val top: List<CloudLeaderboardEntry> = emptyList(),
    val me: CloudLeaderboardEntry? = null,
)

data class CloudSoundEffect(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val descriptionZh: String,
    val descriptionEn: String,
    val style: String,
    val bpm: Int,
    val durationMs: Int,
    val url: String,
)

data class CloudSoundEffectCatalog(
    val success: Boolean,
    val message: String,
    val version: Int = 0,
    val updatedAt: String? = null,
    val items: List<CloudSoundEffect> = emptyList(),
)

data class CloudBackgroundMusic(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val descriptionZh: String,
    val descriptionEn: String,
    val style: String,
    val bpm: Int,
    val durationMs: Int,
    val url: String,
)

data class CloudBackgroundMusicCatalog(
    val success: Boolean,
    val message: String,
    val version: Int = 0,
    val updatedAt: String? = null,
    val items: List<CloudBackgroundMusic> = emptyList(),
)

