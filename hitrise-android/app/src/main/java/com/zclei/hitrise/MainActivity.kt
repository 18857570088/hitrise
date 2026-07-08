package com.zclei.hitrise

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaPlayer
import android.media.AudioTrack
import android.media.SoundPool
import android.net.Uri
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.ToneGenerator
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.zclei.hitrise.auth.ActivationApiResult
import com.zclei.hitrise.auth.ActivationService
import com.zclei.hitrise.auth.ActivationState
import com.zclei.hitrise.cloud.CloudBackgroundMusic
import com.zclei.hitrise.cloud.CloudBootstrapResult
import com.zclei.hitrise.cloud.CloudAchievementItem
import com.zclei.hitrise.cloud.CloudLeaderboardEntry
import com.zclei.hitrise.cloud.CloudLeaderboardResult
import com.zclei.hitrise.cloud.CloudSoundEffect
import com.zclei.hitrise.cloud.CloudSyncService
import com.zclei.hitrise.cloud.CloudTierProgress
import com.zclei.hitrise.cloud.CloudTrainingHistoryItem
import com.zclei.hitrise.cloud.CloudUserProfile
import com.zclei.hitrise.cloud.CloudUserStatistics
import com.zclei.hitrise.bluetooth.SensorBallBluetoothCallback
import com.zclei.hitrise.bluetooth.SensorBallBluetoothManager
import com.zclei.hitrise.bluetooth.SensorBallDevice
import com.zclei.hitrise.bluetooth.SensorBallTelemetry
import com.zclei.hitrise.bluetooth.SensorBallTransport
import com.zclei.hitrise.model.AppLanguage
import com.zclei.hitrise.model.BeatScore
import com.zclei.hitrise.model.ComboEvent
import com.zclei.hitrise.model.PunchEvent
import com.zclei.hitrise.model.RoundConfig
import com.zclei.hitrise.model.RhythmSummary
import com.zclei.hitrise.model.TrainingMode
import com.zclei.hitrise.model.TrainingReport
import com.zclei.hitrise.model.TrainingRoundReport
import com.zclei.hitrise.ui.CircularTimerView
import com.zclei.hitrise.ui.Haptics
import com.zclei.hitrise.ui.HistoryItemAdapter
import com.zclei.hitrise.ui.LeaderboardRowAdapter
import com.zclei.hitrise.ui.PunchWaveformView
import com.zclei.hitrise.ui.VerticalSpacingDecoration
import com.zclei.hitrise.ui.applyRippleOverlay
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val avatarPalette =
        listOf("#CC4400", "#E07010", "#A73A54", "#FFD060", "#5C3D99", "#7A1400", "#8B5E3C", "#C06014")
    private enum class HomePage {
        TrainingCenter,
        TrainingAchievements,
        Leaderboard,
        Profile,
    }

    private enum class LeaderboardBoard(val apiKey: String) {
        TrainingDuration("total_training_seconds"),
        TotalHits("total_hits"),
        PeakForce("peak_force_n"),
        AvgForce("avg_force_n"),
        Calories("calories_burned"),
        FatBurned("fat_burned_grams"),
    }

    private enum class TrainingPlayMode {
        Classic30,
        Classic60,
        Burst10,
        Burst15,
        LevelChallenge,
        DailyChallenge,
    }

    private enum class TrainingRhythmMode {
        Free,
        Rhythm,
    }

    private enum class SoundPack(val id: String) {
        Gym("sfx_gym"),
        Street("sfx_street"),
    }

    private data class TrainingSessionSetup(
        val workMinutes: Int = 1,
        val restHalfMinutes: Int = 1,
        val rounds: Int = 3,
        val rhythmMode: TrainingRhythmMode = TrainingRhythmMode.Rhythm,
        val bpm: Int = 80,
    ) {
        val workSeconds: Int
            get() = workMinutes * 60

        val restSeconds: Int
            get() = restHalfMinutes * 30

        val totalEstimatedSeconds: Int
            get() = (workSeconds + restSeconds) * rounds

        fun toRoundConfig(): RoundConfig =
            RoundConfig(
                id = "custom_${workMinutes}_${restHalfMinutes}_$rounds",
                label = "${workMinutes}/${restHalfMinutesLabel(restHalfMinutes)} x $rounds",
                workSeconds = workSeconds,
                restSeconds = restSeconds,
                rounds = rounds,
            )

        companion object {
            fun restHalfMinutesLabel(value: Int): String =
                if (value % 2 == 0) {
                    (value / 2).toString()
                } else {
                    String.format(Locale.US, "%.1f", value / 2f)
                }
        }
    }

    private data class TrainingLevelDefinition(
        val level: Int,
        val targetHits: Int,
    )

    private data class TrainingGoalPresentation(
        val title: String,
        val body: String,
        val accentColor: String,
        val targetHits: Int? = null,
    )

    private data class LocalSessionSummary(
        val dateKey: String,
        val endedAtMs: Long,
        val durationSeconds: Int,
        val hits: Int,
        val playMode: String,
    )

    private data class TrainingCoachOutcome(
        val playMode: TrainingPlayMode,
        val goalMet: Boolean,
        val levelBefore: Int,
        val levelAfter: Int,
        val targetHits: Int?,
        val streak: Int,
        val xpGain: Int,
    )

    private var selectedMode: TrainingMode = TrainingMode.Seconds30
    private var selectedPlayMode: TrainingPlayMode = TrainingPlayMode.Classic30
    private var selectedRhythmMode: TrainingRhythmMode = TrainingRhythmMode.Rhythm
    private var selectedBeatBpm: Int = 80
    private var selectedSoundPack: SoundPack = SoundPack.Gym
    private var trainingSessionSetup = TrainingSessionSetup()
    private var lastCoachMessage: String? = null
    private var lastCoachOutcome: TrainingCoachOutcome? = null
    private var selectedLanguage: AppLanguage = defaultLanguage()
    private var selectedPalette: AppPalette = HitRisePalettes.byId(HitRisePalettes.DEFAULT_ID)
    private var selectedHomePage: HomePage = HomePage.TrainingCenter
    private var trainingJob: Job? = null
    private var activationJob: Job? = null
    private var bluetoothTrainingCount: Int = 0
    private var bluetoothTrainingMode: TrainingMode? = null
    private var partialTrainingUploadTriggered: Boolean = false
    private var trainingAcceptingPunches: Boolean = false
    private var currentTrainingRound: Int = 1
    private var currentTrainingRoundCount: Int = 1
    private var currentRoundDurationMs: Long = 0L
    private var currentRoundRemainingMs: Long = 0L
    @Volatile private var trainingResting: Boolean = false
    private var lastDisplayedCount = 0
    private var lastSpokenCountdown: Int? = null
    private var goSpoken = false
    private var tts: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null
    private var immersiveAudioJob: Job? = null
    @Volatile private var immersiveAudioTrack: AudioTrack? = null
    private var ttsInitialized = false
    private var ttsReady = false
    private var ttsLocaleInUse: Locale? = null
    private val ttsCompletionCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private var latestReport: TrainingReport? = null
    private val trainingRoundReports = mutableListOf<TrainingReport>()
    private var activationState: ActivationState? = null
    private var installId: String = ""
    private var deviceHash: String = ""
    private var authStatusMessageKey: String? = null
    private var authStatusFallbackMessage: String? = null
    private var authStatusColor: Int = Color.parseColor("#FFD060")
    private var cloudJob: Job? = null
    private var cloudProfile: CloudUserProfile? = null
    private var cloudStatistics: CloudUserStatistics? = null
    private var cloudHistory: List<CloudTrainingHistoryItem> = emptyList()
    private var cloudAchievements: List<CloudAchievementItem> = emptyList()
    private var cloudTier: CloudTierProgress? = null
    private var leaderboardResult: CloudLeaderboardResult? = null
    private var leaderboardBoard: LeaderboardBoard = LeaderboardBoard.TrainingDuration
    private var cloudStatusMessageKey: String? = null
    private var cloudStatusFallbackMessage: String? = null
    private var cloudStatusColor: Int = Color.parseColor("#FFD060")
    private var trainingBluetoothReconnectJob: Job? = null
    private var bluetoothAutoConnectInProgress: Boolean = false
    private var bluetoothLastAutoConnectStartedMs: Long = 0L
    private var pendingAvatarSelection: ((Uri?) -> Unit)? = null
    private var autoRestoreAttempted = false
    private var splashDismissed = false
    private var celebrationShowing = false
    private var dismissingCelebrationForTraining = false
    private var activeCelebrationDialog: AlertDialog? = null
    private val celebrationQueue: ArrayDeque<() -> Unit> = ArrayDeque()

    private var trainingSessionId: String = ""
    private var trainingStartedElapsedMs: Long = 0L
    private var trainingPeakForceN: Float = 0f
    private var trainingCurrentBpm: Float = 0f
    private var trainingPerfectBeats: Int = 0
    private var trainingGoodBeats: Int = 0
    private var trainingMissBeats: Int = 0
    private var trainingLastEvaluatedBeat: Int = -1
    private val trainingPunchTimesMs: ArrayDeque<Long> = ArrayDeque()
    private val trainingForceSamples: ArrayDeque<Float> = ArrayDeque()
    private val trainingPunchEvents = mutableListOf<PunchEvent>()
    private val trainingComboEvents = mutableListOf<ComboEvent>()
    private val trainingComboCounts = linkedMapOf<String, Int>()
    private var trainingLastCoachCueKey: String? = null
    private var trainingLastCoachCueElapsedMs: Long = -30_000L
    private var trainingLastCoachSpeechElapsedMs: Long = -120_000L
    private val trainingDeliveredCoachCues = mutableSetOf<String>()

    private val activationService = ActivationService()
    private val cloudSyncService = CloudSyncService()
    private var cloudSoundEffects: List<CloudSoundEffect> = emptyList()
    private var cloudSoundEffectsMessage: String? = null
    private var cloudSoundEffectsLoadingJob: Job? = null
    private var selectedCloudSoundEffectId: String = ""
    private var selectedCloudSoundEffectName: String = ""
    private var selectedCloudSoundEffectUrl: String = ""
    private var cloudEffectPreviewPlayer: MediaPlayer? = null
    private var cloudEffectPreviewJob: Job? = null
    private var cloudEffectSoundPool: SoundPool? = null
    private var loadedCloudEffectId: String? = null
    private var loadedCloudEffectSampleId: Int = 0
    private var loadingCloudEffectId: String? = null
    private var cloudBackgroundMusic: List<CloudBackgroundMusic> = emptyList()
    private var cloudBackgroundMusicMessage: String? = null
    private var cloudBackgroundMusicLoadingJob: Job? = null
    private var selectedBackgroundMusicId: String = ""
    private var selectedBackgroundMusicName: String = ""
    private var selectedBackgroundMusicUrl: String = ""
    private var backgroundMusicPreviewPlayer: MediaPlayer? = null
    private var backgroundMusicPreviewJob: Job? = null
    private var trainingBackgroundMusicPlayer: MediaPlayer? = null
    private var trainingBackgroundMusicPreparingId: String? = null

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var promotionBannerView: TextView
    private lateinit var trainingHeroCard: LinearLayout
    private lateinit var trainingHeroBadgeView: TextView
    private lateinit var trainingHeroHeadlineView: TextView
    private lateinit var trainingHeroSummaryView: TextView
    private lateinit var trainingHeroInsightView: TextView
    private lateinit var trainingHeroProgressView: TextView
    private lateinit var shareTrainingButton: Button
    private lateinit var modeTitleView: TextView
    private lateinit var reportTitleView: TextView
    private lateinit var profileTitleView: TextView
    private lateinit var profileSubtitleView: TextView
    private lateinit var profileCard: LinearLayout
    private lateinit var profileAvatarShell: FrameLayout
    private lateinit var profileAvatarImageView: ImageView
    private lateinit var profileAvatarFallbackView: TextView
    private lateinit var profileHeroTagView: TextView
    private lateinit var profileHeroBadgeView: TextView
    private lateinit var profileSummaryView: TextView
    private lateinit var profileMetaView: TextView
    private lateinit var profileTierView: TextView
    private lateinit var profileStatsView: TextView
    private lateinit var profileBadgesView: TextView
    private lateinit var cloudStatusView: TextView
    private lateinit var editProfileButton: Button
    private lateinit var refreshCloudButton: Button
    private lateinit var developerInfoButton: Button
    private lateinit var historyTitleView: TextView
    private lateinit var historySubtitleView: TextView
    private lateinit var historyCard: LinearLayout
    private lateinit var historyListRecycler: RecyclerView
    private lateinit var historyEmptyView: LinearLayout
    private lateinit var historyItemAdapter: HistoryItemAdapter
    private lateinit var historyView: TextView
    private lateinit var leaderboardTitleView: TextView
    private lateinit var leaderboardSubtitleView: TextView
    private lateinit var leaderboardCard: LinearLayout
    private lateinit var leaderboardPodiumContainer: LinearLayout
    private lateinit var leaderboardListRecycler: RecyclerView
    private lateinit var leaderboardRowAdapter: LeaderboardRowAdapter
    private lateinit var leaderboardMeCard: LinearLayout
    private lateinit var leaderboardMeTitleView: TextView
    private lateinit var leaderboardMeView: TextView
    private lateinit var shareLeaderboardButton: Button
    private lateinit var leaderboardModeGroup: RadioGroup
    private lateinit var leaderboardDurationButton: RadioButton
    private lateinit var leaderboardTotalHitsButton: RadioButton
    private lateinit var leaderboardPeakForceButton: RadioButton
    private lateinit var leaderboardAvgForceButton: RadioButton
    private lateinit var leaderboardCaloriesButton: RadioButton
    private lateinit var leaderboardFatButton: RadioButton
    private lateinit var refreshLeaderboardButton: Button
    private lateinit var leaderboardView: TextView
    private lateinit var achievementsTitleView: TextView
    private lateinit var achievementsSubtitleView: TextView
    private lateinit var achievementsCard: LinearLayout
    private lateinit var achievementsGridContainer: LinearLayout
    private lateinit var achievementsSummaryView: TextView
    private lateinit var shareAchievementsButton: Button
    private lateinit var statusView: TextView
    private lateinit var countdownView: TextView
    private lateinit var countView: TextView
    private lateinit var remainingView: TextView
    private lateinit var reportView: LinearLayout
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var bluetoothHeaderIndicatorView: ImageView
    private lateinit var batteryHeaderView: TextView
    private lateinit var splashOverlay: FrameLayout
    private lateinit var splashBrandCard: LinearLayout
    private lateinit var quietIconView: ImageView
    private lateinit var modeGroup: RadioGroup
    private lateinit var mode30Button: RadioButton
    private lateinit var mode60Button: RadioButton
    private lateinit var modeBurst10Button: RadioButton
    private lateinit var modeBurst15Button: RadioButton
    private lateinit var modeLevelButton: RadioButton
    private lateinit var modeDailyButton: RadioButton
    private lateinit var trainingPlayCard: LinearLayout
    private lateinit var trainingPlayTitleView: TextView
    private lateinit var trainingPlayBodyView: TextView
    private lateinit var trainingPlayProgressView: TextView
    private lateinit var realtimeDashboardCard: LinearLayout
    private lateinit var timerRingView: CircularTimerView
    private lateinit var dashboardRoundBadgeView: TextView
    private lateinit var dashboardPunchValueView: TextView
    private lateinit var dashboardBpmValueView: TextView
    private lateinit var dashboardCaloriesValueView: TextView
    private lateinit var dashboardFatValueView: TextView
    private lateinit var dashboardPeakValueView: TextView
    private lateinit var dashboardRhythmValueView: TextView
    private lateinit var dashboardPeakTagView: TextView
    private lateinit var dashboardGoalProgressView: TextView
    private lateinit var dashboardGoalProgressTrackView: FrameLayout
    private lateinit var dashboardGoalProgressFillView: View
    private lateinit var dashboardForceSummaryView: TextView
    private lateinit var dashboardTrainingSettingsButton: LinearLayout
    private lateinit var dashboardComboContainer: LinearLayout
    private lateinit var dashboardComboSummaryView: TextView
    private var dashboardCenterCueText: String? = null
    private var dashboardCenterCueCaption: String? = null
    private var dashboardCenterCueColor: Int? = null
    private lateinit var waveformView: PunchWaveformView
    private lateinit var homeConnectionStatusView: TextView
    private lateinit var homeReportHitsValueView: TextView
    private lateinit var homeReportPeakValueView: TextView
    private lateinit var homeReportAvgValueView: TextView
    private lateinit var homeGoalPercentView: TextView
    private lateinit var homeGoalNextBadgeView: TextView
    private lateinit var aiCoachCard: LinearLayout
    private lateinit var aiCoachStatusView: TextView
    private lateinit var aiCoachMessageView: TextView
    private lateinit var aiCoachMetaView: TextView
    private lateinit var aiCoachVoiceBar: LinearLayout
    private lateinit var musicImmersionCard: LinearLayout
    private lateinit var rhythmFreeButton: RadioButton
    private lateinit var rhythmBeatButton: RadioButton
    private lateinit var beat40Button: RadioButton
    private lateinit var beat65Button: RadioButton
    private lateinit var beat80Button: RadioButton
    private lateinit var beat100Button: RadioButton
    private lateinit var beat120Button: RadioButton
    private lateinit var soundGymButton: RadioButton
    private lateinit var soundStreetButton: RadioButton

    private lateinit var activationCard: LinearLayout
    private lateinit var activationTitleView: TextView
    private lateinit var activationHintView: TextView
    private lateinit var serialInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var serialInputErrorView: TextView
    private lateinit var codeInputErrorView: TextView
    private var activationInputsValid: Boolean = false
    private lateinit var activateButton: Button
    private lateinit var authStatusView: TextView
    private lateinit var activationDetailsView: TextView
    private lateinit var pageTabsCard: LinearLayout
    private lateinit var pageTrainingButton: TextView
    private lateinit var pageAchievementsButton: TextView
    private lateinit var pageLeaderboardButton: TextView
    private lateinit var pageProfileButton: TextView
    private lateinit var pageHost: FrameLayout
    private lateinit var contentRootView: LinearLayout
    private lateinit var trainingWatermarkPage: FrameLayout
    private lateinit var trainingSwipe: SwipeRefreshLayout
    private var trainingScrollView: ScrollView? = null
    private lateinit var achievementsSwipe: SwipeRefreshLayout
    private lateinit var leaderboardSwipe: SwipeRefreshLayout
    private lateinit var profileSwipe: SwipeRefreshLayout
    private lateinit var pageTrainingContainer: LinearLayout
    private lateinit var pageAchievementsContainer: LinearLayout
    private lateinit var pageLeaderboardContainer: LinearLayout
    private lateinit var pageProfileContainer: LinearLayout
    private val hideActivationCardRunnable =
        Runnable {
            if (isActivated() && trainingJob?.isActive != true) {
                setActivationVisible(false)
                clearAuthStatusMessage()
            }
        }

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    private val sensorBallBluetooth by lazy {
        SensorBallBluetoothManager(
            this,
            object : SensorBallBluetoothCallback {
                override fun onStatus(message: String) {
                    runOnUiThread {
                        bluetoothStatusMessage = bluetoothManagerStatusText(message)
                        updateBluetoothSettingsViews()
                    }
                }

                override fun onDevicesChanged(devices: List<SensorBallDevice>) {
                    runOnUiThread {
                        bluetoothDevices.clear()
                        bluetoothDevices.addAll(devices)
                        if (bluetoothConnectedDevice == null && devices.size == 1) {
                            selectedBluetoothDevice = devices.first()
                        }
                        bluetoothStatusMessage =
                            if (devices.isEmpty()) {
                                bluetoothStatusMessage
                            } else {
                                if (devices.size == 1) {
                                    bluetoothAutoSelectedText(devices.first().name)
                                } else {
                                    bluetoothDevicesFoundText(devices.size)
                                }
                            }
                        updateBluetoothSettingsViews()
                    }
                }

                override fun onConnected(device: SensorBallDevice) {
                    runOnUiThread {
                        selectedBluetoothDevice = device
                        bluetoothConnectedDevice = device
                        rememberBluetoothDevice(device)
                        saveLastBluetoothDevice(device)
                        bluetoothBatteryText = localText("读取中", "Reading", "Lecture", "กำลังอ่าน")
                        bluetoothBatteryRaw = null
                        bluetoothHitCount = 0
                        bluetoothPeakText = "--"
                        bluetoothRealHitCount = 0
                        lastBluetoothGyroRawCount = null
                        pendingBluetoothGyroHitTimes.clear()
                        bluetoothAutoConnectInProgress = false
                        trainingBluetoothReconnectJob?.cancel()
                        trainingBluetoothReconnectJob = null
                        if (trainingJob?.isActive == true) {
                            bluetoothGyroscopeEnabled = false
                            bluetoothStatusMessage = bluetoothTrainingReconnectedText(device.name)
                            if (trainingAcceptingPunches && !trainingResting) {
                                lifecycleScope.launch(Dispatchers.Main) {
                                    delay(350L)
                                    setTrainingGyroscopeEnabled(true, reportFailure = false)
                                }
                            } else {
                                updateBluetoothSettingsViews()
                            }
                        } else {
                            ensureGyroscopeOffAfterConnection()
                            bluetoothStatusMessage = bluetoothConnectedText(device.name)
                        }
                        updateBluetoothSettingsViews()
                    }
                }

                override fun onDisconnected() {
                    runOnUiThread {
                        bluetoothConnectedDevice = null
                        selectedBluetoothDevice = null
                        bluetoothDevices.clear()
                        bluetoothBatteryText = "--"
                        bluetoothBatteryRaw = null
                        pendingBluetoothGyroHitTimes.clear()
                        bluetoothAutoConnectInProgress = false
                        bluetoothStatusMessage =
                            if (trainingJob?.isActive == true) {
                                bluetoothTrainingReconnectText()
                            } else {
                                bluetoothDisconnectedText()
                            }
                        updateBluetoothSettingsViews()
                        scheduleTrainingBluetoothReconnect()
                    }
                }

                override fun onTelemetry(telemetry: SensorBallTelemetry) {
                    runOnUiThread {
                        var forceSettingsRefresh = false
                        telemetry.batteryRaw.takeIf { it in 0..102 }?.let { batteryRaw ->
                            if (trainingJob?.isActive == true) {
                                deferredTrainingBatteryRaw = batteryRaw
                            } else {
                                forceSettingsRefresh = bluetoothBatteryRaw != batteryRaw
                                bluetoothBatteryRaw = batteryRaw
                                bluetoothBatteryText = bluetoothBatteryDisplayText(batteryRaw)
                            }
                        }
                        bluetoothPeakText = telemetry.forceN.toString()
                        updateBluetoothGyroHitCount(telemetry.hitCount)
                        bluetoothStatusMessage = bluetoothPacketReceivedText(telemetry.packetIndex)
                        updateBluetoothSettingsViewsFromTelemetry(force = forceSettingsRefresh)
                    }
                }
            },
        )
    }
    private val bluetoothDevices = mutableListOf<SensorBallDevice>()
    private var selectedBluetoothDevice: SensorBallDevice? = null
    private var bluetoothConnectedDevice: SensorBallDevice? = null
    private var bluetoothStatusMessage: String = bluetoothDisconnectedText()
    private var bluetoothBatteryText: String = "--"
    private var bluetoothBatteryRaw: Int? = null
    private var deferredTrainingBatteryRaw: Int? = null
    private var bluetoothHitCount: Int? = null
    private var bluetoothPeakText: String = "--"
    private var bluetoothRealHitCount: Int = 0
    private var lastBluetoothGyroRawCount: Int? = null
    private val pendingBluetoothGyroHitTimes = ArrayDeque<Long>()
    private var bluetoothGyroscopeEnabled: Boolean = false
    private var lastBluetoothSettingsRefreshElapsedMs: Long = 0L
    private var lastSecondaryHomeRefreshElapsedMs: Long = 0L
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var bluetoothStatusView: TextView? = null
    private var bluetoothDeviceListView: LinearLayout? = null
    private var bluetoothBatteryView: TextView? = null
    private var bluetoothHitCountView: TextView? = null
    private var bluetoothScanButton: Button? = null
    private var bluetoothConnectButton: Button? = null
    private var bluetoothDisconnectButton: Button? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startTraining()
            } else {
                renderError(tr("permission_required"))
            }
        }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = requiredBluetoothPermissions().all { permission -> grants[permission] == true }
            val action = pendingBluetoothAction
            pendingBluetoothAction = null
            if (allGranted) {
                action?.invoke()
            } else {
                bluetoothStatusMessage = bluetoothPermissionDeniedText()
                updateBluetoothSettingsViews()
                Toast.makeText(this, bluetoothPermissionDeniedText(), Toast.LENGTH_SHORT).show()
            }
        }

    private val avatarPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingAvatarSelection
            pendingAvatarSelection = null
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
            }
            callback?.invoke(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        loadSettings()
        ensureInstallIdentity()
        loadActivationState()
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        initTextToSpeech()
        renderIdle()
        refreshCloudData(forceLeaderboard = true)
        startLaunchSplash()
        contentRootView.post { autoConnectLastBluetoothDevice() }
        contentRootView.postDelayed({ maybeShowBluetoothFirstUseGuide() }, 1200L)
    }

    override fun onDestroy() {
        trainingJob?.cancel()
        trainingBluetoothReconnectJob?.cancel()
        activationJob?.cancel()
        cloudJob?.cancel()
        cloudSoundEffectsLoadingJob?.cancel()
        cloudBackgroundMusicLoadingJob?.cancel()
        sensorBallBluetooth.close()
        tts?.stop()
        tts?.shutdown()
        stopImmersiveTrainingAudio()
        stopCloudEffectPreview()
        stopBackgroundMusicPreview()
        cloudEffectSoundPool?.release()
        cloudEffectSoundPool = null
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#F0FFFB"))
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        val contentRoot =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        contentRootView = contentRoot
        applySystemBarInsets(root, contentRoot)
        val topContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        pageHost =
            FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1.0f,
                    )
            }

        val headerRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
            }
        val headerTextColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1.0f,
                    )
            }

        titleView =
            titleText("", 20f).apply {
                gravity = Gravity.START
                translationY = -dp(2).toFloat()
            }
        subtitleView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, dp(12), 0)
                translationY = -dp(2).toFloat()
            }
        headerTextColumn.addView(titleView)
        val deviceStatusRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, 0)
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = 0
                    }
            }
        bluetoothHeaderIndicatorView =
            ImageView(this).apply {
                setImageResource(R.drawable.ic_bluetooth_universal)
                setColorFilter(Color.parseColor("#17343B"))
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) }
            }
        batteryHeaderView =
            TextView(this).apply {
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#17343B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                includeFontPadding = false
                setPadding(0, 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(16))
            }
        deviceStatusRow.addView(bluetoothHeaderIndicatorView)
        deviceStatusRow.addView(batteryHeaderView)
        headerTextColumn.addView(deviceStatusRow)
        headerTextColumn.addView(subtitleView)
        headerRow.addView(headerTextColumn)

        settingsButton =
            ImageButton(this).apply {
                setImageResource(R.drawable.home_icon_settings)
                setBackgroundColor(Color.TRANSPARENT)
                clearColorFilter()
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(0, 0, 0, 0)
                translationY = 0f
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                setOnClickListener {
                    if (trainingJob?.isActive != true) {
                        showFormalSettingsDialog()
                    }
            }
        }
        topContainer.addView(headerRow)
        updateHeaderBluetoothStatus()

        promotionBannerView =
            bodyText("").apply {
                visibility = View.GONE
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = roundedBackground("#10BDAA", "#8BEDE2", 22)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(8)
                    }
                alpha = 0f
                translationY = -dp(12).toFloat()
            }
        topContainer.addView(promotionBannerView)

        activationCard =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = surfaceCardBackground()
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(12)
                    }
            }
        activationTitleView = sectionLabel("")
        activationHintView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#DFFFF0"))
                setPadding(0, 0, 0, dp(10))
            }
        serialInput =
            activationInput("").apply {
                filters = arrayOf(InputFilter.LengthFilter(11))
            }
        serialInputErrorView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFB347"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(2), dp(4), 0, 0)
                visibility = View.GONE
            }
        codeInput =
            activationInput("").apply {
                filters = arrayOf(InputFilter.LengthFilter(8))
            }
        codeInputErrorView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFB347"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(2), dp(4), 0, 0)
                visibility = View.GONE
            }
        activateButton =
            actionButton("", "#10BDAA").apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setOnClickListener { activateDevice() }
            }
        serialInput.doAfterTextChanged {
            updateActivationInputState()
        }
        codeInput.doAfterTextChanged {
            updateActivationInputState()
        }
        authStatusView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFD060"))
                setPadding(0, dp(10), 0, 0)
            }
        activationDetailsView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#557A7D"))
                setPadding(0, dp(12), 0, 0)
                visibility = View.GONE
            }
        activationCard.addView(activationTitleView)
        activationCard.addView(activationHintView)
        activationCard.addView(serialInput)
        activationCard.addView(serialInputErrorView)
        activationCard.addView(spacer(dp(8)))
        activationCard.addView(codeInput)
        activationCard.addView(codeInputErrorView)
        activationCard.addView(spacer(dp(12)))
        activationCard.addView(activateButton)
        activationCard.addView(authStatusView)
        activationCard.addView(activationDetailsView)
        activationCard.visibility = View.GONE
        topContainer.addView(activationCard)
        updateActivationInputState()

        pageTabsCard =
            surfaceCard().apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(4)
                    }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = bottomNavBackground()
                elevation = dp(6).toFloat()
            }
        pageTrainingButton = homePageButton { selectHomePage(HomePage.TrainingCenter) }
        pageAchievementsButton = homePageButton { selectHomePage(HomePage.TrainingAchievements) }
        pageLeaderboardButton = homePageButton { selectHomePage(HomePage.Leaderboard) }
        pageProfileButton = homePageButton { selectHomePage(HomePage.Profile) }
        pageTabsCard.addView(pageTrainingButton)
        pageTabsCard.addView(horizontalSpace(dp(4)))
        pageTabsCard.addView(pageAchievementsButton)
        pageTabsCard.addView(horizontalSpace(dp(4)))
        pageTabsCard.addView(pageLeaderboardButton)
        pageTabsCard.addView(horizontalSpace(dp(4)))
        pageTabsCard.addView(pageProfileButton)
        pageTrainingContainer = pageContentContainer().apply {
            setPadding(dp(10), 0, dp(10), dp(12))
        }
        trainingSwipe = wrapInSwipeRefresh(pageTrainingContainer, enabled = false)
        trainingScrollView = findScrollViewChild(trainingSwipe)
        trainingWatermarkPage = buildTrainingWatermarkPage(trainingSwipe)
        pageHost.addView(trainingWatermarkPage)

        pageAchievementsContainer = pageContentContainer().apply {
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        achievementsSwipe = wrapInSwipeRefresh(pageAchievementsContainer, enabled = true) {
            refreshCloudData(forceLeaderboard = false)
        }
        pageHost.addView(achievementsSwipe)

        pageLeaderboardContainer = pageContentContainer().apply {
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        leaderboardSwipe = wrapInSwipeRefresh(pageLeaderboardContainer, enabled = true) {
            refreshLeaderboardOnly()
        }
        pageHost.addView(leaderboardSwipe)

        pageProfileContainer = pageContentContainer().apply {
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        profileSwipe = wrapInSwipeRefresh(pageProfileContainer, enabled = true) {
            refreshCloudData(forceLeaderboard = true)
        }
        pageHost.addView(profileSwipe)

        trainingHeroCard =
            detailCard(fillColor = "#F3FFFC", strokeColor = "#C9F0E9", cornerDp = 24).apply {
                background = roundedBackground("#F3FFFC", "#C9F0E9", 24)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(10)
                        bottomMargin = dp(8)
                    }
            }
        trainingHeroBadgeView =
            badgeText(
                text = "",
                textColor = "#096D65",
                fillColor = "#DFFFF7",
            ).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        trainingHeroHeadlineView =
            titleText("", 21f).apply {
                gravity = Gravity.START
                setTextColor(Color.parseColor("#17343B"))
                setPadding(0, dp(10), 0, 0)
            }
        trainingHeroSummaryView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(8), 0, 0)
            }
        trainingHeroInsightView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#245A60"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(10), 0, 0)
            }
        trainingHeroProgressView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#0CA99A"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(12), 0, 0)
            }
        shareTrainingButton =
            compactActionButton("", "#16C8B5").apply {
                minHeight = dp(52)
                minimumHeight = dp(52)
                setTextColor(Color.WHITE)
                background = roundedBackground("#16C8B5", "#6AF2E7", 999)
                setOnClickListener { shareTrainingSummary() }
            }
        trainingHeroCard.addView(trainingHeroBadgeView)
        trainingHeroCard.addView(trainingHeroHeadlineView)
        trainingHeroCard.addView(trainingHeroSummaryView)
        trainingHeroCard.addView(trainingHeroInsightView)
        trainingHeroCard.addView(trainingHeroProgressView)

        val trainingControlShell =
            FrameLayout(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setBackgroundColor(Color.TRANSPARENT)
                elevation = 0f
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                        }
                    }
                clipToOutline = false
            }
        val trainingControlCard =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        modeTitleView = sectionTitle("")

        modeGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                gravity = Gravity.START
                setPadding(0, dp(2), 0, dp(10))
            }
        mode30Button =
            RadioButton(this).apply {
                id = View.generateViewId()
                isChecked = true
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        mode60Button =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        modeBurst10Button =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        modeBurst15Button =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        modeLevelButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        modeDailyButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        configureModeButton(mode30Button)
        configureModeButton(mode60Button)
        configureModeButton(modeBurst10Button)
        configureModeButton(modeBurst15Button)
        configureModeButton(modeLevelButton)
        configureModeButton(modeDailyButton)
        modeGroup.addView(mode30Button)
        modeGroup.addView(mode60Button)
        modeGroup.addView(modeBurst10Button)
        modeGroup.addView(modeBurst15Button)
        modeGroup.addView(modeLevelButton)
        modeGroup.addView(modeDailyButton)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedPlayMode = trainingPlayModeForCheckedId(checkedId)
            selectedMode = modeForPlayMode(selectedPlayMode)
            prefs.edit().putString(KEY_SELECTED_PLAY_MODE, selectedPlayMode.name).apply()
            if (trainingJob?.isActive != true) {
                remainingView.text = displayRemaining(selectedMode.durationSeconds * 1_000L)
            }
            refreshModeButtonStyles()
            renderTrainingPlayStatus()
        }

        trainingPlayCard =
            detailCard(fillColor = "#EFFFFA", strokeColor = "#BFEFE5", cornerDp = 22).apply {
                background = roundedBackground("#EFFFFA", "#BFEFE5", 22)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(4)
                        bottomMargin = 0
                    }
            }
        trainingPlayTitleView =
            titleText("", 18f).apply {
                gravity = Gravity.START
                setTextColor(Color.parseColor("#096D65"))
            }
        trainingPlayBodyView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(0, dp(8), 0, 0)
            }
        trainingPlayProgressView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FF8A32"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(10), 0, 0)
            }
        trainingPlayCard.addView(trainingPlayTitleView)
        trainingPlayCard.addView(trainingPlayBodyView)
        trainingPlayCard.addView(trainingPlayProgressView)

        quietIconView =
            ImageView(this).apply {
                setImageResource(android.R.drawable.ic_lock_silent_mode)
                setColorFilter(Color.parseColor("#10BDAA"))
                layoutParams =
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                visibility = View.GONE
                alpha = 0.95f
            }
        trainingControlCard.addView(quietIconView)

        statusView =
            bodyText("").apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                visibility = View.GONE
            }
        trainingControlCard.addView(statusView)

        countdownView =
            titleText("3", 40f).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FF8A32"))
                setPadding(0, dp(6), 0, dp(2))
                visibility = View.GONE
            }
        trainingControlCard.addView(countdownView)

        countView =
            titleText("0", 72f).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#17343B"))
                setPadding(0, dp(6), 0, dp(2))
                visibility = View.GONE
            }
        trainingControlCard.addView(countView)

        remainingView =
            bodyText("").apply {
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FF8A32"))
                setPadding(0, 0, 0, dp(12))
                visibility = View.GONE
            }
        trainingControlCard.addView(remainingView)

        realtimeDashboardCard = buildRealtimeDashboardCard()
        trainingControlCard.addView(realtimeDashboardCard)
        aiCoachCard =
            buildAiCoachRealtimeCard().apply {
                visibility = View.GONE
            }
        trainingControlShell.addView(trainingControlCard)
        pageTrainingContainer.addView(buildHomeHeroCard())
        pageTrainingContainer.addView(trainingControlShell)
        pageTrainingContainer.addView(buildHomeForceCard())
        pageTrainingContainer.addView(buildHomeConnectionReportCard())
        pageTrainingContainer.addView(buildHomeGoalAchievementCard())
        pageTrainingContainer.addView(aiCoachCard)

        reportTitleView = sectionTitle("")
        pageTrainingContainer.addView(reportTitleView)
        reportView =
            surfaceCard().apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(6)
                    }
            }
        pageTrainingContainer.addView(reportView)

        profileTitleView = sectionTitle("")
        pageProfileContainer.addView(profileTitleView)
        profileSubtitleView = sectionSubtitle("")
        pageProfileContainer.addView(profileSubtitleView)
        profileCard =
            surfaceCard().apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(6)
                        bottomMargin = dp(8)
                    }
            }
        val profileHeroShell =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground("#EFFFFA", "#BFEFE5", 28)
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
        val profileHeroRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
        profileAvatarShell =
            FrameLayout(this).apply {
                background = avatarBackground("#CC4400")
                clipToOutline = true
                elevation = dp(4).toFloat()
                layoutParams =
                    LinearLayout.LayoutParams(dp(74), dp(74)).apply {
                        rightMargin = dp(16)
                    }
            }
        profileAvatarImageView =
            ImageView(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                visibility = View.GONE
            }
        profileAvatarFallbackView =
            TextView(this).apply {
                text = "R"
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        profileAvatarShell.addView(profileAvatarImageView)
        profileAvatarShell.addView(profileAvatarFallbackView)
        val profileHeadlineColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1.0f,
                    )
            }
        profileHeroBadgeView =
            badgeText(
                text = "",
                textColor = "#096D65",
                fillColor = "#DFFFF7",
            ).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        profileSummaryView =
            titleText("", 24f).apply {
                gravity = Gravity.START
                setTextColor(Color.parseColor("#17343B"))
            }
        profileMetaView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            }
        profileTierView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#0CA99A"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(10), 0, 0)
            }
        profileStatsView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#17343B"))
                setPadding(0, dp(14), 0, 0)
            }
        profileBadgesView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(12), 0, 0)
            }
        cloudStatusView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#0CA99A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
        profileHeadlineColumn.addView(profileHeroBadgeView)
        profileHeadlineColumn.addView(profileSummaryView)
        profileHeadlineColumn.addView(profileMetaView)
        profileHeadlineColumn.addView(profileTierView)
        profileHeroTagView =
            TextView(this).apply {
                text = profileHeroTagText()
                setTextColor(Color.parseColor("#096D65"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = roundedBackground("#DFFFF7", "#BFEFE5", 999)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
        profileHeroShell.addView(profileHeroTagView)
        profileHeroRow.addView(profileAvatarShell)
        profileHeroRow.addView(profileHeadlineColumn)
        profileHeroShell.addView(profileHeroRow)
        val profileActionRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(16), 0, 0)
            }
        editProfileButton =
            compactActionButton("", "#10BDAA").apply {
                setOnClickListener { showEditProfileDialog() }
            }
        refreshCloudButton =
            compactActionButton("", "#FF8A32").apply {
                setOnClickListener {
                    profileSwipe.isRefreshing = true
                    refreshCloudData(forceLeaderboard = true)
                }
            }
        profileActionRow.addView(editProfileButton)
        profileActionRow.addView(horizontalSpace(dp(12)))
        profileActionRow.addView(refreshCloudButton)
        developerInfoButton =
            compactActionButton("", "#10BDAA").apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(12)
                    }
                setOnClickListener { showDeveloperInfoDialog() }
            }
        profileCard.addView(profileHeroShell)
        profileCard.addView(profileStatsView)
        profileCard.addView(profileBadgesView)
        profileCard.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(14), 0, 0)
                addView(cloudStatusView)
            },
        )
        profileCard.addView(profileActionRow)
        profileCard.addView(developerInfoButton)
        pageProfileContainer.addView(profileCard)

        achievementsTitleView = sectionTitle("")
        pageAchievementsContainer.addView(achievementsTitleView)
        achievementsSubtitleView = sectionSubtitle("")
        pageAchievementsContainer.addView(achievementsSubtitleView)
        achievementsCard =
            surfaceCard().apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(6)
                        bottomMargin = dp(8)
                    }
            }
        achievementsSummaryView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#557A7D"))
            }
        shareAchievementsButton =
            compactActionButton("", "#10BDAA").apply {
                setOnClickListener { shareAchievementsSummary() }
            }
        val achievementsHeaderRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        achievementsSummaryView.layoutParams =
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f,
            )
        achievementsHeaderRow.addView(achievementsSummaryView)
        achievementsHeaderRow.addView(shareAchievementsButton)
        achievementsGridContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(14), 0, 0)
            }
        achievementsCard.addView(achievementsHeaderRow)
        achievementsCard.addView(achievementsGridContainer)
        pageAchievementsContainer.addView(achievementsCard)

        historyTitleView = sectionTitle("")
        pageAchievementsContainer.addView(historyTitleView)
        historySubtitleView = sectionSubtitle("")
        pageAchievementsContainer.addView(historySubtitleView)
        historyCard = surfaceCard().apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(8)
                    }
        }
        historyItemAdapter = HistoryItemAdapter { item -> historySessionCard(item) }
        historyListRecycler =
            RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@MainActivity)
                adapter = historyItemAdapter
                isNestedScrollingEnabled = false
                addItemDecoration(VerticalSpacingDecoration(dp(10)))
            }
        historyEmptyView =
            emptyStateCard(
                badge = historyEmptyBadgeText(),
                title = historyEmptyTitleText(),
                message = tr("no_history"),
                accentColor = "#FFB347",
            ).apply { visibility = View.GONE }
        historyView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        historyCard.addView(historyListRecycler)
        historyCard.addView(historyEmptyView)
        historyCard.addView(historyView)
        pageAchievementsContainer.addView(historyCard)

        leaderboardTitleView = sectionTitle("")
        pageLeaderboardContainer.addView(leaderboardTitleView)
        leaderboardSubtitleView = sectionSubtitle("")
        pageLeaderboardContainer.addView(leaderboardSubtitleView)
        leaderboardModeGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(4), 0, dp(8))
            }
        leaderboardDurationButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                isChecked = true
                configureLeaderboardFilterButton(this)
            }
        leaderboardTotalHitsButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                configureLeaderboardFilterButton(this)
            }
        leaderboardPeakForceButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                configureLeaderboardFilterButton(this)
            }
        leaderboardAvgForceButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                configureLeaderboardFilterButton(this)
            }
        leaderboardCaloriesButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                configureLeaderboardFilterButton(this)
            }
        leaderboardFatButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                configureLeaderboardFilterButton(this)
            }
        leaderboardModeGroup.addView(leaderboardDurationButton)
        leaderboardModeGroup.addView(leaderboardTotalHitsButton)
        leaderboardModeGroup.addView(leaderboardPeakForceButton)
        leaderboardModeGroup.addView(leaderboardAvgForceButton)
        leaderboardModeGroup.addView(leaderboardCaloriesButton)
        leaderboardModeGroup.addView(leaderboardFatButton)
        leaderboardModeGroup.setOnCheckedChangeListener { _, checkedId ->
            leaderboardBoard =
                when (checkedId) {
                    leaderboardTotalHitsButton.id -> LeaderboardBoard.TotalHits
                    leaderboardPeakForceButton.id -> LeaderboardBoard.PeakForce
                    leaderboardAvgForceButton.id -> LeaderboardBoard.AvgForce
                    leaderboardCaloriesButton.id -> LeaderboardBoard.Calories
                    leaderboardFatButton.id -> LeaderboardBoard.FatBurned
                    else -> LeaderboardBoard.TrainingDuration
                }
            leaderboardSubtitleView.text = leaderboardBoardSubtitle(leaderboardBoard)
            if (isActivated() && trainingJob?.isActive != true) {
                refreshLeaderboardOnly()
            }
        }
        pageLeaderboardContainer.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(leaderboardModeGroup)
            },
        )
        refreshLeaderboardButton =
            compactActionButton("", "#10BDAA").apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = dp(10)
                    }
                setOnClickListener {
                    leaderboardSwipe.isRefreshing = true
                    refreshLeaderboardOnly()
                }
            }
        pageLeaderboardContainer.addView(refreshLeaderboardButton)
        leaderboardCard =
            surfaceCard().apply {
                background = roundedBackground("#FFFFFF", "#BFEFE5", 24)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(2)
                    }
            }
        leaderboardPodiumContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
        leaderboardRowAdapter = LeaderboardRowAdapter { entry -> leaderboardRowCardPremium(entry) }
        leaderboardListRecycler =
            RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@MainActivity)
                adapter = leaderboardRowAdapter
                isNestedScrollingEnabled = false
                setPadding(0, dp(12), 0, 0)
                clipToPadding = false
                addItemDecoration(VerticalSpacingDecoration(dp(10)))
            }
        leaderboardMeCard =
            detailCard(fillColor = "#F7FFFD", strokeColor = "#CDEFE8", cornerDp = 20).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
        leaderboardMeTitleView =
            bodyText("").apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#0CA99A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
        leaderboardMeView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#17343B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(8), 0, 0)
            }
        shareLeaderboardButton =
            compactActionButton("", "#10BDAA").apply {
                setOnClickListener { shareLeaderboardSummary() }
            }
        val leaderboardMeHeaderRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        leaderboardMeTitleView.layoutParams =
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f,
            )
        leaderboardMeHeaderRow.addView(leaderboardMeTitleView)
        leaderboardMeHeaderRow.addView(shareLeaderboardButton)
        leaderboardView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        leaderboardCard.addView(leaderboardPodiumContainer)
        leaderboardCard.addView(leaderboardListRecycler)
        leaderboardMeCard.addView(leaderboardMeHeaderRow)
        leaderboardMeCard.addView(leaderboardMeView)
        leaderboardCard.addView(leaderboardMeCard)
        leaderboardCard.addView(leaderboardView)
        pageLeaderboardContainer.addView(leaderboardCard)

        applyStaticTexts()
        contentRoot.addView(topContainer)
        contentRoot.addView(pageHost)
        contentRoot.addView(
            pageTabsCard.apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = dp(14)
                        rightMargin = dp(14)
                        bottomMargin = dp(6)
                    }
            },
        )
        root.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.home_achievement_bg)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.05f
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            },
        )
        root.addView(contentRoot)
        splashOverlay = buildLaunchSplashOverlay()
        root.addView(splashOverlay)
        return root
    }

    private fun applySystemBarInsets(
        root: View,
        contentRoot: View,
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            contentRoot.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun buildLaunchSplashOverlay(): FrameLayout =
        FrameLayout(this).apply {
            layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            setBackgroundColor(Color.parseColor("#140800"))
            isClickable = true
            isFocusable = true
            alpha = 1f

            val splashHeroImage =
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.home_banner)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    alpha = 0.36f
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                }

            val topScrim =
                View(this@MainActivity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(220),
                            Gravity.TOP,
                        )
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.parseColor("#E608111A"), Color.parseColor("#1206001A")),
                        )
                }

            val bottomScrim =
                View(this@MainActivity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(260),
                            Gravity.BOTTOM,
                        )
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.BOTTOM_TOP,
                            intArrayOf(Color.parseColor("#F208111A"), Color.parseColor("#1206001A")),
                        )
                }

            val brandingColumn =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.START,
                        ).apply {
                            leftMargin = dp(26)
                            rightMargin = dp(26)
                            topMargin = dp(42)
                        }
                }

            val brandTitle =
                TextView(this@MainActivity).apply {
                    text = "HITRISE"
                    setTextColor(Color.parseColor("#FFF8E8"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    letterSpacing = 0.08f
                }

            val brandSubtitle =
                TextView(this@MainActivity).apply {
                    text = localText("智能拳击球训练", "HitRise Training", "Entraînement HitRise", "ฝึก HitRise")
                    setTextColor(Color.parseColor("#CAA26A"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(8), 0, 0)
                }

            splashBrandCard =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    alpha = 0f
                    scaleX = 0.92f
                    scaleY = 0.92f
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        ).apply {
                            leftMargin = dp(24)
                            rightMargin = dp(24)
                        }
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(
                                Color.parseColor("#FFFFFFFF"),
                                Color.parseColor("#FFF7FBFF"),
                            ),
                        ).apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(32).toFloat()
                            setStroke(dp(1), Color.parseColor("#BFEFF7F3"))
                        }
                    elevation = dp(8).toFloat()
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                    addView(
                        ImageView(this@MainActivity).apply {
                            setImageResource(R.drawable.wavemill_logo)
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    dp(300),
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                        },
                    )
                }

            val skipHint =
                TextView(this@MainActivity).apply {
                    text = localText("轻触跳过", "Tap to skip", "Touchez pour passer", "แตะเพื่อข้าม")
                    setTextColor(Color.parseColor("#B88A54"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                        ).apply {
                            bottomMargin = dp(34)
                        }
                }

            brandingColumn.addView(brandTitle)
            brandingColumn.addView(brandSubtitle)

            addView(splashHeroImage)
            addView(topScrim)
            addView(bottomScrim)
            addView(brandingColumn)
            addView(splashBrandCard)
            addView(skipHint)

            setOnClickListener { dismissLaunchSplash() }
        }

    private fun startLaunchSplash() {
        if (!::splashOverlay.isInitialized) {
            return
        }
        splashDismissed = false
        splashOverlay.visibility = View.VISIBLE
        splashOverlay.alpha = 1f
        if (::splashBrandCard.isInitialized) {
            splashBrandCard.animate().cancel()
            splashBrandCard.alpha = 0f
            splashBrandCard.scaleX = 0.78f
            splashBrandCard.scaleY = 0.78f
            splashBrandCard.rotationX = 8f
            splashBrandCard.translationY = dp(32).toFloat()
            splashBrandCard.animate()
                .alpha(1f)
                .scaleX(1.04f)
                .scaleY(1.04f)
                .rotationX(0f)
                .translationY(0f)
                .setStartDelay(180L)
                .setDuration(560L)
                .withEndAction {
                    if (!splashDismissed && ::splashBrandCard.isInitialized) {
                        splashBrandCard.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(220L)
                            .start()
                    }
                }
                .start()
        }
        splashOverlay.postDelayed({
            if (!splashDismissed) {
                dismissLaunchSplash()
            }
        }, 2200L)
    }

    private fun dismissLaunchSplash() {
        if (!::splashOverlay.isInitialized || splashDismissed) {
            return
        }
        splashDismissed = true
        if (::contentRootView.isInitialized) {
            contentRootView.alpha = 0.94f
            contentRootView.translationY = dp(10).toFloat()
            contentRootView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320L)
                .start()
        }
        if (::splashBrandCard.isInitialized) {
            splashBrandCard.animate().cancel()
            splashBrandCard.animate()
                .alpha(0f)
                .scaleX(0.86f)
                .scaleY(0.86f)
                .rotationX(-8f)
                .translationY(-dp(24).toFloat())
                .setDuration(360L)
                .start()
        }
        splashOverlay.animate()
            .alpha(0f)
            .setDuration(320L)
            .withEndAction {
                splashOverlay.visibility = View.GONE
                splashOverlay.alpha = 1f
            }
            .start()
    }

    private fun homePageButton(onClick: () -> Unit): TextView =
        bodyText("").apply {
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
            setPadding(dp(7), dp(7), dp(7), dp(7))
            compoundDrawablePadding = dp(4)
            isAllCaps = false
            minHeight = dp(58)
            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f,
                )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            applyRippleOverlay()
        }

    private fun pageContentContainer(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }

    private fun wrapInSwipeRefresh(
        content: View,
        enabled: Boolean,
        onRefresh: (() -> Unit)? = null,
    ): SwipeRefreshLayout {
        val palette = selectedPalette
        val scroll =
            ScrollView(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        scroll.addView(content)
        return SwipeRefreshLayout(this).apply {
            layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            isEnabled = enabled
            setColorSchemeColors(Color.parseColor(palette.accent), Color.parseColor(palette.accentHot))
            setProgressBackgroundColorSchemeColor(Color.parseColor(palette.surfaceBottom))
            if (onRefresh != null) {
                setOnRefreshListener { onRefresh() }
            }
            addView(scroll)
        }
    }

    private fun buildTrainingWatermarkPage(content: View): FrameLayout =
        FrameLayout(this).apply {
            layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            setBackgroundColor(Color.parseColor("#F0FFFB"))
            addView(
                ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.home_achievement_bg)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 0.08f
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                },
            )
            addView(content)
        }

    private fun bottomNavBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#FFFFFF"))
            setStroke(dp(1), Color.parseColor("#DDF4EF"))
        }

    private fun homePageIconRes(page: HomePage, selected: Boolean): Int =
        when (page) {
            HomePage.TrainingCenter -> if (selected) R.drawable.home_nav_training_selected else R.drawable.home_nav_training
            HomePage.TrainingAchievements -> if (selected) R.drawable.home_nav_achievements_selected else R.drawable.home_nav_achievements
            HomePage.Leaderboard -> if (selected) R.drawable.home_nav_leaderboard_selected else R.drawable.home_nav_leaderboard
            HomePage.Profile -> if (selected) R.drawable.home_nav_profile_selected else R.drawable.home_nav_profile
        }

    private fun selectHomePage(page: HomePage) {
        val previousPage = selectedHomePage
        selectedHomePage = page
        refreshHeaderSubtitle()
        refreshHomePageVisibility(previousPage)
    }

    private fun refreshHomePageVisibility(previousPage: HomePage? = null) {
        refreshHeaderSubtitle()
        pageTabsCard.visibility = View.VISIBLE
        trainingSwipe.visibility = if (selectedHomePage == HomePage.TrainingCenter) View.VISIBLE else View.GONE
        achievementsSwipe.visibility =
            if (selectedHomePage == HomePage.TrainingAchievements) View.VISIBLE else View.GONE
        leaderboardSwipe.visibility =
            if (selectedHomePage == HomePage.Leaderboard) View.VISIBLE else View.GONE
        profileSwipe.visibility = if (selectedHomePage == HomePage.Profile) View.VISIBLE else View.GONE
        updateHomePageTabs()
        if (previousPage != null && previousPage != selectedHomePage) {
            when (selectedHomePage) {
                HomePage.Leaderboard -> animatePageEntrance(leaderboardSwipe)
                HomePage.Profile -> animatePageEntrance(profileSwipe)
                else -> {}
            }
        }
    }

    private fun refreshHeaderSubtitle() {
        if (!::subtitleView.isInitialized) {
            return
        }
        val text = headerSubtitleText()
        subtitleView.text = text
        subtitleView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun updateHomePageTabs() {
        applyHomeTabStyle(pageTrainingButton, HomePage.TrainingCenter, selectedHomePage == HomePage.TrainingCenter, true)
        applyHomeTabStyle(pageAchievementsButton, HomePage.TrainingAchievements, selectedHomePage == HomePage.TrainingAchievements, true)
        applyHomeTabStyle(pageLeaderboardButton, HomePage.Leaderboard, selectedHomePage == HomePage.Leaderboard, true)
        applyHomeTabStyle(pageProfileButton, HomePage.Profile, selectedHomePage == HomePage.Profile, true)
    }

    private fun applyHomeTabStyle(
        button: TextView,
        page: HomePage,
        selected: Boolean,
        enabled: Boolean,
    ) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1.0f else 0.45f
        button.translationY = if (selected) -dp(2).toFloat() else 0f
        button.scaleX = if (selected) 1.03f else 1.0f
        button.scaleY = if (selected) 1.03f else 1.0f
        val icon =
            ContextCompat.getDrawable(this, homePageIconRes(page, selected))?.mutate()?.apply {
                setBounds(0, 0, dp(28), dp(28))
            }
        button.setCompoundDrawables(null, icon, null, null)
        if (selected) {
            button.setTextColor(Color.parseColor("#09A99A"))
            button.background = roundedBackground("#EFFFFA", "#BFEFE5", 22)
            button.elevation = dp(4).toFloat()
        } else {
            button.setTextColor(Color.parseColor("#6B7C80"))
            button.background = roundedBackground("#FFFFFF", "#FFFFFF", 22)
            button.elevation = 0f
        }
    }

    private fun animatePageEntrance(view: View) {
        view.alpha = 0f
        view.translationY = dp(12).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .start()
    }

    private fun ensurePermissionAndStart() {
        if (trainingJob?.isActive == true) {
            return
        }
        startTraining()
    }

    @Suppress("MissingPermission")










    private fun ensureGyroscopeOffAfterConnection() {
        bluetoothGyroscopeEnabled = false
        updateBluetoothSettingsViews()
    }

    private suspend fun setTrainingGyroscopeEnabled(
        enabled: Boolean,
        reportFailure: Boolean = false,
    ): Boolean {
        if (bluetoothConnectedDevice == null) {
            bluetoothGyroscopeEnabled = false
            updateBluetoothSettingsViews()
            return false
        }
        repeat(3) { attempt ->
            val accepted = sensorBallBluetooth.setGyroscopeEnabled(enabled, reportStatus = false)
            if (accepted) {
                bluetoothGyroscopeEnabled = enabled
                updateBluetoothSettingsViews()
                return true
            }
            delay(if (attempt == 0) 180L else 320L)
        }
        if (reportFailure) {
            bluetoothStatusMessage =
                if (enabled) {
                    localText(
                        "蓝牙计数通道未就绪，请保持设备连接后重试。",
                        "Bluetooth counting channel is not ready. Keep the device connected and retry.",
                        "Le canal de comptage Bluetooth n'est pas prêt. Gardez l'appareil connecté et réessayez.",
                        "ช่องนับผ่านบลูทูธยังไม่พร้อม โปรดเชื่อมต่ออุปกรณ์ไว้แล้วลองอีกครั้ง",
                    )
                } else {
                    localText(
                        "陀螺仪关闭指令未确认，已停止本地训练计数。",
                        "Gyro-off command was not confirmed. Local training count has been stopped.",
                        "La commande d'arrêt du gyroscope n'a pas été confirmée. Le comptage local est arrêté.",
                        "ยังไม่ยืนยันคำสั่งปิดไจโร หยุดนับการฝึกในเครื่องแล้ว",
                    )
                }
            updateBluetoothSettingsViews()
        }
        return false
    }

    private fun setTrainingGyroscopeEnabledAsync(enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            setTrainingGyroscopeEnabled(enabled, reportFailure = false)
        }
    }

    private fun startTraining() {
        if (trainingJob?.isActive == true) {
            return
        }
        if (!isActivated()) {
            renderActivationRequired(tr("activation_required"))
            return
        }
        if (bluetoothConnectedDevice == null) {
            renderError(bluetoothConnectFirstText())
            return
        }
        dismissCelebrationBeforeTraining()
        val sessionMode = selectedMode
        val sessionPlayMode = selectedPlayMode
        val sessionSetup = trainingSessionSetup
        partialTrainingUploadTriggered = false
        selectedRhythmMode = sessionSetup.rhythmMode
        selectedBeatBpm = sessionSetup.bpm
        lastDisplayedCount = 0
        lastSpokenCountdown = null
        goSpoken = false
        bluetoothTrainingCount = 0
        bluetoothTrainingMode = null
        trainingAcceptingPunches = false
        currentTrainingRound = 1
        currentTrainingRoundCount = sessionSetup.rounds
        currentRoundDurationMs = sessionSetup.workSeconds * 1_000L
        currentRoundRemainingMs = currentRoundDurationMs
        trainingResting = false
        lastBluetoothGyroRawCount = null
        bluetoothHitCount = 0
        resetRealtimeTrainingSession(sessionMode)
        countView.text = "0"
        countdownView.text = "3"
        statusView.text = displayCountdownStatus(3)
        statusView.setTextColor(Color.parseColor("#FFD060"))
        remainingView.text = displayRemaining(currentRoundRemainingMs)
        quietIconView.visibility = View.GONE
        setTrainingBusyUi(true)
        setActivationVisible(false)
        applyStaticTexts()
        scrollRealtimeTrainingToTop()

        trainingJob =
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    runPreTrainingCueSequence(currentRoundDurationMs)
                    if (!waitForInitialTrainingCountingReady()) {
                        throw IllegalStateException(
                            localText(
                                "蓝牙计数未启动，请检查设备连接后重试。",
                                "Bluetooth counting did not start. Check the device connection and retry.",
                                "Le comptage Bluetooth n'a pas démarré. Vérifiez la connexion puis réessayez.",
                                "เริ่มนับผ่านบลูทูธไม่สำเร็จ โปรดตรวจสอบการเชื่อมต่อแล้วลองอีกครั้ง",
                            ),
                        )
                    }
                    clearDashboardCenterCue()
                    trainingStartedElapsedMs = SystemClock.elapsedRealtime()
                    updateDashboardViews(currentRoundRemainingMs)
                    startImmersiveTrainingAudio()
                    pushAiCoachCue(
                        key = "round_start",
                        message =
                            localText(
                                "开练！先稳住节奏，肩膀放松，前 10 秒用轻拳找感觉。",
                                "Let's go. Settle into rhythm first, keep shoulders relaxed, and use light punches for the first 10 seconds.",
                                "C'est parti. Trouvez le rythme, épaules relâchées, coups légers pendant 10 secondes.",
                                "เริ่มได้ จับจังหวะก่อน ผ่อนหัวไหล่ แล้วใช้หมัดเบาใน 10 วินาทีแรก",
                            ),
                        meta =
                            localText(
                                "触发原因：回合开始 | AI 教练实时指导",
                                "Trigger: round started | AI live coaching",
                                "Déclencheur : début du round | coaching IA",
                                "สาเหตุ: เริ่มรอบ | โค้ช AI สด",
                            ),
                        speak = false,
                    )
                    runConfiguredTrainingRounds(sessionMode, sessionSetup)
                    val report =
                        buildBluetoothTrainingReport(
                            mode = sessionMode,
                            totalHits = bluetoothTrainingCount,
                            setup = sessionSetup,
                            completedRounds = sessionSetup.rounds,
                            includeRoundReports = true,
                        )
                    upsertRoundReport(report)
                    setTrainingGyroscopeEnabled(false, reportFailure = false)
                    stopImmersiveTrainingAudio()
                    lastCoachOutcome = updateTrainingGameAfterReport(report, sessionPlayMode)
                    lastCoachMessage = null
                    latestReport = report
                    renderReport(report)
                    showCompletedForceStats()
                    renderTrainingPlayStatus()
                    renderTrainingHero()
                    syncTrainingReport(report)
                    setTrainingBusyUi(false)
                    quietIconView.visibility = View.GONE
                    countdownView.text = tr("done_short")
                    statusView.text = tr("training_complete")
                    statusView.setTextColor(Color.parseColor("#FFB347"))
                    remainingView.text = displayRemaining(0L)
                    updateBluetoothSettingsViews()
                    lastCoachOutcome?.let { outcome ->
                        countdownView.postDelayed({
                            if (trainingJob == null) {
                                maybeShowTrainingOutcomeCelebration(report, outcome)
                            }
                        }, 350L)
                    }
                } catch (_: CancellationException) {
                    setTrainingGyroscopeEnabled(false, reportFailure = false)
                    stopImmersiveTrainingAudio()
                    if (!isDestroyed && !isFinishing && statusView.text != tr("training_stopped")) {
                        renderIdle()
                    }
                } catch (t: Throwable) {
                    setTrainingGyroscopeEnabled(false, reportFailure = false)
                    stopImmersiveTrainingAudio()
                    renderError(t.message ?: tr("training_failed"))
                } finally {
                    stopImmersiveTrainingAudio()
                    trainingBluetoothReconnectJob?.cancel()
                    trainingBluetoothReconnectJob = null
                    trainingJob = null
                    bluetoothTrainingMode = null
                    trainingAcceptingPunches = false
                    trainingResting = false
                    applyDeferredTrainingBatteryStatus()
                    updateBluetoothSettingsViews()
                }
            }
    }

    private suspend fun waitForInitialTrainingCountingReady(): Boolean {
        val delaysMs = longArrayOf(250L, 350L, 500L, 700L, 900L, 1_100L, 1_400L, 1_700L, 2_000L, 2_300L)
        var refreshedStuckConnection = false
        for (attempt in delaysMs.indices) {
            val readyText =
                localText(
                    "计数通道准备中...",
                    "Preparing counting channel...",
                    "Préparation du canal de comptage...",
                    "กำลังเตรียมช่องนับ...",
                )
            statusView.text = readyText
            statusView.setTextColor(Color.parseColor(selectedPalette.warning))
            remainingView.text = displayRemaining(currentRoundRemainingMs)
            showDashboardCenterCue(
                center = localText("准备", "Ready", "Prêt", "พร้อม"),
                caption = readyText,
                color = Color.parseColor(selectedPalette.warning),
            )
            updateDashboardViews(currentRoundRemainingMs)

            if (bluetoothConnectedDevice != null && sensorBallBluetooth.isGyroscopeCommandChannelReady()) {
                if (setTrainingGyroscopeEnabled(true, reportFailure = false)) {
                    bluetoothStatusMessage =
                        localText(
                            "蓝牙计数已启动",
                            "Bluetooth counting started",
                            "Comptage Bluetooth démarré",
                            "เริ่มนับผ่านบลูทูธแล้ว",
                        )
                    updateBluetoothSettingsViews()
                    return true
                }
            }

            if (bluetoothConnectedDevice == null) {
                bluetoothStatusMessage = bluetoothTrainingReconnectText()
                updateBluetoothSettingsViews()
                scheduleTrainingBluetoothReconnect()
                if (attempt % 2 == 0) {
                    autoConnectLastBluetoothDevice()
                }
            } else if (!refreshedStuckConnection && attempt >= 5 && !sensorBallBluetooth.isGyroscopeCommandChannelReady()) {
                refreshedStuckConnection = true
                bluetoothStatusMessage =
                    localText(
                        "计数通道仍未就绪，正在刷新蓝牙连接...",
                        "Counting channel is still not ready. Refreshing Bluetooth...",
                        "Le canal de comptage n'est pas prêt. Reconnexion Bluetooth...",
                        "ช่องนับยังไม่พร้อม กำลังรีเฟรชบลูทูธ...",
                    )
                updateBluetoothSettingsViews()
                sensorBallBluetooth.disconnect()
                delay(450L)
                autoConnectLastBluetoothDevice()
            }

            delay(delaysMs[attempt])
        }

        if (bluetoothConnectedDevice != null && sensorBallBluetooth.isGyroscopeCommandChannelReady()) {
            return setTrainingGyroscopeEnabled(true, reportFailure = true)
        }
        scheduleTrainingBluetoothReconnect()
        return false
    }

    private suspend fun runConfiguredTrainingRounds(
        sessionMode: TrainingMode,
        setup: TrainingSessionSetup,
    ) {
        val workDurationMs = setup.workSeconds * 1_000L
        val restDurationMs = setup.restSeconds * 1_000L
        for (round in 1..setup.rounds) {
            currentTrainingRound = round
            currentTrainingRoundCount = setup.rounds
            currentRoundDurationMs = workDurationMs
            currentRoundRemainingMs = workDurationMs
            trainingResting = false
            trainingAcceptingPunches = true
            bluetoothTrainingMode = sessionMode
            statusView.text =
                localText(
                    "第 $round 回合训练中",
                    "Round $round in progress",
                    "Round $round en cours",
                    "รอบ $round กำลังฝึก",
                )
            statusView.setTextColor(Color.parseColor("#FFB347"))
            scrollRealtimeTrainingToTop()
            scheduleRoundStartAiCoachCue(round, setup.rounds)
            val startMs = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startMs < workDurationMs) {
                val remainingMs = (workDurationMs - (SystemClock.elapsedRealtime() - startMs)).coerceAtLeast(0L)
                currentRoundRemainingMs = remainingMs
                remainingView.text = displayRemaining(remainingMs)
                evaluateMissedBeats(SystemClock.elapsedRealtime() - trainingStartedElapsedMs)
                evaluateAiCoachTimerCue(remainingMs)
                updateDashboardViews(remainingMs)
                delay(100L)
            }
            trainingAcceptingPunches = false
            bluetoothTrainingMode = null
            val roundReport =
                buildBluetoothTrainingReport(
                    mode = sessionMode,
                    totalHits = bluetoothTrainingCount,
                    setup = setup,
                    completedRounds = round,
                )
            upsertRoundReport(roundReport)
            latestReport = roundReport
            renderReport(roundReport)
            renderTrainingHero()
            showCompletedForceStats()
            if (round < setup.rounds && restDurationMs > 0L) {
                trainingResting = true
                currentRoundDurationMs = restDurationMs
                val restStartMs = SystemClock.elapsedRealtime()
                setTrainingGyroscopeEnabled(false, reportFailure = false)
                startRestBackgroundMusic()
                while (SystemClock.elapsedRealtime() - restStartMs < restDurationMs) {
                    val remainingMs = (restDurationMs - (SystemClock.elapsedRealtime() - restStartMs)).coerceAtLeast(0L)
                    currentRoundRemainingMs = remainingMs
                    remainingView.text =
                        localText(
                            "休息 ${formatDurationClock(remainingMs)}",
                            "Rest ${formatDurationClock(remainingMs)}",
                            "Repos ${formatDurationClock(remainingMs)}",
                            "พัก ${formatDurationClock(remainingMs)}",
                        )
                    updateDashboardViews(remainingMs)
                    delay(100L)
                }
                trainingResting = false
                startSelectedBackgroundMusic()
                var countingRestarted = setTrainingGyroscopeEnabled(true, reportFailure = false)
                if (!countingRestarted) {
                    scheduleTrainingBluetoothReconnect()
                    delay(2_000L)
                    countingRestarted = setTrainingGyroscopeEnabled(true, reportFailure = true)
                }
                if (!countingRestarted) {
                    throw IllegalStateException(
                        localText(
                            "休息结束后蓝牙计数未重新启动，请检查设备连接后重试。",
                            "Bluetooth counting did not restart after rest. Check the device connection and retry.",
                            "Le comptage Bluetooth n'a pas redémarré après le repos. Vérifiez la connexion puis réessayez.",
                            "หลังพักแล้วเริ่มนับผ่านบลูทูธใหม่ไม่สำเร็จ โปรดตรวจสอบการเชื่อมต่อแล้วลองอีกครั้ง",
                        ),
                    )
                }
            }
        }
        currentRoundRemainingMs = 0L
        trainingAcceptingPunches = false
        bluetoothTrainingMode = null
    }

    private suspend fun runPreTrainingCueSequence(workDurationMs: Long) {
        quietIconView.visibility = View.GONE
        remainingView.text = displayRemaining(workDurationMs)
        for (value in 3 downTo 1) {
            countdownView.text = value.toString()
            statusView.text = displayCountdownStatus(value)
            statusView.setTextColor(Color.parseColor("#FFD060"))
            showDashboardCenterCue(
                center = value.toString(),
                caption = displayCountdownStatus(value),
                color = Color.parseColor("#FFD060"),
            )
            lastSpokenCountdown = value
            countdownView.announceForAccessibility(value.toString())
            speakCueAndWait(value.toString(), timeoutMs = 900L)
            delay(120L)
        }
        countdownView.text = displayGoLabel()
        statusView.text = tr("training_live")
        statusView.setTextColor(Color.parseColor("#FFB347"))
        showDashboardCenterCue(
            center = displayGoLabel(),
            caption = tr("training_live"),
            color = Color.parseColor("#FFB347"),
        )
        goSpoken = true
        countdownView.announceForAccessibility(displayGoLabel())
        speakCueAndWait(displayGoCue(), timeoutMs = 900L)
    }

    private suspend fun speakCueAndWait(text: String, timeoutMs: Long) {
        val completed = CompletableDeferred<Unit>()
        speakCue(text) {
            if (!completed.isCompleted) {
                completed.complete(Unit)
            }
        }
        withTimeoutOrNull(timeoutMs) {
            completed.await()
        }
    }

    private fun stopTraining(showStoppedState: Boolean) {
        val partialReport = if (showStoppedState) buildManualStopUploadReport() else null
        trainingJob?.cancel()
        trainingBluetoothReconnectJob?.cancel()
        trainingBluetoothReconnectJob = null
        setTrainingGyroscopeEnabledAsync(false)
        tts?.stop()
        stopImmersiveTrainingAudio()
        trainingJob = null
        bluetoothTrainingMode = null
        trainingAcceptingPunches = false
        trainingResting = false
        applyDeferredTrainingBatteryStatus()
        updateBluetoothSettingsViews()
        lastSpokenCountdown = null
        goSpoken = false
        clearDashboardCenterCue()
        if (showStoppedState) {
            partialReport?.let { latestReport = it }
            setTrainingBusyUi(false)
            statusView.text = tr("training_stopped")
            statusView.setTextColor(Color.parseColor("#FFD060"))
            countdownView.text = "--"
            quietIconView.visibility = View.GONE
            applyStaticTexts()
            updateDashboardViews(selectedMode.durationSeconds * 1_000L)
            showCompletedForceStats()
            partialReport?.let { report ->
                renderReport(report)
                renderTrainingHero()
                syncTrainingReport(report)
            }
        }
    }

    private fun buildManualStopUploadReport(): TrainingReport? {
        if (partialTrainingUploadTriggered) {
            return null
        }
        val latestCompletedRound =
            trainingRoundReports
                .filter { it.totalRounds > 1 && it.completedRounds in 1 until it.totalRounds }
                .maxByOrNull { it.completedRounds }
                ?: return null
        if (latestCompletedRound.totalHits <= 0 && latestCompletedRound.durationSeconds <= 0) {
            return null
        }
        partialTrainingUploadTriggered = true
        return latestCompletedRound.copy(roundReports = buildRoundReportSnapshots())
    }

    private fun buildBluetoothTrainingReport(
        mode: TrainingMode,
        totalHits: Int,
        setup: TrainingSessionSetup,
        completedRounds: Int = setup.rounds,
        includeRoundReports: Boolean = false,
    ): TrainingReport {
        val safeCompletedRounds = completedRounds.coerceIn(1, setup.rounds.coerceAtLeast(1))
        val durationSeconds = setup.workSeconds * safeCompletedRounds
        val forceSamples = trainingPunchEvents.map { it.forceN.toFloat() }.filter { it > 0f }
        val avgForceN = forceSamples.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
        val calories = caloriesForTraining(totalHits, durationSeconds, avgForceN)
        val rhythmSummary = RhythmSummary(trainingPerfectBeats, trainingGoodBeats, trainingMissBeats)
        return TrainingReport(
            mode = mode,
            totalHits = totalHits,
            averageFrequency = if (durationSeconds > 0) totalHits / durationSeconds.toFloat() else 0.0f,
            bestBurstCount = calculateBestBurstCount(),
            bestBurstStartSec = 0.0f,
            endedAtEpochMs = System.currentTimeMillis(),
            durationSeconds = durationSeconds,
            completedRounds = safeCompletedRounds,
            totalRounds = setup.rounds,
            caloriesBurned = calories,
            fatBurnedGrams = fatGramsForCalories(calories),
            avgBpm = trainingCurrentBpm,
            peakForceN = trainingPeakForceN,
            avgForceN = avgForceN,
            comboSummary = trainingComboCounts.toMap(),
            rhythmAccuracy = rhythmSummary.accuracy,
            rhythmSummary = rhythmSummary,
            roundConfig = setup.toRoundConfig(),
            roundReports = if (includeRoundReports) buildRoundReportSnapshots() else emptyList(),
            playMode = setup.rhythmMode.name.lowercase(Locale.US),
            soundPackId = selectedSoundPack.id,
        )
    }

    private fun buildRoundReportSnapshots(): List<TrainingRoundReport> =
        trainingRoundReports
            .sortedBy { it.completedRounds }
            .map { report ->
                TrainingRoundReport(
                    roundIndex = report.completedRounds,
                    totalRounds = report.totalRounds,
                    durationSeconds = report.durationSeconds,
                    totalHits = report.totalHits,
                    caloriesBurned = report.caloriesBurned,
                    fatBurnedGrams = report.fatBurnedGrams,
                    peakForceN = report.peakForceN,
                    avgForceN = report.avgForceN,
                    avgBpm = report.avgBpm,
                    rhythmAccuracy = report.rhythmAccuracy,
                    endedAtEpochMs = report.endedAtEpochMs,
                )
            }

    private fun upsertRoundReport(report: TrainingReport) {
        if (report.totalRounds <= 1) {
            return
        }
        val index = trainingRoundReports.indexOfFirst { it.completedRounds == report.completedRounds }
        if (index >= 0) {
            trainingRoundReports[index] = report
        } else {
            trainingRoundReports += report
        }
        trainingRoundReports.sortBy { it.completedRounds }
    }

    private fun resetRealtimeTrainingSession(mode: TrainingMode) {
        trainingSessionId = UUID.randomUUID().toString()
        trainingStartedElapsedMs = 0L
        clearDashboardCenterCue()
        currentTrainingRound = 1
        currentTrainingRoundCount = trainingSessionSetup.rounds
        currentRoundDurationMs = trainingSessionSetup.workSeconds * 1_000L
        currentRoundRemainingMs = currentRoundDurationMs
        trainingResting = false
        trainingAcceptingPunches = false
        trainingPeakForceN = 0f
        trainingCurrentBpm = 0f
        trainingPerfectBeats = 0
        trainingGoodBeats = 0
        trainingMissBeats = 0
        trainingLastEvaluatedBeat = -1
        trainingPunchTimesMs.clear()
        trainingForceSamples.clear()
        trainingPunchEvents.clear()
        trainingRoundReports.clear()
        trainingComboEvents.clear()
        trainingComboCounts.clear()
        trainingLastCoachCueKey = null
        trainingLastCoachCueElapsedMs = -30_000L
        trainingLastCoachSpeechElapsedMs = -120_000L
        trainingDeliveredCoachCues.clear()
        if (::waveformView.isInitialized) {
            waveformView.reset()
        }
        hideCompletedForceStats()
        updateComboChips()
        resetAiCoachRealtimeCard()
        updateDashboardViews(mode.durationSeconds * 1_000L)
    }

    private fun recordTrainingPunch() {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = if (trainingStartedElapsedMs > 0L) now - trainingStartedElapsedMs else 0L
        val force = (bluetoothPeakText.toFloatOrNull() ?: 0f).let { if (it > 0f) it else 15f }.coerceAtLeast(0f)
        val beatResult = evaluateBeatForPunch(elapsedMs)
        bluetoothTrainingCount += 1
        trainingPeakForceN = max(trainingPeakForceN, force)
        trainingPunchTimesMs.addLast(now)
        trainingForceSamples.addLast(force)
        while (trainingPunchTimesMs.size > 12) trainingPunchTimesMs.removeFirst()
        while (trainingForceSamples.size > 12) trainingForceSamples.removeFirst()
        trainingPunchEvents +=
            PunchEvent(
                id = UUID.randomUUID().toString(),
                sessionId = trainingSessionId,
                forceN = force.toDouble(),
                deviceTs = elapsedMs,
                systemTs = System.currentTimeMillis(),
                beatOffsetMs = beatResult?.first,
                beatScore = beatResult?.second,
        )
        trainingCurrentBpm = calculateRecentBpm()
        val comboType = detectCombo(now, force)
        waveformView.addForce(force)
        playPunchSound(force)
        evaluateAiCoachCue(elapsedMs, force, comboType)
    }

    private fun evaluateBeatForPunch(elapsedMs: Long): Pair<Int, BeatScore>? {
        if (selectedRhythmMode != TrainingRhythmMode.Rhythm || trainingStartedElapsedMs <= 0L) {
            return null
        }
        val intervalMs = 60_000f / selectedBeatBpm.coerceAtLeast(1)
        val nearestBeatMs = (elapsedMs / intervalMs).roundToInt() * intervalMs
        val offset = abs(elapsedMs - nearestBeatMs.roundToInt()).toInt()
        val score =
            when {
                offset <= 100 -> BeatScore.Perfect
                offset <= 200 -> BeatScore.Good
                else -> BeatScore.Miss
            }
        when (score) {
            BeatScore.Perfect -> trainingPerfectBeats += 1
            BeatScore.Good -> trainingGoodBeats += 1
            BeatScore.Miss -> trainingMissBeats += 1
        }
        return offset to score
    }

    private fun evaluateMissedBeats(elapsedMs: Long) {
        if (selectedRhythmMode != TrainingRhythmMode.Rhythm || elapsedMs < 220L) {
            return
        }
        val intervalMs = 60_000f / selectedBeatBpm.coerceAtLeast(1)
        val completedBeat = ((elapsedMs - 220L) / intervalMs).toInt()
        if (completedBeat <= trainingLastEvaluatedBeat) {
            return
        }
        val start = max(0, trainingLastEvaluatedBeat + 1)
        for (beat in start..completedBeat) {
            val beatElapsed = (beat * intervalMs).roundToInt().toLong()
            playBeatTick()
            val hasNearbyPunch =
                trainingPunchEvents.any { event ->
                    event.deviceTs >= 0 && abs(event.deviceTs - beatElapsed) <= 200
                }
            if (!hasNearbyPunch) {
                trainingMissBeats += 1
            }
        }
        trainingLastEvaluatedBeat = completedBeat
    }

    private fun calculateRecentBpm(): Float {
        if (trainingPunchTimesMs.size < 2) {
            return 0f
        }
        val times = trainingPunchTimesMs.toList().takeLast(5)
        if (times.size < 2) {
            return 0f
        }
        val intervals = times.zipWithNext { left, right -> (right - left).coerceAtLeast(1L) }
        val averageInterval = intervals.average().toFloat()
        return 60_000f / averageInterval
    }

    private fun detectCombo(now: Long, force: Float): String {
        val times = trainingPunchTimesMs.toList()
        val forces = trainingForceSamples.toList()
        val comboType =
            when {
                times.size >= 3 &&
                    (times[times.lastIndex] - times[times.lastIndex - 1]) <= 800L &&
                    (times[times.lastIndex - 1] - times[times.lastIndex - 2]) <= 800L &&
                    forces.takeLast(3).all { it >= 80f } -> "power_burst"

                times.size >= 3 &&
                    (times[times.lastIndex] - times[times.lastIndex - 1]) in 150L..450L &&
                    (times[times.lastIndex - 1] - times[times.lastIndex - 2]) in 150L..450L -> "triple_combo"

                times.size >= 2 &&
                    (times[times.lastIndex] - times[times.lastIndex - 1]) in 200L..450L -> "combo"

                force >= 70f -> "heavy_hit"
                else -> "hit"
            }
        trainingComboCounts[comboType] = (trainingComboCounts[comboType] ?: 0) + 1
        trainingComboEvents +=
            ComboEvent(
                type = comboType,
                detectedAtMs = now,
                punchCount = bluetoothTrainingCount,
                peakForceN = force.toDouble(),
            )
        updateComboChips()
        return comboType
    }

    private fun calculateBestBurstCount(): Int {
        if (trainingPunchEvents.isEmpty()) {
            return bluetoothTrainingCount
        }
        val times = trainingPunchEvents.map { it.deviceTs }.sorted()
        var best = 0
        for (index in times.indices) {
            val start = times[index]
            val count = times.count { it in start..(start + 3_000L) }
            best = max(best, count)
        }
        return best
    }

    private fun caloriesForTraining(
        hits: Int,
        durationSeconds: Int,
        avgForceN: Float,
    ): Float {
        val safeHits = hits.coerceAtLeast(0)
        val safeDurationSeconds = durationSeconds.coerceAtLeast(0)
        if (safeHits == 0 || safeDurationSeconds == 0) {
            return 0f
        }
        val minutes = safeDurationSeconds / 60f
        val punchesPerMinute = safeHits / minutes.coerceAtLeast(1f / 60f)
        val frequencyFactor = (punchesPerMinute / 60f).coerceIn(0.50f, 1.60f)
        val forceFactor =
            sqrt((avgForceN.coerceAtLeast(0f) / FORCE_REFERENCE_N).toDouble())
                .toFloat()
                .coerceIn(0.70f, 1.35f)
        val intensity = 0.70f * frequencyFactor + 0.30f * forceFactor
        val dynamicMet = (BASE_BOXING_MET * intensity).coerceIn(MIN_DYNAMIC_MET, MAX_DYNAMIC_MET)
        return dynamicMet * 3.5f * DEFAULT_BODY_WEIGHT_KG / 200f * minutes
    }

    private fun fatGramsForCalories(calories: Float): Float =
        if (calories <= 0f) 0f else calories / KCAL_PER_FAT_GRAM

    private fun formatCalories(value: Float): String = String.format(Locale.US, "%.1f kcal", value)

    private fun formatFatGrams(value: Float): String = String.format(Locale.US, "%.1f g", value)

    private fun formatTrainingDuration(totalSeconds: Int): String {
        val safeSeconds = totalSeconds.coerceAtLeast(0)
        val minutes = safeSeconds / 60
        val seconds = safeSeconds % 60
        return if (minutes <= 0) {
            localText("${seconds} 秒", "${seconds}s", "${seconds} s", "${seconds} วิ")
        } else if (seconds == 0) {
            localText("${minutes} 分钟", "${minutes} min", "${minutes} min", "${minutes} นาที")
        } else {
            localText("${minutes}分${seconds}秒", "${minutes}m ${seconds}s", "${minutes} min ${seconds} s", "${minutes} นาที ${seconds} วิ")
        }
    }

    private fun forceDisplay(value: Float): String =
        if (value > 0f) "${value.roundToInt()} N" else "-- N"

    private fun roundReportBadgeText(report: TrainingReport): String =
        if (report.totalRounds > 1) {
            localText(
                "第 ${report.completedRounds}/${report.totalRounds} 回合",
                "Round ${report.completedRounds}/${report.totalRounds}",
                "Round ${report.completedRounds}/${report.totalRounds}",
                "รอบ ${report.completedRounds}/${report.totalRounds}",
            )
        } else {
            localText("训练战报", "Training Report", "Rapport", "รายงานการฝึก")
        }

    private fun roundReportTitleText(report: TrainingReport): String =
        if (report.totalRounds > 1) {
            localText(
                "第 ${report.completedRounds} 回合训练战报",
                "Round ${report.completedRounds} Training Report",
                "Rapport du round ${report.completedRounds}",
                "รายงานรอบ ${report.completedRounds}",
            )
        } else {
            localText("HitRise 训练战报", "HitRise Training Report", "Rapport HitRise", "รายงาน HitRise")
        }

    private fun trainingBattleReportSummary(report: TrainingReport): String =
        localText(
            "累计锻炼 ${formatTrainingDuration(report.durationSeconds)} | 累计 ${report.totalHits} 拳 | ${formatCalories(report.caloriesBurned)} | 等效燃脂 ${formatFatGrams(report.fatBurnedGrams)}",
            "Total ${formatTrainingDuration(report.durationSeconds)} | ${report.totalHits} punches | ${formatCalories(report.caloriesBurned)} | ${formatFatGrams(report.fatBurnedGrams)} equivalent fat",
            "Total ${formatTrainingDuration(report.durationSeconds)} | ${report.totalHits} coups | ${formatCalories(report.caloriesBurned)} | ${formatFatGrams(report.fatBurnedGrams)} graisse équiv.",
            "รวม ${formatTrainingDuration(report.durationSeconds)} | ${report.totalHits} หมัด | ${formatCalories(report.caloriesBurned)} | ไขมันเทียบเท่า ${formatFatGrams(report.fatBurnedGrams)}",
        )

    private fun trainingBattleReportForceLine(report: TrainingReport): String =
        localText(
            "最大力度 ${forceDisplay(report.peakForceN)} | 平均力度 ${forceDisplay(report.avgForceN)} | ${formatReportEndedTime(report.endedAtEpochMs)}",
            "Peak ${forceDisplay(report.peakForceN)} | Avg ${forceDisplay(report.avgForceN)} | ${formatReportEndedTime(report.endedAtEpochMs)}",
            "Max ${forceDisplay(report.peakForceN)} | Moy. ${forceDisplay(report.avgForceN)} | ${formatReportEndedTime(report.endedAtEpochMs)}",
            "สูงสุด ${forceDisplay(report.peakForceN)} | เฉลี่ย ${forceDisplay(report.avgForceN)} | ${formatReportEndedTime(report.endedAtEpochMs)}",
        )

    private fun trainingStatsSummary(stats: CloudUserStatistics): String =
        localText(
            "累计锻炼 ${formatTrainingDuration(stats.totalTrainingSeconds)} | 累计 ${stats.totalHits} 拳 | ${formatCalories(stats.totalCaloriesBurned)}",
            "Total ${formatTrainingDuration(stats.totalTrainingSeconds)} | ${stats.totalHits} punches | ${formatCalories(stats.totalCaloriesBurned)}",
            "Total ${formatTrainingDuration(stats.totalTrainingSeconds)} | ${stats.totalHits} coups | ${formatCalories(stats.totalCaloriesBurned)}",
            "รวม ${formatTrainingDuration(stats.totalTrainingSeconds)} | ${stats.totalHits} หมัด | ${formatCalories(stats.totalCaloriesBurned)}",
        )

    private fun trainingStatsForceBurnLine(stats: CloudUserStatistics): String =
        localText(
            "最大力度 ${forceDisplay(stats.bestPeakForceN)} | 最佳平均力度 ${forceDisplay(stats.bestAvgForceN)} | 累计等效燃脂 ${formatFatGrams(stats.totalFatBurnedGrams)}",
            "Peak ${forceDisplay(stats.bestPeakForceN)} | Best avg ${forceDisplay(stats.bestAvgForceN)} | Fat ${formatFatGrams(stats.totalFatBurnedGrams)}",
            "Max ${forceDisplay(stats.bestPeakForceN)} | Moy. max ${forceDisplay(stats.bestAvgForceN)} | Graisse ${formatFatGrams(stats.totalFatBurnedGrams)}",
            "สูงสุด ${forceDisplay(stats.bestPeakForceN)} | เฉลี่ยดีที่สุด ${forceDisplay(stats.bestAvgForceN)} | ไขมันเทียบเท่า ${formatFatGrams(stats.totalFatBurnedGrams)}",
        )

    private fun trainingReportLeaderboardLine(): String =
        localText(
            "锻炼成果与榜单按：时间、拳数、最大力度、平均力度、卡路里、等效燃脂量同步统计",
            "Badges and leaderboards track duration, punches, peak force, average force, calories, and equivalent fat burn.",
            "Badges et classements suivent durée, coups, force max, force moyenne, calories et graisse équivalente.",
            "เหรียญและอันดับนับเวลา หมัด แรงสูงสุด แรงเฉลี่ย แคลอรี และไขมันเทียบเท่า",
        )

    private fun showCompletedForceStats() {
        if (!::dashboardForceSummaryView.isInitialized) {
            return
        }
        val forces = trainingPunchEvents.map { it.forceN.toFloat() }.filter { it > 0f }
        dashboardForceSummaryView.text =
            if (forces.isEmpty()) {
                localText(
                    "本次力度：暂无击打数据",
                    "Force: no hit data yet",
                    "Force : aucune donnée de frappe",
                    "แรง: ยังไม่มีข้อมูลหมัด",
                )
            } else {
                val minForce = forces.minOrNull() ?: 0f
                val maxForce = forces.maxOrNull() ?: 0f
                val avgForce = forces.average().toFloat()
                localText(
                    "本次力度  最小 ${forceDisplay(minForce)} | 最大 ${forceDisplay(maxForce)} | 平均 ${forceDisplay(avgForce)}",
                    "Force  Min ${forceDisplay(minForce)} | Max ${forceDisplay(maxForce)} | Avg ${forceDisplay(avgForce)}",
                    "Force  Min ${forceDisplay(minForce)} | Max ${forceDisplay(maxForce)} | Moy ${forceDisplay(avgForce)}",
                    "แรง  ต่ำสุด ${forceDisplay(minForce)} | สูงสุด ${forceDisplay(maxForce)} | เฉลี่ย ${forceDisplay(avgForce)}",
                )
            }
        dashboardForceSummaryView.visibility = View.VISIBLE
    }

    private fun hideCompletedForceStats() {
        if (!::dashboardForceSummaryView.isInitialized) {
            return
        }
        dashboardForceSummaryView.visibility = View.GONE
    }

    private fun resetAiCoachRealtimeCard() {
        if (!::aiCoachMessageView.isInitialized) {
            return
        }
        aiCoachStatusView.text = localText("待命", "Ready", "Prêt", "พร้อม")
        aiCoachStatusView.background = roundedBackground("#17354A", "#2E75B6", 999)
        aiCoachMessageView.text =
            localText(
                "连接 SENBALL# 后开始训练，我会根据节奏、力度和连击表现给你低频关键提示。",
                "Start after connecting SENBALL#. I will give low-frequency key cues for rhythm, force, and combo flow.",
                "Connectez SENBALL# puis lancez. Je donnerai des conseils clés sur rythme, force et combos.",
                "เชื่อมต่อ SENBALL# แล้วเริ่มฝึก ฉันจะเตือนเฉพาะจังหวะ แรง และคอมโบที่สำคัญ",
            )
        aiCoachMetaView.text =
            localText(
                "触发原因：等待训练数据",
                "Trigger: waiting for training data",
                "Déclencheur : attente des données",
                "สาเหตุ: รอข้อมูลการฝึก",
            )
        updateAiCoachVoiceBar(active = false)
    }

    private fun updateAiCoachVoiceBar(active: Boolean) {
        if (!::aiCoachVoiceBar.isInitialized) {
            return
        }
        val heights = intArrayOf(6, 14, 10, 18, 8, 16, 5, 20, 9, 15)
        for (index in 0 until aiCoachVoiceBar.childCount) {
            val bar = aiCoachVoiceBar.getChildAt(index)
            val targetHeight = if (active) heights[index % heights.size] else 5
            bar.alpha = if (active) 1f else 0.42f
            bar.layoutParams =
                (bar.layoutParams as LinearLayout.LayoutParams).apply {
                    height = dp(targetHeight)
                }
        }
    }

    private fun canDeliverAiCoachCue(key: String, elapsedMs: Long): Boolean {
        if (key in trainingDeliveredCoachCues && key != "combo_flow") {
            return false
        }
        if (trainingLastCoachCueKey == key && elapsedMs - trainingLastCoachCueElapsedMs < 45_000L) {
            return false
        }
        if (elapsedMs - trainingLastCoachCueElapsedMs < aiCoachCueCooldownMs()) {
            return false
        }
        return true
    }

    private fun aiCoachCueCooldownMs(): Long =
        when {
            trainingSessionSetup.workSeconds <= 60 -> 18_000L
            trainingSessionSetup.workSeconds <= 120 -> 22_000L
            else -> 25_000L
        }

    private fun aiCoachSpeechCooldownMs(key: String): Long =
        when {
            key.startsWith("round_start") -> 0L
            key.startsWith("final_10") || key.startsWith("final_30") -> 18_000L
            key == "tempo_up" -> 24_000L
            key == "power_burst" -> 28_000L
            key == "force_drop" -> 30_000L
            key == "combo_flow" -> 32_000L
            else -> 45_000L
        }

    private fun shouldSpeakAiCoachCue(key: String, elapsedMs: Long, forced: Boolean): Boolean {
        if (forced) {
            return true
        }
        val importantCue =
            key.startsWith("round_start") ||
                key.startsWith("final_10") ||
                key.startsWith("final_30") ||
                key == "tempo_up" ||
                key == "power_burst" ||
                key == "force_drop" ||
                key == "combo_flow"
        if (!importantCue) {
            return false
        }
        return elapsedMs - trainingLastCoachSpeechElapsedMs >= aiCoachSpeechCooldownMs(key)
    }

    private fun pushAiCoachCue(
        key: String,
        message: String,
        meta: String,
        speak: Boolean = true,
        force: Boolean = false,
    ) {
        val elapsedMs =
            if (trainingStartedElapsedMs > 0L) {
                SystemClock.elapsedRealtime() - trainingStartedElapsedMs
            } else {
                0L
            }
        if (!force && !canDeliverAiCoachCue(key, elapsedMs)) {
            return
        }
        trainingLastCoachCueKey = key
        trainingLastCoachCueElapsedMs = elapsedMs
        trainingDeliveredCoachCues += key
        lastCoachMessage = message
        val willSpeak = speak && shouldSpeakAiCoachCue(key, elapsedMs, force)
        if (::aiCoachStatusView.isInitialized &&
            ::aiCoachMessageView.isInitialized &&
            ::aiCoachMetaView.isInitialized
        ) {
            aiCoachStatusView.text =
                if (willSpeak) {
                    localText("语音播报中", "Speaking", "En annonce", "กำลังพูด")
                } else {
                    localText("提示已更新", "Cue updated", "Conseil mis à jour", "อัปเดตคำแนะนำ")
                }
            aiCoachStatusView.background =
                if (willSpeak) {
                    roundedBackground("#412402", "#BA7517", 999)
                } else {
                    roundedBackground("#04342C", "#0F6E56", 999)
                }
            aiCoachMessageView.text = message
            aiCoachMetaView.text = meta
            updateAiCoachVoiceBar(active = willSpeak)
            aiCoachStatusView.postDelayed(
                {
                    if (::aiCoachStatusView.isInitialized) {
                        aiCoachStatusView.text = localText("实时监听", "Listening", "Écoute", "กำลังฟัง")
                        aiCoachStatusView.background = roundedBackground("#04342C", "#0F6E56", 999)
                        updateAiCoachVoiceBar(active = false)
                    }
                },
                4_000L,
            )
        }
        if (willSpeak) {
            trainingLastCoachSpeechElapsedMs = elapsedMs
            speakAiCoachCue(message)
        }
    }

    private fun evaluateAiCoachCue(elapsedMs: Long, force: Float, comboType: String) {
        if (elapsedMs < 2_500L) {
            return
        }
        if (selectedRhythmMode == TrainingRhythmMode.Rhythm &&
            trainingCurrentBpm > 0f &&
            trainingCurrentBpm < selectedBeatBpm - 12
        ) {
            pushAiCoachCue(
                key = "tempo_up",
                message =
                    localText(
                        "加快节奏！你已经低于目标 BPM，跟上节拍，出拳短一点、回收快一点。",
                        "Pick up the pace. You are under the target BPM, shorten the punch and recover faster.",
                        "Accélérez. Vous êtes sous le BPM cible, coups plus courts et retour plus rapide.",
                        "เร่งจังหวะ ตอนนี้ต่ำกว่า BPM เป้าหมาย หมัดสั้นขึ้นและดึงกลับเร็วขึ้น",
                    ),
                meta =
                    localText(
                        "触发原因：BPM 低于目标节拍",
                        "Trigger: BPM below target",
                        "Déclencheur : BPM sous la cible",
                        "สาเหตุ: BPM ต่ำกว่าเป้า",
                    ),
                )
            return
        }

        if (trainingForceSamples.size >= 8) {
            val recentAverage = trainingForceSamples.toList().takeLast(8).average().toFloat()
            if (recentAverage > 0f && force < recentAverage * 0.72f) {
                pushAiCoachCue(
                    key = "force_drop",
                    message =
                        localText(
                            "注意力度！刚才这一拳明显掉下来了，手腕锁住，拳面打实。",
                            "Watch the force. That punch dropped off, lock the wrist and land clean.",
                            "Attention à la force. Ce coup a chuté, verrouillez le poignet et frappez net.",
                            "ระวังแรง หมัดเมื่อกี้ตกลง ล็อกข้อมือแล้วออกหมัดให้แน่น",
                        ),
                    meta =
                        localText(
                            "触发原因：单拳力度低于近期均值 28%",
                            "Trigger: punch force below recent average by 28%",
                            "Déclencheur : force sous la moyenne récente de 28 %",
                            "สาเหตุ: แรงหมัดต่ำกว่าค่าเฉลี่ยล่าสุด 28%",
                        ),
                )
                return
            }
        }

        if (comboType == "power_burst") {
            pushAiCoachCue(
                key = "power_burst",
                message =
                    localText(
                        "漂亮的爆发！保持这个状态，再来一组短连击。",
                        "Great burst. Hold that state and give me one more short combo.",
                        "Belle explosion. Gardez cet état et refaites un combo court.",
                        "ระเบิดพลังสวยมาก รักษาจังหวะนี้แล้วต่อคอมโบสั้นอีกชุด",
                    ),
                meta =
                    localText(
                        "触发原因：识别到爆发连击",
                        "Trigger: power burst detected",
                        "Déclencheur : rafale détectée",
                        "สาเหตุ: พบชุดระเบิดพลัง",
                    ),
            )
            return
        }

        if ((comboType == "combo" || comboType == "triple_combo") && bluetoothTrainingCount % 16 == 0) {
            pushAiCoachCue(
                key = "combo_flow",
                message =
                    localText(
                        "连击节奏不错！保持手回防，别让组合拳把身体带散。",
                        "Nice combo rhythm. Keep the guard returning so the combination does not pull your posture apart.",
                        "Bon rythme de combo. Ramenez la garde pour garder la posture compacte.",
                        "คอมโบจังหวะดี ดึงการ์ดกลับไว้ อย่าให้ท่าหลุด",
                    ),
                meta =
                    localText(
                        "触发原因：连续命中组合拳",
                        "Trigger: combo streak detected",
                        "Déclencheur : série de combos détectée",
                        "สาเหตุ: พบคอมโบต่อเนื่อง",
                    ),
            )
        }
    }

    private fun scheduleRoundStartAiCoachCue(round: Int, totalRounds: Int) {
        val action =
            Runnable {
                if (trainingJob?.isActive == true && currentTrainingRound == round && !trainingResting) {
                    pushRoundStartAiCoachCue(round, totalRounds)
                }
            }
        if (::contentRootView.isInitialized) {
            contentRootView.postDelayed(action, 900L)
        } else {
            action.run()
        }
    }

    private fun pushRoundStartAiCoachCue(round: Int, totalRounds: Int) {
        pushAiCoachCue(
            key = "round_start_$round",
            message =
                localText(
                    "第 $round 回合开始。先稳住呼吸，出拳短促，注意回防。",
                    "Round $round starts. Settle your breathing, punch short, and bring the guard back.",
                    "Round $round. Respirez, frappez court et ramenez la garde.",
                    "เริ่มรอบ $round คุมลมหายใจ ชกสั้น และยกการ์ดกลับ",
                ),
            meta =
                localText(
                    "触发原因：第 $round/$totalRounds 回合开始",
                    "Trigger: round $round/$totalRounds started",
                    "Déclencheur : round $round/$totalRounds lancé",
                    "สาเหตุ: เริ่มรอบ $round/$totalRounds",
                ),
            force = true,
        )
    }

    private fun evaluateAiCoachTimerCue(remainingMs: Long) {
        if (trainingStartedElapsedMs <= 0L) {
            return
        }
        if (remainingMs in 1L..10_000L) {
            pushAiCoachCue(
                key = "final_10_round_$currentTrainingRound",
                message =
                    localText(
                        "最后 10 秒！全力冲刺，把节奏顶住，拳不要飘。",
                        "Final 10 seconds. Push hard, hold the rhythm, and keep punches clean.",
                        "Dernières 10 secondes. Poussez fort, gardez le rythme et frappez propre.",
                        "10 วินาทีสุดท้าย เร่งเต็มที่ คุมจังหวะและหมัดให้ชัด",
                    ),
                meta =
                    localText(
                        "触发原因：回合结束倒计时 10 秒",
                        "Trigger: final 10 seconds",
                        "Déclencheur : 10 secondes restantes",
                        "สาเหตุ: เหลือ 10 วินาที",
                    ),
            )
        }
    }

    private fun currentRemainingMillis(): Long {
        if (trainingJob?.isActive == true && currentRoundDurationMs > 0L) {
            return currentRoundRemainingMs.coerceIn(0L, currentRoundDurationMs)
        }
        return trainingSessionSetup.workSeconds * 1_000L
    }

    private fun scrollRealtimeTrainingToTop() {
        val scrollView = trainingScrollView ?: return
        if (!::realtimeDashboardCard.isInitialized) {
            return
        }
        scrollView.post {
            if (!scrollView.isShown || !realtimeDashboardCard.isShown) {
                return@post
            }
            val scrollLocation = IntArray(2)
            val cardLocation = IntArray(2)
            scrollView.getLocationOnScreen(scrollLocation)
            realtimeDashboardCard.getLocationOnScreen(cardLocation)
            val targetY =
                scrollView.scrollY +
                    cardLocation[1] -
                    scrollLocation[1] -
                    dp(4)
            scrollView.smoothScrollTo(0, targetY.coerceAtLeast(0))
        }
    }

    private fun findScrollViewChild(root: ViewGroup): ScrollView? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child is ScrollView) {
                return child
            }
            if (child is ViewGroup) {
                findScrollViewChild(child)?.let { return it }
            }
        }
        return null
    }

    private fun currentEffectiveTrainingSeconds(): Int {
        if (trainingJob?.isActive != true) {
            return 0
        }
        val completedBeforeCurrent = (currentTrainingRound - 1).coerceAtLeast(0) * trainingSessionSetup.workSeconds
        val currentRoundWorkSeconds =
            if (trainingResting) {
                trainingSessionSetup.workSeconds
            } else {
                ((currentRoundDurationMs - currentRoundRemainingMs).coerceAtLeast(0L) / 1_000L)
                    .toInt()
                    .coerceIn(0, trainingSessionSetup.workSeconds)
            }
        return completedBeforeCurrent + currentRoundWorkSeconds
    }

    private fun currentAverageTrainingForceN(): Float {
        val samples = trainingPunchEvents.map { it.forceN.toFloat() }.filter { it > 0f }
        return samples.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
    }

    private fun showDashboardCenterCue(center: String, caption: String, color: Int) {
        dashboardCenterCueText = center
        dashboardCenterCueCaption = caption
        dashboardCenterCueColor = color
        if (::timerRingView.isInitialized) {
            timerRingView.setTimerState(
                progressFraction = 1f,
                center = center,
                caption = caption,
                color = color,
            )
        }
    }

    private fun clearDashboardCenterCue() {
        dashboardCenterCueText = null
        dashboardCenterCueCaption = null
        dashboardCenterCueColor = null
    }

    private fun updateDashboardViews(remainingMs: Long) {
        if (!::timerRingView.isInitialized) {
            return
        }
        val durationMs =
            (currentRoundDurationMs.takeIf { it > 0L } ?: (trainingSessionSetup.workSeconds * 1_000L)).coerceAtLeast(1L)
        val progress = remainingMs.toFloat() / durationMs
        val remainingSec = (remainingMs / 1_000L).coerceAtLeast(0L)
        val timerColor =
            when {
                remainingSec <= 10 -> Color.parseColor(selectedPalette.danger)
                remainingSec <= 30 -> Color.parseColor(selectedPalette.accentHot)
                trainingResting -> Color.parseColor(selectedPalette.success)
                else -> Color.parseColor(selectedPalette.accent)
            }
        val centerCue = dashboardCenterCueText
        timerRingView.setTimerState(
            progressFraction = if (centerCue == null) progress else 1f,
            center = centerCue ?: String.format(Locale.US, "%02d:%02d", remainingSec / 60, remainingSec % 60),
            caption =
                dashboardCenterCueCaption
                    ?: if (trainingResting) {
                        localText("休息时间", "Rest time", "Temps de repos", "เวลาพัก")
                    } else {
                        localText("回合时间", "Round time", "Temps du round", "เวลารอบ")
                    },
            color = dashboardCenterCueColor ?: timerColor,
        )
        val report = latestReport
        val useReportSnapshot =
            report != null &&
                (trainingJob?.isActive != true || trainingResting)
        val displayHits = if (useReportSnapshot) report!!.totalHits else bluetoothTrainingCount
        val displayBpm =
            when {
                useReportSnapshot && report!!.avgBpm > 0f -> report.avgBpm
                trainingCurrentBpm > 0f -> trainingCurrentBpm
                else -> 0f
            }
        val calories =
            if (useReportSnapshot) {
                report!!.caloriesBurned
            } else {
                caloriesForTraining(
                    bluetoothTrainingCount,
                    currentEffectiveTrainingSeconds(),
                    currentAverageTrainingForceN(),
                )
            }
        val fat = if (useReportSnapshot) report!!.fatBurnedGrams else fatGramsForCalories(calories)
        val goalTarget = trainingGoalPresentationFor(selectedPlayMode).targetHits ?: 500
        dashboardRoundBadgeView.text =
            localText(
                "第 $currentTrainingRound / $currentTrainingRoundCount 回合",
                "Round $currentTrainingRound / $currentTrainingRoundCount",
                "Round $currentTrainingRound / $currentTrainingRoundCount",
                "รอบ $currentTrainingRound / $currentTrainingRoundCount",
            )
        dashboardPunchValueView.text = displayHits.toString()
        dashboardBpmValueView.text = if (displayBpm > 0f) displayBpm.roundToInt().toString() else "--"
        dashboardCaloriesValueView.text = String.format(Locale.US, "%.1f", calories)
        dashboardFatValueView.text = String.format(Locale.US, "%.1f", fat)
        dashboardPeakValueView.text = forceDisplay(trainingPeakForceN)
        dashboardPeakTagView.text =
            localText(
                "峰值 ${forceDisplay(trainingPeakForceN)}",
                "Peak ${forceDisplay(trainingPeakForceN)}",
                "Pic ${forceDisplay(trainingPeakForceN)}",
                "สูงสุด ${forceDisplay(trainingPeakForceN)}",
            )
        dashboardRhythmValueView.text =
            if (selectedRhythmMode == TrainingRhythmMode.Rhythm) {
                val summary = RhythmSummary(trainingPerfectBeats, trainingGoodBeats, trainingMissBeats)
                "${(summary.accuracy * 100f).roundToInt()}%"
            } else {
                localText("自由", "Free", "Libre", "อิสระ")
            }
        dashboardGoalProgressView.text =
            localText(
                "今日目标：$goalTarget 拳 | 已完成 $displayHits 拳",
                "Today: $goalTarget hits | Done $displayHits hits",
                "Aujourd'hui : $goalTarget coups | $displayHits coups faits",
                "วันนี้ $goalTarget หมัด | ทำแล้ว $displayHits หมัด",
            )
        updateDashboardGoalProgressBar(displayHits, goalTarget)
        refreshSecondaryHomeCardsThrottled()
        dashboardComboSummaryView.text =
            if (trainingComboCounts.isEmpty()) {
                localText("连击识别等待第一拳。", "Combo recognition is waiting for the first hit.", "En attente du premier coup.", "รอหมัดแรก")
            } else {
                trainingComboCounts.entries.joinToString("  ") { "${comboDisplayName(it.key)} ×${it.value}" }
            }
    }

    private fun refreshSecondaryHomeCardsThrottled(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && trainingJob?.isActive == true && now - lastSecondaryHomeRefreshElapsedMs < 5_000L) {
            return
        }
        lastSecondaryHomeRefreshElapsedMs = now
        renderHomeConnectionReportCard()
        renderHomeGoalAchievementCard()
    }

    private fun updateDashboardGoalProgressBar(
        completedHits: Int,
        targetHits: Int,
    ) {
        if (!::dashboardGoalProgressTrackView.isInitialized || !::dashboardGoalProgressFillView.isInitialized) {
            return
        }
        dashboardGoalProgressTrackView.post {
            val trackWidth = dashboardGoalProgressTrackView.width
            if (trackWidth <= 0) {
                return@post
            }
            val progress = if (targetHits > 0) completedHits.toFloat() / targetHits.toFloat() else 0f
            val fillWidth =
                (trackWidth * progress.coerceIn(0f, 1f))
                    .roundToInt()
                    .let { if (completedHits > 0) it.coerceAtLeast(dp(8)) else it }
            val params = dashboardGoalProgressFillView.layoutParams as FrameLayout.LayoutParams
            if (params.width != fillWidth) {
                params.width = fillWidth
                dashboardGoalProgressFillView.layoutParams = params
            }
        }
    }

    private fun updateComboChips() {
        if (!::dashboardComboContainer.isInitialized) {
            return
        }
        dashboardComboContainer.removeAllViews()
        val combos = listOf("hit", "heavy_hit", "combo", "triple_combo", "power_burst")
        combos.forEach { combo ->
            val count = trainingComboCounts[combo] ?: 0
            dashboardComboContainer.addView(
                badgeText(
                    text = if (count > 0) "${comboDisplayName(combo)} ×$count" else comboDisplayName(combo),
                    textColor = if (count > 0) "#FFFFFF" else "#557A7D",
                    fillColor = if (count > 0) comboAccentFill(combo) else "#F7FFFD",
                ).apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = dp(6)
                    alpha = if (count > 0) 1f else 0.64f
                },
            )
        }
    }

    private fun comboAccentFill(combo: String): String =
        when (combo) {
            "heavy_hit" -> "#2DD4BF"
            "combo" -> "#20B7A8"
            "triple_combo" -> "#FFC15C"
            "power_burst" -> "#FF7A45"
            else -> "#6A8F92"
        }

    private fun comboDisplayName(combo: String): String =
        when (combo) {
            "heavy_hit" -> localText("重击", "Heavy", "Puissant", "หนัก")
            "combo" -> localText("连击", "Combo", "Combo", "คอมโบ")
            "triple_combo" -> localText("三连击", "Triple", "Triple", "สามต่อ")
            "power_burst" -> localText("爆发连击", "Burst", "Rafale", "ระเบิด")
            else -> localText("击打", "Hit", "Coup", "หมัด")
        }

    private fun startImmersiveTrainingAudio() {
        stopImmersiveTrainingAudio()
        startSelectedBackgroundMusic()
        if (selectedRhythmMode != TrainingRhythmMode.Rhythm) {
            return
        }
        prepareSelectedCloudSoundEffect()
        val bpm = immersiveGrooveBpm()
        val soundPack = selectedSoundPack
        immersiveAudioJob =
            lifecycleScope.launch(Dispatchers.Default) {
                val sampleRate = 22_050
                val channelMask = AudioFormat.CHANNEL_OUT_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBufferBytes =
                    AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding).coerceAtLeast(sampleRate / 4)
                val track =
                    runCatching {
                        AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_GAME)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build(),
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(sampleRate)
                                    .setEncoding(encoding)
                                    .setChannelMask(channelMask)
                                    .build(),
                            )
                            .setBufferSizeInBytes(minBufferBytes)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                    }.getOrNull()
                if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                    return@launch
                }
                immersiveAudioTrack = track
                val buffer = ShortArray(1024)
                var frameCursor = 0L
                try {
                    track.play()
                    while (isActive) {
                        if (trainingResting) {
                            buffer.fill(0)
                        } else {
                            renderImmersiveGroove(buffer, frameCursor, sampleRate, bpm, soundPack)
                        }
                        val written = runCatching { track.write(buffer, 0, buffer.size) }.getOrDefault(-1)
                        if (written < 0) {
                            break
                        }
                        if (written == 0) {
                            delay(8L)
                        } else {
                            frameCursor += written
                        }
                    }
                } finally {
                    if (immersiveAudioTrack === track) {
                        immersiveAudioTrack = null
                    }
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    runCatching { track.release() }
                }
            }
    }

    private fun stopImmersiveTrainingAudio() {
        immersiveAudioJob?.cancel()
        immersiveAudioJob = null
        immersiveAudioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        immersiveAudioTrack = null
        stopTrainingBackgroundMusic()
    }

    private fun immersiveGrooveBpm(): Int {
        val base = selectedBeatBpm.coerceIn(40, 120)
        return when {
            base < 70 -> base * 2
            base < 90 -> 100
            else -> base
        }.coerceIn(80, 130)
    }

    private fun renderImmersiveGroove(
        buffer: ShortArray,
        startFrame: Long,
        sampleRate: Int,
        bpm: Int,
        soundPack: SoundPack,
    ) {
        val beatSeconds = 60.0 / bpm
        val halfBeatSeconds = beatSeconds / 2.0
        val bassNotes = doubleArrayOf(55.0, 55.0, 65.4, 73.4)
        val packGain = if (soundPack == SoundPack.Street) 0.82 else 0.68
        for (index in buffer.indices) {
            val frame = startFrame + index
            val t = frame.toDouble() / sampleRate
            val beatElapsed = t % beatSeconds
            val halfBeatElapsed = t % halfBeatSeconds
            val beatIndex = (t / beatSeconds).toInt()
            val barBeat = beatIndex % 4
            val kickWindow = 0.18
            val kickEnv =
                if (beatElapsed < kickWindow) {
                    val x = 1.0 - beatElapsed / kickWindow
                    x * x
                } else {
                    0.0
                }
            val kickFreq =
                if (soundPack == SoundPack.Street) {
                    78.0 - 34.0 * (beatElapsed / kickWindow).coerceIn(0.0, 1.0)
                } else {
                    66.0 - 28.0 * (beatElapsed / kickWindow).coerceIn(0.0, 1.0)
                }
            var sample = sin(2.0 * PI * kickFreq * t) * kickEnv * 0.55

            if ((barBeat == 1 || barBeat == 3) && beatElapsed < 0.13) {
                val snareEnv = 1.0 - beatElapsed / 0.13
                val noiseSeed = ((frame * 1_103_515_245L + 12_345L).and(0x7fffffffL))
                val noise = noiseSeed / 1_073_741_824.0 - 1.0
                val snareTone = sin(2.0 * PI * 185.0 * t) * 0.45 + noise * 0.55
                sample += snareTone * snareEnv * if (soundPack == SoundPack.Street) 0.34 else 0.26
            }

            if (halfBeatElapsed < 0.045) {
                val hatEnv = 1.0 - halfBeatElapsed / 0.045
                sample += sin(2.0 * PI * 5_200.0 * t) * hatEnv * if (soundPack == SoundPack.Street) 0.13 else 0.09
            }

            val bassEnv = (1.0 - beatElapsed / beatSeconds).coerceIn(0.0, 1.0)
            sample += sin(2.0 * PI * bassNotes[beatIndex % bassNotes.size] * t) * bassEnv * 0.16

            if (soundPack == SoundPack.Street && barBeat == 3 && beatElapsed < 0.16) {
                val leadEnv = 1.0 - beatElapsed / 0.16
                sample += sin(2.0 * PI * 880.0 * t) * leadEnv * 0.06
            }

            buffer[index] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * packGain).toInt().toShort()
        }
    }

    private fun previewCloudSoundEffect(effect: CloudSoundEffect) {
        stopCloudEffectPreview()
        stopBackgroundMusicPreview()
        Toast.makeText(this, localText("正在试听：${cloudSoundEffectName(effect)}", "Previewing: ${cloudSoundEffectName(effect)}", "Écoute : ${cloudSoundEffectName(effect)}", "ลองฟัง: ${cloudSoundEffectName(effect)}"), Toast.LENGTH_SHORT).show()
        cloudEffectPreviewPlayer =
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setOnPreparedListener { player ->
                    val durationMs = player.duration.takeIf { it > 0 }?.toLong() ?: 0L
                    val previewMs = max(CLOUD_EFFECT_PREVIEW_MIN_MS, durationMs)
                    player.isLooping = durationMs < previewMs
                    player.start()
                    cloudEffectPreviewJob?.cancel()
                    cloudEffectPreviewJob =
                        lifecycleScope.launch {
                            delay(previewMs)
                            stopCloudEffectPreview()
                        }
                }
                setOnCompletionListener {
                    stopCloudEffectPreview()
                }
                setOnErrorListener { _, _, _ ->
                    stopCloudEffectPreview()
                    Toast.makeText(this@MainActivity, localText("试听失败", "Preview failed", "Échec de l'écoute", "ฟังไม่สำเร็จ"), Toast.LENGTH_SHORT).show()
                    true
                }
                if (effect.url.startsWith("asset://")) {
                    val assetPath = effect.url.removePrefix("asset://")
                    assets.openFd(assetPath).use { descriptor ->
                        setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                    }
                } else {
                    setDataSource(effect.url)
                }
                prepareAsync()
            }
        prepareCloudSoundEffect(effect, playWhenReady = false)
    }

    private fun stopCloudEffectPreview() {
        cloudEffectPreviewJob?.cancel()
        cloudEffectPreviewJob = null
        cloudEffectPreviewPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        cloudEffectPreviewPlayer = null
    }

    private fun previewBackgroundMusic(track: CloudBackgroundMusic) {
        stopBackgroundMusicPreview()
        stopCloudEffectPreview()
        if (isNoBackgroundMusic(track)) {
            Toast.makeText(this, backgroundMusicDescription(track), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, localText("正在试听：${backgroundMusicName(track)}", "Previewing: ${backgroundMusicName(track)}", "Écoute : ${backgroundMusicName(track)}", "ลองฟัง: ${backgroundMusicName(track)}"), Toast.LENGTH_SHORT).show()
        backgroundMusicPreviewPlayer =
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setVolume(0.76f, 0.76f)
                setOnPreparedListener { player ->
                    val durationMs = player.duration.takeIf { it > 0 }?.toLong() ?: 0L
                    val previewMs =
                        durationMs
                            .takeIf { it > 0L }
                            ?.coerceAtMost(BACKGROUND_MUSIC_PREVIEW_MAX_MS)
                            ?.coerceAtLeast(BACKGROUND_MUSIC_PREVIEW_MIN_MS)
                            ?: BACKGROUND_MUSIC_PREVIEW_MIN_MS
                    player.isLooping = durationMs < previewMs
                    player.start()
                    backgroundMusicPreviewJob?.cancel()
                    backgroundMusicPreviewJob =
                        lifecycleScope.launch {
                            delay(previewMs)
                            stopBackgroundMusicPreview()
                        }
                }
                setOnCompletionListener {
                    stopBackgroundMusicPreview()
                }
                setOnErrorListener { _, _, _ ->
                    stopBackgroundMusicPreview()
                    Toast.makeText(this@MainActivity, localText("试听失败", "Preview failed", "Échec de l'écoute", "ฟังไม่สำเร็จ"), Toast.LENGTH_SHORT).show()
                    true
                }
                setMusicDataSource(track.url)
                prepareAsync()
            }
    }

    private fun stopBackgroundMusicPreview() {
        backgroundMusicPreviewJob?.cancel()
        backgroundMusicPreviewJob = null
        backgroundMusicPreviewPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        backgroundMusicPreviewPlayer = null
    }

    private fun selectedBackgroundMusicTrack(): CloudBackgroundMusic? =
        when {
            selectedBackgroundMusicId == BACKGROUND_MUSIC_NONE_ID -> null
            else ->
                cloudBackgroundMusic.firstOrNull { it.id == selectedBackgroundMusicId }
                    ?: CloudBackgroundMusic(
                        id = selectedBackgroundMusicId,
                        nameZh = selectedBackgroundMusicName,
                        nameEn = selectedBackgroundMusicName,
                        descriptionZh = "",
                        descriptionEn = "",
                        style = "",
                        bpm = selectedBeatBpm,
                        durationMs = 0,
                        url = selectedBackgroundMusicUrl,
                    ).takeIf { it.id.isNotBlank() && it.url.isNotBlank() }
        }

    private fun startSelectedBackgroundMusic() {
        val track = selectedBackgroundMusicTrack()
        if (track == null) {
            stopTrainingBackgroundMusic()
            return
        }
        startTrainingBackgroundMusic(track.id, track.url, 0.42f)
    }

    private fun startRestBackgroundMusic() {
        startTrainingBackgroundMusic(REST_BACKGROUND_MUSIC_ID, REST_BACKGROUND_MUSIC_URL, 0.34f)
    }

    private fun startTrainingBackgroundMusic(trackId: String, url: String, volume: Float) {
        stopTrainingBackgroundMusic()
        if (url.isBlank()) {
            return
        }
        trainingBackgroundMusicPreparingId = trackId
        trainingBackgroundMusicPlayer =
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                isLooping = true
                setVolume(volume, volume)
                setOnPreparedListener { player ->
                    if (trainingBackgroundMusicPreparingId == trackId) {
                        player.start()
                    }
                }
                setOnErrorListener { _, _, _ ->
                    stopTrainingBackgroundMusic()
                    true
                }
                try {
                    setMusicDataSource(url)
                    prepareAsync()
                } catch (_: Throwable) {
                    stopTrainingBackgroundMusic()
                }
            }
    }

    private fun stopTrainingBackgroundMusic() {
        trainingBackgroundMusicPreparingId = null
        trainingBackgroundMusicPlayer?.let { player ->
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        trainingBackgroundMusicPlayer = null
    }

    private fun MediaPlayer.setMusicDataSource(url: String) {
        if (url.startsWith("asset://")) {
            val assetPath = url.removePrefix("asset://")
            assets.openFd(assetPath).use { descriptor ->
                setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
        } else {
            setDataSource(url)
        }
    }

    private fun prepareCloudSoundEffect(effect: CloudSoundEffect, playWhenReady: Boolean) {
        if (effect.id.isBlank() || effect.url.isBlank()) {
            return
        }
        if (loadedCloudEffectId == effect.id && loadedCloudEffectSampleId != 0) {
            if (playWhenReady) {
                playLoadedCloudEffect(1f)
            }
            return
        }
        if (loadingCloudEffectId == effect.id) {
            return
        }
        loadingCloudEffectId = effect.id
        lifecycleScope.launch(Dispatchers.IO) {
            val file =
                runCatching {
                    downloadCloudSoundEffect(effect)
                }.getOrNull()
            withContext(Dispatchers.Main) {
                loadingCloudEffectId = null
                if (file == null || !file.exists()) {
                    return@withContext
                }
                val pool = ensureCloudEffectSoundPool()
                val previousSample = loadedCloudEffectSampleId
                if (previousSample != 0) {
                    runCatching { pool.unload(previousSample) }
                }
                loadedCloudEffectId = null
                loadedCloudEffectSampleId = 0
                val sampleId = pool.load(file.absolutePath, 1)
                pool.setOnLoadCompleteListener { soundPool, loadedSampleId, status ->
                    if (loadedSampleId == sampleId && status == 0 && selectedCloudSoundEffectId == effect.id) {
                        loadedCloudEffectId = effect.id
                        loadedCloudEffectSampleId = sampleId
                        if (playWhenReady) {
                            soundPool.play(sampleId, 1f, 1f, 1, 0, 1f)
                        }
                    }
                }
            }
        }
    }

    private fun downloadCloudSoundEffect(effect: CloudSoundEffect): File {
        val dir = File(cacheDir, "cloud_sfx").apply { mkdirs() }
        val file = File(dir, "${effect.id}.wav")
        if (file.exists() && file.length() > 1_024L) {
            return file
        }
        if (effect.url.startsWith("asset://")) {
            val assetPath = effect.url.removePrefix("asset://")
            assets.open(assetPath).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            return file
        }
        val connection = URL(effect.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 12_000
            connection.requestMethod = "GET"
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
        return file
    }

    private fun ensureCloudEffectSoundPool(): SoundPool {
        cloudEffectSoundPool?.let { return it }
        val pool =
            SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
        cloudEffectSoundPool = pool
        return pool
    }

    private fun playLoadedCloudEffect(force: Float): Boolean {
        val pool = cloudEffectSoundPool ?: return false
        val sampleId = loadedCloudEffectSampleId.takeIf { it != 0 } ?: return false
        if (loadedCloudEffectId != selectedCloudSoundEffectId) {
            return false
        }
        val volume = when {
            force >= 70f -> 1.0f
            force >= 30f -> 0.82f
            else -> 0.66f
        }
        val rate = when {
            force >= 70f -> 1.04f
            force >= 30f -> 1.0f
            else -> 0.96f
        }
        pool.play(sampleId, volume, volume, 2, 0, rate)
        return true
    }

    private fun prepareSelectedCloudSoundEffect() {
        val effect =
            cloudSoundEffects.firstOrNull { it.id == selectedCloudSoundEffectId }
                ?: CloudSoundEffect(
                    id = selectedCloudSoundEffectId,
                    nameZh = selectedCloudSoundEffectName,
                    nameEn = selectedCloudSoundEffectName,
                    descriptionZh = "",
                    descriptionEn = "",
                    style = "",
                    bpm = selectedBeatBpm,
                    durationMs = 0,
                    url = selectedCloudSoundEffectUrl,
                ).takeIf { it.id.isNotBlank() && it.url.isNotBlank() }
        effect?.let { prepareCloudSoundEffect(it, playWhenReady = false) }
    }

    private fun playPunchSound(force: Float) {
        if (playLoadedCloudEffect(force)) {
            return
        }
        prepareSelectedCloudSoundEffect()
        val tone = ensureToneGenerator()
        val toneType =
            when {
                selectedSoundPack == SoundPack.Street && force >= 70f -> ToneGenerator.TONE_PROP_NACK
                selectedSoundPack == SoundPack.Street -> ToneGenerator.TONE_PROP_BEEP2
                force >= 70f -> ToneGenerator.TONE_PROP_BEEP2
                force >= 30f -> ToneGenerator.TONE_PROP_BEEP
                else -> ToneGenerator.TONE_PROP_ACK
            }
        tone?.startTone(toneType, if (force >= 70f) 90 else 55)
    }

    private fun playBeatTick() {
        ensureToneGenerator()?.startTone(ToneGenerator.TONE_PROP_PROMPT, 45)
    }

    private fun ensureToneGenerator(): ToneGenerator? {
        if (toneGenerator == null) {
            toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 78) }.getOrNull()
        }
        return toneGenerator
    }

    private fun refreshMusicImmersionControls() {
        if (!::rhythmFreeButton.isInitialized) {
            return
        }
        rhythmFreeButton.text = localText("自由", "Free", "Libre", "อิสระ")
        rhythmBeatButton.text = localText("跟拍", "Beat", "Tempo", "ตามจังหวะ")
        soundGymButton.text = localText("拳击馆", "Gym", "Salle", "ยิม")
        soundStreetButton.text = localText("街头", "Street", "Rue", "สตรีท")
        rhythmFreeButton.isChecked = selectedRhythmMode == TrainingRhythmMode.Free
        rhythmBeatButton.isChecked = selectedRhythmMode == TrainingRhythmMode.Rhythm
        beat40Button.isChecked = selectedBeatBpm == 40
        beat65Button.isChecked = selectedBeatBpm == 65
        beat80Button.isChecked = selectedBeatBpm == 80
        beat100Button.isChecked = selectedBeatBpm == 100
        beat120Button.isChecked = selectedBeatBpm == 120
        soundGymButton.isChecked = selectedSoundPack == SoundPack.Gym
        soundStreetButton.isChecked = selectedSoundPack == SoundPack.Street
        val musicEnabled = trainingJob?.isActive != true
        listOf(rhythmFreeButton, rhythmBeatButton, beat40Button, beat65Button, beat80Button, beat100Button, beat120Button, soundGymButton, soundStreetButton).forEach {
            it.isEnabled = musicEnabled
            it.alpha = if (musicEnabled) 1f else 0.62f
        }
    }


    private fun renderIdle(authMessageKey: String? = null) {
        if (!isActivated()) {
            renderActivationRequired()
            return
        }
        setTrainingBusyUi(false)
        setActivationVisible(authMessageKey != null)
        statusView.text = tr("ready")
        statusView.setTextColor(Color.parseColor("#17343B"))
        countdownView.text = "3"
        countView.text = "0"
        remainingView.text = displayRemaining(selectedMode.durationSeconds * 1_000L)
        bluetoothTrainingCount = 0
        resetRealtimeTrainingSession(selectedMode)
        quietIconView.visibility = View.GONE
        lastSpokenCountdown = null
        goSpoken = false
        if (authMessageKey != null) {
            setAuthStatusMessage("#FFB347", key = authMessageKey)
        } else {
            clearAuthStatusMessage()
        }
        applyStaticTexts()
        if (latestReport == null) {
            renderEmptyReport()
        }
        if (authMessageKey == "activation_success_ready") {
            activationCard.removeCallbacks(hideActivationCardRunnable)
            activationCard.postDelayed(hideActivationCardRunnable, 3_000L)
        }
    }

    private fun renderActivationRequired(message: String? = null) {
        setTrainingBusyUi(false)
        setActivationBusy(false)
        setActivationVisible(true)
        statusView.text = tr("activation_required")
        statusView.setTextColor(Color.parseColor("#FF8A32"))
        countdownView.text = tr("lock_short")
        countView.text = "0"
        remainingView.text = displayRemaining(selectedMode.durationSeconds * 1_000L)
        quietIconView.visibility = View.GONE
        setAuthStatusMessage(
            colorHex = "#FFD060",
            key = if (message == null) "activation_hint" else null,
            fallback = message,
        )
        lastSpokenCountdown = null
        goSpoken = false
        applyStaticTexts()
    }


    private fun renderError(message: String) {
        setTrainingBusyUi(false)
        setActivationVisible(!isActivated())
        statusView.text = message
        statusView.setTextColor(Color.parseColor("#FF8A80"))
        countdownView.text = tr("error_short")
        remainingView.text = displayRemaining(selectedMode.durationSeconds * 1_000L)
        quietIconView.visibility = View.GONE
        lastSpokenCountdown = null
        goSpoken = false
        applyStaticTexts()
    }

    private fun renderReport(report: TrainingReport) {
        reportView.removeAllViews()
        val headerRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val modeChip =
            badgeText(
                roundReportBadgeText(report),
                textColor = "#096D65",
                fillColor = "#DFFFF7",
            )
        val hitsChip = badgeText("${report.totalHits} ${tr("hits")}", textColor = "#FFFFFF", fillColor = "#FF8A32")
        val spacer =
            View(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        1,
                        1f,
                    )
            }
        headerRow.addView(modeChip)
        headerRow.addView(spacer)
        headerRow.addView(hitsChip)

        val heroTitle =
            titleText(
                roundReportTitleText(report),
                22f,
            ).apply {
                setTextColor(Color.parseColor("#17343B"))
                setPadding(0, dp(14), 0, 0)
            }
        val summaryLine =
            bodyText(
                trainingBattleReportSummary(report),
            ).apply {
                setTextColor(Color.parseColor("#557A7D"))
                setPadding(0, dp(6), 0, 0)
            }
        val metricsGrid =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(16), 0, 0)
            }
        val topRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        topRow.addView(
            reportMetricCard(
                label = localText("累计锻炼时间", "Total duration", "Durée totale", "เวลารวม"),
                value = formatTrainingDuration(report.durationSeconds),
                accentColor = "#10BDAA",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        rightMargin = dp(10)
                    }
            },
        )
        topRow.addView(
            reportMetricCard(
                label = localText("累计击拳数", "Total punches", "Coups cumulés", "หมัดรวม"),
                value = "${report.totalHits} ${tr("hits")}",
                accentColor = "#16C8B5",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
            },
        )
        val bottomRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
        bottomRow.addView(
            reportMetricCard(
                label = localText("最大力度", "Peak force", "Force max", "แรงสูงสุด"),
                value = forceDisplay(report.peakForceN),
                accentColor = "#E24B4A",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        rightMargin = dp(10)
                    }
            },
        )
        bottomRow.addView(
            reportMetricCard(
                label = localText("平均力度", "Avg force", "Force moy.", "แรงเฉลี่ย"),
                value = forceDisplay(report.avgForceN),
                accentColor = "#A7F3D0",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
            },
        )
        metricsGrid.addView(topRow)
        metricsGrid.addView(bottomRow)
        val burnRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
        burnRow.addView(
            reportMetricCard(
                label = tr("calories_burned"),
                value = formatCalories(report.caloriesBurned),
                accentColor = "#3BCE7A",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(10)
                    }
            },
        )
        burnRow.addView(
            reportMetricCard(
                label = tr("fat_burned"),
                value = formatFatGrams(report.fatBurnedGrams),
                accentColor = "#FFD060",
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val trainingQualityRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
        trainingQualityRow.addView(
            reportMetricCard(
                label = localText("平均 BPM", "Avg BPM", "BPM moy.", "BPM เฉลี่ย"),
                value = if (report.avgBpm > 0f) report.avgBpm.roundToInt().toString() else "--",
                accentColor = "#4FB6FF",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(10)
                    }
            },
        )
        trainingQualityRow.addView(
            reportMetricCard(
                label = localText("最佳连击", "Best burst", "Meilleure rafale", "คอมโบสูงสุด"),
                value = "${report.bestBurstCount} ${tr("hits")}",
                accentColor = "#FFD060",
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        metricsGrid.addView(burnRow)
        metricsGrid.addView(trainingQualityRow)
        roundReportsSummaryCard(report)?.let { metricsGrid.addView(it) }

        reportView.addView(headerRow)
        reportView.addView(heroTitle)
        reportView.addView(summaryLine)
        reportView.addView(metricsGrid)
        coachMessageForReport(report)?.takeIf { it.isNotBlank() }?.let { message ->
            reportView.addView(
                detailCard(fillColor = "#FFF8EF", strokeColor = "#FFD3A1", cornerDp = 18).apply {
                    background = roundedBackground("#FFF8EF", "#FFD3A1", 18)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = dp(14)
                        }
                    addView(
                        bodyText(message).apply {
                            setTextColor(Color.parseColor("#915012"))
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        },
                    )
                },
            )
        }
        addShareTrainingButtonToReport()
        renderHomeConnectionReportCard()
        renderHomeGoalAchievementCard()
    }

    private fun roundReportsSummaryCard(currentReport: TrainingReport): View? {
        if (currentReport.totalRounds <= 1) {
            return null
        }
        val reports =
            trainingRoundReports
                .filter { it.totalRounds == currentReport.totalRounds && it.completedRounds <= currentReport.completedRounds }
                .sortedBy { it.completedRounds }
                .ifEmpty { listOf(currentReport) }
        return detailCard(fillColor = "#F7FFFD", strokeColor = "#CDEFE8", cornerDp = 18).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                }
            addView(
                bodyText(localText("回合累计战报", "Round Cumulative Reports", "Rapports cumulés", "รายงานสะสมรายรอบ")).apply {
                    setTextColor(Color.parseColor("#17343B"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                },
            )
            reports.forEach { report ->
                addView(
                    bodyText(roundReportCumulativeLine(report)).apply {
                        setTextColor(Color.parseColor("#557A7D"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(0, dp(8), 0, 0)
                    },
                )
            }
        }
    }

    private fun roundReportCumulativeLine(report: TrainingReport): String =
        localText(
            "第 ${report.completedRounds} 回合：累计 ${formatTrainingDuration(report.durationSeconds)} | ${report.totalHits} 拳 | ${formatCalories(report.caloriesBurned)} | 等效燃脂 ${formatFatGrams(report.fatBurnedGrams)}",
            "Round ${report.completedRounds}: ${formatTrainingDuration(report.durationSeconds)} | ${report.totalHits} punches | ${formatCalories(report.caloriesBurned)} | ${formatFatGrams(report.fatBurnedGrams)} equivalent fat",
            "Round ${report.completedRounds} : ${formatTrainingDuration(report.durationSeconds)} | ${report.totalHits} coups | ${formatCalories(report.caloriesBurned)} | ${formatFatGrams(report.fatBurnedGrams)} graisse équiv.",
            "รอบ ${report.completedRounds}: ${formatTrainingDuration(report.durationSeconds)} | ${report.totalHits} หมัด | ${formatCalories(report.caloriesBurned)} | ไขมันเทียบเท่า ${formatFatGrams(report.fatBurnedGrams)}",
        )

    private fun addShareTrainingButtonToReport() {
        (shareTrainingButton.parent as? ViewGroup)?.removeView(shareTrainingButton)
        val shareRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(16)
                    }
                addView(
                    shareTrainingButton.apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                    },
                )
            }
        reportView.addView(shareRow)
    }

    private fun activateDevice() {
        val serial = normalizeDigits(serialInput.text?.toString()).take(11)
        val code = normalizeDigits(codeInput.text?.toString()).take(8)
        if (serial.length != 11) {
            setAuthStatusMessage("#FFB347", key = "serial_invalid")
            return
        }
        if (code.length != 8) {
            setAuthStatusMessage("#FFB347", key = "code_invalid")
            return
        }

        activationJob?.cancel()
        setActivationBusy(true)
        setAuthStatusMessage("#FFD060", key = "activation_loading")
        activationJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val result =
                    activationService.activate(
                        serial = serial,
                        code = code,
                        installId = installId,
                        deviceHash = deviceHash,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                withContext(Dispatchers.Main) {
                    setActivationBusy(false)
                    handleActivationResult(serial, result)
                }
            }
    }

    private fun handleActivationResult(
        serial: String,
        result: ActivationApiResult,
    ) {
        if (result.success && !result.activationToken.isNullOrBlank()) {
            persistActivationState(result.serial ?: serial, result.activationToken)
            codeInput.setText("")
            renderIdle(authMessageKey = "activation_success_ready")
            refreshCloudData(forceLeaderboard = true)
            return
        }

        setAuthStatusFailure(result.reason, result.message)
    }

    private fun attemptAutoRestoreActivation(force: Boolean = false) {
        if (isActivated()) {
            return
        }
        if (autoRestoreAttempted && !force) {
            return
        }
        autoRestoreAttempted = true
        activationJob?.cancel()
        setActivationBusy(true)
        setAuthStatusMessage("#FFD060", fallback = activationRestoreLoadingMessage())
        activationJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val result =
                    activationService.reactivateByDevice(
                        installId = installId,
                        deviceHash = deviceHash,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                withContext(Dispatchers.Main) {
                    setActivationBusy(false)
                    if (result.success && !result.activationToken.isNullOrBlank() && !result.serial.isNullOrBlank()) {
                        persistActivationState(result.serial, result.activationToken)
                        clearAuthStatusMessage()
                        renderIdle()
                        refreshCloudData(forceLeaderboard = true)
                    } else if (result.reason == ActivationService.NETWORK_REASON) {
                        setAuthStatusMessage("#FFD060", fallback = activationRestoreNetworkMessage())
                    } else {
                        clearAuthStatusMessage()
                        renderActivationRequired()
                    }
                }
            }
    }

    private fun verifyActivationInBackground() {
        val state = activationState ?: return
        activationJob?.cancel()
        activationJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val result =
                    activationService.check(
                        serial = state.serial,
                        activationToken = state.activationToken,
                        installId = installId,
                        deviceHash = deviceHash,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        markActivationCheckedNow()
                        refreshCloudData(forceLeaderboard = true)
                        if (trainingJob?.isActive != true) {
                            clearAuthStatusMessage()
                            applyStaticTexts()
                        }
                    } else if (result.reason != ActivationService.NETWORK_REASON) {
                        clearActivationState()
                        setAuthStatusFailure(result.reason, result.message)
                        renderActivationRequired(currentAuthStatusMessage())
                        attemptAutoRestoreActivation(force = true)
                    }
                }
            }
    }

    private fun ensureInstallIdentity() {
        installId = prefs.getString(KEY_INSTALL_ID, null).orEmpty()
        if (installId.isBlank()) {
            installId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, installId).apply()
        }
        deviceHash = computeDeviceHash()
    }

    private fun loadActivationState() {
        val serial = prefs.getString(KEY_AUTH_SERIAL, null).orEmpty()
        val token = prefs.getString(KEY_AUTH_TOKEN, null).orEmpty()
        if (serial.isBlank() || token.isBlank()) {
            persistActivationState(generateLocalUserSerial(), "local")
            return
        }
        activationState =
            ActivationState(
                serial = serial,
                activationToken = token,
                installId = prefs.getString(KEY_AUTH_INSTALL_ID, installId).orEmpty().ifBlank { installId },
                deviceHash = prefs.getString(KEY_AUTH_DEVICE_HASH, deviceHash).orEmpty().ifBlank { deviceHash },
                activatedAtEpochMs = prefs.getLong(KEY_AUTH_ACTIVATED_AT, System.currentTimeMillis()),
                lastCheckAtEpochMs = prefs.getLong(KEY_AUTH_LAST_CHECK_AT, 0L),
            )
    }

    private fun persistActivationState(
        serial: String,
        activationToken: String,
    ) {
        val now = System.currentTimeMillis()
        activationState =
            ActivationState(
                serial = serial,
                activationToken = activationToken,
                installId = installId,
                deviceHash = deviceHash,
                activatedAtEpochMs = now,
                lastCheckAtEpochMs = now,
            )
        prefs.edit()
            .putString(KEY_AUTH_SERIAL, serial)
            .putString(KEY_AUTH_TOKEN, activationToken)
            .putString(KEY_AUTH_INSTALL_ID, installId)
            .putString(KEY_AUTH_DEVICE_HASH, deviceHash)
            .putLong(KEY_AUTH_ACTIVATED_AT, now)
            .putLong(KEY_AUTH_LAST_CHECK_AT, now)
            .apply()
    }

    private fun clearActivationState() {
        persistActivationState(generateLocalUserSerial(), "local")
    }

    private fun markActivationCheckedNow() {
        val state = activationState ?: return
        val now = System.currentTimeMillis()
        activationState = state.copy(lastCheckAtEpochMs = now)
        prefs.edit().putLong(KEY_AUTH_LAST_CHECK_AT, now).apply()
    }

    private fun isActivated(): Boolean = true

    private fun generateLocalUserSerial(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(installId.toByteArray(Charsets.UTF_8))
        val numeric = digest.joinToString("") { byte -> ((byte.toInt() and 0xFF) % 10).toString() }
        return numeric.take(11).padEnd(11, '0')
    }

    private fun authFailureMessageKey(reason: String?): String? =
        when (reason) {
            "serial_not_found" -> "activation_serial_not_found"
            "invalid_code" -> "activation_invalid_code"
            "already_bound" -> "activation_already_bound"
            "not_activated" -> "activation_not_activated"
            ActivationService.NETWORK_REASON -> "activation_network_error"
            else -> null
        }

    private fun setAuthStatusFailure(
        reason: String?,
        fallbackMessage: String,
    ) {
        val key = authFailureMessageKey(reason)
        setAuthStatusMessage(
            colorHex = if (reason == ActivationService.NETWORK_REASON) "#FFD060" else "#FFB347",
            key = key,
            fallback = if (key == null) fallbackMessage.ifBlank { tr("activation_failed") } else null,
        )
    }

    private fun setAuthStatusMessage(
        colorHex: String,
        key: String? = null,
        fallback: String? = null,
    ) {
        authStatusMessageKey = key
        authStatusFallbackMessage = fallback
        authStatusColor = Color.parseColor(colorHex)
        applyAuthStatusView()
    }

    private fun currentAuthStatusMessage(): String =
        authStatusMessageKey?.let(::tr) ?: authStatusFallbackMessage.orEmpty()

    private fun clearAuthStatusMessage() {
        authStatusMessageKey = null
        authStatusFallbackMessage = null
        applyAuthStatusView()
    }

    private fun applyAuthStatusView() {
        val message = currentAuthStatusMessage()
        authStatusView.text = message
        if (message.isBlank()) {
            authStatusView.visibility = View.GONE
            authStatusView.background = null
            return
        }
        authStatusView.visibility = View.VISIBLE
        authStatusView.setTextColor(authStatusColor)
        authStatusView.background = chipBackground(authStatusColor)
        authStatusView.setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun refreshActivationCardState() {
        val activated = isActivated()
        activationCard.background = if (activated) heroBackground("#0E4057") else surfaceCardBackground()
        activationTitleView.text =
            if (activated) tr("activation_ready_title") else tr("activation_title")
        activationHintView.text =
            if (activated) tr("activation_ready_subtitle") else tr("activation_subtitle")
        serialInput.hint = tr("serial_hint")
        codeInput.hint = tr("code_hint")
        activateButton.text = tr("activate")

        serialInput.visibility = if (activated) View.GONE else View.VISIBLE
        codeInput.visibility = if (activated) View.GONE else View.VISIBLE
        if (activated) {
            serialInputErrorView.visibility = View.GONE
            codeInputErrorView.visibility = View.GONE
        }
        activateButton.visibility = if (activated) View.GONE else View.VISIBLE
        activationDetailsView.visibility = if (activated) View.VISIBLE else View.GONE

        if (activated) {
            activationDetailsView.text = buildActivationDetailsText()
            applyAuthStatusView()
        } else {
            activationDetailsView.text = ""
            if (authStatusMessageKey == null && authStatusFallbackMessage.isNullOrBlank()) {
                setAuthStatusMessage("#FFD060", key = "activation_hint")
            } else {
                applyAuthStatusView()
            }
        }
    }

    private fun buildActivationDetailsText(): String {
        val state = activationState ?: return ""
        val serialText = if (state.serial.length <= 4) state.serial else "*******" + state.serial.takeLast(4)
        val checkedAt = formatActivationCheckTime(state.lastCheckAtEpochMs)
        return buildString {
            append(localUserLabel())
            append(": ")
            append(serialText)
            append('\n')
            append(readyStatusLabel())
            append(": ")
            append(readyStatusValue())
            append('\n')
            append(lastSeenLabel())
            append(": ")
            append(checkedAt)
        }
    }

    private fun maskSerial(serial: String): String =
        if (serial.length <= 4) serial else "•••••••" + serial.takeLast(4)

    private fun localUserLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "用户ID"
            AppLanguage.English -> "User ID"
            AppLanguage.French -> "ID utilisateur"
            AppLanguage.Thai -> "รหัสผู้ใช้"
        }

    private fun readyStatusLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "状态"
            AppLanguage.English -> "Status"
            AppLanguage.French -> "Statut"
            AppLanguage.Thai -> "สถานะ"
        }

    private fun readyStatusValue(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "可用"
            AppLanguage.English -> "Ready"
            AppLanguage.French -> "Prêt"
            AppLanguage.Thai -> "พร้อมใช้งาน"
        }

    private fun lastSeenLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "最近同步"
            AppLanguage.English -> "Last sync"
            AppLanguage.French -> "Derniere sync"
            AppLanguage.Thai -> "ซิงก์ล่าสุด"
        }

    private fun formatActivationCheckTime(epochMs: Long): String {
        if (epochMs <= 0L) {
            return tr("activation_just_now")
        }
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
    }

    private fun normalizeDigits(value: String?): String = value.orEmpty().filter { it.isDigit() }

    private fun activationRestoreLoadingMessage(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "正在准备本机用户资料..."
            AppLanguage.English -> "Preparing this device profile..."
            AppLanguage.French -> "Preparation du profil de cet appareil..."
            AppLanguage.Thai -> "กำลังเตรียมโปรไฟล์ของอุปกรณ์นี้..."
        }

    private fun activationRestoreNetworkMessage(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "暂时无法同步本机用户资料，请联网后重试。"
            AppLanguage.English -> "Unable to sync this device profile right now. Please connect to the internet and try again."
            AppLanguage.French -> "Impossible de synchroniser ce profil pour le moment. Connectez-vous a Internet puis reessayez."
            AppLanguage.Thai -> "ยังไม่สามารถซิงก์โปรไฟล์อุปกรณ์นี้ได้ โปรดเชื่อมต่ออินเทอร์เน็ตแล้วลองอีกครั้ง"
        }

    private fun computeDeviceHash(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return sha256Hex(if (androidId.isBlank()) "unknown-device" else androidId)
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun refreshCloudData(forceLeaderboard: Boolean = true) {
        val state = activationState ?: return
        cloudJob?.cancel()
        setCloudStatusMessage("#FFD060", key = "cloud_sync_loading")
        cloudJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val bootstrap =
                    cloudSyncService.bootstrap(
                        state = state,
                        language = selectedLanguage,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                val leaderboard =
                    if (bootstrap.success && forceLeaderboard) {
                        cloudSyncService.fetchLeaderboard(
                            state = state,
                            boardKey = leaderboardBoard.apiKey,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    } else {
                        leaderboardResult
                    }
                withContext(Dispatchers.Main) {
                    applyCloudBootstrap(bootstrap)
                    leaderboard?.let { applyLeaderboardResult(it) }
                }
            }
    }

    private fun refreshLeaderboardOnly() {
        val state = activationState ?: return
        cloudJob?.cancel()
        setCloudStatusMessage("#FFD060", key = "leaderboard_loading")
        cloudJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val leaderboard =
                    cloudSyncService.fetchLeaderboard(
                        state = state,
                        boardKey = leaderboardBoard.apiKey,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                withContext(Dispatchers.Main) {
                    applyLeaderboardResult(leaderboard)
                }
            }
    }

    private fun syncTrainingReport(report: TrainingReport) {
        val state = activationState ?: return
        val previousUnlockedKeys = cloudAchievements.filter { it.unlocked }.map { it.key }.toSet()
        val previousTierLevel = cloudTier?.level ?: cloudProfile?.currentTier ?: prefs.getInt(KEY_LAST_SEEN_TIER, 0)
        lifecycleScope.launch(Dispatchers.IO) {
            val upload =
                cloudSyncService.uploadTrainingSession(
                    state = state,
                    report = report,
                    appVersion = BuildConfig.VERSION_NAME,
                )
                val leaderboard =
                    if (upload.success) {
                        cloudSyncService.fetchLeaderboard(
                            state = state,
                            boardKey = leaderboardBoard.apiKey,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    } else {
                    null
                }
            withContext(Dispatchers.Main) {
                if (upload.success) {
                    val newlyUnlocked = computeNewlyUnlockedAchievements(previousUnlockedKeys, upload.achievements)
                    val promotedTier =
                        upload.tier?.takeIf {
                            shouldCelebrateTier(
                                tier = it,
                                promotedHint = upload.promoted,
                                previousLevel = previousTierLevel,
                            )
                        }
                    cloudProfile = upload.profile ?: cloudProfile
                    cloudStatistics = upload.statistics ?: cloudStatistics
                    cloudHistory = if (upload.history.isNotEmpty()) upload.history else cloudHistory
                    if (upload.achievements.isNotEmpty()) {
                        cloudAchievements = upload.achievements
                    }
                    cloudTier = upload.tier ?: cloudTier
                    syncSeenTier(upload.tier)
                    setCloudStatusMessage("#FFB347", key = "cloud_sync_ready")
                    leaderboard?.let { applyLeaderboardResult(it) }
                    refreshCloudViews()
                    maybeShowPostTrainingCelebrations(newlyUnlocked, promotedTier)
                } else {
                    setCloudStatusMessage(
                        colorHex = "#FFD060",
                        key = if (upload.reason == CloudSyncService.NETWORK_REASON) "cloud_sync_network" else null,
                        fallback = upload.message,
                    )
                    refreshCloudViews()
                }
            }
        }
    }

    private fun applyCloudBootstrap(result: CloudBootstrapResult) {
        if (result.success) {
            cloudProfile = result.profile ?: cloudProfile
            cloudStatistics = result.statistics ?: cloudStatistics
            if (result.history.isNotEmpty()) {
                cloudHistory = result.history
            }
            if (result.achievements.isNotEmpty()) {
                cloudAchievements = result.achievements
            }
            cloudTier = result.tier ?: cloudTier
            syncSeenTier(result.tier)
            setCloudStatusMessage("#FFB347", key = "cloud_sync_ready")
        } else {
            setCloudStatusMessage(
                colorHex = "#FFD060",
                key = if (result.reason == CloudSyncService.NETWORK_REASON) "cloud_sync_network" else null,
                fallback = result.message,
            )
        }
        refreshCloudViews()
    }

    private fun applyLeaderboardResult(result: CloudLeaderboardResult) {
        leaderboardResult = result
        leaderboardBoard = leaderboardBoardFromKey(result.boardKey)
        if (result.success) {
            setCloudStatusMessage("#FFB347", key = "leaderboard_ready")
        } else {
            setCloudStatusMessage(
                colorHex = "#FFD060",
                key = if (result.reason == CloudSyncService.NETWORK_REASON) "cloud_sync_network" else null,
                fallback = result.message,
            )
        }
        refreshCloudViews()
    }

    private fun showEditProfileDialog() {
        val currentProfile = cloudProfile ?: return
        val dialogRoot =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(4))
            }
        var selectedAvatarColor = sanitizeAvatarColor(currentProfile.avatarColor)
        var selectedAvatarUri = currentAvatarImageUri()
        val avatarSwatches = mutableListOf<View>()
        val nicknameInput =
            EditText(this).apply {
                setText(currentProfile.nickname)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#8F6A44"))
                setBackgroundColor(Color.parseColor("#2A1000"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                filters = arrayOf(InputFilter.LengthFilter(64))
            }
        val avatarPreviewShell =
            FrameLayout(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(12)
                    }
                background = avatarBackground(selectedAvatarColor)
                clipToOutline = true
            }
        val avatarPreviewImageView =
            ImageView(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                visibility = View.GONE
            }
        val avatarPreviewFallbackView =
            TextView(this).apply {
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        avatarPreviewShell.addView(avatarPreviewImageView)
        avatarPreviewShell.addView(avatarPreviewFallbackView)
        val avatarPaletteRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        avatarPalette.forEachIndexed { index, color ->
            val swatchVisual =
                View(this).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(dp(24), dp(24)).apply {
                            gravity = Gravity.CENTER
                        }
                    background = roundedBackground(color, if (color == selectedAvatarColor) "#FFFFFF" else color, 999)
                }
            val swatchTouch =
                FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    isClickable = true
                    isFocusable = true
                    contentDescription =
                        String.format(Locale.US, tr("cd_avatar_swatch"), index + 1)
                    addView(swatchVisual)
                    setOnClickListener {
                        selectedAvatarColor = color
                        bindAvatarPresentation(
                            container = avatarPreviewShell,
                            imageView = avatarPreviewImageView,
                            fallbackView = avatarPreviewFallbackView,
                            seedText = nicknameInput.text?.toString(),
                            colorHex = selectedAvatarColor,
                            imageUri = selectedAvatarUri,
                        )
                        avatarSwatches.forEachIndexed { idx, child ->
                            val paletteColor = avatarPalette[idx]
                            child.background =
                                roundedBackground(
                                    paletteColor,
                                    if (paletteColor == selectedAvatarColor) "#FFFFFF" else paletteColor,
                                    999,
                                )
                        }
                    }
                }
            avatarSwatches += swatchVisual
            avatarPaletteRow.addView(swatchTouch)
        }
        val avatarPaletteScroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                addView(
                    avatarPaletteRow,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
            }
        val avatarActionRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        val chooseAvatarButton =
            compactActionButton(avatarChooseButtonLabel(), "#16384A").apply {
                setOnClickListener {
                    pendingAvatarSelection = { uri ->
                        if (uri != null) {
                            selectedAvatarUri = uri
                            bindAvatarPresentation(
                                container = avatarPreviewShell,
                                imageView = avatarPreviewImageView,
                                fallbackView = avatarPreviewFallbackView,
                                seedText = nicknameInput.text?.toString(),
                                colorHex = selectedAvatarColor,
                                imageUri = selectedAvatarUri,
                            )
                        }
                    }
                    avatarPickerLauncher.launch(arrayOf("image/*"))
                }
            }
        val clearAvatarButton =
            compactActionButton(avatarClearButtonLabel(), "#5C3D99").apply {
                setOnClickListener {
                    selectedAvatarUri = null
                    bindAvatarPresentation(
                        container = avatarPreviewShell,
                        imageView = avatarPreviewImageView,
                        fallbackView = avatarPreviewFallbackView,
                        seedText = nicknameInput.text?.toString(),
                        colorHex = selectedAvatarColor,
                        imageUri = selectedAvatarUri,
                    )
                }
            }
        avatarActionRow.addView(chooseAvatarButton)
        avatarActionRow.addView(horizontalSpace(dp(12)))
        avatarActionRow.addView(clearAvatarButton)
        dialogRoot.addView(sectionLabel(tr("profile_nickname")))
        dialogRoot.addView(nicknameInput)
        dialogRoot.addView(spacer(dp(12)))
        dialogRoot.addView(sectionLabel(tr("profile_avatar")))
        dialogRoot.addView(avatarPreviewShell)
        dialogRoot.addView(avatarActionRow)
        dialogRoot.addView(
            bodyText(avatarImageHintText()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(Color.parseColor("#B88A54"))
                setPadding(0, dp(8), 0, dp(8))
            },
        )
        dialogRoot.addView(avatarPaletteScroll)
        bindAvatarPresentation(
            container = avatarPreviewShell,
            imageView = avatarPreviewImageView,
            fallbackView = avatarPreviewFallbackView,
            seedText = nicknameInput.text?.toString(),
            colorHex = selectedAvatarColor,
            imageUri = selectedAvatarUri,
        )
        nicknameInput.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    bindAvatarPresentation(
                        container = avatarPreviewShell,
                        imageView = avatarPreviewImageView,
                        fallbackView = avatarPreviewFallbackView,
                        seedText = s?.toString(),
                        colorHex = selectedAvatarColor,
                        imageUri = selectedAvatarUri,
                    )
                }

                override fun afterTextChanged(s: android.text.Editable?) = Unit
            },
        )

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(tr("profile_edit"))
                .setView(dialogRoot)
                .setNegativeButton(tr("cancel"), null)
                .setPositiveButton(tr("save"), null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val state = activationState ?: return@setOnClickListener
                val nickname = nicknameInput.text?.toString()?.trim().orEmpty()
                if (nickname.isBlank()) {
                    setCloudStatusMessage("#FFB347", key = "profile_save_failed")
                    refreshCloudViews()
                    return@setOnClickListener
                }
                storeAvatarImageUri(selectedAvatarUri)
                refreshProfileAvatar()
                setCloudStatusMessage("#FFD060", key = "cloud_sync_loading")
                refreshCloudViews()
                lifecycleScope.launch(Dispatchers.IO) {
                    val result =
                        cloudSyncService.updateProfile(
                            state = state,
                            nickname = nickname,
                            language = selectedLanguage,
                            avatarColor = selectedAvatarColor,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    withContext(Dispatchers.Main) {
                        applyCloudBootstrap(result)
                        if (result.success) {
                            setCloudStatusMessage("#FFB347", key = "profile_saved")
                            dialog.dismiss()
                        } else if (result.reason != CloudSyncService.NETWORK_REASON) {
                            setCloudStatusMessage("#FFB347", key = "profile_save_failed", fallback = result.message)
                        }
                        refreshCloudViews()
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#F0FFFB"))
    }

    private fun showDeveloperInfoDialog() {
        val scrollView =
            ScrollView(this).apply {
                isFillViewport = true
            }
        val dialogRoot =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(12))
            }
        scrollView.addView(
            dialogRoot,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        dialogRoot.addView(
            bodyText(developerInfoPageSubtitle()).apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, 0, dp(12))
            },
        )

        dialogRoot.addView(sectionLabel(developerCompanySectionTitle()))
        dialogRoot.addView(
            detailCard(fillColor = "#FFFFFF", strokeColor = "#CDEFE8").apply {
                addView(
                    titleText(developerCompanyName(), 18f).apply {
                        setTextColor(Color.parseColor("#17343B"))
                    },
                )
                addView(
                    bodyText(developerCompanyDescription()).apply {
                        setTextColor(Color.parseColor("#557A7D"))
                        setPadding(0, dp(8), 0, 0)
                    },
                )
            },
        )

        dialogRoot.addView(spacer(dp(12)))
        dialogRoot.addView(sectionLabel(developerContactSectionTitle()))
        dialogRoot.addView(
            detailCard(fillColor = "#FFFFFF", strokeColor = "#CDEFE8").apply {
                addView(
                    bodyText(developerEmailLabel()).apply {
                        setTextColor(Color.parseColor("#557A7D"))
                        setTypeface(Typeface.DEFAULT_BOLD)
                    },
                )
                addView(
                    titleText(DEVELOPER_EMAIL, 18f).apply {
                        setTextColor(Color.parseColor("#FF8A32"))
                        setPadding(0, dp(8), 0, 0)
                    },
                )
                addView(
                    compactActionButton(developerEmailActionLabel(), "#E07010").apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = dp(12)
                            }
                        setOnClickListener { openDeveloperEmail() }
                    },
                )
            },
        )

        dialogRoot.addView(spacer(dp(12)))
        dialogRoot.addView(sectionLabel(developerExtrasSectionTitle()))
        dialogRoot.addView(
            detailCard(fillColor = "#FFFFFF", strokeColor = "#CDEFE8").apply {
                addView(
                    bodyText("${developerVersionLabel()}: ${displayAppVersion()}").apply {
                        setTextColor(Color.parseColor("#17343B"))
                        setTypeface(Typeface.DEFAULT_BOLD)
                    },
                )
                addView(
                    bodyText(developerDocumentHint()).apply {
                        setTextColor(Color.parseColor("#557A7D"))
                        setPadding(0, dp(10), 0, 0)
                    },
                )
                addView(
                    compactActionButton(privacyPolicyEntryLabel(), "#10BDAA").apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = dp(12)
                            }
                        setOnClickListener {
                            showDeveloperDocumentDialog(
                                title = privacyPolicyEntryLabel(),
                                assetFile = developerPrivacyPolicyAssetFile(),
                            )
                        }
                    },
                )
                addView(
                    compactActionButton(userAgreementEntryLabel(), "#10BDAA").apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = dp(10)
                            }
                        setOnClickListener {
                            showDeveloperDocumentDialog(
                                title = userAgreementEntryLabel(),
                                assetFile = developerUserAgreementAssetFile(),
                            )
                        }
                    },
                )
            },
        )

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(developerInfoPageTitle())
                .setView(scrollView)
                .setPositiveButton(closeLabel(), null)
                .create()
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#F0FFFB"))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.88f).toInt(),
        )
    }

    private fun showDeveloperDocumentDialog(
        title: String,
        assetFile: String,
    ) {
        val content = loadAssetText(assetFile).ifBlank { developerDocumentUnavailableText() }
        val scrollView =
            ScrollView(this).apply {
                isFillViewport = true
            }
        val body =
            bodyText(content).apply {
                setTextColor(Color.parseColor("#17343B"))
                setLineSpacing(dp(4).toFloat(), 1.15f)
                setPadding(dp(20), dp(18), dp(20), dp(12))
            }
        scrollView.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scrollView)
                .setPositiveButton(closeLabel(), null)
                .create()
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#F0FFFB"))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.88f).toInt(),
        )
    }

    private fun refreshCloudViews() {
        val activated = isActivated()
        refreshHomePageVisibility()
        renderTrainingHero()
        if (!activated) {
            return
        }

        profileSummaryView.text = buildProfileSummaryText()
        profileMetaView.text = buildProfileMetaSummary()
        profileTierView.text = buildProfileTierSummary()
        profileStatsView.text = buildProfileStatsOverview()
        profileBadgesView.text = buildRecentBadgeSummary()
        refreshProfileAvatar()
        cloudStatusView.setTextColor(Color.parseColor("#096D65"))
        cloudStatusView.text = currentCloudStatusMessage().ifBlank { tr("cloud_sync_idle") }
        cloudStatusView.background = roundedBackground("#DFFFF7", "#BFEFE5", 999)
        renderAchievements()
        renderHistoryCards()
        renderLeaderboard()
        refreshCloudListLocaleBindings()
        stopSwipeRefreshSpinners()
    }

    private fun refreshCloudListLocaleBindings() {
        if (::historyItemAdapter.isInitialized && historyItemAdapter.currentList.isNotEmpty()) {
            historyItemAdapter.notifyDataSetChanged()
        }
        if (::leaderboardRowAdapter.isInitialized && leaderboardRowAdapter.currentList.isNotEmpty()) {
            leaderboardRowAdapter.notifyDataSetChanged()
        }
    }

    private fun stopSwipeRefreshSpinners() {
        if (::trainingSwipe.isInitialized) trainingSwipe.isRefreshing = false
        if (::achievementsSwipe.isInitialized) achievementsSwipe.isRefreshing = false
        if (::leaderboardSwipe.isInitialized) leaderboardSwipe.isRefreshing = false
        if (::profileSwipe.isInitialized) profileSwipe.isRefreshing = false
    }

    private fun renderTrainingHero() {
        val activated = isActivated()
        val stats = cloudStatistics
        trainingHeroBadgeView.text =
            when (selectedLanguage) {
                AppLanguage.Chinese -> "训练战报"
                AppLanguage.English -> "TRAINING REPORT"
                AppLanguage.French -> "RAPPORT"
                AppLanguage.Thai -> "รายงานการฝึก"
            }
        trainingHeroHeadlineView.text =
            when {
                latestReport != null -> roundReportTitleText(latestReport!!)
                activated -> localText("准备生成训练战报", "Ready To Generate Report", "Pret a generer le rapport", "พร้อมสร้างรายงาน")
                else -> tr("title")
            }
        trainingHeroSummaryView.text =
            when {
                !activated ->
                    when (selectedLanguage) {
                        AppLanguage.Chinese -> "准备好本机用户资料后，即可开启训练成长与段位挑战。"
                        AppLanguage.English -> "Your device profile is getting ready for training progress and rank challenges."
                        AppLanguage.French -> "Votre profil appareil se prepare pour la progression et les defis de rang."
                        AppLanguage.Thai -> "กำลังเตรียมโปรไฟล์อุปกรณ์สำหรับความก้าวหน้าและความท้าทายด้านระดับ"
                    }
                latestReport != null -> trainingBattleReportSummary(latestReport!!)
                stats != null -> trainingStatsSummary(stats)
                else ->
                    when (selectedLanguage) {
                        AppLanguage.Chinese -> "云端训练数据正在同步，稍后即可看到你的成绩、段位与成长进度。"
                        AppLanguage.English -> "Cloud data is syncing. Your scores, rank, and growth progress will appear soon."
                        AppLanguage.French -> "Les données cloud sont en cours de synchronisation. Vos scores et votre progression apparaîtront bientôt."
                        AppLanguage.Thai -> "กำลังซิงก์ข้อมูลคลาวด์ ไม่นานคุณจะเห็นคะแนน ระดับ และความคืบหน้า"
                    }
            }
        trainingHeroInsightView.text =
            latestReport?.let { report ->
                trainingBattleReportForceLine(report)
            } ?: stats?.let {
                trainingStatsForceBurnLine(it)
            } ?: when (selectedLanguage) {
                AppLanguage.Chinese -> "暂无最新战报，完成一轮训练后这里会展示你的核心成绩。"
                else -> "No battle report yet. Finish a session and your key stats will appear here."
            }
        trainingHeroProgressView.text =
            when {
                !activated ->
                    when (selectedLanguage) {
                        AppLanguage.Chinese -> "训练记录、成就、榜单与个人成长数据将自动同步"
                        AppLanguage.English -> "Cloud records, achievements, leaderboards, and profile progress sync automatically."
                        AppLanguage.French -> "Historique cloud, succes, classements et progression se synchronisent automatiquement."
                        AppLanguage.Thai -> "ประวัติ ความสำเร็จ กระดานจัดอันดับ และความก้าวหน้าจะซิงก์อัตโนมัติ"
                    }
                latestReport != null || stats != null -> trainingReportLeaderboardLine()
                else -> currentCloudStatusMessage().ifBlank { tr("cloud_sync_idle") }
            }
        trainingHeroCard.background = roundedBackground("#F3FFFC", "#C9F0E9", 24)
        shareTrainingButton.alpha = if (latestReport != null) 1.0f else 0.72f
        shareTrainingButton.isEnabled = latestReport != null
        renderHomeConnectionReportCard()
        renderHomeGoalAchievementCard()
    }

    private fun trainingPlayModeForCheckedId(checkedId: Int): TrainingPlayMode =
        when (checkedId) {
            mode60Button.id -> TrainingPlayMode.Classic60
            modeBurst10Button.id -> TrainingPlayMode.Burst10
            modeBurst15Button.id -> TrainingPlayMode.Burst15
            modeLevelButton.id -> TrainingPlayMode.LevelChallenge
            modeDailyButton.id -> TrainingPlayMode.DailyChallenge
            else -> TrainingPlayMode.Classic30
        }

    private fun configureModeButton(button: RadioButton) {
        button.includeFontPadding = false
        button.minHeight = dp(38)
        button.minimumHeight = dp(38)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        button.setPadding(dp(12), dp(7), dp(12), dp(7))
        button.layoutParams =
            RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(6)
            }
    }

    private fun configureLeaderboardFilterButton(button: RadioButton) {
        button.includeFontPadding = false
        button.minHeight = dp(36)
        button.minimumHeight = dp(36)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        button.setTextColor(Color.parseColor("#17343B"))
        button.setPadding(dp(8), dp(6), dp(12), dp(6))
        button.buttonTintList =
            ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked),
                ),
                intArrayOf(
                    Color.parseColor("#10BDAA"),
                    Color.parseColor("#8CCDC4"),
                ),
            )
        button.layoutParams =
            RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                rightMargin = dp(6)
            }
    }

    private fun refreshModeButtonStyles() {
        if (!::mode30Button.isInitialized || !::modeDailyButton.isInitialized) {
            return
        }
        val items =
            listOf(
                Triple(mode30Button, TrainingPlayMode.Classic30, "#FF9A30"),
                Triple(mode60Button, TrainingPlayMode.Classic60, "#FFB347"),
                Triple(modeBurst10Button, TrainingPlayMode.Burst10, "#FFD060"),
                Triple(modeBurst15Button, TrainingPlayMode.Burst15, "#FF9A30"),
                Triple(modeLevelButton, TrainingPlayMode.LevelChallenge, "#10BDAA"),
                Triple(modeDailyButton, TrainingPlayMode.DailyChallenge, "#E07010"),
            )
        items.forEach { (button, playMode, accentColor) ->
            val selected = selectedPlayMode == playMode
            button.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#557A7D"))
            button.text = coloredModeLabel(playMode, accentColor)
            button.setTypeface(if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            button.background =
                roundedBackground(
                    fillColor = if (selected) "#10BDAA" else "#FFFFFF",
                    strokeColor = if (selected) "#10BDAA" else "#CDEFE8",
                    cornerDp = 18,
                )
            button.alpha = if (trainingJob?.isActive == true) 0.62f else 1.0f
        }
    }

    private fun coloredModeLabel(
        playMode: TrainingPlayMode,
        accentColor: String,
    ): SpannableString {
        val label = playModeLabel(playMode)
        return SpannableString(label).apply {
            if (label.isNotEmpty()) {
                setSpan(
                    ForegroundColorSpan(Color.parseColor(accentColor)),
                    0,
                    1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    RelativeSizeSpan(1.22f),
                    0,
                    1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun modeForPlayMode(playMode: TrainingPlayMode): TrainingMode =
        when (playMode) {
            TrainingPlayMode.Classic30,
            TrainingPlayMode.LevelChallenge,
            TrainingPlayMode.DailyChallenge,
            -> TrainingMode.Seconds30

            TrainingPlayMode.Classic60 -> TrainingMode.Seconds60
            TrainingPlayMode.Burst10 -> TrainingMode.Burst10
            TrainingPlayMode.Burst15 -> TrainingMode.Burst15
        }

    private fun playModeLabel(playMode: TrainingPlayMode): String =
        when (playMode) {
            TrainingPlayMode.Classic30 ->
                localText("● 经典30秒", "● Classic 30s", "● Classique 30s", "● คลาสสิก 30วิ")

            TrainingPlayMode.Classic60 ->
                localText("◆ 60秒耐力", "◆ Endurance 60s", "◆ Endurance 60s", "◆ อึด 60วิ")

            TrainingPlayMode.Burst10 ->
                localText("▲ 10秒爆发", "▲ Burst 10s", "▲ Explosif 10s", "▲ ระเบิด 10วิ")

            TrainingPlayMode.Burst15 ->
                localText("▲ 15秒爆发", "▲ Burst 15s", "▲ Explosif 15s", "▲ ระเบิด 15วิ")

            TrainingPlayMode.LevelChallenge ->
                localText("★ 闯关挑战", "★ Level Challenge", "★ Défi niveau", "★ ด่านท้าทาย")

            TrainingPlayMode.DailyChallenge ->
                localText("✓ 每日挑战", "✓ Daily Challenge", "✓ Défi quotidien", "✓ ภารกิจวันนี้")
        }

    private fun renderTrainingPlayStatus() {
        if (!::trainingPlayTitleView.isInitialized) {
            return
        }
        val goal = currentTrainingGoalPresentation()
        trainingPlayTitleView.text = goal.title
        trainingPlayBodyView.text = goal.body
        trainingPlayProgressView.text = buildTrainingProgressLine(goal.targetHits)
        trainingPlayCard.background = metallicBackground("#142F42", "#08131C", goal.accentColor, 22)
    }

    private fun currentTrainingGoalPresentation(): TrainingGoalPresentation {
        return trainingGoalPresentationFor(selectedPlayMode)
    }

    private fun trainingGoalPresentationFor(playMode: TrainingPlayMode): TrainingGoalPresentation {
        val level = currentTrainingLevelDefinition()
        val dailyTarget = dailyChallengeTargetHits()
        return when (playMode) {
            TrainingPlayMode.Classic30 ->
                TrainingGoalPresentation(
                    title = localText("经典训练", "Classic Training", "Entraînement classique", "ฝึกคลาสสิก"),
                    body = localText(
                        "30 秒稳定计数，适合每天热身、测试节奏和刷新历史最佳。",
                        "A steady 30-second session for warm-up, rhythm checks, and best-score attempts.",
                        "Session stable de 30 s pour s'échauffer, tester le rythme et battre son record.",
                        "ฝึก 30 วินาทีแบบมั่นคง เหมาะสำหรับวอร์มอัพ เช็กจังหวะ และทำสถิติใหม่",
                    ),
                    accentColor = "#FF9A30",
                )

            TrainingPlayMode.Classic60 ->
                TrainingGoalPresentation(
                    title = localText("耐力训练", "Endurance Training", "Entraînement endurance", "ฝึกความอึด"),
                    body = localText(
                        "60 秒持续输出，适合练稳定性、耐力和连续节奏。",
                        "A 60-second session for consistency, stamina, and sustained rhythm.",
                        "Session de 60 s pour travailler la régularité, l'endurance et le rythme continu.",
                        "ฝึก 60 วินาที เพื่อความสม่ำเสมอ ความอึด และจังหวะต่อเนื่อง",
                    ),
                    accentColor = "#FFB347",
                )

            TrainingPlayMode.Burst10 ->
                TrainingGoalPresentation(
                    title = localText("10 秒爆发", "10-second Burst", "Explosif 10 s", "ระเบิด 10 วิ"),
                    body = localText(
                        "短时间冲刺，目标 25 击；更适合练启动速度和瞬间爆发。",
                        "Short sprint mode. Target 25 hits for launch speed and explosive rhythm.",
                        "Sprint court. Objectif 25 coups pour travailler le départ et l'explosivité.",
                        "โหมดสปรินต์สั้น เป้าหมาย 25 ครั้ง เพื่อฝึกความเร็วเริ่มต้นและแรงระเบิด",
                    ),
                    accentColor = "#FFD060",
                    targetHits = 25,
                )

            TrainingPlayMode.Burst15 ->
                TrainingGoalPresentation(
                    title = localText("15 秒爆发", "15-second Burst", "Explosif 15 s", "ระเบิด 15 วิ"),
                    body = localText(
                        "稍长冲刺，目标 38 击；兼顾爆发和控制。",
                        "A longer sprint. Target 38 hits while keeping control.",
                        "Sprint plus long. Objectif 38 coups en gardant le contrôle.",
                        "สปรินต์นานขึ้น เป้าหมาย 38 ครั้ง พร้อมคุมจังหวะให้ดี",
                    ),
                    accentColor = "#FFD060",
                    targetHits = 38,
                )

            TrainingPlayMode.LevelChallenge ->
                TrainingGoalPresentation(
                    title = localText(
                        "第 ${level.level} 关 | 目标 ${level.targetHits} 击",
                        "Level ${level.level} | ${level.targetHits} hits",
                        "Niveau ${level.level} | ${level.targetHits} coups",
                        "ด่าน ${level.level} | เป้าหมาย ${level.targetHits} ครั้ง",
                    ),
                    body = localText(
                        "完成本关即可解锁下一关。闯关模式会把训练变成可持续推进的成长路线。",
                        "Clear the target to unlock the next level and turn training into steady progression.",
                        "Atteignez l'objectif pour débloquer le niveau suivant et progresser régulièrement.",
                        "ทำให้ถึงเป้าหมายเพื่อปลดล็อกด่านถัดไป และเปลี่ยนการฝึกให้เป็นเส้นทางเติบโต",
                    ),
                    accentColor = "#10BDAA",
                    targetHits = level.targetHits,
                )

            TrainingPlayMode.DailyChallenge ->
                TrainingGoalPresentation(
                    title = localText(
                        "今日挑战 | 目标 $dailyTarget 击",
                        "Daily Challenge | $dailyTarget hits",
                        "Défi du jour | $dailyTarget coups",
                        "ภารกิจวันนี้ | $dailyTarget ครั้ง",
                    ),
                    body = localText(
                        "每天一个轻量目标，完成后记录今日任务奖励，适合培养连续训练习惯。",
                        "A lightweight daily target that rewards consistency and helps build a training habit.",
                        "Un objectif léger chaque jour pour récompenser la régularité et créer l'habitude.",
                        "เป้าหมายเบา ๆ รายวัน ช่วยให้ฝึกต่อเนื่องและสร้างนิสัยการฝึก",
                    ),
                    accentColor = "#E07010",
                    targetHits = dailyTarget,
                )
        }
    }

    private fun updateTrainingGameAfterReport(
        report: TrainingReport,
        playMode: TrainingPlayMode,
    ): TrainingCoachOutcome {
        val today = todayKey()
        ensureDailyTaskDate(today)
        saveLocalSessionSummary(report)
        val streak = updateTrainingStreak(today)
        prefs.edit().putBoolean(KEY_DAILY_TASK_TRAINED, true).apply()

        var xpGain = 10
        val levelBefore = currentTrainingLevelDefinition().level
        var levelAfter = levelBefore
        val currentGoal = trainingGoalPresentationFor(playMode)
        val goalMet = currentGoal.targetHits?.let { report.totalHits >= it } == true
        if (goalMet) {
            xpGain += 10
            prefs.edit().putBoolean(KEY_DAILY_TASK_TARGET_DONE, true).apply()
        }

        when (playMode) {
            TrainingPlayMode.LevelChallenge -> {
                val level = currentTrainingLevelDefinition()
                if (report.totalHits >= level.targetHits) {
                    val nextLevel = (level.level + 1).coerceAtMost(trainingLevelDefinitions().size)
                    prefs.edit().putInt(KEY_TRAINING_LEVEL, nextLevel).apply()
                    levelAfter = nextLevel
                    xpGain += 25
                }
            }

            TrainingPlayMode.DailyChallenge -> {
                val target = dailyChallengeTargetHits()
                if (report.totalHits >= target) {
                    xpGain += 20
                }
            }

            TrainingPlayMode.Burst10,
            TrainingPlayMode.Burst15,
            -> {
                if (goalMet) {
                    xpGain += 15
                }
            }

            TrainingPlayMode.Classic30,
            TrainingPlayMode.Classic60,
            -> Unit
        }

        addTrainingXp(xpGain)
        return TrainingCoachOutcome(
            playMode = playMode,
            goalMet = goalMet,
            levelBefore = levelBefore,
            levelAfter = levelAfter,
            targetHits = currentGoal.targetHits,
            streak = streak,
            xpGain = xpGain,
        )
    }

    private fun coachMessageForReport(report: TrainingReport): String? =
        lastCoachOutcome?.let { buildCoachMessage(report, it) } ?: lastCoachMessage

    private fun buildCoachMessage(
        report: TrainingReport,
        outcome: TrainingCoachOutcome,
    ): String {
        val challengeMessage = challengeMessageForOutcome(report, outcome)
        val trend = sevenDayTrendText()
        val paceLine =
            when {
                report.averageFrequency >= 3.0f ->
                    localText(
                        "高爆发节奏，注意保持动作质量。",
                        "High burst rhythm. Keep the movement quality clean.",
                        "Rythme explosif. Gardez une bonne qualité de mouvement.",
                        "จังหวะระเบิดดีมาก รักษาคุณภาพท่าไว้",
                    )

                report.averageFrequency >= 2.0f ->
                    localText(
                        "节奏很稳，适合继续挑战更高目标。",
                        "Solid rhythm. You are ready to chase a higher target.",
                        "Rythme solide. Vous pouvez viser plus haut.",
                        "จังหวะมั่นคง พร้อมท้าทายเป้าหมายที่สูงขึ้น",
                    )

                else ->
                    localText(
                        "先稳住命中质量，再逐步加速。",
                        "Lock in clean hits first, then build speed gradually.",
                        "Stabilisez d'abord les frappes, puis accélérez.",
                        "ตั้งคุณภาพหมัดให้แน่นก่อน แล้วค่อยเพิ่มความเร็ว",
                    )
            }
        return localText(
            "$challengeMessage $paceLine 连续训练 ${outcome.streak} 天，XP +${outcome.xpGain}。$trend",
            "$challengeMessage $paceLine Streak ${outcome.streak} day(s), XP +${outcome.xpGain}. $trend",
            "$challengeMessage $paceLine Série de ${outcome.streak} jour(s), XP +${outcome.xpGain}. $trend",
            "$challengeMessage $paceLine ฝึกต่อเนื่อง ${outcome.streak} วัน, XP +${outcome.xpGain}. $trend",
        )
    }

    private fun challengeMessageForOutcome(
        report: TrainingReport,
        outcome: TrainingCoachOutcome,
    ): String =
        when (outcome.playMode) {
            TrainingPlayMode.LevelChallenge -> {
                val target = outcome.targetHits ?: 0
                if (outcome.goalMet) {
                    if (outcome.levelAfter > outcome.levelBefore) {
                        localText(
                            "闯关成功，已解锁第 ${outcome.levelAfter} 关。",
                            "Level cleared. Level ${outcome.levelAfter} unlocked.",
                            "Niveau réussi. Niveau ${outcome.levelAfter} débloqué.",
                            "ผ่านด่านแล้ว ปลดล็อกด่าน ${outcome.levelAfter}",
                        )
                    } else {
                        localText(
                            "已完成最高关卡，继续刷新极限。",
                            "Top level cleared. Keep pushing your limit.",
                            "Dernier niveau terminé. Continuez à dépasser vos limites.",
                            "ผ่านด่านสูงสุดแล้ว ลุยทำลายขีดจำกัดต่อไป",
                        )
                    }
                } else {
                    val remaining = (target - report.totalHits).coerceAtLeast(0)
                    localText(
                        "本关还差 $remaining 击，下次优先稳住节奏。",
                        "$remaining hits to clear this level. Keep the rhythm steady next time.",
                        "Il manque $remaining coups pour réussir. Gardez le rythme la prochaine fois.",
                        "ยังขาด $remaining หมัดสำหรับด่านนี้ ครั้งหน้าคุมจังหวะให้มั่น",
                    )
                }
            }

            TrainingPlayMode.DailyChallenge -> {
                val target = outcome.targetHits ?: dailyChallengeTargetHits()
                if (outcome.goalMet) {
                    localText(
                        "今日挑战完成，已记录任务奖励。",
                        "Daily challenge completed and rewarded.",
                        "Défi quotidien terminé, récompense enregistrée.",
                        "ทำภารกิจวันนี้สำเร็จ บันทึกรางวัลแล้ว",
                    )
                } else {
                    val remaining = (target - report.totalHits).coerceAtLeast(0)
                    localText(
                        "今日挑战还差 $remaining 击，可以再来一轮。",
                        "$remaining hits short of today's challenge. One more run can do it.",
                        "Il manque $remaining coups au défi du jour. Un autre round peut suffire.",
                        "ภารกิจวันนี้ยังขาด $remaining หมัด อีกหนึ่งรอบทำได้",
                    )
                }
            }

            TrainingPlayMode.Burst10,
            TrainingPlayMode.Burst15,
            -> {
                if (outcome.goalMet) {
                    localText(
                        "爆发目标达成，启动速度很漂亮。",
                        "Burst target reached. Your launch speed looks sharp.",
                        "Objectif explosif atteint. Votre démarrage est net.",
                        "ถึงเป้าหมายระเบิดแล้ว จังหวะเริ่มสวยมาก",
                    )
                } else {
                    localText(
                        "爆发训练已完成，下一轮可以尝试把前 3 秒打得更主动。",
                        "Burst session complete. Try attacking the first 3 seconds harder next round.",
                        "Séance explosive terminée. Attaquez plus fort les 3 premières secondes au prochain round.",
                        "จบรอบระเบิดแล้ว รอบหน้าลองเร่ง 3 วินาทีแรกให้ดุดันขึ้น",
                    )
                }
            }

            TrainingPlayMode.Classic30,
            TrainingPlayMode.Classic60,
            -> localText(
                "训练已记录，今天的节奏又往前推进了一步。",
                "Session recorded. Today's rhythm moved one step forward.",
                "Séance enregistrée. Votre rythme progresse encore aujourd'hui.",
                "บันทึกการฝึกแล้ว จังหวะวันนี้ก้าวหน้าอีกขั้น",
            )
        }

    private fun buildTrainingProgressLine(targetHits: Int?): String {
        ensureDailyTaskDate()
        val targetText =
            targetHits?.let {
                localText("本轮目标 $it 击", "Target $it hits", "Objectif $it coups", "เป้าหมายรอบนี้ $it หมัด")
            } ?: localText("自由训练", "Free training", "Entraînement libre", "ฝึกอิสระ")
        return localText(
            "$targetText | ${dailyTaskSummaryText()} | ${sevenDayTrendText()}",
            "$targetText | ${dailyTaskSummaryText()} | ${sevenDayTrendText()}",
            "$targetText | ${dailyTaskSummaryText()} | ${sevenDayTrendText()}",
            "$targetText | ${dailyTaskSummaryText()} | ${sevenDayTrendText()}",
        )
    }

    private fun dailyTaskSummaryText(): String {
        ensureDailyTaskDate()
        val doneCount =
            listOf(
                prefs.getBoolean(KEY_DAILY_TASK_TRAINED, false),
                prefs.getBoolean(KEY_DAILY_TASK_TARGET_DONE, false),
                prefs.getBoolean(KEY_DAILY_TASK_SHARED, false),
            ).count { it }
        val streak = prefs.getInt(KEY_TRAINING_STREAK, 0)
        val xp = prefs.getInt(KEY_TRAINING_XP, 0)
        return localText(
            "今日任务 $doneCount/3 | 连续 $streak 天 | XP $xp",
            "Daily tasks $doneCount/3 | Streak $streak | XP $xp",
            "Tâches du jour $doneCount/3 | Série $streak | XP $xp",
            "ภารกิจวันนี้ $doneCount/3 | ต่อเนื่อง $streak วัน | XP $xp",
        )
    }

    private fun markTrainingSharedForDailyTask() {
        ensureDailyTaskDate()
        prefs.edit().putBoolean(KEY_DAILY_TASK_SHARED, true).apply()
        renderTrainingPlayStatus()
    }

    private fun trainingLevelDefinitions(): List<TrainingLevelDefinition> =
        listOf(
            TrainingLevelDefinition(1, 12),
            TrainingLevelDefinition(2, 18),
            TrainingLevelDefinition(3, 25),
            TrainingLevelDefinition(4, 32),
            TrainingLevelDefinition(5, 40),
            TrainingLevelDefinition(6, 50),
            TrainingLevelDefinition(7, 60),
            TrainingLevelDefinition(8, 75),
            TrainingLevelDefinition(9, 90),
        )

    private fun currentTrainingLevelDefinition(): TrainingLevelDefinition {
        val levels = trainingLevelDefinitions()
        val level = prefs.getInt(KEY_TRAINING_LEVEL, 1).coerceIn(1, levels.size)
        return levels.firstOrNull { it.level == level } ?: levels.first()
    }

    private fun dailyChallengeTargetHits(): Int {
        val best30 = max(cloudStatistics?.best30Hits ?: 0, bestLocalThirtySecondHits())
        return if (best30 <= 0) {
            20
        } else {
            max(18, (best30 * 0.82f).toInt()).coerceIn(18, 120)
        }
    }

    private fun bestLocalThirtySecondHits(): Int =
        loadLocalSessionSummaries()
            .filter { it.durationSeconds == 30 }
            .map { it.hits }
            .maxOrNull() ?: 0

    private fun addTrainingXp(value: Int) {
        val current = prefs.getInt(KEY_TRAINING_XP, 0)
        prefs.edit().putInt(KEY_TRAINING_XP, (current + value).coerceAtMost(999_999)).apply()
    }

    private fun updateTrainingStreak(today: String): Int {
        val previousDate = prefs.getString(KEY_TRAINING_LAST_DATE, null)
        val current = prefs.getInt(KEY_TRAINING_STREAK, 0)
        val next =
            when {
                previousDate == today -> current.coerceAtLeast(1)
                previousDate == dayKey(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)) -> current + 1
                else -> 1
            }
        val best = max(prefs.getInt(KEY_BEST_TRAINING_STREAK, 0), next)
        prefs.edit()
            .putString(KEY_TRAINING_LAST_DATE, today)
            .putInt(KEY_TRAINING_STREAK, next)
            .putInt(KEY_BEST_TRAINING_STREAK, best)
            .apply()
        return next
    }

    private fun ensureDailyTaskDate(today: String = todayKey()) {
        if (prefs.getString(KEY_DAILY_TASK_DATE, null) == today) {
            return
        }
        prefs.edit()
            .putString(KEY_DAILY_TASK_DATE, today)
            .putBoolean(KEY_DAILY_TASK_TRAINED, false)
            .putBoolean(KEY_DAILY_TASK_TARGET_DONE, false)
            .putBoolean(KEY_DAILY_TASK_SHARED, false)
            .apply()
    }

    private fun saveLocalSessionSummary(report: TrainingReport) {
        val summaries = loadLocalSessionSummaries().toMutableList()
        summaries.add(
            LocalSessionSummary(
                dateKey = dayKey(report.endedAtEpochMs),
                endedAtMs = report.endedAtEpochMs,
                durationSeconds = report.durationSeconds,
                hits = report.totalHits,
                playMode = selectedPlayMode.name,
            ),
        )
        val array = JSONArray()
        summaries.sortedByDescending { it.endedAtMs }.take(60).forEach { item ->
            array.put(
                JSONObject()
                    .put("date", item.dateKey)
                    .put("endedAt", item.endedAtMs)
                    .put("duration", item.durationSeconds)
                    .put("hits", item.hits)
                    .put("playMode", item.playMode),
            )
        }
        prefs.edit().putString(KEY_LOCAL_TRAINING_SESSIONS, array.toString()).apply()
    }

    private fun loadLocalSessionSummaries(): List<LocalSessionSummary> {
        val raw = prefs.getString(KEY_LOCAL_TRAINING_SESSIONS, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        LocalSessionSummary(
                            dateKey = item.optString("date"),
                            endedAtMs = item.optLong("endedAt"),
                            durationSeconds = item.optInt("duration"),
                            hits = item.optInt("hits"),
                            playMode = item.optString("playMode"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun sevenDayTrendText(): String {
        val sessions = loadLocalSessionSummaries().sortedBy { it.endedAtMs }.takeLast(12)
        if (sessions.size < 2) {
            return localText(
                "7天趋势：等待更多数据",
                "7-day trend: collecting data",
                "Tendance 7 j : collecte de données",
                "แนวโน้ม 7 วัน: กำลังเก็บข้อมูล",
            )
        }
        val splitIndex = (sessions.size / 2).coerceAtLeast(1)
        val early = sessions.take(splitIndex)
        val recent = sessions.drop(splitIndex).ifEmpty { sessions.takeLast(1) }
        val earlyAverage = early.map { it.hits }.average()
        val recentAverage = recent.map { it.hits }.average()
        val diff = recentAverage - earlyAverage
        return when {
            diff >= 2.0 ->
                localText(
                    "7天趋势：提升 +${String.format(Locale.US, "%.1f", diff)}",
                    "7-day trend: +${String.format(Locale.US, "%.1f", diff)}",
                    "Tendance 7 j : +${String.format(Locale.US, "%.1f", diff)}",
                    "แนวโน้ม 7 วัน: +${String.format(Locale.US, "%.1f", diff)}",
                )

            diff <= -2.0 ->
                localText(
                    "7天趋势：回落 ${String.format(Locale.US, "%.1f", diff)}",
                    "7-day trend: ${String.format(Locale.US, "%.1f", diff)}",
                    "Tendance 7 j : ${String.format(Locale.US, "%.1f", diff)}",
                    "แนวโน้ม 7 วัน: ${String.format(Locale.US, "%.1f", diff)}",
                )

            else ->
                localText("7天趋势：稳定", "7-day trend: stable", "Tendance 7 j : stable", "แนวโน้ม 7 วัน: คงที่")
        }
    }

    private fun todayKey(): String = dayKey(System.currentTimeMillis())

    private fun dayKey(epochMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(epochMs))

    private fun localText(
        chinese: String,
        english: String,
        french: String,
        thai: String,
    ): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> chinese
            AppLanguage.English -> english
            AppLanguage.French -> french
            AppLanguage.Thai -> thai
        }

    private fun buildProfileSummaryText(): String {
        val profile = cloudProfile
        if (profile == null) {
            return localText(
                "等待你的拳击档案",
                "Waiting for your fighter profile",
                "En attente de votre profil boxe",
                "กำลังรอโปรไฟล์นักชกของคุณ",
            )
        }
        return profile.nickname
    }

    private fun headerSubtitleText(): String = ""

    private fun buildProfileMetaSummary(): String {
        val profile = cloudProfile ?: return ""
        val parts =
            mutableListOf(
                "${localUserLabel()}: ${profile.serialMasked}",
                "${tr("profile_language")}: ${languageDisplayName(AppLanguage.fromStorage(profile.languageCode))}",
            )
        val countryCode = normalizedCountryCode(profile.countryCode)
        if (countryCode != null) {
            parts += "${tr("profile_country")}: $countryCode"
        }
        return parts.joinToString(" | ")
    }

    private fun buildProfileStatsOverview(): String {
        val stats =
            cloudStatistics
                ?: return localText(
                    "完成首次训练并同步云端后，这里会生成你的训练资产与成长统计。",
                    "Finish and sync your first session to build your training stats and profile assets.",
                    "Terminez et synchronisez votre première séance pour créer vos statistiques.",
                    "ฝึกและซิงก์ครั้งแรกเพื่อสร้างสถิติและโปรไฟล์การเติบโต",
                )
        return buildString {
            append("${tr("total_sessions")}: ${stats.totalSessions}")
            append(" | ")
            append("${localText("总回合", "Rounds", "Rounds", "รอบ")}: ${stats.totalRounds}")
            append('\n')
            append("${tr("total_hits")}: ${stats.totalHits}")
            append(" | ")
            append("${localText("最佳单回合", "Best round", "Meilleur round", "รอบดีที่สุด")}: ${stats.bestRoundHits}")
            append('\n')
            append("${localText("锻炼时间", "Duration", "Durée", "เวลา")}: ${formatTrainingDuration(stats.totalTrainingSeconds)}")
            append(" | ")
            append("${localText("最大力度", "Peak force", "Force max", "แรงสูงสุด")}: ${forceDisplay(stats.bestPeakForceN)}")
            append('\n')
            append("${localText("平均力度", "Avg force", "Force moy.", "แรงเฉลี่ย")}: ${forceDisplay(stats.bestAvgForceN)}")
            append(" | ")
            append("${localText("平均单回合", "Avg round", "Round moy.", "เฉลี่ยต่อรอบ")}: ${String.format(Locale.US, "%.1f", stats.averageRoundHits)}")
            append('\n')
            append("${tr("calories_burned")}: ${formatCalories(stats.totalCaloriesBurned)}")
            append(" | ")
            append("${localText("单回合均卡", "Avg kcal/round", "Kcal/round moy.", "แคลอรี/รอบ")}: ${formatCalories(stats.averageRoundCaloriesBurned)}")
            append('\n')
            append("${tr("fat_burned")}: ${formatFatGrams(stats.totalFatBurnedGrams)}")
            append(" | ")
            append(activeDaysLabel())
            append(": ${stats.activeDays}")
        }
    }

    private fun buildProfileTierSummary(): String {
        val tier = cloudTier ?: return tierLabelForLevel(cloudProfile?.currentTier ?: 1)
        val tierName = tierLabelForKey(tier.key)
        return if (tier.nextHits != null && tier.nextKey != null) {
            "$tierName  Lv.${tier.level}  |  ${nextTierLabel()}: ${tierLabelForKey(tier.nextKey)} (${tier.bestHits}/${tier.nextHits})"
        } else {
            "$tierName  Lv.${tier.level}  |  ${championLabel()}"
        }
    }

    private fun buildRecentBadgeSummary(): String {
        val unlocked = cloudAchievements.filter { it.unlocked }.sortedByDescending { it.unlockedAt.orEmpty() }.take(3)
        if (unlocked.isEmpty()) {
            return localText(
                "最近徽章：继续训练以解锁首枚徽章",
                "Recent badges: keep training to unlock your first badge",
                "Badges récents : continuez pour débloquer le premier",
                "เหรียญล่าสุด: ฝึกต่อเพื่อปลดล็อกเหรียญแรก",
            )
        }
        val names = unlocked.joinToString(" | ") { achievementDisplayName(it.key) }
        return localText(
            "最近徽章：$names",
            "Recent badges: $names",
            "Badges récents : $names",
            "เหรียญล่าสุด: $names",
        )
    }

    private fun refreshProfileAvatar() {
        val profile = cloudProfile
        bindAvatarPresentation(
            container = profileAvatarShell,
            imageView = profileAvatarImageView,
            fallbackView = profileAvatarFallbackView,
            seedText = profile?.nickname,
            colorHex = profile?.avatarColor ?: "#CC4400",
            imageUri = currentAvatarImageUri(),
        )
        profileHeroBadgeView.text = tierLabelForLevel(profile?.currentTier ?: cloudTier?.level ?: 1)
        profileHeroBadgeView.background = roundedBackground("#DFFFF7", "#BFEFE5", 999)
        profileHeroBadgeView.setTextColor(Color.parseColor("#096D65"))
    }

    private fun currentAvatarImageUri(): Uri? =
        prefs.getString(KEY_PROFILE_AVATAR_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                try {
                    Uri.parse(it)
                } catch (_: Throwable) {
                    null
                }
            }

    private fun storeAvatarImageUri(uri: Uri?) {
        prefs.edit().putString(KEY_PROFILE_AVATAR_URI, uri?.toString()).apply()
    }

    private fun bindAvatarPresentation(
        container: FrameLayout,
        imageView: ImageView,
        fallbackView: TextView,
        seedText: String?,
        colorHex: String,
        imageUri: Uri?,
    ) {
        container.background = avatarBackground(sanitizeAvatarColor(colorHex))
        fallbackView.text = avatarInitial(seedText)
        if (imageUri != null && loadAvatarImage(imageView, imageUri)) {
            imageView.visibility = View.VISIBLE
            fallbackView.visibility = View.GONE
        } else {
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            fallbackView.visibility = View.VISIBLE
        }
    }

    private fun loadAvatarImage(
        imageView: ImageView,
        uri: Uri,
    ): Boolean =
        try {
            imageView.setImageURI(null)
            imageView.setImageURI(uri)
            imageView.drawable != null
        } catch (_: Throwable) {
            false
        }

    private fun avatarInitial(seedText: String?): String {
        val normalized = seedText?.trim().orEmpty().ifBlank { "R" }
        return normalized.first().uppercaseChar().toString()
    }

    private fun renderAchievements() {
        achievementsGridContainer.removeAllViews()
        val items = cloudAchievements.sortedBy { it.sortOrder }
        if (items.isEmpty()) {
            achievementsSummaryView.text = achievementsSubtitleText(0, 0)
            achievementsGridContainer.addView(
                emptyStateCard(
                    badge = localText("徽章", "HONOR", "HONNEUR", "เกียรติยศ"),
                    title = localText("荣誉馆等待点亮", "Your honor hall is waiting", "Votre galerie d'honneur vous attend", "หอเกียรติยศกำลังรอคุณ"),
                    message =
                        localText(
                            "完成训练并同步云端后，这里会展示你的段位与徽章成长。",
                            "Finish and sync a session to light up your tier and badge collection here.",
                            "Terminez et synchronisez une séance pour afficher vos rangs et badges.",
                            "ฝึกและซิงก์หนึ่งครั้งเพื่อแสดงระดับและเหรียญของคุณ",
                        ),
                    accentColor = "#FFD060",
                ),
            )
            shareAchievementsButton.alpha = 0.72f
            shareAchievementsButton.isEnabled = false
            renderHomeGoalAchievementCard()
            return
        }
        shareAchievementsButton.alpha = 1.0f
        shareAchievementsButton.isEnabled = true
        val unlockedCount = items.count { it.unlocked }
        achievementsSummaryView.text = achievementsSubtitleText(unlockedCount, items.size)
        cloudTier?.let { tier ->
            achievementsGridContainer.addView(achievementTierHeroCardPremium(tier, unlockedCount, items.size))
            achievementsGridContainer.addView(spacer(dp(14)))
        }

        val recentUnlocked = items.filter { it.unlocked }.sortedByDescending { it.unlockedAt.orEmpty() }.take(3)
        if (recentUnlocked.isNotEmpty()) {
            achievementsGridContainer.addView(sectionLabel(achievementsRecentUnlockedTitle()))
            achievementsGridContainer.addView(spacer(dp(8)))
            val recentRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                }
            recentUnlocked.forEachIndexed { index, item ->
                recentRow.addView(
                    badgeText(
                        text = achievementDisplayName(item.key),
                        textColor = "#096D65",
                        fillColor = "#DFFFF7",
                    ).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                if (index > 0) {
                                    leftMargin = dp(8)
                                }
                            }
                    },
                )
            }
            achievementsGridContainer.addView(recentRow)
            achievementsGridContainer.addView(spacer(dp(14)))
        }

        val itemMap = items.associateBy { it.key }
        achievementGroupSpecs().forEachIndexed { groupIndex, group ->
            val groupItems = group.second.mapNotNull(itemMap::get)
            val unlockedInGroup = groupItems.count { it.unlocked }
            val groupCard = detailCard(fillColor = "#FFFFFF", strokeColor = "#CDEFE8", cornerDp = 22)
            val groupHeader =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            val headerTitle =
                sectionLabel(group.first).apply {
                    setPadding(0, 0, 0, 0)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1.0f,
                        )
                }
            val headerBadge =
                badgeText(
                    text = "$unlockedInGroup/${groupItems.size}",
                    textColor = "#096D65",
                    fillColor = "#DFFFF7",
                ).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
            groupHeader.addView(headerTitle)
            groupHeader.addView(headerBadge)
            groupCard.addView(groupHeader)
            groupCard.addView(spacer(dp(10)))
            groupItems.chunked(2).forEachIndexed { rowIndex, rowItems ->
                val row =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.START
                    }
                    rowItems.forEachIndexed { index, item ->
                        row.addView(
                            achievementBadgeCardPremium(item).apply {
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1.0f,
                                ).apply {
                                    if (index > 0) {
                                        leftMargin = dp(10)
                                    }
                                }
                        },
                    )
                }
                repeat(2 - rowItems.size) {
                    row.addView(horizontalSpace(0).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f) })
                }
                groupCard.addView(row)
                if (rowIndex < (groupItems.size - 1) / 2) {
                    groupCard.addView(spacer(dp(10)))
                }
            }
            achievementsGridContainer.addView(groupCard)
            if (groupIndex < achievementGroupSpecs().lastIndex) {
                achievementsGridContainer.addView(spacer(dp(14)))
            }
        }
        renderHomeGoalAchievementCard()
    }

    private fun achievementGroupSpecs(): List<Pair<String, List<String>>> =
        listOf(
            achievementGroupTitle("duration") to listOf("duration_5m", "duration_15m", "duration_30m", "duration_60m"),
            achievementGroupTitle("total_hits") to listOf("hits_100", "hits_500", "hits_1000", "hits_5000"),
            achievementGroupTitle("peak_force") to listOf("peak_force_50", "peak_force_100", "peak_force_150", "peak_force_200"),
            achievementGroupTitle("avg_force") to listOf("avg_force_30", "avg_force_60", "avg_force_90", "avg_force_120"),
            achievementGroupTitle("calories") to listOf("calories_30", "calories_100", "calories_300", "calories_600"),
            achievementGroupTitle("fat") to listOf("fat_5", "fat_15", "fat_40", "fat_80"),
        )

    private fun achievementGroupTitle(key: String): String =
        when (selectedLanguage) {
            AppLanguage.Chinese ->
                when (key) {
                    "duration" -> "锻炼时间"
                    "total_hits" -> "累计击打"
                    "peak_force" -> "最大拳击力度"
                    "avg_force" -> "平均拳击力度"
                    "calories" -> "卡路里消耗"
                    else -> "等效燃脂量"
                }
            else ->
                when (key) {
                    "duration" -> "Training Duration"
                    "total_hits" -> "Total Hits"
                    "peak_force" -> "Peak Force"
                    "avg_force" -> "Average Force"
                    "calories" -> "Calories Burned"
                    else -> "Equivalent Fat Burn"
                }
        }

    private fun achievementsRecentUnlockedTitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "最近解锁"
            AppLanguage.French -> "Déblocages récents"
            AppLanguage.Thai -> "ปลดล็อกล่าสุด"
            else -> "Recently unlocked"
        }

    private fun achievementTierHeroCard(
        tier: CloudTierProgress,
        unlockedCount: Int,
        totalCount: Int,
    ): LinearLayout =
        detailCard(fillColor = "#EFFFFA", strokeColor = "#BFEFE5", cornerDp = 22).apply {
            addView(
                badgeText(
                    text = localText("当前段位", "Current Tier", "Rang actuel", "ระดับปัจจุบัน"),
                    textColor = "#096D65",
                    fillColor = "#DFFFF7",
                ),
            )
            addView(
                titleText(tierLabelForKey(tier.key), 22f).apply {
                    setPadding(0, dp(12), 0, 0)
                    setTextColor(Color.parseColor("#17343B"))
                },
            )
            addView(
                bodyText(achievementsSubtitleText(unlockedCount, totalCount)).apply {
                    setTextColor(Color.parseColor("#557A7D"))
                    setPadding(0, dp(6), 0, 0)
                },
            )
            addView(
                bodyText(tierHeroProgressText(tier)).apply {
                    setTextColor(Color.parseColor("#0CA99A"))
                    setPadding(0, dp(10), 0, 0)
                },
            )
        }

    private fun tierHeroProgressText(tier: CloudTierProgress): String {
        val bestRoundHits = max(cloudStatistics?.bestRoundHits ?: 0, tier.bestHits)
        return if (tier.nextHits != null && tier.nextKey != null) {
            val remaining = (tier.nextHits - bestRoundHits).coerceAtLeast(0)
            localText(
                "最佳单回合：$bestRoundHits | 距离 ${tierLabelForKey(tier.nextKey)} 还差 $remaining 击",
                "Best round: $bestRoundHits | $remaining hits to ${tierLabelForKey(tier.nextKey)}",
                "Meilleur round : $bestRoundHits | Encore $remaining coups pour ${tierLabelForKey(tier.nextKey)}",
                "รอบดีที่สุด: $bestRoundHits | อีก $remaining ครั้งจะถึง ${tierLabelForKey(tier.nextKey)}",
            )
        } else {
            localText(
                "最佳单回合：$bestRoundHits | 已达到最高段位",
                "Best round: $bestRoundHits | Top tier reached",
                "Meilleur round : $bestRoundHits | Rang maximum atteint",
                "รอบดีที่สุด: $bestRoundHits | ถึงระดับสูงสุดแล้ว",
            )
        }
    }

    private fun renderHistoryCards() {
        val items = cloudHistory.take(6)
        historyView.visibility = View.GONE
        if (items.isEmpty()) {
            historyListRecycler.visibility = View.GONE
            historyEmptyView.visibility = View.VISIBLE
            historyItemAdapter.submitList(emptyList())
            return
        }
        historyEmptyView.visibility = View.GONE
        historyListRecycler.visibility = View.VISIBLE
        historyItemAdapter.submitList(items)
    }

    private fun renderLeaderboard() {
        leaderboardPodiumContainer.removeAllViews()
        leaderboardMeView.text = ""

        val result = leaderboardResult
        if (result == null || !result.success || result.top.isEmpty()) {
            leaderboardView.visibility = View.VISIBLE
            leaderboardView.text = ""
            leaderboardPodiumContainer.visibility = View.GONE
            leaderboardListRecycler.visibility = View.GONE
            leaderboardRowAdapter.submitList(emptyList())
            leaderboardMeCard.visibility = View.VISIBLE
            leaderboardMeCard.background = roundedBackground("#EFFFFA", "#BFEFE5", 22)
            shareLeaderboardButton.alpha = 0.72f
            shareLeaderboardButton.isEnabled = false
            leaderboardMeTitleView.setTextColor(Color.parseColor("#096D65"))
            leaderboardMeTitleView.text =
                localText("榜单竞技", "Leaderboard Arena", "Arène du classement", "สนามจัดอันดับ")
            leaderboardMeView.text = ""
            leaderboardMeView.gravity = Gravity.START
            leaderboardMeCard.removeAllViews()
            val emptyHeader =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            leaderboardMeTitleView.layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f,
                )
            detachFromParent(leaderboardMeTitleView)
            detachFromParent(shareLeaderboardButton)
            emptyHeader.addView(leaderboardMeTitleView)
            emptyHeader.addView(shareLeaderboardButton)
            leaderboardMeCard.addView(emptyHeader)
            leaderboardMeCard.addView(
                emptyStateCard(
                    badge = localText("竞技", "RANK", "RANG", "อันดับ"),
                    title =
                        localText(
                            "等待第一份排名",
                            "Waiting for your first ranking",
                            "En attente de votre premier classement",
                            "รออันดับแรกของคุณ",
                        ),
                    message = tr("leaderboard_empty"),
                    accentColor = "#FF9A30",
                ),
            )
            return
        }

        leaderboardView.visibility = View.GONE
        shareLeaderboardButton.alpha = 1.0f
        shareLeaderboardButton.isEnabled = true
        val boardAccent = leaderboardAccentColor(leaderboardBoard)
        val topThree = result.top.take(3)
        leaderboardPodiumContainer.visibility = if (topThree.isNotEmpty()) View.VISIBLE else View.GONE
        buildPodiumEntries(topThree).forEachIndexed { index, entry ->
            leaderboardPodiumContainer.addView(
                podiumCardPremium(
                    entry = entry,
                    accentColor = podiumAccentForRank(entry.rank),
                    elevated = entry.rank == 1,
                    leftMargin = if (index == 0) 0 else dp(10),
                ),
            )
        }

        val others = result.top.drop(3)
        leaderboardListRecycler.visibility = if (others.isNotEmpty()) View.VISIBLE else View.GONE
        leaderboardRowAdapter.submitList(others)

        leaderboardMeCard.visibility = View.VISIBLE
        leaderboardMeCard.background = roundedBackground(leaderboardAccentFill(leaderboardBoard), boardAccent, 22)
        leaderboardMeTitleView.setTextColor(Color.parseColor(boardAccent))
        leaderboardMeTitleView.text = "${tr("leaderboard_me").uppercase(localeForLanguage())} | ${leaderboardBoardLabel(leaderboardBoard)}"
        leaderboardMeView.text =
            result.me?.let { entry ->
                buildString {
                    append(rankLabel(entry.rank))
                    append(" | ")
                    append(entry.nickname)
                    append(" | ")
                    append(tierLabelForKey(entry.tierKey))
                    append('\n')
                    append(leaderboardPrimaryValueText(entry))
                    val countryCode = normalizedCountryCode(entry.countryCode)
                    if (countryCode != null) {
                        append('\n')
                        append("${tr("profile_country")}: $countryCode")
                    }
                }
            } ?: tr("leaderboard_no_rank")
        leaderboardMeView.gravity = Gravity.START
        leaderboardMeCard.removeAllViews()
        val meHeader =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        leaderboardMeTitleView.layoutParams =
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f,
            )
        detachFromParent(leaderboardMeTitleView)
        detachFromParent(shareLeaderboardButton)
        meHeader.addView(leaderboardMeTitleView)
        meHeader.addView(shareLeaderboardButton)
        leaderboardMeCard.addView(meHeader)
        leaderboardMeCard.addView(leaderboardMeView)
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun buildProfileMetaText(): String {
        val profile = cloudProfile ?: return ""
        return buildString {
            append(localUserLabel())
            append(": ")
            append(profile.serialMasked)
            append("   |   ")
            append(tr("profile_language"))
            append(": ")
            append(languageDisplayName(AppLanguage.fromStorage(profile.languageCode)))
            val countryCode = normalizedCountryCode(profile.countryCode)
            if (countryCode != null) {
                append('\n')
                append(tr("profile_country"))
                append(": ")
                append(countryCode)
            }
        }
    }

    private fun normalizedCountryCode(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return if (normalized.equals("null", ignoreCase = true)) null else normalized
    }

    private fun buildProfileStatsText(): String {
        val stats = cloudStatistics ?: return tr("cloud_sync_idle")
        return buildString {
            append(tr("total_sessions"))
            append(": ")
            append(stats.totalSessions)
            append("   |   ")
            append(tr("total_hits"))
            append(": ")
            append(stats.totalHits)
            append('\n')
            append(localText("锻炼时间", "Duration", "Durée", "เวลา"))
            append(": ")
            append(formatTrainingDuration(stats.totalTrainingSeconds))
            append("   |   ")
            append(localText("最大力度", "Peak force", "Force max", "แรงสูงสุด"))
            append(": ")
            append(forceDisplay(stats.bestPeakForceN))
            append('\n')
            append(localText("平均力度", "Avg force", "Force moy.", "แรงเฉลี่ย"))
            append(": ")
            append(forceDisplay(stats.bestAvgForceN))
            append('\n')
            append(tr("calories_burned"))
            append(": ")
            append(formatCalories(stats.totalCaloriesBurned))
            append("   |   ")
            append(tr("fat_burned"))
            append(": ")
            append(formatFatGrams(stats.totalFatBurnedGrams))
        }
    }

    private fun buildHistoryText(): String {
        if (cloudHistory.isEmpty()) {
            return tr("no_history")
        }
        return cloudHistory.take(6).joinToString("\n\n") { item ->
            buildString {
                append(displayModeLabel(secondsToMode(item.modeSeconds)))
                append("  •  ")
                append(item.totalHits)
                append(" ")
                append(tr("hits"))
                append('\n')
                append(String.format(Locale.US, "%.2f %s", item.averageFrequency, tr("hits_per_second")))
                append("  •  ")
                append(tr("best_burst"))
                append(": ")
                append(item.bestBurstCount)
                append("  •  ")
                append(formatCalories(item.caloriesBurned))
                append("  •  ")
                append(formatFatGrams(item.fatBurnedGrams))
                append("  •  ")
                append(forceDisplay(item.peakForceN))
                append('\n')
                append(formatHistoryTime(item.endedAt))
            }
        }
    }

    private fun buildLeaderboardText(): String {
        val result = leaderboardResult
        if (result == null || !result.success || result.top.isEmpty()) {
            return tr("leaderboard_empty")
        }
        val lines =
            result.top.joinToString("\n") { entry ->
                "${rankLabel(entry.rank)} ${entry.nickname}\n${leaderboardPrimaryValueText(entry)}\n${entry.serialMasked}"
            }
        val meLine =
            result.me?.let { entry ->
                "\n\n${tr("leaderboard_me")}: ${rankLabel(entry.rank)}  ${entry.nickname}  |  ${leaderboardPrimaryValueText(entry)}"
            } ?: "\n\n${tr("leaderboard_no_rank")}"
        return lines + meLine
    }

    private fun leaderboardBoardLabel(board: LeaderboardBoard): String =
        when (selectedLanguage) {
            AppLanguage.Chinese ->
                when (board) {
                    LeaderboardBoard.TrainingDuration -> "锻炼时间"
                    LeaderboardBoard.TotalHits -> "累计榜"
                    LeaderboardBoard.PeakForce -> "最大力度"
                    LeaderboardBoard.AvgForce -> "平均力度"
                    LeaderboardBoard.Calories -> "卡路里"
                    LeaderboardBoard.FatBurned -> "等效燃脂榜"
                }
            else ->
                when (board) {
                    LeaderboardBoard.TrainingDuration -> "Duration"
                    LeaderboardBoard.TotalHits -> "Total Hits"
                    LeaderboardBoard.PeakForce -> "Peak Force"
                    LeaderboardBoard.AvgForce -> "Avg Force"
                    LeaderboardBoard.Calories -> "Calories"
                    LeaderboardBoard.FatBurned -> "Equivalent Fat"
                }
        }

    private fun leaderboardBoardSubtitle(board: LeaderboardBoard): String =
        when (selectedLanguage) {
            AppLanguage.Chinese ->
                when (board) {
                    LeaderboardBoard.TrainingDuration -> "按累计锻炼时间排名"
                    LeaderboardBoard.TotalHits -> "按累计击打总数排名"
                    LeaderboardBoard.PeakForce -> "按历史最大拳击力度排名"
                    LeaderboardBoard.AvgForce -> "按单次训练最佳平均力度排名"
                    LeaderboardBoard.Calories -> "按累计卡路里消耗排名"
                    LeaderboardBoard.FatBurned -> "按累计等效燃脂量排名"
                }
            else ->
                when (board) {
                    LeaderboardBoard.TrainingDuration -> "Ranked by total training time"
                    LeaderboardBoard.TotalHits -> "Ranked by lifetime hit count"
                    LeaderboardBoard.PeakForce -> "Ranked by peak punch force"
                    LeaderboardBoard.AvgForce -> "Ranked by best average force"
                    LeaderboardBoard.Calories -> "Ranked by total calories burned"
                    LeaderboardBoard.FatBurned -> "Ranked by total equivalent fat burn"
                }
        }

    private fun leaderboardBoardFromKey(key: String?): LeaderboardBoard =
        when (key) {
            LeaderboardBoard.TotalHits.apiKey -> LeaderboardBoard.TotalHits
            LeaderboardBoard.PeakForce.apiKey -> LeaderboardBoard.PeakForce
            LeaderboardBoard.AvgForce.apiKey -> LeaderboardBoard.AvgForce
            LeaderboardBoard.Calories.apiKey -> LeaderboardBoard.Calories
            LeaderboardBoard.FatBurned.apiKey -> LeaderboardBoard.FatBurned
            else -> LeaderboardBoard.TrainingDuration
        }

    private fun leaderboardPrimaryValueText(entry: CloudLeaderboardEntry): String =
        when (leaderboardBoard) {
            LeaderboardBoard.TrainingDuration -> formatTrainingDuration(entry.scoreValue.roundToInt())
            LeaderboardBoard.TotalHits ->
                localText(
                    "累计 ${entry.bestHits} 次",
                    "${entry.bestHits} total hits",
                    "${entry.bestHits} coups cumulés",
                    "รวม ${entry.bestHits} หมัด",
                )
            LeaderboardBoard.PeakForce -> "${entry.scoreValue.roundToInt()} N"
            LeaderboardBoard.AvgForce -> "${entry.scoreValue.roundToInt()} N"
            LeaderboardBoard.Calories -> formatCalories(entry.scoreValue)
            LeaderboardBoard.FatBurned -> formatFatGrams(entry.scoreValue)
        }

    private fun leaderboardSecondaryValueText(entry: CloudLeaderboardEntry): String =
        when (leaderboardBoard) {
            LeaderboardBoard.TrainingDuration, LeaderboardBoard.Calories, LeaderboardBoard.FatBurned ->
                localText(
                    "累计 ${entry.totalHits} 拳",
                    "${entry.totalHits} total hits",
                    "${entry.totalHits} coups cumulés",
                    "รวม ${entry.totalHits} หมัด",
                )
            LeaderboardBoard.TotalHits ->
                localText(
                    "最佳爆发 ${entry.bestBurstCount}",
                    "Best burst ${entry.bestBurstCount}",
                    "Meilleure rafale ${entry.bestBurstCount}",
                    "เบิร์สต์สูงสุด ${entry.bestBurstCount}",
                )
            LeaderboardBoard.PeakForce, LeaderboardBoard.AvgForce ->
                localText(
                    "最高频率 ${String.format(Locale.US, "%.2f", entry.averageFrequency)} 次/秒",
                    "Best pace ${String.format(Locale.US, "%.2f", entry.averageFrequency)} hits/s",
                    "Meilleur rythme ${String.format(Locale.US, "%.2f", entry.averageFrequency)} coups/s",
                    "จังหวะสูงสุด ${String.format(Locale.US, "%.2f", entry.averageFrequency)} หมัด/วิ",
                )
        }

    private fun formatHistoryTime(value: String?): String {
        if (value.isNullOrBlank()) {
            return tr("activation_just_now")
        }
        val parsed = parseCloudDate(value)
        if (parsed == null) {
            return value.replace('T', ' ').replace("Z", "").replace(".000", "")
        }
        val pattern =
            when (selectedLanguage) {
                AppLanguage.Chinese -> "MM-dd HH:mm"
                AppLanguage.English -> "MMM dd, HH:mm"
                AppLanguage.French -> "dd MMM HH:mm"
                AppLanguage.Thai -> "dd/MM HH:mm"
            }
        return SimpleDateFormat(pattern, localeForLanguage()).apply {
            timeZone = TimeZone.getDefault()
        }.format(parsed)
    }

    private fun reportCardText(report: TrainingReport): String {
        val frequency = String.format(Locale.US, "%.2f", report.averageFrequency)
        return buildString {
            append("${tr("mode")}: ${displayModeLabel(report.mode)}")
            append('\n')
            append("${tr("total_hits")}: ${report.totalHits}")
            append('\n')
            append("${localText("锻炼时间", "Duration", "Durée", "เวลา")}: ${formatTrainingDuration(report.durationSeconds)}")
            append('\n')
            append("${tr("average_frequency")}: $frequency ${tr("hits_per_second")}")
            append('\n')
            append("${localText("最大力度", "Peak force", "Force max", "แรงสูงสุด")}: ${forceDisplay(report.peakForceN)}")
            append('\n')
            append("${localText("平均力度", "Avg force", "Force moy.", "แรงเฉลี่ย")}: ${forceDisplay(report.avgForceN)}")
            append('\n')
            append("${tr("calories_burned")}: ${formatCalories(report.caloriesBurned)}")
            append('\n')
            append("${tr("fat_burned")}: ${formatFatGrams(report.fatBurnedGrams)}")
        }
    }

    private fun emptyStateCard(
        badge: String,
        title: String,
        message: String,
        accentColor: String = "#FF9A30",
    ): LinearLayout =
        detailCard(fillColor = "#FFFFFF", strokeColor = "#CDEFE8", cornerDp = 22).apply {
            background = roundedBackground("#FFFFFF", "#CDEFE8", 22)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                TextView(this@MainActivity).apply {
                    text = badge
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    background = roundedBackground(accentColor, accentColor, 999)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                },
            )
            addView(
                titleText(title, 20f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(14), 0, 0)
                },
            )
            addView(
                bodyText(message).apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#557A7D"))
                    setPadding(dp(4), dp(8), dp(4), dp(4))
                },
            )
        }

    private fun renderEmptyReport() {
        reportView.removeAllViews()
        reportView.addView(
            emptyStateCard(
                badge = localText("战报", "REPORT", "RAPPORT", "รายงาน"),
                title =
                    localText(
                        "等待首份训练战报",
                        "Waiting for your first report",
                        "En attente de votre premier rapport",
                        "รอรายงานการฝึกครั้งแรก",
                    ),
                message = tr("no_report"),
                accentColor = "#FF9A30",
            ),
        )
        renderHomeConnectionReportCard()
        renderHomeGoalAchievementCard()
    }

    private fun reportMetricCard(
        label: String,
        value: String,
        accentColor: String,
    ): LinearLayout =
        detailCard(fillColor = "#FFFFFF", strokeColor = accentColor, cornerDp = 18).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(
                bodyText(label).apply {
                    setTextColor(Color.parseColor("#557A7D"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                },
            )
            addView(
                titleText(value, 18f).apply {
                    setTextColor(Color.parseColor("#17343B"))
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }

    private fun vividAssetColorFilter(
        saturation: Float = 1.12f,
        contrast: Float = 1.04f,
        brightness: Float = -2f,
    ): ColorMatrixColorFilter {
        val saturationMatrix = ColorMatrix().apply {
            setSaturation(saturation)
        }
        val contrastMatrix =
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        saturationMatrix.postConcat(contrastMatrix)
        return ColorMatrixColorFilter(saturationMatrix)
    }

    private fun buildHomeHeroCard(): FrameLayout =
        FrameLayout(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(300),
                ).apply {
                    bottomMargin = dp(10)
                }
            background = roundedBackground("#E9FFFA", "#C8F0E8", 24)
            clipToOutline = true
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                    }
                }
            elevation = dp(3).toFloat()
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.home_banner)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    alpha = 1f
                    val saturationMatrix = ColorMatrix().apply {
                        setSaturation(1.18f)
                    }
                    val contrastMatrix =
                        ColorMatrix(
                            floatArrayOf(
                                1.06f, 0f, 0f, 0f, -3f,
                                0f, 1.06f, 0f, 0f, -3f,
                                0f, 0f, 1.06f, 0f, -3f,
                                0f, 0f, 0f, 1f, 0f,
                            ),
                        )
                    saturationMatrix.postConcat(contrastMatrix)
                    colorFilter = ColorMatrixColorFilter(saturationMatrix)
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.START
                    setPadding(dp(24), dp(24), dp(18), dp(18))
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    addView(
                        TextView(this@MainActivity).apply {
                            text =
                                SpannableString("HitRise").apply {
                                    setSpan(
                                        ForegroundColorSpan(Color.parseColor("#07A998")),
                                        3,
                                        length,
                                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                    )
                                }
                            setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC))
                            setTextColor(Color.parseColor("#17343B"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25.5f)
                            includeFontPadding = false
                        },
                    )
                    addView(
                        bodyText(localText("家庭健身 · 燃脂拳击", "Home fitness · fat-burning boxing", "Fitness maison · boxe brûle-graisse", "ฟิตเนสที่บ้าน · มวยเผาผลาญ")).apply {
                            setTextColor(Color.parseColor("#4C7478"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                            setPadding(0, dp(6), 0, 0)
                        },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            val headline =
                                localText(
                                    "10分钟\n轻松暴汗！",
                                    "10 minutes\nSweat easy!",
                                    "10 minutes\nTranspirez vite !",
                                    "10 นาที\nเหงื่อออกง่าย!",
                                )
                            text =
                                SpannableString(headline).apply {
                                    val breakIndex = headline.indexOf('\n').takeIf { it >= 0 } ?: headline.length
                                    setSpan(
                                        ForegroundColorSpan(Color.parseColor("#07A998")),
                                        0,
                                        breakIndex,
                                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                    )
                                    if (breakIndex + 1 < headline.length) {
                                        setSpan(
                                            ForegroundColorSpan(Color.parseColor("#050A0B")),
                                            breakIndex + 1,
                                            headline.length,
                                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                        )
                                    }
                                }
                            gravity = Gravity.START
                            setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 31f)
                            setLineSpacing(dp(2).toFloat(), 1.0f)
                            includeFontPadding = false
                            setPadding(0, dp(37), 0, 0)
                        },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = localText("健身 | 减压 | 燃脂", "Fitness | Stress relief | Fat burn", "Fitness | Décompression | Brûle-graisse", "ฟิต | คลายเครียด | เผาผลาญ")
                            setTextColor(Color.parseColor("#17343B"))
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(dp(5), dp(62), 0, 0)
                        },
                    )
                },
            )
            addView(
                settingsButton.apply {
                    layoutParams =
                        FrameLayout.LayoutParams(dp(31), dp(31), Gravity.TOP or Gravity.END).apply {
                            topMargin = dp(24)
                            rightMargin = dp(12)
                        }
                },
            )
        }

    private fun buildHomeForceCard(): LinearLayout =
        detailCard(fillColor = "#FFFFFF", strokeColor = "#CDEFE8", cornerDp = 20).apply {
            background = roundedBackground("#FFFFFF", "#CDEFE8", 20)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                }
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        sectionLabel(localText("击打力度", "Punch force", "Force de frappe", "แรงหมัด")).apply {
                            setTextColor(Color.parseColor("#17343B"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                            layoutParams =
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        },
                    )
                    addView(dashboardPeakTagView)
                },
            )
            addView(
                waveformView.apply {
                    layoutParams =
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118)).apply {
                            topMargin = dp(6)
                        }
                    background = roundedBackground("#FFFFFF", "#FFFFFF", 12)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                    addView(homeForceLegendChip(localText("轻击", "Light", "Léger", "เบา"), "#45DCC8", marginRight = dp(8)))
                    addView(homeForceLegendChip(localText("中击", "Medium", "Moyen", "กลาง"), "#9BE5C4", marginRight = dp(8)))
                    addView(homeForceLegendChip(localText("重拳", "Heavy", "Fort", "หนัก"), "#FFD060", marginRight = dp(8)))
                    addView(homeForceLegendChip(localText("爆发", "Burst", "Explosion", "ระเบิด"), "#FF7A45"))
                },
            )
            addView(
                dashboardForceSummaryView.apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = dp(8)
                        }
                },
            )
        }

    private fun homeForceLegendChip(
        label: String,
        fillColor: String,
        marginRight: Int = 0,
    ): TextView =
        bodyText(label).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#17343B"))
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundedBackground(fillColor, fillColor, 999)
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = marginRight
                }
        }

    private fun buildHomeConnectionReportCard(): FrameLayout =
        FrameLayout(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(170),
                ).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                }
            background = roundedBackground("#F3FFFC", "#CDEFE8", 20)
            clipToOutline = true
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                    }
                }
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.home_report_bg)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    colorFilter = vividAssetColorFilter(saturation = 1.12f, contrast = 1.03f, brightness = -2f)
                    alpha = 0.96f
                    layoutParams =
                        FrameLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL)
                },
            )
            val content =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                }
            content.addView(
                sectionLabel(localText("连接状态 / 最新战报", "Connection / Latest report", "Connexion / Dernier rapport", "สถานะ / รายงานล่าสุด")).apply {
                    setTextColor(Color.parseColor("#17343B"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                },
            )
            homeConnectionStatusView =
                bodyText("").apply {
                    setTextColor(Color.parseColor("#557A7D"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    textScaleX = 0.94f
                    includeFontPadding = false
                    setSingleLine(true)
                    maxLines = 1
                    setPadding(0, dp(4), dp(44), dp(8))
                }
            content.addView(homeConnectionStatusView)
            homeReportHitsValueView = homeV3MetricValue("--")
            homeReportPeakValueView = homeV3MetricValue("--")
            homeReportAvgValueView = homeV3MetricValue("--")
            content.addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), dp(28), 0)
                    addView(homeV3MiniMetric(localText("本次拳数", "Hits", "Coups", "หมัด"), homeReportHitsValueView, dp(8)))
                    addView(homeV3MiniMetric(localText("最大力度", "Peak", "Max", "สูงสุด"), homeReportPeakValueView, dp(8)))
                    addView(homeV3MiniMetric(localText("平均力度", "Avg", "Moy.", "เฉลี่ย"), homeReportAvgValueView))
                },
            )
            addView(content)
            renderHomeConnectionReportCard()
        }

    private fun buildHomeGoalAchievementCard(): FrameLayout =
        FrameLayout(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(172),
                ).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                }
            background = roundedBackground("#F3FFFC", "#CDEFE8", 20)
            clipToOutline = true
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                    }
                }
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.home_achievement_bg)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    colorFilter = vividAssetColorFilter(saturation = 1.12f, contrast = 1.03f, brightness = -2f)
                    alpha = 0.98f
                    layoutParams =
                        FrameLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL)
                },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    addView(
                        sectionLabel(localText("目标与成就", "Goals & achievements", "Objectifs & succès", "เป้าหมายและเหรียญ")).apply {
                            setTextColor(Color.parseColor("#17343B"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        },
                    )
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(14), dp(10), dp(14), dp(10))
                            background = roundedBackground("#FFFFFF", "#BDEFE6", 16)
                            layoutParams =
                                LinearLayout.LayoutParams(dp(140), dp(86)).apply {
                                    topMargin = dp(12)
                                }
                            addView(
                                bodyText(localText("今日已完成", "Today done", "Aujourd'hui", "วันนี้")).apply {
                                    setTextColor(Color.parseColor("#7FA0A3"))
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                },
                            )
                            homeGoalPercentView =
                                titleText("0%", 30f).apply {
                                    gravity = Gravity.START
                                    setTextColor(Color.parseColor("#10BDAA"))
                                    includeFontPadding = false
                                    setPadding(0, dp(4), 0, 0)
                                }
                            addView(homeGoalPercentView)
                        },
                    )
                    homeGoalNextBadgeView =
                        bodyText("").apply {
                            setTextColor(Color.parseColor("#096D65"))
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            gravity = Gravity.CENTER
                            setPadding(dp(12), dp(7), dp(12), dp(7))
                            background = roundedBackground("#DFFFF7", "#DFFFF7", 999)
                            layoutParams =
                                LinearLayout.LayoutParams(dp(188), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                    topMargin = dp(12)
                                }
                        }
                    addView(homeGoalNextBadgeView)
                },
            )
            renderHomeGoalAchievementCard()
        }

    private fun homeV3MetricValue(initial: String): TextView =
        titleText(initial, 18f).apply {
            gravity = Gravity.START
            setTextColor(Color.parseColor("#17343B"))
            includeFontPadding = false
            setSingleLine(true)
            maxLines = 1
            textScaleX = 0.96f
            setPadding(0, dp(4), 0, 0)
        }

    private fun homeV3MiniMetric(
        label: String,
        valueView: TextView,
        marginRight: Int = 0,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(8), dp(9))
            background = roundedBackground("#FFFFFF", "#E0F3EF", 12)
            layoutParams =
                LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                    rightMargin = marginRight
                }
            addView(
                bodyText(label).apply {
                    setTextColor(Color.parseColor("#7FA0A3"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    includeFontPadding = false
                    setSingleLine(true)
                    maxLines = 1
                },
            )
            addView(valueView)
        }

    private fun renderHomeConnectionReportCard() {
        if (!::homeConnectionStatusView.isInitialized) {
            return
        }
        val connectedDevice = bluetoothConnectedDevice
        homeConnectionStatusView.text =
            connectedDevice?.let { device ->
                localText(
                    "${device.name}·电量 ${currentBluetoothBatteryText()}·已连接",
                    "${device.name}·Battery ${currentBluetoothBatteryText()}·Connected",
                    "${device.name}·Batterie ${currentBluetoothBatteryText()}·Connecté",
                    "${device.name}·แบต ${currentBluetoothBatteryText()}·เชื่อมต่อแล้ว",
                )
            } ?: localText(
                "没有连接蓝牙设备",
                "No Bluetooth device connected",
                "Aucun appareil Bluetooth connecté",
                "ยังไม่ได้เชื่อมต่อบลูทูธ",
            )
        val report = latestReport
        val showLive = trainingJob?.isActive == true || bluetoothTrainingCount > 0
        val hits = if (showLive) bluetoothTrainingCount else report?.totalHits ?: 0
        val peakForce = if (showLive || trainingPeakForceN > 0f) trainingPeakForceN else report?.peakForceN ?: 0f
        val avgForce = if (showLive || currentAverageTrainingForceN() > 0f) currentAverageTrainingForceN() else report?.avgForceN ?: 0f
        homeReportHitsValueView.text = hits.toString()
        homeReportPeakValueView.text = if (peakForce > 0f) forceDisplay(peakForce) else "--"
        homeReportAvgValueView.text = if (avgForce > 0f) forceDisplay(avgForce) else "--"
    }

    private fun renderHomeGoalAchievementCard() {
        if (!::homeGoalPercentView.isInitialized) {
            return
        }
        val target = trainingGoalPresentationFor(selectedPlayMode).targetHits ?: 500
        val completed =
            when {
                trainingJob?.isActive == true || bluetoothTrainingCount > 0 -> bluetoothTrainingCount
                latestReport != null -> latestReport!!.totalHits
                else -> 0
            }
        val percent = if (target > 0) ((completed.toFloat() / target.toFloat()) * 100f).roundToInt().coerceIn(0, 999) else 0
        homeGoalPercentView.text = "$percent%"
        val nextLocked = cloudAchievements.filterNot { it.unlocked }.sortedBy { it.sortOrder }.firstOrNull()
        homeGoalNextBadgeView.text =
            nextLocked?.let {
                localText(
                    "下一枚徽章：${achievementDisplayName(it.key)}",
                    "Next badge: ${achievementDisplayName(it.key)}",
                    "Prochain badge : ${achievementDisplayName(it.key)}",
                    "เหรียญถัดไป: ${achievementDisplayName(it.key)}",
                )
            } ?: localText(
                "全部徽章已解锁",
                "All badges unlocked",
                "Tous les badges sont débloqués",
                "ปลดล็อกครบแล้ว",
            )
    }

    private fun buildRealtimeDashboardCard(): LinearLayout {
        val cardFill = "#FFFFFF"
        val cardStroke = "#CDEFE8"
        val cardAlt = "#F7FFFD"
        val textPrimary = "#17343B"
        val textSecondary = "#557A7D"
        val textMuted = "#7FA0A3"
        val mint = "#16C8B5"
        val mintSoft = "#DFFFF7"
        val orange = "#FF8A32"
        val orangeSoft = "#FFE0BA"
        fun metricValueView(initial: String): TextView =
            titleText(initial, 23f).apply {
                setTextColor(Color.parseColor(textPrimary))
                gravity = Gravity.CENTER
                includeFontPadding = false
                setSingleLine(true)
                maxLines = 1
                setPadding(0, 0, 0, 0)
            }

        fun metricTile(
            iconRes: Int,
            label: String,
            valueView: TextView,
            unit: String = "",
        ): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBackground(cardAlt, "#DDF3EF", 14)
                elevation = dp(2).toFloat()
                setPadding(dp(8), dp(10), dp(5), dp(10))
                valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
                valueView.textScaleX = 1f
                val compactLabel = label.length >= 4
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            ImageView(this@MainActivity).apply {
                                setImageResource(iconRes)
                                adjustViewBounds = true
                                layoutParams =
                                    LinearLayout.LayoutParams(dp(if (compactLabel) 21 else 24), dp(if (compactLabel) 21 else 24)).apply {
                                        rightMargin = dp(if (compactLabel) 3 else 5)
                                    }
                            },
                        )
                        addView(
                            bodyText(label).apply {
                                gravity = Gravity.CENTER_VERTICAL
                                setTextColor(Color.parseColor(textSecondary))
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compactLabel) 9.2f else 10.5f)
                                textScaleX = if (compactLabel) 0.92f else 1f
                                includeFontPadding = false
                                setSingleLine(true)
                                maxLines = 1
                            },
                        )
                    },
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, dp(7), 0, 0)
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                        addView(
                            valueView.apply {
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ).apply {
                                        gravity = Gravity.CENTER_HORIZONTAL
                                    }
                            },
                        )
                        if (unit.isNotBlank()) {
                            addView(
                                bodyText(unit).apply {
                                    setTextColor(Color.parseColor(textMuted))
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (unit.length >= 3) 9f else 9.5f)
                                    includeFontPadding = false
                                    setSingleLine(true)
                                    maxLines = 1
                                    gravity = Gravity.CENTER
                                    setPadding(0, dp(1), 0, 0)
                                    layoutParams =
                                        LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ).apply {
                                            gravity = Gravity.CENTER_HORIZONTAL
                                        }
                                },
                            )
                        }
                    },
                )
            }

        fun sectionHeader(title: String, tagView: TextView? = null): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(8))
                addView(
                    bodyText(title).apply {
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(Color.parseColor(textSecondary))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                tagView?.let { addView(it) }
            }

        fun sideTrainingButton(label: String, fillColor: String, strokeColor: String): Button =
            Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(46)
                minimumHeight = dp(46)
                includeFontPadding = false
                setPadding(dp(8), 0, dp(8), 0)
                background = roundedBackground(fillColor, strokeColor, 999)
                elevation = dp(3).toFloat()
                applyRippleOverlay()
            }

        dashboardPunchValueView = metricValueView("0")
        dashboardBpmValueView = metricValueView("--")
        dashboardCaloriesValueView = metricValueView("0.0")
        dashboardFatValueView = metricValueView("0.0")
        dashboardPeakValueView = metricValueView("-- N")
        dashboardRhythmValueView = metricValueView("--")
        dashboardRoundBadgeView =
            badgeText(localText("第 1 回合", "Round 1", "Round 1", "รอบ 1"), textColor = "#096D65", fillColor = mintSoft)
        dashboardPeakTagView =
            badgeText(localText("峰值 -- N", "Peak -- N", "Pic -- N", "สูงสุด -- N"), textColor = "#F06B22", fillColor = orangeSoft)
        dashboardGoalProgressView =
            bodyText(localText("今日目标：500 拳 | 已完成 0 拳", "Today: 500 hits | Done 0 hits", "Aujourd'hui : 500 coups | 0 coups faits", "วันนี้ 500 หมัด | ทำแล้ว 0 หมัด")).apply {
                setTextColor(Color.parseColor(textPrimary))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
            }
        timerRingView =
            CircularTimerView(this).apply {
                setPalette(
                    trackColor = Color.parseColor("#DFF8F2"),
                    captionColor = Color.parseColor(textMuted),
                    centerColor = Color.parseColor(textPrimary),
                )
            }
        waveformView =
            PunchWaveformView(this).apply {
                setPalette(
                    guideColor = Color.parseColor("#D5E9E5"),
                    labelColor = Color.parseColor(textSecondary),
                    lowColor = Color.parseColor("#45DCC8"),
                    midColor = Color.parseColor("#FFD060"),
                    highColor = Color.parseColor("#FF7A45"),
                )
            }
        refreshWaveformLocalizedLabels()
        dashboardForceSummaryView =
            bodyText("").apply {
                visibility = View.GONE
                setTextColor(Color.parseColor(textSecondary))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBackground(cardAlt, "#E0F3EF", 12)
            }
        dashboardTrainingSettingsButton =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = roundedBackground("#FFFFFF", "#D7F0EA", 999)
                isClickable = true
                isFocusable = true
                applyRippleOverlay()
                setOnClickListener {
                    if (trainingJob?.isActive == true) {
                        Toast.makeText(
                            this@MainActivity,
                            localText("训练中不可修改训练设置。", "Training settings are locked during a session.", "Réglages verrouillés pendant l'entraînement.", "ล็อกการตั้งค่าระหว่างฝึก"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        showTrainingSettingsDialog()
                    }
                }
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.home_icon_settings)
                        layoutParams =
                            LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                                rightMargin = dp(4)
                            }
                    },
                )
                addView(
                    bodyText(localText("训练设置", "Settings", "Réglages", "ตั้งค่า")).apply {
                        setTextColor(Color.parseColor(textPrimary))
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        gravity = Gravity.CENTER
                    },
                )
            }
        dashboardComboContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 0)
            }
        dashboardComboSummaryView =
            bodyText("").apply {
                setTextColor(Color.parseColor(textSecondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dp(7), 0, 0)
            }

        return detailCard(fillColor = cardFill, strokeColor = cardStroke, cornerDp = 24).apply {
            background = roundedBackground(cardFill, cardStroke, 24)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                }
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    addView(
                        sectionLabel(localText("实时训练", "Live Training", "Entraînement live", "ฝึกสด")).apply {
                            setTextColor(Color.parseColor(textPrimary))
                            textSize = 16f
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    0,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    1f,
                                ).apply { gravity = Gravity.CENTER_VERTICAL }
                        },
                    )
                    addView(
                        dashboardRoundBadgeView.apply {
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply {
                                    gravity = Gravity.CENTER
                                    leftMargin = dp(6)
                                    rightMargin = dp(6)
                                }
                        },
                    )
                    addView(
                        dashboardTrainingSettingsButton.apply {
                            layoutParams =
                                LinearLayout.LayoutParams(dp(96), dp(38)).apply {
                                    gravity = Gravity.CENTER_VERTICAL
                                }
                        },
                    )
                },
            )
            startButton =
                sideTrainingButton(localText("开始", "Start", "Démarrer", "เริ่ม"), mint, "#4CE0D2").apply {
                    setOnClickListener { startTraining() }
                }
            stopButton =
                sideTrainingButton(localText("结束", "End", "Terminer", "จบ"), orange, "#FFC47D").apply {
                    isEnabled = false
                    alpha = 0.5f
                    setOnClickListener { stopTraining(showStoppedState = true) }
                }
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(8))
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    addView(
                        startButton.apply {
                            layoutParams =
                                LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                                    rightMargin = dp(8)
                                    gravity = Gravity.CENTER_VERTICAL
                                }
                        },
                    )
                    addView(
                        timerRingView.apply {
                            layoutParams =
                                LinearLayout.LayoutParams(dp(156), dp(156)).apply {
                                    gravity = Gravity.CENTER_VERTICAL
                                }
                        },
                    )
                    addView(
                        stopButton.apply {
                            layoutParams =
                                LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                                    leftMargin = dp(8)
                                    gravity = Gravity.CENTER_VERTICAL
                                }
                        },
                    )
                },
            )
            val metricsRow =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 4f
                }
            metricsRow.addView(
                metricTile(R.drawable.home_metric_hits, localText("拳数", "Hits", "Coups", "หมัด"), dashboardPunchValueView, localText("次", "", "", "")).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(104), 1f).apply { rightMargin = dp(7) }
                },
            )
            metricsRow.addView(
                metricTile(R.drawable.home_metric_bpm, "BPM", dashboardBpmValueView).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(104), 1f).apply { rightMargin = dp(7) }
                },
            )
            metricsRow.addView(
                metricTile(R.drawable.home_metric_calories, localText("卡路里", "Calories", "Calories", "แคลอรี"), dashboardCaloriesValueView, "Kcal").apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(104), 1f).apply { rightMargin = dp(7) }
                },
            )
            metricsRow.addView(
                metricTile(R.drawable.home_metric_fat, localText("等效燃脂", "Eq. fat", "Graisse", "ไขมัน"), dashboardFatValueView, "g").apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(104), 1f)
                },
            )
            addView(metricsRow)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(10), dp(6), 0)
                    addView(
                        dashboardGoalProgressView.apply {
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply {
                                    rightMargin = dp(8)
                                }
                        },
                    )
                    dashboardGoalProgressFillView =
                        View(this@MainActivity).apply {
                            background = roundedBackground(mint, mint, 999)
                            layoutParams = FrameLayout.LayoutParams(0, dp(10), Gravity.START or Gravity.CENTER_VERTICAL)
                        }
                    dashboardGoalProgressTrackView =
                        FrameLayout(this@MainActivity).apply {
                            background = roundedBackground("#DFF4EF", "#DFF4EF", 999)
                            layoutParams = LinearLayout.LayoutParams(0, dp(10), 1f)
                            addView(dashboardGoalProgressFillView)
                        }
                    addView(dashboardGoalProgressTrackView)
                },
            )
            addView(sectionHeader(localText("连击识别", "Combo recognition", "Combos détectés", "ตรวจจับคอมโบ")))
            addView(
                HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(dashboardComboContainer)
                },
            )
            addView(dashboardComboSummaryView)
            updateDashboardViews(selectedMode.durationSeconds * 1_000L)
        }
    }

    private fun buildAiCoachRealtimeCard(): LinearLayout {
        fun voiceBar(widthDp: Int, heightDp: Int): View =
            View(this).apply {
                background = roundedBackground("#10BDAA", "#10BDAA", 999)
                alpha = 0.42f
                layoutParams =
                    LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).apply {
                        rightMargin = dp(3)
                        gravity = Gravity.BOTTOM
                    }
            }

        fun cueItem(trigger: String, message: String, meta: String, fill: String, stroke: String): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBackground("#FFFFFF", "#D7F0EA", 12)
                setPadding(dp(9), dp(7), dp(9), dp(7))
                addView(
                    badgeText(trigger, textColor = "#FFFFFF", fillColor = fill).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                rightMargin = dp(8)
                            }
                    },
                )
                addView(
                    bodyText(message).apply {
                        setTextColor(Color.parseColor("#557A7D"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                addView(
                    bodyText("▶").apply {
                        setTextColor(Color.parseColor(stroke))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(dp(8), 0, 0, 0)
                    },
                )
                setOnClickListener {
                    pushAiCoachCue(
                        key = "manual_${trigger.hashCode()}",
                        message = message,
                        meta = meta,
                        speak = true,
                        force = true,
                    )
                }
            }

        aiCoachStatusView =
            badgeText(localText("待命", "Ready", "Prêt", "พร้อม"), textColor = "#096D65", fillColor = "#DFFFF7")
        aiCoachMessageView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#17343B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setLineSpacing(0f, 1.35f)
            }
        aiCoachMetaView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#7FA0A3"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setPadding(0, dp(4), 0, 0)
            }
        aiCoachVoiceBar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(dp(42), dp(8), 0, 0)
                listOf(5, 14, 10, 18, 8, 16, 6, 20, 9, 15).forEach { height ->
                    addView(voiceBar(3, height))
                }
            }

        return detailCard(fillColor = "#FFFFFF", strokeColor = "#BFEFE5", cornerDp = 22).apply {
            background = roundedBackground("#FFFFFF", "#BFEFE5", 22)
            setPadding(dp(14), dp(13), dp(14), dp(14))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(10)
                }
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        sectionLabel(localText("AI 教练实时指导", "AI Live Coach", "Coach IA live", "โค้ช AI สด")).apply {
                            setTextColor(Color.parseColor("#096D65"))
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        },
                    )
                    addView(aiCoachStatusView)
                },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundedBackground("#F7FFFD", "#D7F0EA", 14)
                    setPadding(dp(11), dp(11), dp(11), dp(11))
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.TOP
                            addView(
                                TextView(this@MainActivity).apply {
                                    text = "AI"
                                    gravity = Gravity.CENTER
                                    setTypeface(Typeface.DEFAULT_BOLD)
                                    setTextColor(Color.WHITE)
                                    textSize = 12f
                                    background = roundedBackground("#10BDAA", "#8BEDE2", 999)
                                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) }
                                },
                            )
                            addView(
                                LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                    addView(aiCoachMessageView)
                                    addView(aiCoachMetaView)
                                },
                            )
                        },
                    )
                    addView(aiCoachVoiceBar)
                },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(9), 0, 0)
                    val cues =
                        listOf(
                            arrayOf(
                                localText("BPM 低于目标", "Low BPM", "BPM bas", "BPM ต่ำ"),
                                localText("加快节奏，短一点、快一点，跟上节拍！", "Pick up the pace, shorter and faster, stay on beat.", "Accélérez, plus court et plus vite.", "เร่งจังหวะ สั้นและเร็วขึ้น"),
                                localText("触发原因：BPM 低于目标", "Trigger: BPM below target", "Déclencheur : BPM bas", "สาเหตุ: BPM ต่ำกว่าเป้า"),
                                "#10BDAA",
                                "#096D65",
                            ),
                            arrayOf(
                                localText("连续命中 10 拳", "10-hit streak", "10 coups", "ต่อเนื่อง 10"),
                                localText("漂亮！保持这个状态，再来一组！", "Great. Keep this state and give me one more set.", "Très bien, encore une série.", "เยี่ยม รักษาจังหวะแล้วต่ออีกชุด"),
                                localText("触发原因：连续命中", "Trigger: hit streak", "Déclencheur : série de coups", "สาเหตุ: หมัดต่อเนื่อง"),
                                "#16C8B5",
                                "#096D65",
                            ),
                            arrayOf(
                                localText("力度骤降", "Force drop", "Baisse force", "แรงตก"),
                                localText("注意力度，手腕锁住，拳面打实。", "Watch the force, lock the wrist and land clean.", "Force : verrouillez le poignet.", "ระวังแรง ล็อกข้อมือแล้วออกหมัดให้แน่น"),
                                localText("触发原因：力度低于近期均值", "Trigger: force below recent average", "Déclencheur : force sous moyenne", "สาเหตุ: แรงต่ำกว่าค่าเฉลี่ย"),
                                "#FF8A32",
                                "#B65A18",
                            ),
                            arrayOf(
                                localText("最后 10 秒", "Final 10s", "10 s restantes", "10 วิสุดท้าย"),
                                localText("最后 10 秒，全力冲刺，把节奏顶住。", "Final 10 seconds, push hard and hold the rhythm.", "Dernières 10 s, poussez fort.", "10 วิสุดท้าย เร่งเต็มที่"),
                                localText("触发原因：回合结束倒计时", "Trigger: round ending", "Déclencheur : fin de round", "สาเหตุ: ใกล้จบรอบ"),
                                "#E65A4F",
                                "#B9433C",
                            ),
                        )
                    cues.forEachIndexed { index, cue ->
                        addView(
                            cueItem(cue[0], cue[1], cue[2], cue[3], cue[4]).apply {
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ).apply {
                                        if (index > 0) {
                                            topMargin = dp(6)
                                        }
                                }
                            },
                        )
                    }
                },
            )
            resetAiCoachRealtimeCard()
        }
    }

    private fun buildMusicImmersionCard(): LinearLayout {
        fun optionButton(label: String): RadioButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                setTextColor(Color.parseColor("#17343B"))
                buttonTintList =
                    ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(-android.R.attr.state_checked),
                        ),
                        intArrayOf(
                            Color.parseColor("#10BDAA"),
                            Color.parseColor("#8CCDC4"),
                        ),
                    )
                minHeight = dp(40)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }

        rhythmFreeButton = optionButton(localText("自由", "Free", "Libre", "อิสระ"))
        rhythmBeatButton = optionButton(localText("跟拍", "Beat", "Tempo", "ตามจังหวะ"))
        beat40Button = optionButton("40")
        beat65Button = optionButton("65")
        beat80Button = optionButton("80")
        beat100Button = optionButton("100")
        beat120Button = optionButton("120")
        soundGymButton = optionButton(localText("拳击馆", "Gym", "Salle", "ยิม"))
        soundStreetButton = optionButton(localText("街头", "Street", "Rue", "สตรีท"))

        val card =
            detailCard(fillColor = "#FFFFFF", strokeColor = "#BFEFE5", cornerDp = 22).apply {
                background = roundedBackground("#FFFFFF", "#BFEFE5", 22)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = dp(10)
                    }
                addView(
                    sectionLabel(localText("音乐节拍 & 沉浸模式", "Music Beat & Immersion", "Tempo & immersion", "จังหวะเพลง")).apply {
                        setTextColor(Color.parseColor("#096D65"))
                    },
                )
                addView(
                    bodyText(localText("训练时自动播放鼓点音床、跟拍评分与击打音效。", "Auto drum groove, rhythm scoring, and hit sounds during training.", "Groove batterie, score rythme et sons de frappe pendant l'entraînement.", "เปิดจังหวะกลอง คะแนนจังหวะ และเสียงหมัดตอนฝึก")).apply {
                        setTextColor(Color.parseColor("#557A7D"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setPadding(0, dp(6), 0, dp(8))
                    },
                )
            }
        val rhythmGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                addView(rhythmFreeButton)
                addView(rhythmBeatButton)
                setOnCheckedChangeListener { _, checkedId ->
                    selectedRhythmMode = if (checkedId == rhythmBeatButton.id) TrainingRhythmMode.Rhythm else TrainingRhythmMode.Free
                    prefs.edit().putString(KEY_RHYTHM_MODE, selectedRhythmMode.name).apply()
                    refreshMusicImmersionControls()
                    updateDashboardViews(currentRemainingMillis())
                }
            }
        val bpmGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                addView(beat40Button)
                addView(beat65Button)
                addView(beat80Button)
                addView(beat100Button)
                addView(beat120Button)
                setOnCheckedChangeListener { _, checkedId ->
                    selectedBeatBpm =
                        when (checkedId) {
                            beat40Button.id -> 40
                            beat65Button.id -> 65
                            beat100Button.id -> 100
                            beat120Button.id -> 120
                            else -> 80
                        }
                    prefs.edit().putInt(KEY_BEAT_BPM, selectedBeatBpm).apply()
                    refreshMusicImmersionControls()
                }
            }
        val soundGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                addView(soundGymButton)
                addView(soundStreetButton)
                setOnCheckedChangeListener { _, checkedId ->
                    selectedSoundPack = if (checkedId == soundStreetButton.id) SoundPack.Street else SoundPack.Gym
                    prefs.edit().putString(KEY_SOUND_PACK, selectedSoundPack.name).apply()
                    refreshMusicImmersionControls()
                }
            }
        card.addView(sectionSubtitle(localText("训练方式", "Mode", "Mode", "โหมด")))
        card.addView(rhythmGroup)
        card.addView(sectionSubtitle("BPM"))
        card.addView(bpmGroup)
        card.addView(sectionSubtitle(localText("音效包", "Sound pack", "Sons", "ชุดเสียง")))
        card.addView(soundGroup)
        refreshMusicImmersionControls()
        return card
    }

    private fun formatReportEndedTime(epochMs: Long): String {
        val pattern =
            when (selectedLanguage) {
                AppLanguage.Chinese -> "MM-dd HH:mm"
                AppLanguage.English -> "MMM dd, HH:mm"
                AppLanguage.French -> "dd MMM HH:mm"
                AppLanguage.Thai -> "dd/MM HH:mm"
            }
        return SimpleDateFormat(pattern, localeForLanguage()).format(Date(epochMs))
    }

    private fun setCloudStatusMessage(
        colorHex: String,
        key: String? = null,
        fallback: String? = null,
    ) {
        cloudStatusMessageKey = key
        cloudStatusFallbackMessage = fallback
        cloudStatusColor = Color.parseColor(colorHex)
        if (::cloudStatusView.isInitialized) {
            cloudStatusView.setTextColor(cloudStatusColor)
            cloudStatusView.text = currentCloudStatusMessage()
            cloudStatusView.background = chipBackground(cloudStatusColor)
        }
    }

    private fun currentCloudStatusMessage(): String =
        cloudStatusMessageKey?.let(::tr) ?: cloudStatusFallbackMessage.orEmpty()

    private fun secondsToMode(seconds: Int): TrainingMode =
        when {
            seconds >= 60 -> TrainingMode.Seconds60
            seconds >= 30 -> TrainingMode.Seconds30
            seconds >= 15 -> TrainingMode.Burst15
            else -> TrainingMode.Burst10
        }

    private fun setTrainingBusyUi(isBusy: Boolean) {
        val activated = isActivated()
        startButton.isEnabled = !isBusy && activated
        startButton.alpha = if (startButton.isEnabled) 1.0f else 0.5f
        stopButton.isEnabled = isBusy
        stopButton.alpha = if (isBusy) 1.0f else 0.5f
        settingsButton.isEnabled = !isBusy
        settingsButton.alpha = if (isBusy) 0.5f else 1.0f
        if (::dashboardTrainingSettingsButton.isInitialized) {
            dashboardTrainingSettingsButton.isEnabled = !isBusy
            dashboardTrainingSettingsButton.alpha = if (isBusy) 0.5f else 1.0f
        }
        activateButton.isEnabled = !isBusy && !activated && activationInputsValid
        activateButton.alpha = if (activateButton.isEnabled) 1.0f else 0.6f
        serialInput.isEnabled = !isBusy && !activated
        codeInput.isEnabled = !isBusy && !activated
        for (index in 0 until modeGroup.childCount) {
            modeGroup.getChildAt(index).isEnabled = !isBusy
            modeGroup.getChildAt(index).alpha = if (isBusy) 0.6f else 1.0f
        }
        refreshModeButtonStyles()
        refreshMusicImmersionControls()
    }

    private fun showTrainingSettingsDialog() {
        var pending = trainingSessionSetup
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(8))
                background = settingsDialogPanelBackground()
                elevation = dp(6).toFloat()
            }
        lateinit var render: () -> Unit

        fun presetCard(title: String, subtitle: String, setup: TrainingSessionSetup): TextView =
            bodyText("$title\n$subtitle").apply {
                val selected = pending.workMinutes == setup.workMinutes && pending.restHalfMinutes == setup.restHalfMinutes && pending.rounds == setup.rounds
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#12333A"))
                background =
                    if (selected) {
                        metallicBackground("#68F1E5", "#10BDAA", "#C9FFF8", 16)
                    } else {
                        roundedBackground("#FFFFFF", "#BDEFE6", 16)
                    }
                elevation = if (selected) dp(4).toFloat() else dp(2).toFloat()
                setPadding(dp(8), dp(9), dp(8), dp(9))
                setOnClickListener {
                    pending =
                        pending.copy(
                            workMinutes = setup.workMinutes,
                            restHalfMinutes = setup.restHalfMinutes,
                            rounds = setup.rounds,
                        )
                    render()
                }
            }

        fun stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout =
            detailCard(fillColor = "#FFFFFF", strokeColor = "#BDEFE6", cornerDp = 16).apply {
                elevation = dp(2).toFloat()
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView(
                    bodyText(label).apply {
                        setTextColor(Color.parseColor("#456F73"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    },
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            compactActionButton("-", "#F4FFFC").apply {
                                applySettingsNeutralButtonChrome(this)
                                setOnClickListener { onMinus() }
                            },
                        )
                        addView(
                            bodyText(value).apply {
                                gravity = Gravity.CENTER
                                setTypeface(Typeface.DEFAULT_BOLD)
                                setTextColor(Color.parseColor("#12333A"))
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            },
                        )
                        addView(
                            compactActionButton("+", "#F4FFFC").apply {
                                applySettingsNeutralButtonChrome(this)
                                setOnClickListener { onPlus() }
                            },
                        )
                    },
                )
            }

        fun modeCard(title: String, subtitle: String, mode: TrainingRhythmMode): LinearLayout =
            detailCard(
                fillColor = if (pending.rhythmMode == mode) "#10BDAA" else "#FFFFFF",
                strokeColor = if (pending.rhythmMode == mode) "#C9FFF8" else "#BDEFE6",
                cornerDp = 16,
            ).apply {
                if (pending.rhythmMode == mode) {
                    background = metallicBackground("#68F1E5", "#10BDAA", "#C9FFF8", 16)
                }
                elevation = if (pending.rhythmMode == mode) dp(4).toFloat() else dp(2).toFloat()
                setPadding(dp(11), dp(10), dp(11), dp(10))
                setOnClickListener {
                    pending = pending.copy(rhythmMode = mode)
                    render()
                }
                addView(
                    bodyText(title).apply {
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(Color.parseColor(if (pending.rhythmMode == mode) "#FFFFFF" else "#12333A"))
                    },
                )
                addView(
                    bodyText(subtitle).apply {
                        setTextColor(Color.parseColor(if (pending.rhythmMode == mode) "#EFFFFA" else "#456F73"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                        setPadding(0, dp(3), 0, 0)
                    },
                )
            }

        fun renderBpmSection() {
            root.addView(
                settingsSectionHeader(
                    title = localText("BPM 节拍速度", "BPM Tempo", "Tempo BPM", "จังหวะ BPM"),
                    subtitle =
                        if (pending.rhythmMode == TrainingRhythmMode.Rhythm) {
                            localText("跟拍模式下用于节拍评分和训练音床。", "Used for rhythm scoring and the training groove.", "Utilisé pour le score de rythme.", "ใช้ให้คะแนนจังหวะ")
                    } else {
                        localText("自由模式下 BPM 仅作为参考节拍，不参与评分。", "In free mode BPM is only a reference metronome.", "En mode libre, BPM sert de référence.", "โหมดอิสระ BPM เป็นข้อมูลอ้างอิง")
                    },
                    accentColor = "#10BDAA",
                ),
            )
            root.addView(
                detailCard(fillColor = "#FFFFFF", strokeColor = "#BDEFE6", cornerDp = 16).apply {
                    elevation = dp(2).toFloat()
                    alpha = if (pending.rhythmMode == TrainingRhythmMode.Rhythm) 1f else 0.58f
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                compactActionButton("-", "#F4FFFC").apply {
                                    applySettingsNeutralButtonChrome(this)
                                    setOnClickListener {
                                        pending = pending.copy(bpm = (pending.bpm - 5).coerceAtLeast(40))
                                        render()
                                    }
                                },
                            )
                            addView(
                                SeekBar(this@MainActivity).apply {
                                    max = 100
                                    progress = pending.bpm.coerceIn(40, 140) - 40
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                    setOnSeekBarChangeListener(
                                        object : SeekBar.OnSeekBarChangeListener {
                                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                                if (fromUser) {
                                                    val stepped = ((progress + 40) / 5) * 5
                                                    pending = pending.copy(bpm = stepped.coerceIn(40, 140))
                                                    render()
                                                }
                                            }

                                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                                            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                                        },
                                    )
                                },
                            )
                            addView(
                                compactActionButton("+", "#F4FFFC").apply {
                                    applySettingsNeutralButtonChrome(this)
                                    setOnClickListener {
                                        pending = pending.copy(bpm = (pending.bpm + 5).coerceAtMost(140))
                                        render()
                                    }
                                },
                            )
                            addView(
                                bodyText("${pending.bpm}\nBPM").apply {
                                    gravity = Gravity.CENTER
                                    setTypeface(Typeface.DEFAULT_BOLD)
                                    setTextColor(Color.parseColor("#12333A"))
                                    setPadding(dp(10), 0, 0, 0)
                                },
                            )
                        },
                    )
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, dp(8), 0, 0)
                            listOf(
                                52 to localText("热身", "Warm-up", "Échauff.", "วอร์ม"),
                                80 to localText("标准", "Standard", "Standard", "มาตรฐาน"),
                                96 to localText("进阶", "Advanced", "Avancé", "ขั้นสูง"),
                                110 to localText("冲刺", "Sprint", "Sprint", "สปรินต์"),
                                128 to localText("极速", "Max", "Max", "เร็วสุด"),
                            ).forEach { (value, label) ->
                                addView(
                                    bodyText("$value\n$label").apply {
                                        gravity = Gravity.CENTER
                                        setTypeface(Typeface.DEFAULT_BOLD)
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                                        setTextColor(Color.parseColor(if (pending.bpm == value) "#FFFFFF" else "#12333A"))
                                        background =
                                            if (pending.bpm == value) {
                                                metallicBackground("#68F1E5", "#10BDAA", "#C9FFF8", 12)
                                            } else {
                                                roundedBackground("#F4FFFC", "#BDEFE6", 12)
                                            }
                                        elevation = if (pending.bpm == value) dp(3).toFloat() else dp(1).toFloat()
                                        setPadding(dp(4), dp(5), dp(4), dp(5))
                                        setOnClickListener {
                                            pending = pending.copy(bpm = value)
                                            render()
                                        }
                                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) }
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

        fun summaryText(): String {
            val rest = TrainingSessionSetup.restHalfMinutesLabel(pending.restHalfMinutes)
            val totalMinutes = pending.totalEstimatedSeconds / 60f
            val totalText = if (totalMinutes % 1f == 0f) "${totalMinutes.toInt()}" else String.format(Locale.US, "%.1f", totalMinutes)
            val modeText =
                if (pending.rhythmMode == TrainingRhythmMode.Rhythm) {
                    localText("跟拍模式", "Beat mode", "Mode tempo", "โหมดจังหวะ")
                } else {
                    localText("自由模式", "Free mode", "Mode libre", "โหมดอิสระ")
                }
            return localText(
                "回合设置：${pending.workMinutes} 分打 | $rest 分休 | ${pending.rounds} 局\n训练方式：$modeText\n节拍速度：${if (pending.rhythmMode == TrainingRhythmMode.Rhythm) "${pending.bpm} BPM" else "自由参考"}\n预计总时长：$totalText 分钟",
                "Rounds: ${pending.workMinutes} min work | $rest min rest | ${pending.rounds} rounds\nMode: $modeText\nTempo: ${if (pending.rhythmMode == TrainingRhythmMode.Rhythm) "${pending.bpm} BPM" else "free reference"}\nEstimated total: $totalText min",
                "Rounds : ${pending.workMinutes} min travail | $rest min repos | ${pending.rounds}\nMode : $modeText\nTempo : ${if (pending.rhythmMode == TrainingRhythmMode.Rhythm) "${pending.bpm} BPM" else "référence libre"}\nTotal estimé : $totalText min",
                "รอบ: ชก ${pending.workMinutes} นาที | พัก $rest นาที | ${pending.rounds} รอบ\nโหมด: $modeText\nจังหวะ: ${if (pending.rhythmMode == TrainingRhythmMode.Rhythm) "${pending.bpm} BPM" else "อ้างอิงอิสระ"}\nเวลารวม: $totalText นาที",
            )
        }

        render = {
            root.removeAllViews()
            root.addView(
                settingsDialogTitleBlock(
                    title = localText("训练设置", "Training Settings", "Réglages", "ตั้งค่าการฝึก"),
                    subtitle = localText("设置回合、休息、训练方式和节拍速度。", "Set rounds, rest, training mode, and tempo.", "Réglez les rounds, le repos, le mode et le tempo.", "ตั้งค่ารอบ พัก โหมด และจังหวะ"),
                ),
            )
            root.addView(settingsSectionHeader(localText("回合预设", "Round presets", "Préréglages", "พรีเซ็ตรอบ"), localText("选择一个常用结构，也可以继续微调。", "Choose a structure, then fine tune.", "Choisissez puis ajustez.", "เลือกแล้วปรับต่อได้"), "#10BDAA"))
            root.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            listOf(
                                Triple("1/0.5", localText("初学者", "Beginner", "Débutant", "มือใหม่"), pending.copy(workMinutes = 1, restHalfMinutes = 1, rounds = 3)),
                                Triple("2/0.5", localText("经典", "Classic", "Classique", "คลาสสิก"), pending.copy(workMinutes = 2, restHalfMinutes = 1, rounds = 3)),
                            ).forEach { (title, subtitle, setup) ->
                                addView(presetCard(title, subtitle, setup).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) } })
                            }
                        },
                    )
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, dp(7), 0, dp(10))
                            listOf(
                                Triple("5/1", localText("高强度", "High intensity", "Haute intensité", "เข้มข้น"), pending.copy(workMinutes = 5, restHalfMinutes = 2, rounds = 3)),
                                Triple("1/1", "HIIT", pending.copy(workMinutes = 1, restHalfMinutes = 2, rounds = 6)),
                            ).forEach { (title, subtitle, setup) ->
                                addView(presetCard(title, subtitle, setup).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) } })
                            }
                        },
                    )
                },
            )
            root.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(stepper(localText("训练时长", "Work", "Travail", "ชก"), "${pending.workMinutes} min", {
                        pending = pending.copy(workMinutes = (pending.workMinutes - 1).coerceAtLeast(1)); render()
                    }, {
                        pending = pending.copy(workMinutes = (pending.workMinutes + 1).coerceAtMost(10)); render()
                    }).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } })
                    addView(stepper(localText("休息时长", "Rest", "Repos", "พัก"), "${TrainingSessionSetup.restHalfMinutesLabel(pending.restHalfMinutes)} min", {
                        pending = pending.copy(restHalfMinutes = (pending.restHalfMinutes - 1).coerceAtLeast(0)); render()
                    }, {
                        pending = pending.copy(restHalfMinutes = (pending.restHalfMinutes + 1).coerceAtMost(10)); render()
                    }).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                },
            )
            root.addView(
                stepper(localText("回合数", "Rounds", "Rounds", "จำนวนรอบ"), "${pending.rounds}", {
                    pending = pending.copy(rounds = (pending.rounds - 1).coerceAtLeast(1)); render()
                }, {
                    pending = pending.copy(rounds = (pending.rounds + 1).coerceAtMost(10)); render()
                }).apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) },
            )
            root.addView(settingsSectionHeader(localText("训练方式", "Training mode", "Mode", "โหมดฝึก"), localText("自由模式不计算节拍分；跟拍模式启用 Perfect/Good/Miss。", "Free mode skips beat scoring; beat mode enables Perfect/Good/Miss.", "Libre sans score; tempo avec score.", "อิสระไม่ให้คะแนนจังหวะ"), "#10BDAA"))
            root.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(modeCard(localText("自由模式", "Free", "Libre", "อิสระ"), localText("记录拳数、力度和 BPM", "Track hits, force, and BPM", "Compte, force et BPM", "นับหมัด แรง BPM"), TrainingRhythmMode.Free).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } })
                    addView(modeCard(localText("跟拍模式", "Beat", "Tempo", "จังหวะ"), localText("音乐节拍与跟拍评分", "Beat scoring with groove", "Score sur tempo", "คะแนนตามจังหวะ"), TrainingRhythmMode.Rhythm).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                },
            )
            renderBpmSection()
            root.addView(
                detailCard(fillColor = "#FFF8EF", strokeColor = "#FFB86A", cornerDp = 16).apply {
                    elevation = dp(3).toFloat()
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(
                        bodyText(summaryText()).apply {
                            setTextColor(Color.parseColor(if (pending.totalEstimatedSeconds > 3600) "#D06B00" else "#12333A"))
                            setTypeface(Typeface.DEFAULT_BOLD)
                        },
                    )
                },
            )
        }

        render()
        val dialog =
            AlertDialog.Builder(this)
                .setView(ScrollView(this).apply { addView(root) })
                .setNegativeButton(tr("cancel"), null)
                .setNeutralButton(localText("重置", "Reset", "Réinitialiser", "รีเซ็ต"), null)
                .setPositiveButton(tr("save"), null)
                .create()
        dialog.setOnShowListener {
            applySettingsNeutralButtonChrome(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
            applySettingsNeutralButtonChrome(dialog.getButton(AlertDialog.BUTTON_NEUTRAL))
            applySettingsButtonChrome(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                pending = TrainingSessionSetup()
                render()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                applyTrainingSessionSetup(pending)
                dialog.dismiss()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun applyTrainingSessionSetup(setup: TrainingSessionSetup) {
        trainingSessionSetup = setup
        selectedRhythmMode = setup.rhythmMode
        selectedBeatBpm = setup.bpm
        prefs.edit()
            .putInt(KEY_TRAINING_SETUP_WORK_MINUTES, setup.workMinutes)
            .putInt(KEY_TRAINING_SETUP_REST_HALF_MINUTES, setup.restHalfMinutes)
            .putInt(KEY_TRAINING_SETUP_ROUNDS, setup.rounds)
            .putString(KEY_TRAINING_SETUP_RHYTHM_MODE, setup.rhythmMode.name)
            .putInt(KEY_TRAINING_SETUP_BPM, setup.bpm)
            .putString(KEY_RHYTHM_MODE, setup.rhythmMode.name)
            .putInt(KEY_BEAT_BPM, setup.bpm)
            .apply()
        currentTrainingRound = 1
        currentTrainingRoundCount = setup.rounds
        currentRoundDurationMs = setup.workSeconds * 1_000L
        currentRoundRemainingMs = currentRoundDurationMs
        trainingResting = false
        updateDashboardViews(currentRoundRemainingMs)
        refreshMusicImmersionControls()
    }

    private fun setActivationVisible(visible: Boolean) {
        if (!visible && ::activationCard.isInitialized) {
            activationCard.removeCallbacks(hideActivationCardRunnable)
        }
        activationCard.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setActivationBusy(isBusy: Boolean) {
        val allowInput = !isBusy && !isActivated()
        activateButton.isEnabled = allowInput && activationInputsValid
        activateButton.alpha = if (activateButton.isEnabled) 1.0f else 0.6f
        serialInput.isEnabled = allowInput
        codeInput.isEnabled = allowInput
    }

    private fun updateActivationInputState() {
        val serialDigits = normalizeDigits(serialInput.text?.toString())
        val codeDigits = normalizeDigits(codeInput.text?.toString())
        val serialRaw = serialInput.text?.toString().orEmpty()
        val codeRaw = codeInput.text?.toString().orEmpty()
        val serialOk = serialDigits.length == 11
        val codeOk = codeDigits.length == 8

        if (serialRaw.isEmpty() || serialOk) {
            serialInputErrorView.text = ""
            serialInputErrorView.visibility = View.GONE
        } else {
            serialInputErrorView.text = tr("serial_invalid")
            serialInputErrorView.visibility = View.VISIBLE
        }
        if (codeRaw.isEmpty() || codeOk) {
            codeInputErrorView.text = ""
            codeInputErrorView.visibility = View.GONE
        } else {
            codeInputErrorView.text = tr("code_invalid")
            codeInputErrorView.visibility = View.VISIBLE
        }

        activationInputsValid = serialOk && codeOk
        val activated = isActivated()
        val busy = activationJob?.isActive == true || trainingJob?.isActive == true
        val enabled = !activated && !busy && activationInputsValid
        activateButton.isEnabled = enabled
        activateButton.alpha = if (enabled) 1.0f else 0.6f
    }

    private fun showFormalSettingsDialog() {
        val dialogRoot =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(8))
                background = settingsDialogPanelBackground()
                elevation = dp(6).toFloat()
            }

        dialogRoot.addView(
            settingsDialogTitleBlock(
                title = settingsDialogTitle(),
                subtitle =
                    localText(
                        "连接 SENBALL# 设备，并选择 APP 显示语言。",
                        "Connect the SENBALL# device and choose the app language.",
                        "Connectez l'appareil SENBALL# et choisissez la langue.",
                        "เชื่อมต่ออุปกรณ์ SENBALL# และเลือกภาษาแอป",
                    ),
            ),
        )
        dialogRoot.addView(createBluetoothSettingsPanel())
        val languageCard =
            detailCard(fillColor = "#FFFFFF", strokeColor = "#BDEFE6", cornerDp = 20).apply {
                elevation = dp(3).toFloat()
                setPadding(dp(14), dp(13), dp(14), dp(12))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                addView(
                    settingsSectionHeader(
                        title = tr("language"),
                        subtitle = tr("language_helper"),
                        accentColor = "#10BDAA",
                    ),
                )
            }
        val languageGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
        val zhOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_chinese")
                isChecked = selectedLanguage == AppLanguage.Chinese
                styleSettingsRadioButton(this)
            }
        val enOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_english")
                isChecked = selectedLanguage == AppLanguage.English
                styleSettingsRadioButton(this)
            }
        val frOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_french")
                isChecked = selectedLanguage == AppLanguage.French
                styleSettingsRadioButton(this)
            }
        val thOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_thai")
                isChecked = selectedLanguage == AppLanguage.Thai
                styleSettingsRadioButton(this)
            }
        languageGroup.setOnCheckedChangeListener { _, _ ->
            listOf(zhOption, enOption, frOption, thOption).forEach(::styleSettingsRadioButton)
        }
        languageGroup.addView(zhOption)
        languageGroup.addView(enOption)
        languageGroup.addView(frOption)
        languageGroup.addView(thOption)
        languageCard.addView(languageGroup)
        dialogRoot.addView(languageCard)

        val selectedPaletteHolder = arrayOf(selectedPalette.id)
        val paletteCard =
            detailCard(fillColor = selectedPalette.card, strokeColor = selectedPalette.strokeStrong, cornerDp = 20).apply {
                setPadding(dp(14), dp(13), dp(14), dp(12))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(16)
                    }
                addView(
                    settingsSectionHeader(
                        title = paletteSettingTitle(),
                        subtitle = paletteSettingSubtitle(),
                        accentColor = selectedPalette.accentSoft,
                    ),
                )
            }
        val paletteContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
        fun rerenderPaletteOptions() {
            renderPaletteSettings(
                container = paletteContainer,
                pendingSelectedId = selectedPaletteHolder[0],
                onSelect = { palette ->
                    selectedPaletteHolder[0] = palette.id
                    rerenderPaletteOptions()
                },
            )
        }
        paletteCard.addView(paletteContainer)
        rerenderPaletteOptions()

        val selectedCloudEffectHolder = arrayOf(selectedCloudSoundEffectId)
        val soundEffectCard =
            detailCard(fillColor = selectedPalette.card, strokeColor = selectedPalette.accentHot, cornerDp = 20).apply {
                setPadding(dp(14), dp(13), dp(14), dp(12))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(16)
                    }
                addView(
                    settingsSectionHeader(
                        title = localText("云端音效", "Cloud Sound Effects", "Sons cloud", "เสียงบนคลาวด์"),
                        subtitle =
                            localText(
                                "试听后选择一个拳击音效，训练击打时会使用所选音效。",
                                "Preview and choose one punch sound for training hits.",
                                "Écoutez puis choisissez un son pour les frappes.",
                                "ฟังตัวอย่างแล้วเลือกเสียงหมัดสำหรับการฝึก",
                            ),
                        accentColor = selectedPalette.accentSoft,
                    ),
                )
            }
        val soundEffectsContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        fun rerenderSoundEffects() {
            renderCloudSoundEffectSettings(
                container = soundEffectsContainer,
                pendingSelectedId = selectedCloudEffectHolder[0],
                onSelect = { effect ->
                    selectedCloudEffectHolder[0] = effect.id
                    rerenderSoundEffects()
                },
                onPreview = ::previewCloudSoundEffect,
            )
        }
        soundEffectCard.addView(
            compactActionButton(localText("刷新音效", "Refresh Effects", "Actualiser", "รีเฟรชเสียง"), selectedPalette.button).apply {
                setOnClickListener {
                    fetchCloudSoundEffects { rerenderSoundEffects() }
                    rerenderSoundEffects()
                }
            },
        )
        soundEffectCard.addView(soundEffectsContainer)

        val selectedBackgroundMusicHolder = arrayOf(selectedBackgroundMusicId)
        val backgroundMusicCard =
            detailCard(fillColor = selectedPalette.card, strokeColor = selectedPalette.success, cornerDp = 20).apply {
                setPadding(dp(14), dp(13), dp(14), dp(12))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(16)
                    }
                addView(
                    settingsSectionHeader(
                        title = localText("背景音乐", "Background Music", "Musique", "เพลงพื้นหลัง"),
                        subtitle =
                            localText(
                                "试听后选择一首训练背景音乐，训练时会自动循环播放。",
                                "Preview and choose one looped training background track.",
                                "Écoutez puis choisissez une musique de fond pour l'entraînement.",
                                "ฟังตัวอย่างแล้วเลือกเพลงพื้นหลังที่จะวนระหว่างฝึก",
                            ),
                        accentColor = selectedPalette.success,
                    ),
                )
            }
        val backgroundMusicContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        fun rerenderBackgroundMusic() {
            renderBackgroundMusicSettings(
                container = backgroundMusicContainer,
                pendingSelectedId = selectedBackgroundMusicHolder[0],
                onSelect = { track ->
                    selectedBackgroundMusicHolder[0] = track.id
                    rerenderBackgroundMusic()
                },
                onPreview = ::previewBackgroundMusic,
            )
        }
        backgroundMusicCard.addView(
            compactActionButton(localText("刷新音乐", "Refresh Music", "Actualiser", "รีเฟรชเพลง"), selectedPalette.button).apply {
                setOnClickListener {
                    fetchBackgroundMusic {
                        if (selectedBackgroundMusicHolder[0].isBlank()) {
                            selectedBackgroundMusicHolder[0] = selectedBackgroundMusicId
                        }
                        rerenderBackgroundMusic()
                    }
                    rerenderBackgroundMusic()
                }
            },
        )
        backgroundMusicCard.addView(backgroundMusicContainer)
        if (selectedBackgroundMusicHolder[0].isBlank()) {
            selectedBackgroundMusicHolder[0] = selectedBackgroundMusicId
        }

        val dialog =
            AlertDialog.Builder(this)
                .setView(
                    ScrollView(this).apply {
                        addView(dialogRoot)
                    },
                )
                .setNegativeButton(tr("cancel"), null)
                .setPositiveButton(tr("save"), null)
                .create()
        dialog.setOnShowListener {
            applySettingsNeutralButtonChrome(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
            applySettingsButtonChrome(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                applyLanguageAndSensitivitySettings(
                    language =
                        when (languageGroup.checkedRadioButtonId) {
                            enOption.id -> AppLanguage.English
                            frOption.id -> AppLanguage.French
                            thOption.id -> AppLanguage.Thai
                            else -> AppLanguage.Chinese
                    },
                    refreshCloud = true,
                )
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            bluetoothStatusView = null
            bluetoothDeviceListView = null
            bluetoothScanButton = null
            bluetoothConnectButton = null
            bluetoothDisconnectButton = null
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.let { window ->
            val attributes = window.attributes
            attributes.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            attributes.y = 0
            attributes.width = (resources.displayMetrics.widthPixels * 0.94f).toInt()
            window.attributes = attributes
        }
    }

    private fun createBluetoothSettingsPanel(): LinearLayout =
        detailCard(fillColor = "#FFFFFF", strokeColor = "#BDEFE6", cornerDp = 20).apply {
            elevation = dp(3).toFloat()
            setPadding(dp(14), dp(13), dp(14), dp(14))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(16) }

            addView(
                settingsSectionHeader(
                    title = bluetoothSectionTitle(),
                    subtitle = bluetoothSectionSubtitle(),
                    accentColor = "#10BDAA",
                ),
            )
            bluetoothStatusView =
                bodyText(bluetoothStatusMessage).apply {
                    setTextColor(Color.parseColor("#12333A"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = roundedBackground("#F4FFFC", "#BDEFE6", 16)
                    elevation = dp(2).toFloat()
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(10); bottomMargin = dp(10) }
                }
            addView(bluetoothStatusView)

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        compactActionButton(bluetoothScanLabel(), selectedPalette.button).apply {
                            bluetoothScanButton = this
                            applySettingsButtonChrome(this)
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnClickListener {
                                runWithBluetoothPermissions {
                                    sensorBallBluetooth.startScan()
                                }
                            }
                        },
                    )
                    addView(horizontalSpace(dp(8)))
                    addView(
                        compactActionButton(bluetoothConnectLabel(), "#F4FFFC").apply {
                            bluetoothConnectButton = this
                            applySettingsNeutralButtonChrome(this)
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnClickListener {
                                runWithBluetoothPermissions {
                                    val device = selectedBluetoothDevice ?: bluetoothDevices.firstOrNull()
                                    if (device == null) {
                                        bluetoothStatusMessage = bluetoothSelectDeviceText()
                                        updateBluetoothSettingsViews()
                                    } else {
                                        selectedBluetoothDevice = device
                                        sensorBallBluetooth.connect(device)
                                    }
                                }
                            }
                        },
                    )
                    addView(horizontalSpace(dp(8)))
                    addView(
                        compactActionButton(bluetoothDisconnectLabel(), selectedPalette.danger).apply {
                            bluetoothDisconnectButton = this
                            applySettingsButtonChrome(this, fillColor = "#E65A4F", highlightColor = "#FFB4A6", strokeColor = "#FFD2C9")
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnClickListener {
                                runWithBluetoothPermissions {
                                    sensorBallBluetooth.disconnect()
                                }
                            }
                        },
                    )
                },
            )
            bluetoothDeviceListView =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(12), 0, 0)
            }
            addView(bluetoothDeviceListView)

            updateBluetoothSettingsViews()
        }

    private fun settingsSectionHeader(title: String, subtitle: String, accentColor: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                sectionLabel(title).apply {
                    setTextColor(Color.parseColor(readableSettingsAccent(accentColor)))
                    setPadding(0, 0, 0, dp(4))
                },
            )
            addView(
                bodyText(subtitle).apply {
                    setTextColor(Color.parseColor("#456F73"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, 0, 0, dp(2))
                },
            )
        }

    private fun settingsDialogPanelBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor("#F7FFFC"),
                Color.parseColor("#EFFFFA"),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), Color.parseColor("#AEEDE4"))
        }

    private fun settingsDialogTitleBlock(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(14))
            addView(
                sectionLabel(title).apply {
                    setTextColor(Color.parseColor("#12333A"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTypeface(Typeface.DEFAULT_BOLD)
                },
            )
            addView(
                bodyText(subtitle).apply {
                    setTextColor(Color.parseColor("#456F73"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }

    private fun readableSettingsAccent(accentColor: String): String {
        fun luminance(hex: String): Double {
            val color = Color.parseColor(hex)
            return (0.299 * Color.red(color)) + (0.587 * Color.green(color)) + (0.114 * Color.blue(color))
        }
        val first = if (luminance(accentColor) > 180.0) selectedPalette.accent else accentColor
        val second = if (luminance(first) > 180.0) selectedPalette.accentHot else first
        return if (luminance(second) > 180.0) "#12333A" else second
    }

    private fun applySettingsButtonChrome(
        button: Button?,
        fillColor: String = "#10BDAA",
        textColor: String = "#FFFFFF",
        highlightColor: String = "#68F1E5",
        strokeColor: String = "#BFFFF7",
    ) {
        button ?: return
        button.setTextColor(Color.parseColor(textColor))
        button.background = metallicBackground(highlightColor, fillColor, strokeColor, 999)
        button.setTypeface(Typeface.DEFAULT_BOLD)
        button.isAllCaps = false
        button.minHeight = dp(42)
        button.elevation = dp(5).toFloat()
        button.translationZ = dp(1).toFloat()
        button.setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun applySettingsNeutralButtonChrome(button: Button?) {
        applySettingsButtonChrome(
            button = button,
            fillColor = "#F4FFFC",
            textColor = "#12333A",
            highlightColor = "#FFFFFF",
            strokeColor = "#BDEFE6",
        )
    }

    private fun styleSettingsRadioButton(button: RadioButton) {
        val selected = button.isChecked
        button.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#12333A"))
        button.setTypeface(Typeface.DEFAULT_BOLD)
        button.buttonTintList = ColorStateList.valueOf(Color.parseColor(if (selected) "#FFFFFF" else "#10BDAA"))
        button.background =
            if (selected) {
                metallicBackground("#68F1E5", "#10BDAA", "#C9FFF8", 16)
            } else {
                roundedBackground("#FFFFFF", "#BDEFE6", 16)
            }
        button.elevation = if (selected) dp(4).toFloat() else dp(2).toFloat()
        button.setPadding(dp(12), dp(10), dp(12), dp(10))
        button.layoutParams =
            RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
    }

    private fun paletteSettingTitle(): String =
        localText("配色选择", "Color Theme", "Thème couleur", "ธีมสี")

    private fun paletteSettingSubtitle(): String =
        localText(
            "选择 1 套界面配色，保存后立即应用；界面结构保持不变。",
            "Choose one color theme. It applies after saving without changing the layout.",
            "Choisissez un thème. Il s'applique après l'enregistrement sans modifier la mise en page.",
            "เลือกธีมสีหนึ่งชุด บันทึกแล้วใช้ทันที โดยไม่เปลี่ยนโครงสร้างหน้าจอ",
        )

    private fun paletteCurrentDefaultLabel(): String =
        localText("默认", "Default", "Défaut", "ค่าเริ่มต้น")

    private fun paletteSelectedLabel(): String =
        localText("已选择", "Selected", "Sélectionné", "เลือกแล้ว")

    private fun renderPaletteSettings(
        container: LinearLayout,
        pendingSelectedId: String,
        onSelect: (AppPalette) -> Unit,
    ) {
        container.removeAllViews()
        HitRisePalettes.all.forEachIndexed { index, option ->
            val isSelected = option.id == pendingSelectedId
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    background =
                        roundedBackground(
                            if (isSelected) selectedPalette.cardAlt else selectedPalette.surfaceBottom,
                            if (isSelected) selectedPalette.accentHot else selectedPalette.stroke,
                            14,
                        )
                    isClickable = true
                    isFocusable = true
                    applyRippleOverlay()
                    setOnClickListener { onSelect(option) }
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            if (index > 0) {
                                topMargin = dp(8)
                            }
                        }
                }
            row.addView(palettePreviewView(option))
            row.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ).apply {
                            leftMargin = dp(10)
                            rightMargin = dp(8)
                        }
                    addView(
                        bodyText(option.displayName(selectedLanguage)).apply {
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextColor(Color.parseColor(selectedPalette.textPrimary))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        },
                    )
                    addView(
                        bodyText(
                            buildString {
                                if (option.id == HitRisePalettes.DEFAULT_ID) {
                                    append(paletteCurrentDefaultLabel())
                                    append(" · ")
                                }
                                append(option.previewColors.joinToString(" / "))
                            },
                        ).apply {
                            setTextColor(Color.parseColor(selectedPalette.textMuted))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                            setPadding(0, dp(3), 0, 0)
                        },
                    )
                },
            )
            row.addView(
                TextView(this).apply {
                    text = if (isSelected) "✓" else ""
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.parseColor(selectedPalette.accentHot))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    contentDescription = if (isSelected) paletteSelectedLabel() else option.displayName(selectedLanguage)
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(28))
                },
            )
            container.addView(row)
        }
    }

    private fun palettePreviewView(option: AppPalette): View =
        FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            background = roundedBackground(selectedPalette.card, selectedPalette.stroke, 14)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            if (option.iconRes != null) {
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(option.iconRes)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                        outlineProvider =
                            object : ViewOutlineProvider() {
                                override fun getOutline(view: View, outline: Outline) {
                                    outline.setRoundRect(0, 0, view.width, view.height, dp(11).toFloat())
                                }
                            }
                        layoutParams =
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                    },
                )
            } else {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        option.previewColors.forEachIndexed { colorIndex, color ->
                            addView(
                                View(this@MainActivity).apply {
                                    background = roundedBackground(color, color, 999)
                                    layoutParams =
                                        LinearLayout.LayoutParams(dp(10), dp(28)).apply {
                                            if (colorIndex > 0) {
                                                leftMargin = dp(3)
                                            }
                                        }
                                },
                            )
                        }
                        layoutParams =
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                    },
                )
            }
        }

    private fun bluetoothMetricView(label: String, value: String): TextView =
        bodyText("$label\n$value").apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(selectedPalette.textSecondary))
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(selectedPalette.cardAlt, selectedPalette.stroke, 16)
        }

    private fun updateBluetoothGyroHitCount(rawCount: Int) {
        val previous = lastBluetoothGyroRawCount
        if (previous == null) {
            lastBluetoothGyroRawCount = rawCount
            if (bluetoothHitCount == null) {
                bluetoothHitCount = 0
            }
            return
        }
        val delta =
            when {
                rawCount >= previous -> rawCount - previous
                previous >= 240 && rawCount <= 15 -> rawCount + 256 - previous
                else -> 0
        }
        if (delta > 0) {
            bluetoothHitCount = (bluetoothHitCount ?: 0) + delta
            if (trainingJob?.isActive == true && trainingAcceptingPunches) {
                repeat(delta.coerceAtMost(32)) {
                    recordTrainingPunch()
                }
                if (delta > 32) {
                    bluetoothTrainingCount += delta - 32
                }
                countView.text = bluetoothTrainingCount.toString()
                pulseCount()
                Haptics.tap(this)
                lastDisplayedCount = bluetoothTrainingCount
                updateDashboardViews(currentRemainingMillis())
            }
        }
        lastBluetoothGyroRawCount = rawCount
    }




    private fun refreshWaveformLocalizedLabels() {
        if (!::waveformView.isInitialized) {
            return
        }
        waveformView.setLabelText(
            empty = localText("等待击打力度", "Waiting for punch force", "En attente de force", "รอแรงหมัด"),
            latest = localText("最新", "Latest", "Dernier", "ล่าสุด"),
            peak = localText("峰值", "Peak", "Pic", "สูงสุด"),
        )
    }

    private fun updateBluetoothSettingsViewsFromTelemetry(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && trainingJob?.isActive == true && now - lastBluetoothSettingsRefreshElapsedMs < 5_000L) {
            return
        }
        lastBluetoothSettingsRefreshElapsedMs = now
        updateBluetoothSettingsViews()
    }

    private fun applyDeferredTrainingBatteryStatus() {
        val batteryRaw = deferredTrainingBatteryRaw ?: return
        deferredTrainingBatteryRaw = null
        bluetoothBatteryRaw = batteryRaw
        bluetoothBatteryText = bluetoothBatteryDisplayText(batteryRaw)
    }

    private fun updateBluetoothSettingsViews() {
        lastBluetoothSettingsRefreshElapsedMs = SystemClock.elapsedRealtime()
        updateHeaderBluetoothStatus()
        updateBluetoothActionButtons()
        renderHomeConnectionReportCard()
        val batteryDisplay = currentBluetoothBatteryText()
        bluetoothStatusView?.text =
            buildString {
                append(bluetoothStatusMessage)
                bluetoothConnectedDevice?.let { append(" | ").append(it.name) }
                if (bluetoothGyroscopeEnabled) {
                    append(" | ").append(bluetoothGyroOnStateText())
                }
        }
        bluetoothBatteryView?.text = "${bluetoothBatteryLabel()}\n$batteryDisplay"
        bluetoothHitCountView?.text = "${bluetoothHitCountLabel()}\n${bluetoothHitCount?.toString() ?: "--"}"
        bluetoothDeviceListView?.let { list ->
            list.removeAllViews()
            val visibleDevices = visibleBluetoothDevices()
            if (visibleDevices.isEmpty()) {
                list.addView(
                    bodyText(bluetoothNoDeviceText()).apply {
                        setTextColor(Color.parseColor("#456F73"))
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = roundedBackground("#F4FFFC", "#BDEFE6", 14)
                    },
                )
            } else {
                visibleDevices.forEach { device ->
                    val selected =
                        selectedBluetoothDevice?.matchesBluetoothDevice(device) == true ||
                            bluetoothConnectedDevice?.matchesBluetoothDevice(device) == true
                    list.addView(
                        bodyText("${device.name}\n${device.address} | ${device.transportLabel()} | RSSI ${device.rssi}").apply {
                            setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#12333A"))
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setPadding(dp(12), dp(9), dp(12), dp(9))
                            background =
                                if (selected) {
                                    metallicBackground("#68F1E5", "#10BDAA", "#C9FFF8", 14)
                                } else {
                                    roundedBackground("#F4FFFC", "#BDEFE6", 14)
                                }
                            elevation = if (selected) dp(4).toFloat() else dp(2).toFloat()
                            setOnClickListener {
                                selectedBluetoothDevice = device
                                bluetoothStatusMessage = bluetoothDeviceSelectedText(device.name)
                                updateBluetoothSettingsViews()
                            }
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply { bottomMargin = dp(6) }
                        },
                    )
                }
            }
        }
    }

    private fun visibleBluetoothDevices(): List<SensorBallDevice> {
        val result = bluetoothDevices.toMutableList()
        bluetoothConnectedDevice?.let { connected ->
            if (result.none { it.matchesBluetoothDevice(connected) }) {
                result.add(0, connected)
            }
        }
        selectedBluetoothDevice?.let { selected ->
            if (result.none { it.matchesBluetoothDevice(selected) }) {
                result.add(selected)
            }
        }
        return result
    }

    private fun rememberBluetoothDevice(device: SensorBallDevice) {
        val existingIndex = bluetoothDevices.indexOfFirst { it.matchesBluetoothDevice(device) }
        if (existingIndex >= 0) {
            bluetoothDevices[existingIndex] = device
        } else {
            bluetoothDevices.add(0, device)
        }
    }

    private fun updateHeaderBluetoothStatus() {
        if (!::bluetoothHeaderIndicatorView.isInitialized || !::batteryHeaderView.isInitialized) {
            return
        }
        val connected = bluetoothConnectedDevice != null
        bluetoothHeaderIndicatorView.setColorFilter(Color.parseColor(if (connected) "#2E8BFF" else "#FF4A6A"))
        bluetoothHeaderIndicatorView.background = null
        bluetoothHeaderIndicatorView.contentDescription =
            if (connected) "Bluetooth connected" else "Bluetooth disconnected"
        val batteryText = currentBluetoothBatteryText().takeIf { it != "--" } ?: if (connected) "..." else "--"
        batteryHeaderView.text = batteryText
        batteryHeaderView.setTextColor(Color.parseColor(if (connected) "#FFFFFF" else "#AAB3C2"))
        batteryHeaderView.setBackgroundResource(R.drawable.battery_status_background)
        batteryHeaderView.contentDescription =
            localText("电量 $batteryText", "Battery $batteryText", "Batterie $batteryText", "แบตเตอรี่ $batteryText")
    }

    private fun updateBluetoothActionButtons() {
        val connected = bluetoothConnectedDevice != null
        val selected = selectedBluetoothDevice != null
        setBluetoothButtonEnabled(bluetoothScanButton, !connected)
        setBluetoothButtonEnabled(bluetoothConnectButton, !connected && selected)
        setBluetoothButtonEnabled(bluetoothDisconnectButton, connected)
    }

    private fun setBluetoothButtonEnabled(button: Button?, enabled: Boolean) {
        button?.isEnabled = enabled
        button?.alpha = if (enabled) 1.0f else 0.42f
    }

    private fun currentBluetoothBatteryText(): String =
        bluetoothBatteryRaw?.let(::bluetoothBatteryDisplayText) ?: bluetoothBatteryText

    private fun bluetoothBatteryDisplayText(raw: Int): String =
        when (raw) {
            101 -> localText("充电", "Charging", "En charge", "กำลังชาร์จ")
            102 -> localText("充满", "Full", "Chargée", "เต็มแล้ว")
            in 0..100 -> "$raw%"
            else -> "--"
        }

    private fun bluetoothManagerStatusText(message: String): String =
        when {
            message.startsWith("扫描完成，发现 ") && message.endsWith(" 个 SENBALL# 设备") -> {
                val count = message.substringAfter("扫描完成，发现 ").substringBefore(" 个").toIntOrNull() ?: 0
                bluetoothDevicesFoundText(count)
            }
            message.startsWith("扫描失败：") ->
                localText("扫描失败：${message.substringAfter("扫描失败：")}", "Scan failed: ${message.substringAfter("扫描失败：")}", "Échec du scan : ${message.substringAfter("扫描失败：")}", "สแกนไม่สำเร็จ: ${message.substringAfter("扫描失败：")}")
            message == "蓝牙未开启" ->
                localText("蓝牙未开启", "Bluetooth is off", "Bluetooth désactivé", "บลูทูธปิดอยู่")
            message == "正在扫描 SENBALL# 设备..." ->
                localText("正在扫描 SENBALL# 设备...", "Scanning SENBALL# devices...", "Scan des appareils SENBALL#...", "กำลังสแกนอุปกรณ์ SENBALL#...")
            message == "设备地址无效" ->
                localText("设备地址无效", "Invalid device address", "Adresse appareil invalide", "ที่อยู่อุปกรณ์ไม่ถูกต้อง")
            message.startsWith("正在连接 ") ->
                localText("正在连接 ${message.substringAfter("正在连接 ")}", "Connecting ${message.substringAfter("正在连接 ")}", "Connexion ${message.substringAfter("正在连接 ")}", "กำลังเชื่อมต่อ ${message.substringAfter("正在连接 ")}")
            message == "请先连接蓝牙设备" ->
                bluetoothConnectFirstText()
            message == "未找到可写入的蓝牙通道" ->
                localText("未找到可写入的蓝牙通道", "No writable Bluetooth channel found", "Aucun canal Bluetooth inscriptible", "ไม่พบช่องบลูทูธที่เขียนได้")
            message == "开启陀螺仪指令等待蓝牙通道就绪" ->
                localText("开启陀螺仪指令等待蓝牙通道就绪", "Gyro on command is waiting for the Bluetooth channel", "Commande gyroscope en attente du canal Bluetooth", "คำสั่งเปิดไจโรรอช่องบลูทูธพร้อม")
            message == "关闭陀螺仪指令等待蓝牙通道就绪" ->
                localText("关闭陀螺仪指令等待蓝牙通道就绪", "Gyro off command is waiting for the Bluetooth channel", "Arrêt gyroscope en attente du canal Bluetooth", "คำสั่งปิดไจโรรอช่องบลูทูธพร้อม")
            message == "已发送开启陀螺仪指令" ->
                localText("已发送开启陀螺仪指令", "Gyro on command sent", "Commande gyroscope envoyée", "ส่งคำสั่งเปิดไจโรแล้ว")
            message == "已发送关闭陀螺仪指令" ->
                localText("已发送关闭陀螺仪指令", "Gyro off command sent", "Commande arrêt gyroscope envoyée", "ส่งคำสั่งปิดไจโรแล้ว")
            message == "陀螺仪指令未发送，请保持设备连接后重试" ->
                localText("陀螺仪指令未发送，请保持设备连接后重试", "Gyro command was not sent. Keep the device connected and retry.", "Commande gyroscope non envoyée. Gardez l'appareil connecté puis réessayez.", "ยังไม่ได้ส่งคำสั่งไจโร โปรดเชื่อมต่ออุปกรณ์แล้วลองใหม่")
            message == "蓝牙串口已就绪" ->
                localText("蓝牙串口已就绪", "Bluetooth serial ready", "Port série Bluetooth prêt", "พอร์ตบลูทูธพร้อม")
            message == "经典蓝牙连接失败" ->
                localText("经典蓝牙连接失败", "Classic Bluetooth connection failed", "Connexion Bluetooth classique échouée", "เชื่อมต่อบลูทูธคลาสสิกไม่สำเร็จ")
            message == "已连接，正在发现服务..." ->
                localText("已连接，正在发现服务...", "Connected. Discovering services...", "Connecté. Recherche des services...", "เชื่อมต่อแล้ว กำลังค้นหาบริการ...")
            message.startsWith("服务发现失败：") ->
                localText("服务发现失败：${message.substringAfter("服务发现失败：")}", "Service discovery failed: ${message.substringAfter("服务发现失败：")}", "Échec découverte services : ${message.substringAfter("服务发现失败：")}", "ค้นหาบริการไม่สำเร็จ: ${message.substringAfter("服务发现失败：")}")
            message.startsWith("蓝牙已就绪，通知通道 ") && message.endsWith(" 个") -> {
                val count = message.substringAfter("蓝牙已就绪，通知通道 ").substringBefore(" 个").toIntOrNull() ?: 0
                localText("蓝牙已就绪，通知通道 $count 个", "Bluetooth ready, $count notify channel(s)", "Bluetooth prêt, $count canal(aux) de notification", "บลูทูธพร้อม ช่องแจ้งเตือน $count ช่อง")
            }
            message == "BLE连接失败，尝试经典蓝牙..." ->
                localText("BLE连接失败，尝试经典蓝牙...", "BLE failed. Trying Classic Bluetooth...", "BLE échoué. Essai Bluetooth classique...", "BLE ไม่สำเร็จ กำลังลองบลูทูธคลาสสิก...")
            message == "自动连接仅使用 BLE，请在设置中手动连接经典蓝牙设备" ->
                localText("自动连接仅使用 BLE，请在设置中手动连接经典蓝牙设备", "Auto-connect uses BLE only. Connect Classic Bluetooth manually in Settings.", "La connexion auto utilise seulement BLE. Connectez le Bluetooth classique dans les paramètres.", "เชื่อมต่ออัตโนมัติใช้เฉพาะ BLE โปรดเชื่อมต่อคลาสสิกในตั้งค่า")
            message == "已取消蓝牙配对请求，HitRise 将继续使用免配对 BLE 连接" ->
                localText("已取消蓝牙配对请求，HitRise 将继续使用免配对 BLE 连接", "Pairing request canceled. HitRise will keep using BLE without pairing.", "Demande d'appairage annulée. HitRise continue en BLE sans appairage.", "ยกเลิกคำขอจับคู่แล้ว HitRise จะใช้ BLE แบบไม่จับคู่ต่อ")
            message == "已阻止 SENBALL# 设备进入配对流程" ->
                localText("已阻止 SENBALL# 设备进入配对流程", "SENBALL# pairing flow blocked", "Appairage SENBALL# bloqué", "บล็อกขั้นตอนจับคู่ SENBALL# แล้ว")
            else -> message
        }

    private fun runWithBluetoothPermissions(action: () -> Unit) {
        val missing =
            requiredBluetoothPermissions().filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingBluetoothAction = action
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun maybeShowBluetoothFirstUseGuide() {
        if (isFinishing || isDestroyed || trainingJob?.isActive == true || bluetoothConnectedDevice != null) {
            return
        }
        if (prefs.getBoolean(KEY_BLUETOOTH_FIRST_USE_GUIDE_SHOWN, false)) {
            return
        }
        prefs.edit().putBoolean(KEY_BLUETOOTH_FIRST_USE_GUIDE_SHOWN, true).apply()
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), 0)
                addView(
                    bodyText(bluetoothFirstUseGuideMessage()).apply {
                        setTextColor(Color.parseColor("#FFF5E6"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setPadding(0, 0, 0, dp(12))
                    },
                )
                addView(
                    bodyText(bluetoothFirstUseGuideHint()).apply {
                        setTextColor(Color.parseColor("#B9F8D0"))
                        background = roundedBackground("#082018", "#1D5C3D", 16)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    },
                )
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(bluetoothFirstUseGuideTitle())
                .setView(content)
                .setNegativeButton(bluetoothFirstUseLaterLabel(), null)
                .setPositiveButton(bluetoothFirstUseOpenSettingsLabel()) { _, _ ->
                    showFormalSettingsDialog()
                }
                .create()
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#F0FFFB"))
    }

    private fun scheduleTrainingBluetoothReconnect() {
        if (trainingJob?.isActive != true) {
            return
        }
        if (trainingBluetoothReconnectJob?.isActive == true) {
            return
        }
        trainingBluetoothReconnectJob =
            lifecycleScope.launch(Dispatchers.Main) {
                repeat(18) { attempt ->
                    if (trainingJob?.isActive != true || bluetoothConnectedDevice != null) {
                        return@launch
                    }
                    val delayMs =
                        if (attempt == 0) {
                            800L
                        } else {
                            (1_600L + attempt * 350L).coerceAtMost(4_500L)
                        }
                    delay(delayMs)
                    if (trainingJob?.isActive == true && bluetoothConnectedDevice == null) {
                        autoConnectLastBluetoothDevice()
                    }
                }
            }
    }

    private fun autoConnectLastBluetoothDevice() {
        if (bluetoothConnectedDevice != null) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (bluetoothAutoConnectInProgress && now - bluetoothLastAutoConnectStartedMs < 6_000L) {
            return
        }
        val savedDevice = loadLastBluetoothDevice() ?: return
        val bleAddress =
            savedDevice.bleAddress
                ?: savedDevice.address.takeIf { savedDevice.transport == SensorBallTransport.Ble }
                ?: return
        val bleDevice =
            savedDevice.copy(
                address = bleAddress,
                transport = SensorBallTransport.Ble,
                hasBle = true,
                hasClassic = false,
                bleAddress = bleAddress,
                classicAddress = null,
            )
        selectedBluetoothDevice = bleDevice
        bluetoothStatusMessage = bluetoothAutoConnectingText(bleDevice.name)
        updateBluetoothSettingsViews()
        runWithBluetoothPermissions {
            if (bluetoothConnectedDevice == null) {
                bluetoothAutoConnectInProgress = true
                bluetoothLastAutoConnectStartedMs = SystemClock.elapsedRealtime()
                sensorBallBluetooth.connect(
                    bleDevice,
                    allowClassicFallback = false,
                )
            }
        }
    }

    private fun saveLastBluetoothDevice(device: SensorBallDevice) {
        prefs.edit()
            .putString(KEY_LAST_BLUETOOTH_NAME, device.name)
            .putString(KEY_LAST_BLUETOOTH_ADDRESS, device.address)
            .putString(KEY_LAST_BLUETOOTH_TRANSPORT, device.transport.name)
            .putBoolean(KEY_LAST_BLUETOOTH_HAS_BLE, device.hasBle)
            .putBoolean(KEY_LAST_BLUETOOTH_HAS_CLASSIC, device.hasClassic)
            .putString(KEY_LAST_BLUETOOTH_BLE_ADDRESS, device.bleAddress)
            .putString(KEY_LAST_BLUETOOTH_CLASSIC_ADDRESS, device.classicAddress)
            .apply()
    }

    private fun loadLastBluetoothDevice(): SensorBallDevice? {
        val name = prefs.getString(KEY_LAST_BLUETOOTH_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val address = prefs.getString(KEY_LAST_BLUETOOTH_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        val transport =
            runCatching {
                SensorBallTransport.valueOf(prefs.getString(KEY_LAST_BLUETOOTH_TRANSPORT, SensorBallTransport.Classic.name).orEmpty())
            }.getOrElse { SensorBallTransport.Classic }
        val hasBle = prefs.getBoolean(KEY_LAST_BLUETOOTH_HAS_BLE, transport == SensorBallTransport.Ble)
        val hasClassic = prefs.getBoolean(KEY_LAST_BLUETOOTH_HAS_CLASSIC, transport == SensorBallTransport.Classic)
        val bleAddress = prefs.getString(KEY_LAST_BLUETOOTH_BLE_ADDRESS, null)?.takeIf { it.isNotBlank() }
        val classicAddress = prefs.getString(KEY_LAST_BLUETOOTH_CLASSIC_ADDRESS, null)?.takeIf { it.isNotBlank() }
        return SensorBallDevice(
            name = name,
            address = address,
            rssi = 0,
            transport = transport,
            hasBle = hasBle,
            hasClassic = hasClassic,
            bleAddress = bleAddress,
            classicAddress = classicAddress,
        )
    }

    private fun requiredBluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun bluetoothSectionTitle(): String =
        localText("蓝牙连接", "Bluetooth Connection", "Connexion Bluetooth", "การเชื่อมต่อบลูทูธ")

    private fun bluetoothSectionSubtitle(): String =
        localText(
            "请先扫描 SENBALL# 设备，连接成功后即可开始训练。",
            "Scan for a SENBALL# device first. Training is available after connection.",
            "Scannez d'abord un appareil SENBALL#. L'entraînement sera disponible après connexion.",
            "โปรดสแกนอุปกรณ์ SENBALL# ก่อน เมื่อเชื่อมต่อแล้วจึงเริ่มฝึกได้",
        )

    private fun bluetoothScanLabel(): String =
        localText("扫描", "Scan", "Scanner", "สแกน")

    private fun bluetoothConnectLabel(): String =
        localText("连接", "Connect", "Connecter", "เชื่อมต่อ")

    private fun bluetoothDisconnectLabel(): String =
        localText("断开", "Disconnect", "Déconnecter", "ตัดการเชื่อมต่อ")

    private fun bluetoothBatteryLabel(): String =
        localText("电量", "Battery", "Batterie", "แบตเตอรี่")

    private fun bluetoothHitCountLabel(): String =
        localText("拳击次数", "Punch Count", "Nombre de coups", "จำนวนหมัด")




    private fun bluetoothGyroOnLabel(): String =
        localText("开启", "Enable", "Activer", "เปิด")

    private fun bluetoothGyroOffLabel(): String =
        localText("关闭", "Disable", "Désactiver", "ปิด")

    private fun bluetoothGyroOnStateText(): String =
        localText("陀螺仪已开启", "Gyro on", "Gyroscope activé", "ไจโรเปิดอยู่")






    private fun bluetoothNoDeviceText(): String =
        localText("未扫描到 SENBALL# 设备", "No SENBALL# devices found", "Aucun appareil SENBALL# trouvé", "ไม่พบอุปกรณ์ SENBALL#")

    private fun bluetoothSelectDeviceText(): String =
        localText("请先扫描并选择设备", "Scan and select a device first", "Scannez puis sélectionnez un appareil", "โปรดสแกนและเลือกอุปกรณ์ก่อน")

    private fun bluetoothDeviceSelectedText(name: String): String =
        localText("已选择 $name", "Selected $name", "$name sélectionné", "เลือก $name แล้ว")

    private fun bluetoothPermissionDeniedText(): String =
        localText("未获得蓝牙权限，无法连接设备。", "Bluetooth permission is required to connect.", "L'autorisation Bluetooth est nécessaire pour se connecter.", "ต้องอนุญาตบลูทูธเพื่อเชื่อมต่อ")

    private fun bluetoothConnectFirstText(): String =
        localText("请先在设置中连接蓝牙设备。", "Connect the Bluetooth device in Settings first.", "Connectez d'abord l'appareil Bluetooth dans les paramètres.", "โปรดเชื่อมต่ออุปกรณ์บลูทูธในตั้งค่าก่อน")

    private fun bluetoothAutoSelectedText(name: String): String =
        localText("已自动选择 $name", "Auto-selected $name", "$name sélectionné automatiquement", "เลือก $name โดยอัตโนมัติ")

    private fun bluetoothDevicesFoundText(count: Int): String =
        localText("已扫描到 $count 个设备，请选择设备", "$count devices found. Select one.", "$count appareils trouvés. Sélectionnez-en un.", "พบอุปกรณ์ $count เครื่อง โปรดเลือกอุปกรณ์")

    private fun bluetoothConnectedText(name: String): String =
        localText("已连接 $name", "Connected to $name", "Connecté à $name", "เชื่อมต่อกับ $name แล้ว")

    private fun bluetoothTrainingReconnectText(): String =
        localText(
            "训练中蓝牙断开，正在自动重连...",
            "Bluetooth dropped during training. Reconnecting...",
            "Bluetooth coupé pendant l'entraînement. Reconnexion...",
            "บลูทูธหลุดระหว่างฝึก กำลังเชื่อมต่อใหม่...",
        )

    private fun bluetoothTrainingReconnectedText(name: String): String =
        localText(
            "训练中已重连 $name",
            "Reconnected during training: $name",
            "Reconnecté pendant l'entraînement : $name",
            "เชื่อมต่อใหม่ระหว่างฝึก: $name",
        )

    private fun bluetoothDisconnectedText(): String =
        localText("蓝牙已断开", "Bluetooth disconnected", "Bluetooth déconnecté", "ตัดการเชื่อมต่อบลูทูธแล้ว")

    private fun bluetoothPacketReceivedText(packetIndex: Int): String =
        localText("已接收数据包 $packetIndex", "Packet $packetIndex received", "Paquet $packetIndex reçu", "ได้รับแพ็กเก็ต $packetIndex แล้ว")

    private fun bluetoothAutoConnectingText(name: String): String =
        localText("正在自动连接上次设备 $name...", "Auto-connecting last device $name...", "Connexion automatique au dernier appareil $name...", "กำลังเชื่อมต่ออุปกรณ์ล่าสุด $name อัตโนมัติ...")

    private fun settingsDialogTitle(): String =
        localText("蓝牙与语言设置", "Bluetooth and Language", "Bluetooth et langue", "บลูทูธและภาษา")

    private fun bluetoothFirstUseGuideTitle(): String =
        localText("连接蓝牙设备", "Connect Bluetooth Device", "Connecter l'appareil Bluetooth", "เชื่อมต่ออุปกรณ์บลูทูธ")

    private fun bluetoothFirstUseGuideMessage(): String =
        localText(
            "首次使用前，请进入设置界面扫描并连接 HitRise 蓝牙设备。",
            "Before your first session, open Settings to scan and connect your HitRise device.",
            "Avant la première séance, ouvrez les paramètres pour scanner et connecter votre appareil HitRise.",
            "ก่อนใช้งานครั้งแรก โปรดเปิดตั้งค่าเพื่อสแกนและเชื่อมต่ออุปกรณ์ HitRise",
        )

    private fun bluetoothFirstUseGuideHint(): String =
        localText(
            "连接成功后，顶部蓝牙图标会变为蓝色，并显示电量。",
            "After connection, the top Bluetooth icon turns blue and shows battery level.",
            "Après connexion, l'icône Bluetooth en haut devient bleue et affiche la batterie.",
            "หลังเชื่อมต่อ ไอคอนบลูทูธด้านบนจะเป็นสีน้ำเงินและแสดงแบตเตอรี่",
        )

    private fun bluetoothFirstUseOpenSettingsLabel(): String =
        localText("去设置", "Open Settings", "Ouvrir les paramètres", "ไปที่ตั้งค่า")

    private fun bluetoothFirstUseLaterLabel(): String =
        localText("稍后", "Later", "Plus tard", "ภายหลัง")

    private fun SensorBallDevice.transportLabel(): String =
        when {
            hasBle && hasClassic -> "BLE/CLASSIC"
            hasBle -> "BLE"
            hasClassic -> "CLASSIC"
            else -> transport.name
        }

    private fun SensorBallDevice.matchesBluetoothDevice(other: SensorBallDevice): Boolean {
        val thisAddresses = listOf(address, bleAddress, classicAddress).filter { !it.isNullOrBlank() }
        val otherAddresses = listOf(other.address, other.bleAddress, other.classicAddress).filter { !it.isNullOrBlank() }
        return thisAddresses.any { left -> otherAddresses.any { right -> left.equals(right, ignoreCase = true) } } ||
            name.trim().equals(other.name.trim(), ignoreCase = true)
    }

    private fun showSettingsDialog() {
        val dialogRoot =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(4))
            }
        val scrollRoot =
            ScrollView(this).apply {
                addView(dialogRoot)
            }

        dialogRoot.addView(sectionLabel(tr("language")))
        val languageGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(0, dp(4), 0, dp(16))
            }
        val zhOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_chinese")
                isChecked = selectedLanguage == AppLanguage.Chinese
                setTextColor(Color.WHITE)
            }
        val enOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_english")
                isChecked = selectedLanguage == AppLanguage.English
                setTextColor(Color.WHITE)
            }
        val frOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_french")
                isChecked = selectedLanguage == AppLanguage.French
                setTextColor(Color.WHITE)
            }
        val thOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_thai")
                isChecked = selectedLanguage == AppLanguage.Thai
                setTextColor(Color.WHITE)
            }
        languageGroup.addView(zhOption)
        languageGroup.addView(enOption)
        languageGroup.addView(frOption)
        languageGroup.addView(thOption)
        dialogRoot.addView(languageGroup)

        dialogRoot.addView(sectionLabel(localText("云端音效", "Cloud Sound Effects", "Sons cloud", "เสียงบนคลาวด์")))
        dialogRoot.addView(
            bodyText(
                localText(
                    "试听后选择一个拳击音效，训练击打时会使用所选音效。",
                    "Preview and choose one punch sound for training hits.",
                    "Écoutez puis choisissez un son pour les frappes.",
                    "ฟังตัวอย่างแล้วเลือกเสียงหมัดสำหรับการฝึก",
                ),
            ).apply {
                setTextColor(Color.parseColor("#B7CFE0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, dp(8))
            },
        )
        val selectedCloudEffectHolder = arrayOf(selectedCloudSoundEffectId)
        val soundEffectsContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        fun rerenderSoundEffects() {
            renderCloudSoundEffectSettings(
                container = soundEffectsContainer,
                pendingSelectedId = selectedCloudEffectHolder[0],
                onSelect = { effect ->
                    selectedCloudEffectHolder[0] = effect.id
                    rerenderSoundEffects()
                },
                onPreview = ::previewCloudSoundEffect,
            )
        }
        val refreshEffectsButton =
            compactActionButton(localText("刷新音效", "Refresh Effects", "Actualiser", "รีเฟรชเสียง"), "#17354A").apply {
                setOnClickListener {
                    fetchCloudSoundEffects { rerenderSoundEffects() }
                    rerenderSoundEffects()
                }
            }
        dialogRoot.addView(refreshEffectsButton)
        dialogRoot.addView(soundEffectsContainer)
        rerenderSoundEffects()
        if (cloudSoundEffects.isEmpty()) {
            fetchCloudSoundEffects { rerenderSoundEffects() }
        }

        dialogRoot.addView(sectionLabel(localText("背景音乐", "Background Music", "Musique", "เพลงพื้นหลัง")))
        dialogRoot.addView(
            bodyText(
                localText(
                    "试听后选择一首训练背景音乐，训练时会自动循环播放。",
                    "Preview and choose one looped training background track.",
                    "Écoutez puis choisissez une musique de fond pour l'entraînement.",
                    "ฟังตัวอย่างแล้วเลือกเพลงพื้นหลังที่จะวนระหว่างฝึก",
                ),
            ).apply {
                setTextColor(Color.parseColor("#B7CFE0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, dp(8))
            },
        )
        val selectedBackgroundMusicHolder = arrayOf(selectedBackgroundMusicId)
        val backgroundMusicContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        fun rerenderBackgroundMusic() {
            renderBackgroundMusicSettings(
                container = backgroundMusicContainer,
                pendingSelectedId = selectedBackgroundMusicHolder[0],
                onSelect = { track ->
                    selectedBackgroundMusicHolder[0] = track.id
                    rerenderBackgroundMusic()
                },
                onPreview = ::previewBackgroundMusic,
            )
        }
        dialogRoot.addView(
            compactActionButton(localText("刷新音乐", "Refresh Music", "Actualiser", "รีเฟรชเพลง"), "#174A2C").apply {
                setOnClickListener {
                    fetchBackgroundMusic {
                        selectedBackgroundMusicHolder[0] = selectedBackgroundMusicId
                        rerenderBackgroundMusic()
                    }
                    rerenderBackgroundMusic()
                }
            },
        )
        dialogRoot.addView(backgroundMusicContainer)
        rerenderBackgroundMusic()
        if (cloudBackgroundMusic.isEmpty()) {
            fetchBackgroundMusic {
                selectedBackgroundMusicHolder[0] = selectedBackgroundMusicId
                rerenderBackgroundMusic()
            }
        }

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(tr("settings"))
                .setView(scrollRoot)
                .setNegativeButton(tr("cancel"), null)
                .setPositiveButton(tr("save"), null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedEffect = cloudSoundEffects.firstOrNull { it.id == selectedCloudEffectHolder[0] }
                if (selectedEffect != null) {
                    applyCloudSoundEffectSelection(selectedEffect)
                }
                findBackgroundMusicOption(selectedBackgroundMusicHolder[0])?.let(::applyBackgroundMusicSelection)
                applyLanguageAndSensitivitySettings(
                    language =
                        when (languageGroup.checkedRadioButtonId) {
                            enOption.id -> AppLanguage.English
                            frOption.id -> AppLanguage.French
                            thOption.id -> AppLanguage.Thai
                            else -> AppLanguage.Chinese
                        },
                    refreshCloud = false,
                )
                dialog.dismiss()
            }
        }
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#F0FFFB"))
    }

    private fun renderCloudSoundEffectSettings(
        container: LinearLayout,
        pendingSelectedId: String,
        onSelect: (CloudSoundEffect) -> Unit,
        onPreview: (CloudSoundEffect) -> Unit,
    ) {
        container.removeAllViews()
        cloudSoundEffectsMessage?.takeIf { it.isNotBlank() }?.let { message ->
            container.addView(
                bodyText(message).apply {
                    setTextColor(Color.parseColor(selectedPalette.accentHot))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(6), 0, dp(8))
                },
            )
        }
        if (cloudSoundEffects.isEmpty()) {
            container.addView(
                bodyText(
                    localText(
                        "正在等待云端音效列表。",
                        "Waiting for the cloud sound list.",
                        "En attente de la liste des sons cloud.",
                        "กำลังรอรายการเสียงบนคลาวด์",
                    ),
                ).apply {
                    setTextColor(Color.parseColor(selectedPalette.textMuted))
                    setPadding(0, dp(4), 0, dp(10))
                },
            )
            return
        }
        val rows =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        cloudSoundEffects.forEach { effect ->
            val isSelected = effect.id == pendingSelectedId
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background =
                        roundedBackground(
                            if (isSelected) selectedPalette.cardAlt else selectedPalette.surfaceBottom,
                            if (isSelected) selectedPalette.accentHot else selectedPalette.stroke,
                            14,
                        )
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    minimumHeight = dp(CLOUD_AUDIO_ROW_HEIGHT_DP - 10)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = dp(7)
                        }
                    setOnClickListener { onSelect(effect) }
                }
            row.addView(
                RadioButton(this).apply {
                    isChecked = isSelected
                    setOnClickListener { onSelect(effect) }
                },
            )
            row.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(
                        bodyText(cloudSoundEffectName(effect)).apply {
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextColor(Color.parseColor(selectedPalette.textPrimary))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        },
                    )
                    addView(
                        bodyText(cloudSoundEffectDescription(effect)).apply {
                            setTextColor(Color.parseColor(selectedPalette.textMuted))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                            setPadding(0, dp(2), 0, 0)
                        },
                    )
                },
            )
            row.addView(
                compactActionButton(localText("试听", "Preview", "Écouter", "ลองฟัง"), selectedPalette.button).apply {
                    setOnClickListener { onPreview(effect) }
                },
            )
            rows.addView(row)
        }
        container.addView(limitedCloudAudioListHost(cloudSoundEffects.size).apply { addView(rows) })
    }

    private fun limitedCloudAudioListHost(itemCount: Int): ScrollView =
        ScrollView(this).apply {
            isVerticalScrollBarEnabled = itemCount > CLOUD_AUDIO_MAX_VISIBLE_ROWS
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (itemCount > CLOUD_AUDIO_MAX_VISIBLE_ROWS) {
                        dp(CLOUD_AUDIO_MAX_VISIBLE_ROWS * CLOUD_AUDIO_ROW_HEIGHT_DP)
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    },
                )
        }

    private fun fetchCloudSoundEffects(onDone: (() -> Unit)? = null) {
        if (cloudSoundEffectsLoadingJob?.isActive == true) {
            return
        }
        cloudSoundEffectsMessage = localText("正在拉取云端音效...", "Fetching cloud effects...", "Chargement des sons cloud...", "กำลังโหลดเสียงบนคลาวด์...")
        cloudSoundEffectsLoadingJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val result = cloudSyncService.fetchSoundEffects()
                withContext(Dispatchers.Main) {
                    cloudSoundEffectsLoadingJob = null
                    if (result.success) {
                        cloudSoundEffects = result.items.ifEmpty { bundledCloudSoundEffects() }
                        cloudSoundEffectsMessage =
                            localText(
                                "已加载 ${cloudSoundEffects.size} 个云端音效。",
                                "Loaded ${cloudSoundEffects.size} cloud effects.",
                                "${cloudSoundEffects.size} sons chargés.",
                                "โหลดเสียงแล้ว ${cloudSoundEffects.size} รายการ",
                            )
                        val selected = cloudSoundEffects.firstOrNull { it.id == selectedCloudSoundEffectId }
                        when {
                            selected != null -> prepareCloudSoundEffect(selected, playWhenReady = false)
                            cloudSoundEffects.isNotEmpty() -> applyCloudSoundEffectSelection(cloudSoundEffects.first())
                        }
                    } else {
                        cloudSoundEffects = bundledCloudSoundEffects()
                        cloudSoundEffectsMessage =
                            if (cloudSoundEffects.isNotEmpty()) {
                                localText(
                                    "云端暂不可达，已使用内置 5 个音效。",
                                    "Cloud unavailable. Using 5 bundled effects.",
                                    "Cloud indisponible. 5 sons intégrés utilisés.",
                                    "คลาวด์ไม่พร้อม ใช้เสียงในเครื่อง 5 รายการ",
                                )
                            } else {
                                localText(
                                    "云端音效加载失败：${result.message}",
                                    "Cloud effects failed: ${result.message}",
                                    "Échec des sons cloud : ${result.message}",
                                    "โหลดเสียงไม่สำเร็จ: ${result.message}",
                                )
                            }
                    }
                    onDone?.invoke()
                }
            }
    }

    private fun cloudSoundEffectName(effect: CloudSoundEffect): String =
        localizedCloudSoundEffectName(effect.id)
            ?: when (selectedLanguage) {
                AppLanguage.Chinese -> effect.nameZh.ifBlank { effect.nameEn }
                AppLanguage.English,
                AppLanguage.French,
                AppLanguage.Thai,
                -> effect.nameEn.ifBlank { effect.nameZh }
            }

    private fun cloudSoundEffectDescription(effect: CloudSoundEffect): String =
        localizedCloudSoundEffectDescription(effect.id)
            ?: when (selectedLanguage) {
                AppLanguage.Chinese -> effect.descriptionZh.ifBlank { effect.descriptionEn }
                AppLanguage.English,
                AppLanguage.French,
                AppLanguage.Thai,
                -> effect.descriptionEn.ifBlank { effect.descriptionZh }
            }

    private fun localizedCloudSoundEffectName(id: String): String? =
        when (id) {
            "htr_punch_arena_thunder" -> localText("赛场雷击", "Arena Thunder", "Tonnerre du ring", "สนามสายฟ้า")
            "htr_punch_street_spark" -> localText("街头火花", "Street Spark", "Étincelle urbaine", "ประกายสตรีท")
            "htr_punch_iron_hook" -> localText("铁拳冲击", "Iron Hook", "Crochet d'acier", "ฮุกเหล็ก")
            "htr_punch_neon_jab" -> localText("霓虹快拳", "Neon Jab", "Direct néon", "แย็บนีออน")
            "htr_punch_bass_smash" -> localText("低频重锤", "Bass Smash", "Frappe basse", "เบสสแมช")
            else -> null
        }

    private fun localizedCloudSoundEffectDescription(id: String): String? =
        when (id) {
            "htr_punch_arena_thunder" ->
                localText(
                    "高能拳击馆重击音，适合约 100 BPM 训练。",
                    "Arena impact sound for about 100 BPM training.",
                    "Impact de ring pour un entraînement autour de 100 BPM.",
                    "เสียงกระแทกสนามสำหรับการฝึกราว 100 BPM",
                )
            "htr_punch_street_spark" ->
                localText(
                    "清脆街头打击音，适合约 110 BPM 训练。",
                    "Crisp street impact for about 110 BPM training.",
                    "Impact urbain net pour un entraînement autour de 110 BPM.",
                    "เสียงกระแทกสตรีทชัดเจนสำหรับราว 110 BPM",
                )
            "htr_punch_iron_hook" ->
                localText(
                    "金属质感冲击音，适合约 95 BPM 稳定训练。",
                    "Metal impact sound for steady training around 95 BPM.",
                    "Impact métallique pour un rythme stable autour de 95 BPM.",
                    "เสียงโลหะหนักสำหรับจังหวะมั่นคงราว 95 BPM",
                )
            "htr_punch_neon_jab" ->
                localText(
                    "轻快快速击打音，适合约 120 BPM 连击训练。",
                    "Fast bright impact for combo drills around 120 BPM.",
                    "Impact vif pour les combos autour de 120 BPM.",
                    "เสียงเร็วสดใสสำหรับคอมโบราว 120 BPM",
                )
            "htr_punch_bass_smash" ->
                localText(
                    "低频重拳音效，适合约 90 BPM 力量训练。",
                    "Bass-heavy impact for power work around 90 BPM.",
                    "Impact grave pour le travail de puissance autour de 90 BPM.",
                    "เสียงเบสหนักสำหรับฝึกพลังราว 90 BPM",
                )
            else -> null
        }

    private fun bundledCloudSoundEffects(): List<CloudSoundEffect> =
        runCatching {
            assets.open("sfx/manifest.json").bufferedReader(Charsets.UTF_8).use { reader ->
                val json = JSONObject(reader.readText())
                val items = json.optJSONArray("items") ?: JSONArray()
                buildList {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val file = item.optString("file")
                        val id = item.optString("id")
                        if (id.isBlank() || file.isBlank()) {
                            continue
                        }
                        add(
                            CloudSoundEffect(
                                id = id,
                                nameZh = item.optString("name_zh").ifBlank { item.optString("name_en") },
                                nameEn = item.optString("name_en").ifBlank { item.optString("name_zh") },
                                descriptionZh = item.optString("description_zh").ifBlank { item.optString("description_en") },
                                descriptionEn = item.optString("description_en").ifBlank { item.optString("description_zh") },
                                style = item.optString("style"),
                                bpm = item.optInt("bpm"),
                                durationMs = item.optInt("duration_ms"),
                                url = "asset://sfx/$file",
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())

    private fun applyCloudSoundEffectSelection(effect: CloudSoundEffect) {
        selectedCloudSoundEffectId = effect.id
        selectedCloudSoundEffectName = cloudSoundEffectName(effect)
        selectedCloudSoundEffectUrl = effect.url
        prefs.edit()
            .putString(KEY_CLOUD_SOUND_EFFECT_ID, selectedCloudSoundEffectId)
            .putString(KEY_CLOUD_SOUND_EFFECT_NAME, selectedCloudSoundEffectName)
            .putString(KEY_CLOUD_SOUND_EFFECT_URL, selectedCloudSoundEffectUrl)
            .apply()
        prepareCloudSoundEffect(effect, playWhenReady = false)
    }

    private fun renderBackgroundMusicSettings(
        container: LinearLayout,
        pendingSelectedId: String,
        onSelect: (CloudBackgroundMusic) -> Unit,
        onPreview: (CloudBackgroundMusic) -> Unit,
    ) {
        container.removeAllViews()
        val options = backgroundMusicOptions()
        val effectiveSelectedId = pendingSelectedId.ifBlank { BACKGROUND_MUSIC_NONE_ID }
        cloudBackgroundMusicMessage?.takeIf { it.isNotBlank() }?.let { message ->
            container.addView(
                bodyText(message).apply {
                    setTextColor(Color.parseColor(selectedPalette.accentHot))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(6), 0, dp(8))
                },
            )
        }
        if (cloudBackgroundMusic.isEmpty()) {
            container.addView(
                bodyText(
                    localText(
                        "正在等待云端背景音乐列表。",
                        "Waiting for the cloud background music list.",
                        "En attente de la musique cloud.",
                        "กำลังรอรายการเพลงพื้นหลังบนคลาวด์",
                    ),
                ).apply {
                    setTextColor(Color.parseColor(selectedPalette.textMuted))
                    setPadding(0, dp(4), 0, dp(10))
                },
            )
        }
        val rows =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
        }
        options.forEach { track ->
            val isSelected = track.id == effectiveSelectedId
            val isNoMusic = isNoBackgroundMusic(track)
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background =
                        roundedBackground(
                            if (isSelected) selectedPalette.cardAlt else selectedPalette.surfaceBottom,
                            if (isSelected) selectedPalette.success else selectedPalette.stroke,
                            14,
                        )
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    minimumHeight = dp(CLOUD_AUDIO_ROW_HEIGHT_DP - 10)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = dp(7)
                        }
                    setOnClickListener { onSelect(track) }
                }
            row.addView(
                RadioButton(this).apply {
                    isChecked = isSelected
                    setOnClickListener { onSelect(track) }
                },
            )
            row.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(
                        bodyText(backgroundMusicName(track)).apply {
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextColor(Color.parseColor(selectedPalette.textPrimary))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        },
                    )
                    addView(
                        bodyText(backgroundMusicDescription(track)).apply {
                            setTextColor(Color.parseColor(selectedPalette.textMuted))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                            setPadding(0, dp(2), 0, 0)
                        },
                    )
                },
            )
            row.addView(
                compactActionButton(
                    if (isNoMusic) localText("无", "Off", "Sans", "ปิด") else localText("试听", "Preview", "Écouter", "ลองฟัง"),
                    if (isNoMusic) selectedPalette.cardAlt else selectedPalette.button,
                ).apply {
                    isEnabled = !isNoMusic
                    alpha = if (isNoMusic) 0.58f else 1f
                    if (!isNoMusic) {
                        setOnClickListener { onPreview(track) }
                    }
                },
            )
            rows.addView(row)
        }
        container.addView(limitedCloudAudioListHost(options.size).apply { addView(rows) })
    }

    private fun fetchBackgroundMusic(onDone: (() -> Unit)? = null) {
        if (cloudBackgroundMusicLoadingJob?.isActive == true) {
            return
        }
        cloudBackgroundMusicMessage = localText("正在拉取云端背景音乐...", "Fetching background music...", "Chargement de la musique cloud...", "กำลังโหลดเพลงพื้นหลัง...")
        cloudBackgroundMusicLoadingJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val result = cloudSyncService.fetchBackgroundMusic()
                withContext(Dispatchers.Main) {
                    cloudBackgroundMusicLoadingJob = null
                    if (result.success) {
                        cloudBackgroundMusic = result.items.ifEmpty { bundledBackgroundMusic() }
                        cloudBackgroundMusicMessage =
                            localText(
                                "已加载 ${cloudBackgroundMusic.size} 首背景音乐。",
                                "Loaded ${cloudBackgroundMusic.size} background tracks.",
                                "${cloudBackgroundMusic.size} musiques chargées.",
                                "โหลดเพลงพื้นหลังแล้ว ${cloudBackgroundMusic.size} รายการ",
                            )
                    } else {
                        cloudBackgroundMusic = bundledBackgroundMusic()
                        cloudBackgroundMusicMessage =
                            if (cloudBackgroundMusic.isNotEmpty()) {
                                localText(
                                    "云端暂不可达，已使用内置 5 首背景音乐。",
                                    "Cloud unavailable. Using 5 bundled tracks.",
                                    "Cloud indisponible. 5 musiques intégrées utilisées.",
                                    "คลาวด์ไม่พร้อม ใช้เพลงในเครื่อง 5 รายการ",
                                )
                            } else {
                                localText(
                                    "背景音乐加载失败：${result.message}",
                                    "Background music failed: ${result.message}",
                                    "Échec musique : ${result.message}",
                                    "โหลดเพลงไม่สำเร็จ: ${result.message}",
                                )
                            }
                    }
                    ensureBackgroundMusicSelection()
                    onDone?.invoke()
                }
            }
    }

    private fun backgroundMusicName(track: CloudBackgroundMusic): String =
        localizedBackgroundMusicName(track.id)
            ?: when (selectedLanguage) {
                AppLanguage.Chinese -> track.nameZh.ifBlank { track.nameEn }
                AppLanguage.English,
                AppLanguage.French,
                AppLanguage.Thai,
                -> track.nameEn.ifBlank { track.nameZh }
            }

    private fun backgroundMusicDescription(track: CloudBackgroundMusic): String =
        localizedBackgroundMusicDescription(track.id)
            ?: when (selectedLanguage) {
                AppLanguage.Chinese -> track.descriptionZh.ifBlank { track.descriptionEn }
                AppLanguage.English,
                AppLanguage.French,
                AppLanguage.Thai,
                -> track.descriptionEn.ifBlank { track.descriptionZh }
            }

    private fun localizedBackgroundMusicName(id: String): String? =
        when (id) {
            BACKGROUND_MUSIC_NONE_ID -> localText("无背景音乐", "No background music", "Sans musique", "ไม่มีเพลงพื้นหลัง")
            "htr_music_champion_rush" -> localText("冠军冲刺", "Champion Rush", "Sprint champion", "แชมป์รัช")
            "htr_music_ring_voltage" -> localText("擂台电光", "Ring Voltage", "Voltage du ring", "ริงโวลเทจ")
            "htr_music_street_ignite" -> localText("街区开场", "Street Ignite", "Allumage urbain", "สตรีทอิกไนต์")
            "htr_music_iron_drive" -> localText("钢铁律动", "Iron Drive", "Élan d'acier", "ไอรอนไดรฟ์")
            "htr_music_neon_combo" -> localText("霓虹连击", "Neon Combo", "Combo néon", "นีออนคอมโบ")
            else -> null
        }

    private fun localizedBackgroundMusicDescription(id: String): String? =
        when (id) {
            BACKGROUND_MUSIC_NONE_ID ->
                localText(
                    "训练时不播放背景音乐，只保留节拍和击打音效。",
                    "No background music during training. Beat and punch sounds remain available.",
                    "Aucune musique pendant l'entraînement. Le tempo et les impacts restent actifs.",
                    "ไม่เล่นเพลงพื้นหลังระหว่างฝึก ยังมีจังหวะและเสียงหมัด",
                )
            "htr_music_champion_rush" ->
                localText(
                    "明亮电子旋律，适合热身后提速。约 122 BPM。",
                    "Bright electro melody for energetic warm-up pushes. Around 122 BPM.",
                    "Mélodie électro lumineuse pour accélérer après l'échauffement. Environ 122 BPM.",
                    "ทำนองอิเล็กทรอนิกส์สดใส เหมาะเร่งหลังวอร์ม ราว 122 BPM",
                )
            "htr_music_ring_voltage" ->
                localText(
                    "流行运动律动，轻鼓点带动出拳节奏。约 118 BPM。",
                    "Pop fitness groove with light drums and clean motion. Around 118 BPM.",
                    "Groove fitness pop avec batterie légère. Environ 118 BPM.",
                    "กรูฟป๊อปฟิตเนสพร้อมกลองเบา ราว 118 BPM",
                )
            "htr_music_street_ignite" ->
                localText(
                    "轻摇滚律动，吉他式分解和轻快鼓组。约 112 BPM。",
                    "Light rock rhythm with guitar-like strums and easy drums. Around 112 BPM.",
                    "Rythme rock léger avec accords façon guitare. Environ 112 BPM.",
                    "จังหวะไลท์ร็อกพร้อมกีตาร์และกลองสบาย ราว 112 BPM",
                )
            "htr_music_iron_drive" ->
                localText(
                    "流行摇滚推进感，有力但不刺耳。约 116 BPM。",
                    "Pop-rock drive that feels athletic without heavy impact. Around 116 BPM.",
                    "Élan pop-rock sportif, puissant sans être agressif. Environ 116 BPM.",
                    "ป๊อปร็อกขับเคลื่อน ฟังสนุกไม่หนักเกิน ราว 116 BPM",
                )
            "htr_music_neon_combo" ->
                localText(
                    "跳动合成器和清亮音色，适合连击练习。约 124 BPM。",
                    "Bouncy synth colors for combo drills. Around 124 BPM.",
                    "Synthés rebondissants pour les exercices de combo. Environ 124 BPM.",
                    "ซินธ์เด้งสดใส เหมาะฝึกคอมโบ ราว 124 BPM",
                )
            else -> null
        }

    private fun noBackgroundMusicTrack(): CloudBackgroundMusic =
        CloudBackgroundMusic(
            id = BACKGROUND_MUSIC_NONE_ID,
            nameZh = "无背景音乐",
            nameEn = "No background music",
            descriptionZh = "训练时不播放背景音乐，只保留节拍和击打音效。",
            descriptionEn = "No background music during training. Beat and punch sounds remain available.",
            style = "none",
            bpm = 0,
            durationMs = 0,
            url = "",
        )

    private fun isNoBackgroundMusic(track: CloudBackgroundMusic): Boolean =
        track.id == BACKGROUND_MUSIC_NONE_ID

    private fun backgroundMusicOptions(): List<CloudBackgroundMusic> =
        listOf(noBackgroundMusicTrack()) + cloudBackgroundMusic.filterNot(::isNoBackgroundMusic)

    private fun findBackgroundMusicOption(id: String): CloudBackgroundMusic? =
        backgroundMusicOptions().firstOrNull { it.id == id.ifBlank { BACKGROUND_MUSIC_NONE_ID } }

    private fun bundledBackgroundMusic(): List<CloudBackgroundMusic> =
        runCatching {
            assets.open("music/manifest.json").bufferedReader(Charsets.UTF_8).use { reader ->
                val json = JSONObject(reader.readText())
                val items = json.optJSONArray("items") ?: JSONArray()
                buildList {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val file = item.optString("file")
                        val id = item.optString("id")
                        if (id.isBlank() || file.isBlank()) {
                            continue
                        }
                        add(
                            CloudBackgroundMusic(
                                id = id,
                                nameZh = item.optString("name_zh").ifBlank { item.optString("name_en") },
                                nameEn = item.optString("name_en").ifBlank { item.optString("name_zh") },
                                descriptionZh = item.optString("description_zh").ifBlank { item.optString("description_en") },
                                descriptionEn = item.optString("description_en").ifBlank { item.optString("description_zh") },
                                style = item.optString("style"),
                                bpm = item.optInt("bpm"),
                                durationMs = item.optInt("duration_ms"),
                                url = "asset://music/$file",
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())

    private fun ensureBackgroundMusicSelection() {
        if (selectedBackgroundMusicId.isBlank() || selectedBackgroundMusicId == BACKGROUND_MUSIC_NONE_ID) {
            applyNoBackgroundMusicSelection()
            return
        }
        val selected = cloudBackgroundMusic.firstOrNull { it.id == selectedBackgroundMusicId }
        when {
            selected != null && selectedBackgroundMusicUrl != selected.url -> applyBackgroundMusicSelection(selected)
            selected == null -> applyNoBackgroundMusicSelection()
        }
    }

    private fun applyBackgroundMusicSelection(track: CloudBackgroundMusic) {
        if (isNoBackgroundMusic(track)) {
            applyNoBackgroundMusicSelection()
            return
        }
        selectedBackgroundMusicId = track.id
        selectedBackgroundMusicName = backgroundMusicName(track)
        selectedBackgroundMusicUrl = track.url
        prefs.edit()
            .putString(KEY_BACKGROUND_MUSIC_ID, selectedBackgroundMusicId)
            .putString(KEY_BACKGROUND_MUSIC_NAME, selectedBackgroundMusicName)
            .putString(KEY_BACKGROUND_MUSIC_URL, selectedBackgroundMusicUrl)
            .apply()
    }

    private fun applyNoBackgroundMusicSelection() {
        val track = noBackgroundMusicTrack()
        selectedBackgroundMusicId = track.id
        selectedBackgroundMusicName = backgroundMusicName(track)
        selectedBackgroundMusicUrl = ""
        prefs.edit()
            .putString(KEY_BACKGROUND_MUSIC_ID, selectedBackgroundMusicId)
            .putString(KEY_BACKGROUND_MUSIC_NAME, selectedBackgroundMusicName)
            .putString(KEY_BACKGROUND_MUSIC_URL, selectedBackgroundMusicUrl)
            .putBoolean(KEY_BACKGROUND_MUSIC_NONE_DEFAULT_APPLIED, true)
            .apply()
        stopBackgroundMusicPreview()
        stopTrainingBackgroundMusic()
    }

    private fun refreshLocalizedStoredAudioNames() {
        val selectedEffect =
            cloudSoundEffects.firstOrNull { it.id == selectedCloudSoundEffectId }
                ?: bundledCloudSoundEffects().firstOrNull { it.id == selectedCloudSoundEffectId }
        selectedEffect?.let {
            selectedCloudSoundEffectName = cloudSoundEffectName(it)
        }

        val selectedMusic =
            findBackgroundMusicOption(selectedBackgroundMusicId)
                ?: bundledBackgroundMusic().firstOrNull { it.id == selectedBackgroundMusicId }
                ?: noBackgroundMusicTrack().takeIf {
                    selectedBackgroundMusicId.isBlank() || selectedBackgroundMusicId == BACKGROUND_MUSIC_NONE_ID
                }
        selectedMusic?.let {
            selectedBackgroundMusicId = it.id
            selectedBackgroundMusicName = backgroundMusicName(it)
            selectedBackgroundMusicUrl = if (isNoBackgroundMusic(it)) "" else it.url
        }

        prefs.edit()
            .putString(KEY_CLOUD_SOUND_EFFECT_NAME, selectedCloudSoundEffectName)
            .putString(KEY_BACKGROUND_MUSIC_ID, selectedBackgroundMusicId)
            .putString(KEY_BACKGROUND_MUSIC_NAME, selectedBackgroundMusicName)
            .putString(KEY_BACKGROUND_MUSIC_URL, selectedBackgroundMusicUrl)
            .apply()
    }

    private fun rebuildLocalizedContent(refreshCloud: Boolean) {
        stopCloudEffectPreview()
        stopBackgroundMusicPreview()
        setContentView(buildContentView())
        if (::splashOverlay.isInitialized) {
            splashOverlay.visibility = View.GONE
        }
        if (isActivated()) {
            renderIdle()
            latestReport?.let(::renderReport) ?: renderEmptyReport()
            renderTrainingPlayStatus()
            refreshModeButtonStyles()
            refreshCloudViews()
            if (refreshCloud) {
                refreshCloudData(forceLeaderboard = false)
            }
        } else {
            renderActivationRequired()
        }
        updateHeaderBluetoothStatus()
    }

    private fun applyLanguageAndSensitivitySettings(
        language: AppLanguage,
        refreshCloud: Boolean,
    ) {
        val languageChanged = selectedLanguage != language
        selectedLanguage = language
        saveSettings()
        updateTtsLanguage()
        refreshLocalizedStoredAudioNames()
        clearLanguageSensitiveFallbackMessages()
        if (languageChanged && trainingJob?.isActive != true) {
            rebuildLocalizedContent(refreshCloud)
            return
        }
        refreshWaveformLocalizedLabels()
        if (isActivated()) {
            renderIdle()
            latestReport?.let(::renderReport) ?: renderEmptyReport()
            renderTrainingPlayStatus()
            refreshModeButtonStyles()
            refreshCloudViews()
            if (refreshCloud) {
                refreshCloudData(forceLeaderboard = false)
            }
        } else {
            renderActivationRequired()
        }
    }

    private fun clearLanguageSensitiveFallbackMessages() {
        if (cloudStatusMessageKey == null) {
            cloudStatusFallbackMessage = null
        }
        if (authStatusMessageKey == null) {
            authStatusFallbackMessage = null
        }
        lastCoachMessage = null
    }

    private fun profileHeroTagText(): String =
        localText("拳击训练档案", "FIGHTER PROFILE", "DOSSIER DU BOXEUR", "โปรไฟล์นักชก")

    private fun historyEmptyBadgeText(): String =
        localText("记录", "HISTORY", "HISTORIQUE", "ประวัติ")

    private fun historyEmptyTitleText(): String =
        localText(
            "训练记录尚未生成",
            "No training history yet",
            "Aucun historique pour le moment",
            "ยังไม่มีประวัติการฝึก",
        )

    private fun updateEmptyStateCardText(
        card: LinearLayout,
        badge: String,
        title: String,
        message: String,
    ) {
        (card.getChildAt(0) as? TextView)?.text = badge
        (card.getChildAt(1) as? TextView)?.text = title
        (card.getChildAt(2) as? TextView)?.text = message
    }

    private fun applyStaticTexts() {
        titleView.text = tr("title")
        subtitleView.text = headerSubtitleText()
        subtitleView.visibility = if (headerSubtitleText().isBlank()) View.GONE else View.VISIBLE
        modeTitleView.text = tr("mode")
        mode30Button.text = playModeLabel(TrainingPlayMode.Classic30)
        mode60Button.text = playModeLabel(TrainingPlayMode.Classic60)
        modeBurst10Button.text = playModeLabel(TrainingPlayMode.Burst10)
        modeBurst15Button.text = playModeLabel(TrainingPlayMode.Burst15)
        modeLevelButton.text = playModeLabel(TrainingPlayMode.LevelChallenge)
        modeDailyButton.text = playModeLabel(TrainingPlayMode.DailyChallenge)
        when (selectedPlayMode) {
            TrainingPlayMode.Classic30 -> mode30Button.isChecked = true
            TrainingPlayMode.Classic60 -> mode60Button.isChecked = true
            TrainingPlayMode.Burst10 -> modeBurst10Button.isChecked = true
            TrainingPlayMode.Burst15 -> modeBurst15Button.isChecked = true
            TrainingPlayMode.LevelChallenge -> modeLevelButton.isChecked = true
            TrainingPlayMode.DailyChallenge -> modeDailyButton.isChecked = true
        }
        refreshModeButtonStyles()
        renderTrainingPlayStatus()
        refreshMusicImmersionControls()
        updateDashboardViews(currentRemainingMillis())
        startButton.text = tr("start")
        stopButton.text = tr("stop")
        pageTrainingButton.text = tr("page_training_center")
        pageAchievementsButton.text = tr("page_training_achievements")
        pageLeaderboardButton.text = tr("page_leaderboard")
        pageProfileButton.text = tr("page_profile")
        reportTitleView.text = localText("回合训练战报", "Round Training Report", "Rapport par round", "รายงานรายรอบ")
        profileTitleView.text = tr("profile_title")
        profileSubtitleView.text = profilePageSubtitle()
        profileSubtitleView.visibility = View.VISIBLE
        if (::profileHeroTagView.isInitialized) {
            profileHeroTagView.text = profileHeroTagText()
        }
        achievementsTitleView.text = achievementsTitleText()
        achievementsSubtitleView.text = achievementsSectionHint()
        achievementsSubtitleView.visibility = View.VISIBLE
        historyTitleView.text = tr("history_title")
        historySubtitleView.text = historySectionSubtitle()
        historySubtitleView.visibility = View.VISIBLE
        if (::historyEmptyView.isInitialized) {
            updateEmptyStateCardText(
                historyEmptyView,
                historyEmptyBadgeText(),
                historyEmptyTitleText(),
                tr("no_history"),
            )
        }
        leaderboardTitleView.text = tr("leaderboard_title")
        leaderboardSubtitleView.text = leaderboardBoardSubtitle(leaderboardBoard)
        leaderboardSubtitleView.visibility = View.VISIBLE
        leaderboardDurationButton.text = leaderboardBoardLabel(LeaderboardBoard.TrainingDuration)
        leaderboardTotalHitsButton.text = leaderboardBoardLabel(LeaderboardBoard.TotalHits)
        leaderboardPeakForceButton.text = leaderboardBoardLabel(LeaderboardBoard.PeakForce)
        leaderboardAvgForceButton.text = leaderboardBoardLabel(LeaderboardBoard.AvgForce)
        leaderboardCaloriesButton.text = leaderboardBoardLabel(LeaderboardBoard.Calories)
        leaderboardFatButton.text = leaderboardBoardLabel(LeaderboardBoard.FatBurned)
        when (leaderboardBoard) {
            LeaderboardBoard.TrainingDuration -> leaderboardDurationButton.isChecked = true
            LeaderboardBoard.TotalHits -> leaderboardTotalHitsButton.isChecked = true
            LeaderboardBoard.PeakForce -> leaderboardPeakForceButton.isChecked = true
            LeaderboardBoard.AvgForce -> leaderboardAvgForceButton.isChecked = true
            LeaderboardBoard.Calories -> leaderboardCaloriesButton.isChecked = true
            LeaderboardBoard.FatBurned -> leaderboardFatButton.isChecked = true
        }
        editProfileButton.text = tr("profile_edit")
        refreshCloudButton.text = tr("cloud_refresh")
        developerInfoButton.text = developerInfoButtonLabel()
        refreshLeaderboardButton.text = tr("leaderboard_refresh")
        shareTrainingButton.text = shareTrainingLabel()
        shareAchievementsButton.text = shareAchievementsLabel()
        shareLeaderboardButton.text = shareLeaderboardLabel()
        settingsButton.contentDescription = tr("settings")
        quietIconView.contentDescription = tr("keep_quiet")
        refreshActivationCardState()
        if (::serialInput.isInitialized && ::codeInput.isInitialized) {
            updateActivationInputState()
        }
        refreshHomePageVisibility()
        if (latestReport == null) {
            renderEmptyReport()
        }
        refreshCloudViews()
    }
































































    private fun shareTrainingLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "分享战报"
            AppLanguage.English -> "Share Report"
            AppLanguage.French -> "Partager"
            AppLanguage.Thai -> "แชร์"
        }

    private fun shareAchievementsLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "分享荣誉"
            AppLanguage.English -> "Share Honors"
            AppLanguage.French -> "Partager"
            AppLanguage.Thai -> "แชร์"
        }

    private fun shareLeaderboardLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "分享排名"
            AppLanguage.English -> "Share Rank"
            AppLanguage.French -> "Partager"
            AppLanguage.Thai -> "แชร์"
        }

    private fun currentTierShareLabel(): String =
        cloudTier?.let { tierLabelForKey(it.key) } ?: tierLabelForLevel(cloudProfile?.currentTier ?: 1)

    private fun posterRoot(
        accentColor: String,
        secondaryAccent: String = "#BDEFE6",
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(32), dp(42), dp(32), dp(42))
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor("#E9FFF9"),
                        Color.parseColor("#F7FFFD"),
                        Color.parseColor("#FFFFFF"),
                    ),
                ).apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(34).toFloat()
                    setStroke(dp(2), Color.parseColor(accentColor))
                }
            addView(
                TextView(this@MainActivity).apply {
                    text = "HITRISE"
                    setTextColor(Color.parseColor("#17343B"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    letterSpacing = 0.08f
                },
            )
            addView(
                View(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(1),
                        ).apply {
                            topMargin = dp(16)
                            bottomMargin = dp(20)
                        }
                    background = roundedBackground(secondaryAccent, secondaryAccent, 999)
                    alpha = 0.92f
                },
            )
        }

    private fun posterSectionCard(
        accentColor: String,
        fillColor: String = "#FFFFFF",
        strokeColor: String = accentColor,
    ): LinearLayout =
        detailCard(fillColor = fillColor, strokeColor = strokeColor, cornerDp = 26).apply {
            background = metallicBackground("#FFFFFF", fillColor, strokeColor, 26)
        }

    private fun posterMetricCard(
        label: String,
        value: String,
        accentColor: String,
    ): LinearLayout =
        detailCard(fillColor = "#F7FFFD", strokeColor = accentColor, cornerDp = 18).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(
                bodyText(label).apply {
                    setTextColor(Color.parseColor("#557A7D"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                },
            )
            addView(
                titleText(value, 19f).apply {
                    gravity = Gravity.START
                    setTextColor(Color.parseColor("#17343B"))
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }

    private fun posterIdentityCard(
        nickname: String,
        subline: String,
        accentColor: String,
    ): LinearLayout =
        posterSectionCard(accentColor = accentColor, fillColor = "#F3FFFC", strokeColor = "#CDEFE8").apply {
            val row =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            val avatarShell =
                FrameLayout(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                            rightMargin = dp(16)
                        }
                }
            val avatarImage =
                ImageView(this@MainActivity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                }
            val avatarFallback =
                TextView(this@MainActivity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                }
            bindAvatarPresentation(
                container = avatarShell,
                imageView = avatarImage,
                fallbackView = avatarFallback,
                seedText = nickname,
                colorHex = cloudProfile?.avatarColor ?: "#16C8B5",
                imageUri = currentAvatarImageUri(),
            )
            avatarShell.addView(avatarImage)
            avatarShell.addView(avatarFallback)

            val textColumn =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                }
            textColumn.addView(
                titleText(nickname, 20f).apply {
                    gravity = Gravity.START
                    setTextColor(Color.parseColor("#17343B"))
                },
            )
            textColumn.addView(
                bodyText(subline).apply {
                    setTextColor(Color.parseColor("#557A7D"))
                    setPadding(0, dp(6), 0, 0)
                },
            )
            row.addView(avatarShell)
            row.addView(textColumn)
            addView(row)
        }

    private fun renderPosterBitmap(root: View): Bitmap {
        val widthPx = 1080
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return Bitmap.createBitmap(root.measuredWidth, root.measuredHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            root.draw(canvas)
        }
    }

    private fun sharePosterBitmap(
        bitmap: Bitmap,
        filePrefix: String,
        chooserTitle: String,
        shareText: String,
    ) {
        val shareDir = File(cacheDir, "shared").apply { mkdirs() }
        val outputFile = File(shareDir, "${filePrefix}_${System.currentTimeMillis()}.png")
        FileOutputStream(outputFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", outputFile)
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, shareText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    private fun shareTextPlain(
        text: String,
        chooserTitle: String = shareTrainingLabel(),
    ) {
        if (text.isBlank()) {
            return
        }
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    private fun buildTrainingPosterBitmap(report: TrainingReport): Bitmap {
        val accentColor = "#16C8B5"
        val root = posterRoot(accentColor)
        root.addView(
            TextView(this).apply {
                text = localText("训练战报", "TRAINING REPORT", "RAPPORT", "รายงานการฝึก")
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = metallicBackground("#62E5D8", accentColor, "#DFFFF7", 999)
                setPadding(dp(14), dp(7), dp(14), dp(7))
            },
        )
        root.addView(
            posterSectionCard(accentColor = accentColor, fillColor = "#FFFFFF", strokeColor = "#BDEFE6").apply {
                val heroRow =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                    }
                val leftColumn =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                    }
                leftColumn.addView(
                    bodyText(localText("本次训练成绩", "SESSION RESULT", "RÉSULTAT", "ผลการฝึก")).apply {
                        setTextColor(Color.parseColor("#557A7D"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    },
                )
                leftColumn.addView(
                    titleText(localText("训练战报", "Training Report", "Rapport entraînement", "รายงานการฝึก"), 30f).apply {
                        gravity = Gravity.START
                        setTextColor(Color.parseColor("#17343B"))
                        setPadding(0, dp(10), 0, 0)
                    },
                )
                leftColumn.addView(
                    bodyText(trainingBattleReportSummary(report)).apply {
                        setTextColor(Color.parseColor("#096D65"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setLineSpacing(dp(2).toFloat(), 1.0f)
                        setPadding(0, dp(8), 0, 0)
                    },
                )
                leftColumn.addView(
                    bodyText(formatReportEndedTime(report.endedAtEpochMs)).apply {
                        setTextColor(Color.parseColor("#7FA0A3"))
                        setPadding(0, dp(12), 0, 0)
                    },
                )
                val scoreOrb =
                    FrameLayout(this@MainActivity).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(dp(196), dp(196)).apply {
                                gravity = Gravity.CENTER_HORIZONTAL
                                topMargin = dp(18)
                            }
                        background = metallicBackground("#DFFFF7", "#F7FFFD", "#BDEFE6", 999)
                        addView(
                            FrameLayout(this@MainActivity).apply {
                                layoutParams =
                                    FrameLayout.LayoutParams(dp(166), dp(166), Gravity.CENTER)
                                background = metallicBackground("#35D8CB", "#16C8B5", "#E7FBFF", 999)
                                addView(
                                    LinearLayout(this@MainActivity).apply {
                                        orientation = LinearLayout.VERTICAL
                                        gravity = Gravity.CENTER
                                        layoutParams =
                                            FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                        )
                                        addView(
                                            bodyText(localText("拳数", "PUNCHES", "COUPS", "หมัด")).apply {
                                                gravity = Gravity.CENTER
                                                setTextColor(Color.parseColor("#EFFFFA"))
                                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                            },
                                        )
                                        addView(
                                            titleText(report.totalHits.toString(), 40f).apply {
                                                gravity = Gravity.CENTER
                                                setTextColor(Color.parseColor("#FFFFFF"))
                                                setPadding(0, dp(4), 0, 0)
                                            },
                                        )
                                        addView(
                                            bodyText(tr("hits")).apply {
                                                gravity = Gravity.CENTER
                                                setTextColor(Color.parseColor("#EFFFFA"))
                                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                heroRow.addView(leftColumn)
                heroRow.addView(scoreOrb)
                addView(heroRow)
                val statusRow =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.START
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(18) }
                    }
                statusRow.addView(
                    badgeText(
                        text = formatTrainingDuration(report.durationSeconds),
                        textColor = "#096D65",
                        fillColor = "#DFFFF7",
                    ).apply {
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    },
                )
                statusRow.addView(
                    badgeText(
                        text = localText("最大力度 ${forceDisplay(report.peakForceN)}", "Peak ${forceDisplay(report.peakForceN)}", "Max ${forceDisplay(report.peakForceN)}", "สูงสุด ${forceDisplay(report.peakForceN)}"),
                        textColor = "#FFFFFF",
                        fillColor = "#FF8A32",
                    ).apply {
                        (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(10)
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    },
                )
                addView(statusRow)
            }.apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) }
            },
        )
        val metricsRow1 =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(18) }
            }
        metricsRow1.addView(
            posterMetricCard(
                localText("累计锻炼时间", "Total duration", "Durée totale", "เวลารวม"),
                formatTrainingDuration(report.durationSeconds),
                "#16C8B5",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) }
            },
        )
        metricsRow1.addView(
            posterMetricCard(localText("累计击拳数", "Total punches", "Coups cumulés", "หมัดรวม"), "${report.totalHits} ${tr("hits")}", "#16C8B5").apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        root.addView(metricsRow1)
        val metricsRow2 =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
            }
        metricsRow2.addView(
            posterMetricCard(localText("最大力度", "Peak force", "Force max", "แรงสูงสุด"), forceDisplay(report.peakForceN), "#FF8A32").apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) }
            },
        )
        metricsRow2.addView(
            posterMetricCard(localText("平均力度", "Avg force", "Force moy.", "แรงเฉลี่ย"), forceDisplay(report.avgForceN), "#9BE5C4").apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        root.addView(metricsRow2)
        val burnPosterRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
            }
        burnPosterRow.addView(
            posterMetricCard(tr("calories_burned"), formatCalories(report.caloriesBurned), "#3BCE7A").apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) }
            },
        )
        burnPosterRow.addView(
            posterMetricCard(tr("fat_burned"), formatFatGrams(report.fatBurnedGrams), "#FFD060").apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        root.addView(burnPosterRow)
        root.addView(
            posterIdentityCard(
                nickname = cloudProfile?.nickname.orEmpty().ifBlank { "Fighter" },
                subline = localText("成果已同步到六项榜单", "Synced to six leaderboard metrics", "Synchronisé avec 6 classements", "ซิงก์กับอันดับ 6 รายการ"),
                accentColor = "#16C8B5",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(22) }
            },
        )
        return renderPosterBitmap(root)
    }

    private fun buildAchievementsPosterBitmap(): Bitmap {
        val recent = cloudAchievements.filter { it.unlocked }.sortedByDescending { it.unlockedAt.orEmpty() }.take(1).firstOrNull()
        val nextLocked = cloudAchievements.filterNot { it.unlocked }.sortedBy { it.sortOrder }.firstOrNull()
        val badgeName = recent?.let { achievementDisplayName(it.key) } ?: currentTierShareLabel()
        val badgeCode = recent?.let { achievementBadgeCode(it.key) } ?: "TIER"
        val unlockedCount = cloudAchievements.count { it.unlocked }
        val accentColor = "#16C8B5"
        val honorAccent = recent?.let { achievementAccentColor(it.key) } ?: "#FFD060"
        val root = posterRoot(accentColor, "#BDEFE6")
        root.addView(
            TextView(this).apply {
                text = localText("新徽章解锁", "NEW HONOR", "NOUVEL HONNEUR", "เกียรติยศใหม่")
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = metallicBackground("#62E5D8", accentColor, "#DFFFF7", 999)
                setPadding(dp(14), dp(7), dp(14), dp(7))
            },
        )
        root.addView(
            posterSectionCard(accentColor = accentColor, fillColor = "#FFFFFF", strokeColor = "#BDEFE6").apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(4) }
                        val ribbonRow =
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER
                            }
                        ribbonRow.addView(
                            View(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(40), dp(92)).apply { rightMargin = dp(14) }
                                background = metallicBackground("#DFFFF7", "#BDEFE6", accentColor, 16)
                            },
                        )
                        ribbonRow.addView(
                            FrameLayout(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(188), dp(188))
                                background = metallicBackground("#F7FFFD", "#EFFFFA", accentColor, 999)
                                addView(
                                    FrameLayout(this@MainActivity).apply {
                                        layoutParams =
                                            FrameLayout.LayoutParams(dp(152), dp(152), Gravity.CENTER)
                                        background = metallicBackground("#FFF8E6", honorAccent, "#FFFFFF", 999)
                                        addView(
                                            LinearLayout(this@MainActivity).apply {
                                                orientation = LinearLayout.VERTICAL
                                                gravity = Gravity.CENTER
                                                layoutParams =
                                                    FrameLayout.LayoutParams(
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                    )
                                                addView(
                                                    bodyText("BADGE").apply {
                                                        gravity = Gravity.CENTER
                                                        setTextColor(Color.parseColor("#096D65"))
                                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                                    },
                                                )
                                                addView(
                                                    titleText(badgeCode, 30f).apply {
                                                        gravity = Gravity.CENTER
                                                        setTextColor(Color.parseColor("#17343B"))
                                                        setPadding(0, dp(6), 0, 0)
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        ribbonRow.addView(
                            View(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(40), dp(92)).apply { leftMargin = dp(14) }
                                background = metallicBackground("#DFFFF7", "#BDEFE6", accentColor, 16)
                            },
                        )
                        addView(ribbonRow)
                        addView(
                            badgeText(
                                text = localText("荣誉馆珍藏", "HONOR VAULT", "GALERIE D'HONNEUR", "หอเกียรติยศ"),
                                textColor = "#096D65",
                                fillColor = "#DFFFF7",
                            ).apply {
                                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(16)
                                setPadding(dp(12), dp(6), dp(12), dp(6))
                            },
                        )
                    },
                )
                addView(
                    titleText(badgeName, 28f).apply {
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#17343B"))
                        setPadding(0, dp(18), 0, 0)
                    },
                )
                addView(
                    bodyText(localText("当前段位：${currentTierShareLabel()}", "Current tier: ${currentTierShareLabel()}", "Rang actuel : ${currentTierShareLabel()}", "ระดับปัจจุบัน: ${currentTierShareLabel()}")).apply {
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#557A7D"))
                        setPadding(0, dp(10), 0, 0)
                    },
                )
                addView(
                    bodyText(localText("已解锁徽章：$unlockedCount / ${cloudAchievements.size}", "Unlocked badges: $unlockedCount / ${cloudAchievements.size}", "Badges débloqués : $unlockedCount / ${cloudAchievements.size}", "เหรียญที่ปลดล็อก: $unlockedCount / ${cloudAchievements.size}")).apply {
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#096D65"))
                        setPadding(0, dp(8), 0, 0)
                    },
                )
                if (nextLocked != null) {
                    addView(
                        detailCard(fillColor = "#F7FFFD", strokeColor = "#CDEFE8", cornerDp = 18).apply {
                            background = roundedBackground("#F7FFFD", "#CDEFE8", 18)
                            setPadding(dp(16), dp(14), dp(16), dp(14))
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply { topMargin = dp(16) }
                            addView(
                                bodyText(localText("下一枚目标", "NEXT TARGET", "PROCHAIN OBJECTIF", "เป้าหมายถัดไป")).apply {
                                    setTextColor(Color.parseColor("#557A7D"))
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                },
                            )
                            addView(
                                titleText(achievementDisplayName(nextLocked.key), 18f).apply {
                                    gravity = Gravity.START
                                    setTextColor(Color.parseColor("#17343B"))
                                    setPadding(0, dp(8), 0, 0)
                                },
                            )
                            addView(
                                bodyText("${nextLocked.progress}/${nextLocked.goal}").apply {
                                    setTextColor(Color.parseColor("#096D65"))
                                    setPadding(0, dp(8), 0, 0)
                                },
                            )
                        },
                    )
                }
            }.apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
            },
        )
        root.addView(
            posterIdentityCard(
                nickname = cloudProfile?.nickname.orEmpty().ifBlank { "Fighter" },
                subline = localText("每一次训练都在积累成长", "Every session adds to your growth", "Chaque séance nourrit votre progression", "ทุกการฝึกช่วยเพิ่มพัฒนาการ"),
                accentColor = accentColor,
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(20) }
            },
        )
        return renderPosterBitmap(root)
    }

    private fun buildLeaderboardPosterBitmap(): Bitmap {
        val me = leaderboardResult?.me
        val topThree = leaderboardResult?.top?.take(3).orEmpty()
        val accentColor = "#16C8B5"
        val root = posterRoot(accentColor, "#BDEFE6")
        root.addView(
            TextView(this).apply {
                text = leaderboardBoardLabel(leaderboardBoard)
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = metallicBackground("#62E5D8", accentColor, "#DFFFF7", 999)
                setPadding(dp(14), dp(7), dp(14), dp(7))
            },
        )
        root.addView(
            posterSectionCard(accentColor = accentColor, fillColor = "#FFFFFF", strokeColor = "#BDEFE6").apply {
                val heroRow =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                val rankBlock =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams =
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply { rightMargin = dp(14) }
                    }
                rankBlock.addView(
                    bodyText(localText("当前排名", "CURRENT RANK", "RANG ACTUEL", "อันดับปัจจุบัน")).apply {
                        setTextColor(Color.parseColor("#557A7D"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    },
                )
                rankBlock.addView(
                    titleText(me?.let { "NO.${it.rank}" } ?: "NO.--", 42f).apply {
                        gravity = Gravity.START
                        setTextColor(Color.parseColor("#17343B"))
                        setPadding(0, dp(10), 0, 0)
                    },
                )
                rankBlock.addView(
                    bodyText(me?.let { leaderboardPrimaryValueText(it) } ?: localText("准备冲榜", "Ready to climb", "Prêt à monter", "พร้อมไต่อันดับ")).apply {
                        setTextColor(Color.parseColor("#096D65"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setPadding(0, dp(8), 0, 0)
                    },
                )
                rankBlock.addView(
                    bodyText(currentTierShareLabel()).apply {
                        setTextColor(Color.parseColor("#FF8A32"))
                        setPadding(0, dp(10), 0, 0)
                    },
                )
                val trophySeal =
                    FrameLayout(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(174), dp(174))
                        background = metallicBackground("#DFFFF7", "#16C8B5", "#E7FBFF", 999)
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                layoutParams =
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                addView(
                                    bodyText("RANK").apply {
                                        gravity = Gravity.CENTER
                                        setTextColor(Color.parseColor("#EFFFFA"))
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                    },
                                )
                                addView(
                                    titleText(me?.rank?.toString() ?: "--", 38f).apply {
                                        gravity = Gravity.CENTER
                                        setTextColor(Color.parseColor("#FFFFFF"))
                                        setPadding(0, dp(6), 0, 0)
                                    },
                                )
                                addView(
                                    bodyText(leaderboardBoardLabel(leaderboardBoard)).apply {
                                        gravity = Gravity.CENTER
                                        setTextColor(Color.parseColor("#EFFFFA"))
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                    },
                                )
                            },
                        )
                    }
                heroRow.addView(rankBlock)
                heroRow.addView(trophySeal)
                addView(heroRow)
            }.apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
            },
        )
        if (topThree.isNotEmpty()) {
            val podiumRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(18) }
                }
            topThree.forEachIndexed { index, entry ->
                val podiumAccent =
                    when (entry.rank) {
                        1 -> "#F2C14E"
                        2 -> "#16C8B5"
                        else -> "#FF8A32"
                    }
                podiumRow.addView(
                    detailCard(fillColor = "#F7FFFD", strokeColor = podiumAccent, cornerDp = 22).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply { if (index > 0) leftMargin = dp(10) }
                        background =
                            metallicBackground(
                                when (entry.rank) {
                                    1 -> "#FFF8E6"
                                    2 -> "#DFFFF7"
                                    else -> "#FFF0E2"
                                },
                                "#FFFFFF",
                                podiumAccent,
                                22,
                            )
                        minimumHeight =
                            when (entry.rank) {
                                1 -> dp(226)
                                2 -> dp(192)
                                else -> dp(178)
                            }
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(
                            badgeText("TOP ${entry.rank}", textColor = if (entry.rank == 2) "#096D65" else "#17343B", fillColor = podiumAccent).apply {
                                setPadding(dp(12), dp(6), dp(12), dp(6))
                            },
                        )
                        addView(
                            titleText(entry.nickname, if (entry.rank == 1) 22f else 18f).apply {
                                gravity = Gravity.CENTER
                                setPadding(0, dp(16), 0, 0)
                                setTextColor(Color.parseColor("#17343B"))
                            },
                        )
                        addView(
                            badgeText(tierLabelForKey(entry.tierKey), textColor = "#096D65", fillColor = "#DFFFF7").apply {
                                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
                                setPadding(dp(10), dp(5), dp(10), dp(5))
                            },
                        )
                        addView(
                            titleText(entry.bestHits.toString(), if (entry.rank == 1) 32f else 26f).apply {
                                gravity = Gravity.CENTER
                                setTextColor(Color.parseColor(podiumAccent))
                                setPadding(0, dp(14), 0, 0)
                            },
                        )
                        addView(
                            bodyText(leaderboardBoardLabel(leaderboardBoard)).apply {
                                gravity = Gravity.CENTER
                                setTextColor(Color.parseColor("#557A7D"))
                                setPadding(0, dp(4), 0, 0)
                            },
                        )
                        addView(
                            bodyText(leaderboardSecondaryValueText(entry)).apply {
                                gravity = Gravity.CENTER
                                setTextColor(Color.parseColor("#7FA0A3"))
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                setPadding(0, dp(10), 0, 0)
                            },
                        )
                    },
                )
            }
            root.addView(podiumRow)
        }
        root.addView(
            posterIdentityCard(
                nickname = cloudProfile?.nickname.orEmpty().ifBlank { "Fighter" },
                subline = localText("来挑战我的成绩", "Come challenge my score", "Venez défier mon score", "มาท้าคะแนนของฉัน"),
                accentColor = accentColor,
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(20) }
            },
        )
        return renderPosterBitmap(root)
    }

    private fun shareTrainingSummary() {
        val report = latestReport
        val shareText =
            if (report != null) {
                localText(
                    "我刚完成 ${roundReportBadgeText(report)} HitRise 训练战报：累计锻炼 ${formatTrainingDuration(report.durationSeconds)}，累计击打 ${report.totalHits} 次，最大力度 ${forceDisplay(report.peakForceN)}，平均力度 ${forceDisplay(report.avgForceN)}，消耗 ${formatCalories(report.caloriesBurned)}，等效燃脂约 ${formatFatGrams(report.fatBurnedGrams)}。",
                    "I just finished a HitRise ${roundReportBadgeText(report)} report: total ${formatTrainingDuration(report.durationSeconds)}, ${report.totalHits} punches, peak ${forceDisplay(report.peakForceN)}, avg ${forceDisplay(report.avgForceN)}, ${formatCalories(report.caloriesBurned)}, and ${formatFatGrams(report.fatBurnedGrams)} equivalent fat burn.",
                    "Rapport HitRise ${roundReportBadgeText(report)} terminé : total ${formatTrainingDuration(report.durationSeconds)}, ${report.totalHits} coups, max ${forceDisplay(report.peakForceN)}, moy. ${forceDisplay(report.avgForceN)}, ${formatCalories(report.caloriesBurned)}, ${formatFatGrams(report.fatBurnedGrams)} graisse équivalente.",
                    "รายงาน HitRise ${roundReportBadgeText(report)}: รวม ${formatTrainingDuration(report.durationSeconds)}, ${report.totalHits} หมัด, สูงสุด ${forceDisplay(report.peakForceN)}, เฉลี่ย ${forceDisplay(report.avgForceN)}, ${formatCalories(report.caloriesBurned)}, ไขมันเทียบเท่า ${formatFatGrams(report.fatBurnedGrams)}",
                )
            } else {
                localText(
                    "我的 HitRise 训练已经开始，完成后会生成训练战报。",
                    "My HitRise training is on. A training report will be generated after the session.",
                    "Mon entraînement HitRise a commencé. Un rapport sera généré après la séance.",
                    "เริ่มฝึก HitRise แล้ว รายงานจะถูกสร้างหลังจบการฝึก",
                )
            }
        if (report == null) {
            shareTextPlain(shareText, shareTrainingLabel())
            return
        }
        runCatching {
            sharePosterBitmap(
                bitmap = buildTrainingPosterBitmap(report),
                filePrefix = "training_report",
                chooserTitle = shareTrainingLabel(),
                shareText = shareText,
            )
        }.getOrElse {
            shareTextPlain(shareText, shareTrainingLabel())
        }
        markTrainingSharedForDailyTask()
    }

    private fun shareAchievementsSummary() {
        val recent = cloudAchievements.filter { it.unlocked }.sortedByDescending { it.unlockedAt.orEmpty() }.take(3)
        val shareText =
            if (recent.isNotEmpty()) {
                val names = recent.joinToString(", ") { achievementDisplayName(it.key) }
                localText(
                    "我已解锁 ${recent.size} 枚最新训练徽章：$names。当前段位：${currentTierShareLabel()}。",
                    "I unlocked ${recent.size} new training badges: $names. Current tier: ${currentTierShareLabel()}.",
                    "J'ai débloqué ${recent.size} nouveau(x) badge(s) : $names. Rang actuel : ${currentTierShareLabel()}.",
                    "ฉันปลดล็อกเหรียญใหม่ ${recent.size} เหรียญ: $names ระดับปัจจุบัน: ${currentTierShareLabel()}",
                )
            } else {
                localText(
                    "我正在 HitRise 训练中持续成长，下一枚徽章很快就会解锁。",
                    "I am progressing through HitRise training and my next badge is on the way.",
                    "Je progresse avec HitRise et mon prochain badge arrive bientôt.",
                    "ฉันกำลังก้าวหน้าในการฝึก HitRise เหรียญถัดไปกำลังมา",
                )
            }
        runCatching {
            sharePosterBitmap(
                bitmap = buildAchievementsPosterBitmap(),
                filePrefix = "training_honor",
                chooserTitle = shareAchievementsLabel(),
                shareText = shareText,
            )
        }.getOrElse {
            shareTextPlain(shareText, shareAchievementsLabel())
        }
    }

    private fun shareLeaderboardSummary() {
        val me = leaderboardResult?.me
        val shareText =
            if (me != null) {
                localText(
                    "我当前在${leaderboardBoardLabel(leaderboardBoard)}中排名 ${me.rank}，成绩 ${leaderboardPrimaryValueText(me)}。来挑战我的成绩。",
                    "I am ranked ${me.rank} on the ${leaderboardBoardLabel(leaderboardBoard)} with ${leaderboardPrimaryValueText(me)}. Come challenge my score.",
                    "Je suis ${me.rank}e du classement ${leaderboardBoardLabel(leaderboardBoard)} avec ${leaderboardPrimaryValueText(me)}. Venez me défier.",
                    "ฉันอยู่อันดับ ${me.rank} ใน${leaderboardBoardLabel(leaderboardBoard)} ด้วยคะแนน ${leaderboardPrimaryValueText(me)} มาท้าฉันได้เลย",
                )
            } else {
                localText(
                    "我正在冲击${leaderboardBoardLabel(leaderboardBoard)}，欢迎来挑战。",
                    "I am climbing the ${leaderboardBoardLabel(leaderboardBoard)}. Come challenge me.",
                    "Je vise le classement ${leaderboardBoardLabel(leaderboardBoard)}. Venez me défier.",
                    "ฉันกำลังไต่อันดับ${leaderboardBoardLabel(leaderboardBoard)} มาท้ากันได้",
                )
            }
        runCatching {
            sharePosterBitmap(
                bitmap = buildLeaderboardPosterBitmap(),
                filePrefix = "leaderboard_rank",
                chooserTitle = shareLeaderboardLabel(),
                shareText = shareText,
            )
        }.getOrElse {
            shareTextPlain(shareText, shareLeaderboardLabel())
        }
    }
    private fun displayCountdownStatus(value: Int): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "${value} 秒后开始..."
            AppLanguage.English -> "Starting in $value..."
            AppLanguage.French -> "Départ dans $value..."
            AppLanguage.Thai -> "เริ่มใน $value..."
        }

    private fun displayGoCue(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "开始"
            AppLanguage.English -> "Go"
            AppLanguage.French -> "Go"
            AppLanguage.Thai -> "ไป"
        }

    private fun displayGoLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "开始"
            AppLanguage.English -> "GO"
            AppLanguage.French -> "GO"
            AppLanguage.Thai -> "ไป"
        }

    private fun displayModeLabel(mode: TrainingMode): String =
        when (selectedLanguage) {
            AppLanguage.Chinese ->
                when (mode) {
                    TrainingMode.Seconds30 -> "30 秒"
                    TrainingMode.Seconds60 -> "60 秒"
                    TrainingMode.Burst10 -> "10 秒爆发"
                    TrainingMode.Burst15 -> "15 秒爆发"
                }

            AppLanguage.English ->
                when (mode) {
                    TrainingMode.Seconds30 -> "30 sec"
                    TrainingMode.Seconds60 -> "60 sec"
                    TrainingMode.Burst10 -> "10 sec burst"
                    TrainingMode.Burst15 -> "15 sec burst"
                }

            AppLanguage.French ->
                when (mode) {
                    TrainingMode.Seconds30 -> "30 s"
                    TrainingMode.Seconds60 -> "60 s"
                    TrainingMode.Burst10 -> "Explosif 10 s"
                    TrainingMode.Burst15 -> "Explosif 15 s"
                }

            AppLanguage.Thai ->
                when (mode) {
                    TrainingMode.Seconds30 -> "30 วินาที"
                    TrainingMode.Seconds60 -> "60 วินาที"
                    TrainingMode.Burst10 -> "ระเบิด 10 วิ"
                    TrainingMode.Burst15 -> "ระเบิด 15 วิ"
                }
        }

    private fun displayRemaining(remainingMillis: Long): String {
        val seconds = remainingMillis.coerceAtLeast(0L) / 100L / 10.0f
        return when (selectedLanguage) {
            AppLanguage.Chinese -> String.format(Locale.US, "剩余 %.1f 秒", seconds)
            AppLanguage.English -> String.format(Locale.US, "%.1fs left", seconds)
            AppLanguage.French -> String.format(Locale.US, "%.1fs restantes", seconds)
            AppLanguage.Thai -> String.format(Locale.US, "เหลือ %.1f วินาที", seconds)
        }
    }

    private fun formatDurationClock(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L) + 999L) / 1_000L
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun loadSettings() {
        selectedLanguage = AppLanguage.fromStorage(prefs.getString(KEY_LANGUAGE, defaultLanguage().storageValue))
        selectedPalette = HitRisePalettes.byId(prefs.getString(KEY_COLOR_PALETTE, HitRisePalettes.DEFAULT_ID))
        selectedPlayMode =
            runCatching {
                TrainingPlayMode.valueOf(prefs.getString(KEY_SELECTED_PLAY_MODE, TrainingPlayMode.Classic30.name).orEmpty())
            }.getOrDefault(TrainingPlayMode.Classic30)
        selectedRhythmMode =
            runCatching {
                TrainingRhythmMode.valueOf(prefs.getString(KEY_RHYTHM_MODE, TrainingRhythmMode.Rhythm.name).orEmpty())
            }.getOrDefault(TrainingRhythmMode.Rhythm)
        if (!prefs.getBoolean(KEY_RHYTHM_MODE_ENABLED_ONCE, false)) {
            selectedRhythmMode = TrainingRhythmMode.Rhythm
            prefs.edit()
                .putString(KEY_RHYTHM_MODE, TrainingRhythmMode.Rhythm.name)
                .putBoolean(KEY_RHYTHM_MODE_ENABLED_ONCE, true)
                .apply()
        }
        selectedBeatBpm = prefs.getInt(KEY_BEAT_BPM, 80).coerceIn(40, 140)
        selectedSoundPack =
            runCatching {
                SoundPack.valueOf(prefs.getString(KEY_SOUND_PACK, SoundPack.Gym.name).orEmpty())
            }.getOrDefault(SoundPack.Gym)
        selectedCloudSoundEffectId = prefs.getString(KEY_CLOUD_SOUND_EFFECT_ID, "").orEmpty()
        selectedCloudSoundEffectName = prefs.getString(KEY_CLOUD_SOUND_EFFECT_NAME, "").orEmpty()
        selectedCloudSoundEffectUrl = prefs.getString(KEY_CLOUD_SOUND_EFFECT_URL, "").orEmpty()
        applyNoBackgroundMusicSelection()
        if (!prefs.getBoolean(KEY_IMMERSIVE_AUDIO_ENABLED_ONCE, false)) {
            selectedRhythmMode = TrainingRhythmMode.Rhythm
            selectedBeatBpm = 100
            selectedSoundPack = SoundPack.Street
            prefs.edit()
                .putString(KEY_RHYTHM_MODE, TrainingRhythmMode.Rhythm.name)
                .putInt(KEY_BEAT_BPM, selectedBeatBpm)
                .putString(KEY_SOUND_PACK, selectedSoundPack.name)
                .putBoolean(KEY_IMMERSIVE_AUDIO_ENABLED_ONCE, true)
                .apply()
        }
        val storedTrainingWorkMinutes = prefs.getInt(KEY_TRAINING_SETUP_WORK_MINUTES, TrainingSessionSetup().workMinutes).coerceIn(1, 10)
        val storedTrainingRestHalfMinutes = prefs.getInt(KEY_TRAINING_SETUP_REST_HALF_MINUTES, TrainingSessionSetup().restHalfMinutes).coerceIn(0, 10)
        val storedTrainingRounds = prefs.getInt(KEY_TRAINING_SETUP_ROUNDS, TrainingSessionSetup().rounds).coerceIn(1, 10)
        trainingSessionSetup =
            TrainingSessionSetup(
                workMinutes = storedTrainingWorkMinutes,
                restHalfMinutes = storedTrainingRestHalfMinutes,
                rounds = storedTrainingRounds,
                rhythmMode =
                    runCatching {
                        TrainingRhythmMode.valueOf(prefs.getString(KEY_TRAINING_SETUP_RHYTHM_MODE, selectedRhythmMode.name).orEmpty())
                    }.getOrDefault(selectedRhythmMode),
                bpm = prefs.getInt(KEY_TRAINING_SETUP_BPM, 80).coerceIn(40, 140),
            )
        if (!prefs.getBoolean(KEY_TRAINING_SETUP_BEGINNER_DEFAULT_APPLIED, false)) {
            val hasStoredTrainingSetup =
                prefs.contains(KEY_TRAINING_SETUP_WORK_MINUTES) ||
                    prefs.contains(KEY_TRAINING_SETUP_REST_HALF_MINUTES) ||
                    prefs.contains(KEY_TRAINING_SETUP_ROUNDS)
            val looksLikeOldDefault =
                trainingSessionSetup.workMinutes == 2 &&
                    trainingSessionSetup.restHalfMinutes == 2 &&
                    trainingSessionSetup.rounds == 3
            if (!hasStoredTrainingSetup || looksLikeOldDefault) {
                trainingSessionSetup = TrainingSessionSetup()
                prefs.edit()
                    .putInt(KEY_TRAINING_SETUP_WORK_MINUTES, trainingSessionSetup.workMinutes)
                    .putInt(KEY_TRAINING_SETUP_REST_HALF_MINUTES, trainingSessionSetup.restHalfMinutes)
                    .putInt(KEY_TRAINING_SETUP_ROUNDS, trainingSessionSetup.rounds)
                    .putString(KEY_TRAINING_SETUP_RHYTHM_MODE, trainingSessionSetup.rhythmMode.name)
                    .putInt(KEY_TRAINING_SETUP_BPM, trainingSessionSetup.bpm)
                    .putBoolean(KEY_TRAINING_SETUP_BEGINNER_DEFAULT_APPLIED, true)
                    .apply()
            } else {
                prefs.edit()
                    .putBoolean(KEY_TRAINING_SETUP_BEGINNER_DEFAULT_APPLIED, true)
                    .apply()
            }
        }
        if (!prefs.getBoolean(KEY_TRAINING_SETUP_BEGINNER_20260522_APPLIED, false)) {
            trainingSessionSetup = TrainingSessionSetup()
            prefs.edit()
                .putInt(KEY_TRAINING_SETUP_WORK_MINUTES, trainingSessionSetup.workMinutes)
                .putInt(KEY_TRAINING_SETUP_REST_HALF_MINUTES, trainingSessionSetup.restHalfMinutes)
                .putInt(KEY_TRAINING_SETUP_ROUNDS, trainingSessionSetup.rounds)
                .putString(KEY_TRAINING_SETUP_RHYTHM_MODE, trainingSessionSetup.rhythmMode.name)
                .putInt(KEY_TRAINING_SETUP_BPM, trainingSessionSetup.bpm)
                .putBoolean(KEY_TRAINING_SETUP_BEGINNER_DEFAULT_APPLIED, true)
                .putBoolean(KEY_TRAINING_SETUP_BEGINNER_20260522_APPLIED, true)
                .apply()
        }
        selectedRhythmMode = trainingSessionSetup.rhythmMode
        selectedBeatBpm = trainingSessionSetup.bpm
        currentTrainingRound = 1
        currentTrainingRoundCount = trainingSessionSetup.rounds
        currentRoundDurationMs = trainingSessionSetup.workSeconds * 1_000L
        currentRoundRemainingMs = currentRoundDurationMs
        selectedMode = modeForPlayMode(selectedPlayMode)
        refreshLocalizedStoredAudioNames()
    }

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_LANGUAGE, selectedLanguage.storageValue)
            .putString(KEY_COLOR_PALETTE, selectedPalette.id)
            .apply()
    }

    private fun initTextToSpeech() {
        tts =
            TextToSpeech(applicationContext) { status ->
                val speaker = tts
                ttsInitialized = true
                if (status != TextToSpeech.SUCCESS || speaker == null) {
                    ttsReady = false
                    ttsLocaleInUse = null
                } else {
                    ttsReady = true
                    updateTtsLanguage()
                    speaker.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    speaker.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit

                            override fun onDone(utteranceId: String?) {
                                completeTtsCue(utteranceId)
                            }

                            @Deprecated("Deprecated in Android framework")
                            override fun onError(utteranceId: String?) {
                                completeTtsCue(utteranceId)
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                completeTtsCue(utteranceId)
                            }
                        },
                    )
                    speaker.setSpeechRate(1.0f)
                }
            }
    }

    private fun preferredTtsLocales(): LinkedHashSet<Locale> =
        when (selectedLanguage) {
            AppLanguage.Chinese ->
                linkedSetOf(
                    Locale.CHINA,
                    Locale.SIMPLIFIED_CHINESE,
                    Locale.CHINESE,
                    Locale.US,
                    Locale.ENGLISH,
                )

            AppLanguage.English ->
                linkedSetOf(
                    Locale.US,
                    Locale.UK,
                    Locale.ENGLISH,
                )

            AppLanguage.French ->
                linkedSetOf(
                    Locale.FRANCE,
                    Locale.FRENCH,
                    Locale.CANADA_FRENCH,
                    Locale.US,
                    Locale.ENGLISH,
                )

            AppLanguage.Thai ->
                linkedSetOf(
                    Locale("th", "TH"),
                    Locale("th"),
                    Locale.US,
                    Locale.ENGLISH,
                )
        }

    private fun selectedSpeechLanguageCode(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> Locale.CHINESE.language
            AppLanguage.English -> Locale.ENGLISH.language
            AppLanguage.French -> Locale.FRENCH.language
            AppLanguage.Thai -> "th"
        }

    private fun usesEnglishSpeechFallback(): Boolean {
        val currentLanguage = ttsLocaleInUse?.language ?: return false
        return selectedLanguage != AppLanguage.English &&
            !currentLanguage.equals(selectedSpeechLanguageCode(), ignoreCase = true) &&
            currentLanguage.equals(Locale.ENGLISH.language, ignoreCase = true)
    }

    private fun updateTtsLanguage() {
        if (!ttsInitialized) {
            return
        }
        val speaker = tts ?: return
        val preferredLocale = preferredTtsLocales().first()
        val cached = ttsLocaleInUse
        if (cached != null && cached.language == preferredLocale.language) {
            // Already serving the right language family — skip the slow setLanguage round-trip.
            return
        }
        val candidates = preferredTtsLocales()
        var appliedLocale: Locale? = null
        candidates.forEach { locale ->
            if (appliedLocale != null) {
                return@forEach
            }
            val result = speaker.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                appliedLocale = locale
            }
        }
        ttsLocaleInUse = appliedLocale
        ttsReady = appliedLocale != null
    }

    private fun speakCue(text: String, onDone: (() -> Unit)? = null): Boolean {
        updateTtsLanguage()
        if (!ttsReady) {
            onDone?.invoke()
            return false
        }
        val cueText = spokenCueText(text)
        if (cueText.isBlank()) {
            onDone?.invoke()
            return false
        }
        val utteranceId = "cue-${UUID.randomUUID()}"
        if (onDone != null) {
            ttsCompletionCallbacks[utteranceId] = onDone
        }
        val params =
            Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
            }
        val result = tts?.speak(cueText, TextToSpeech.QUEUE_FLUSH, params, utteranceId) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            ttsCompletionCallbacks.remove(utteranceId)
            onDone?.invoke()
            return false
        }
        return true
    }

    private fun speakAiCoachCue(text: String, attempt: Int = 0) {
        updateTtsLanguage()
        if (!ttsReady) {
            if (attempt < 3 && ::contentRootView.isInitialized) {
                contentRootView.postDelayed({ speakAiCoachCue(text, attempt + 1) }, 700L)
            }
            return
        }
        val cueText = if (usesEnglishSpeechFallback()) englishAiCoachFallbackText(text) else text
        val loweredVolume = if (trainingResting) 0.12f else 0.16f
        val restoreVolume = if (trainingResting) 0.34f else 0.42f
        runCatching { trainingBackgroundMusicPlayer?.setVolume(loweredVolume, loweredVolume) }
        val queued = speakCue(cueText) {
            val restore =
                Runnable {
                runCatching { trainingBackgroundMusicPlayer?.setVolume(restoreVolume, restoreVolume) }
            }
            if (::contentRootView.isInitialized) {
                contentRootView.post(restore)
            } else {
                restore.run()
            }
        }
        if (!queued && attempt < 3 && ::contentRootView.isInitialized) {
            contentRootView.postDelayed({ speakAiCoachCue(text, attempt + 1) }, 700L)
        }
    }

    private fun englishAiCoachFallbackText(text: String): String =
        when {
            text.contains("回合开始") || text.contains("Round") ->
                "Round starts. Settle your breathing, punch short, and bring the guard back."
            text.contains("最后") || text.contains("Final") ->
                "Final 10 seconds. Push hard, hold the rhythm, and keep punches clean."
            text.contains("节奏") || text.contains("pace", ignoreCase = true) || text.contains("BPM") ->
                "Pick up the pace. Shorten the punch and recover faster."
            text.contains("力度") || text.contains("force", ignoreCase = true) ->
                "Watch the force. Lock the wrist and land clean."
            text.contains("连击") || text.contains("combo", ignoreCase = true) || text.contains("burst", ignoreCase = true) ->
                "Nice combo rhythm. Keep the guard returning and stay compact."
            else ->
                "Keep the rhythm steady, breathe, and stay sharp."
        }

    private fun completeTtsCue(utteranceId: String?) {
        if (utteranceId.isNullOrBlank()) {
            return
        }
        ttsCompletionCallbacks.remove(utteranceId)?.invoke()
    }

    private fun spokenCueText(text: String): String =
        when {
            usesEnglishSpeechFallback() ->
                when (text) {
                    displayGoCue(), displayGoLabel(), "开始", "ไป", "GO", "Go" -> "Go"
                    else -> text
                }

            selectedLanguage == AppLanguage.French ->
                when (text) {
                    displayGoCue(), displayGoLabel() -> "Partez"
                    else -> text
                }

            selectedLanguage == AppLanguage.Thai ->
                when (text) {
                    displayGoCue(), displayGoLabel() -> "เริ่ม"
                    else -> text
                }

            else ->
                when (text) {
                    displayGoCue(), displayGoLabel() -> if (selectedLanguage == AppLanguage.Chinese) "开始" else "Go"
                    else -> text
                }
        }

    private var countPulseAnimator: AnimatorSet? = null

    private fun pulseCount() {
        val targetView =
            when {
                ::dashboardPunchValueView.isInitialized -> dashboardPunchValueView
                ::countView.isInitialized -> countView
                else -> return
            }
        countPulseAnimator?.cancel()
        targetView.scaleX = 1.0f
        targetView.scaleY = 1.0f
        val growX = ObjectAnimator.ofFloat(targetView, View.SCALE_X, 1.0f, 1.12f)
        val growY = ObjectAnimator.ofFloat(targetView, View.SCALE_Y, 1.0f, 1.12f)
        val shrinkX = ObjectAnimator.ofFloat(targetView, View.SCALE_X, 1.12f, 1.0f)
        val shrinkY = ObjectAnimator.ofFloat(targetView, View.SCALE_Y, 1.12f, 1.0f)
        countPulseAnimator = AnimatorSet().apply {
            play(growX).with(growY)
            play(shrinkX).with(shrinkY).after(growX)
            duration = 110L
            start()
        }
    }


    private fun tr(key: String): String = UiStrings.get(selectedLanguage, key)

    private fun languageDisplayName(language: AppLanguage): String =
        when (language) {
            AppLanguage.Chinese -> tr("language_chinese")
            AppLanguage.English -> tr("language_english")
            AppLanguage.French -> tr("language_french")
            AppLanguage.Thai -> tr("language_thai")
        }

    private fun countdownStatus(value: Int): String = displayCountdownStatus(value)

    private fun goCue(): String = displayGoCue()

    private fun goLabel(): String = displayGoLabel()

    private fun modeLabel(mode: TrainingMode): String = displayModeLabel(mode)

    private fun formatRemaining(remainingMillis: Long): String = displayRemaining(remainingMillis)

    private fun defaultLanguage(): AppLanguage =
        when (Locale.getDefault().language.lowercase(Locale.US)) {
            "zh" -> AppLanguage.Chinese
            "fr" -> AppLanguage.French
            "th" -> AppLanguage.Thai
            else -> AppLanguage.English
        }

    private fun profileSubtitleText(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "账号状态、语言与训练概览"
            AppLanguage.English -> "Account status, language, and training overview"
            AppLanguage.French -> "Statut du compte, langue et aperçu d'entraînement"
            AppLanguage.Thai -> "สถานะบัญชี ภาษา และภาพรวมการฝึก"
        }

    private fun historySubtitleText(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "最近训练结果会自动同步到云端"
            AppLanguage.English -> "Recent sessions are synced to the cloud automatically"
            AppLanguage.French -> "Les dernières séances sont synchronisées automatiquement"
            AppLanguage.Thai -> "ผลการฝึกล่าสุดจะซิงก์ขึ้นคลาวด์อัตโนมัติ"
        }

    private fun leaderboardSubtitleText(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "按锻炼时间、拳数、力度、卡路里与等效燃脂量排名"
            AppLanguage.English -> "Rank by duration, hits, force, calories, and equivalent fat burn"
            AppLanguage.French -> "Classement par durée, coups, force, calories et graisse équivalente"
            AppLanguage.Thai -> "จัดอันดับตามเวลา หมัด แรง แคลอรี และไขมันเทียบเท่า"
        }

    private fun avatarChooseButtonLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "选择图片"
            AppLanguage.English -> "Choose Photo"
            AppLanguage.French -> "Choisir une photo"
            AppLanguage.Thai -> "เลือกรูปภาพ"
        }

    private fun avatarClearButtonLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "移除图片"
            AppLanguage.English -> "Remove Photo"
            AppLanguage.French -> "Supprimer la photo"
            AppLanguage.Thai -> "ลบรูปภาพ"
        }

    private fun avatarImageHintText(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "可从手机相册选择头像图片，未选择时使用颜色头像。"
            AppLanguage.English -> "Choose an avatar photo from this phone, or keep the color avatar."
            AppLanguage.French -> "Choisissez une photo sur ce téléphone ou gardez l'avatar coloré."
            AppLanguage.Thai -> "เลือกรูปโปรไฟล์จากโทรศัพท์ หรือใช้รูปแบบสีเดิม"
        }

    private fun developerInfoButtonLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "联系我们"
            AppLanguage.English -> "Contact Us"
            AppLanguage.French -> "Nous contacter"
            AppLanguage.Thai -> "ติดต่อเรา"
        }

    private fun developerInfoPageTitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "关于我们"
            AppLanguage.English -> "About Us"
            AppLanguage.French -> "À propos"
            AppLanguage.Thai -> "เกี่ยวกับเรา"
        }

    private fun developerInfoPageSubtitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "开发者信息、联系邮箱及平台协议入口"
            AppLanguage.English -> "Developer details, contact email and policy links"
            AppLanguage.French -> "Informations développeur, e-mail et accès aux politiques"
            AppLanguage.Thai -> "ข้อมูลผู้พัฒนา อีเมลติดต่อ และลิงก์นโยบาย"
        }

    private fun developerCompanySectionTitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "公司信息"
            AppLanguage.English -> "Company"
            AppLanguage.French -> "Entreprise"
            AppLanguage.Thai -> "ข้อมูลบริษัท"
        }

    private fun developerContactSectionTitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "联系方式"
            AppLanguage.English -> "Contact"
            AppLanguage.French -> "Contact"
            AppLanguage.Thai -> "ช่องทางติดต่อ"
        }

    private fun developerExtrasSectionTitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "附加信息"
            AppLanguage.English -> "More Info"
            AppLanguage.French -> "Informations"
            AppLanguage.Thai -> "ข้อมูลเพิ่มเติม"
        }

    private fun developerCompanyName(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> DEVELOPER_COMPANY_NAME_ZH
            AppLanguage.English -> DEVELOPER_COMPANY_NAME_EN
            AppLanguage.French -> DEVELOPER_COMPANY_NAME_FR
            AppLanguage.Thai -> DEVELOPER_COMPANY_NAME_TH
        }

    private fun developerCompanyDescription(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "专注于智能拳击产品与运动数据体验"
            AppLanguage.English -> "Focused on HitRise training products and sports data experiences"
            AppLanguage.French -> "Spécialisée dans les produits de balle à capteurs intelligents et l'expérience des données sportives"
            AppLanguage.Thai -> "มุ่งเน้นผลิตภัณฑ์ลูกบอลเซ็นเซอร์อัจฉริยะและประสบการณ์ข้อมูลการกีฬา"
        }

    private fun developerEmailLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "邮箱"
            AppLanguage.English -> "Email"
            AppLanguage.French -> "E-mail"
            AppLanguage.Thai -> "อีเมล"
        }

    private fun developerEmailActionLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "发送邮件"
            AppLanguage.English -> "Send Email"
            AppLanguage.French -> "Envoyer un e-mail"
            AppLanguage.Thai -> "ส่งอีเมล"
        }

    private fun developerVersionLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "当前 APP 版本号"
            AppLanguage.English -> "App Version"
            AppLanguage.French -> "Version de l'application"
            AppLanguage.Thai -> "เวอร์ชันแอป"
        }

    private fun privacyPolicyEntryLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "隐私政策"
            AppLanguage.English -> "Privacy Policy"
            AppLanguage.French -> "Politique de confidentialité"
            AppLanguage.Thai -> "นโยบายความเป็นส่วนตัว"
        }

    private fun userAgreementEntryLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "用户协议"
            AppLanguage.English -> "User Agreement"
            AppLanguage.French -> "Accord utilisateur"
            AppLanguage.Thai -> "ข้อตกลงผู้ใช้"
        }

    private fun developerDocumentHint(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "可查看本应用当前版本对应的隐私政策与用户协议。"
            AppLanguage.English -> "Review the privacy policy and user agreement for the current app version."
            AppLanguage.French -> "Consultez la politique de confidentialité et l'accord utilisateur de cette version."
            AppLanguage.Thai -> "ดูนโยบายความเป็นส่วนตัวและข้อตกลงผู้ใช้ของแอปเวอร์ชันนี้"
        }

    private fun developerPrivacyPolicyAssetFile(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "privacy_policy_zh.txt"
            AppLanguage.English -> "privacy_policy_en.txt"
            AppLanguage.French -> "privacy_policy_fr.txt"
            AppLanguage.Thai -> "privacy_policy_th.txt"
        }

    private fun developerUserAgreementAssetFile(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "user_agreement_zh.txt"
            AppLanguage.English -> "user_agreement_en.txt"
            AppLanguage.French -> "user_agreement_fr.txt"
            AppLanguage.Thai -> "user_agreement_th.txt"
        }

    private fun developerDocumentUnavailableText(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "当前文档暂不可用，请稍后重试。"
            AppLanguage.English -> "This document is currently unavailable. Please try again later."
            AppLanguage.French -> "Ce document n'est pas disponible pour le moment."
            AppLanguage.Thai -> "เอกสารนี้ยังไม่พร้อมใช้งานในขณะนี้"
        }

    private fun closeLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "关闭"
            AppLanguage.English -> "Close"
            AppLanguage.French -> "Fermer"
            AppLanguage.Thai -> "ปิด"
        }

    private fun achievementsTitleText(): String =
        localText("锻炼成果徽章", "Training Result Badges", "Badges de résultats", "เหรียญผลการฝึก")

    private fun achievementsSectionHint(): String =
        localText("按锻炼时间、拳击次数、力度、卡路里和等效燃脂量记录成果", "Track badges by duration, hits, force, calories, and equivalent fat burn.", "Suivez les badges par durée, coups, force, calories et graisse équivalente.", "ติดตามเหรียญจากเวลา หมัด แรง แคลอรี และไขมันเทียบเท่า")

    private fun profilePageSubtitle(): String =
        localText("查看你的段位、训练统计与最近获得的徽章", "View your tier, key stats and recently unlocked badges.", "Consultez votre rang, vos statistiques et vos badges récents.", "ดูระดับ สถิติหลัก และเหรียญล่าสุดของคุณ")

    private fun historySectionSubtitle(): String =
        localText("最近训练结果会自动同步到云端", "Recent training sessions sync to the cloud automatically.", "Les dernières séances se synchronisent automatiquement dans le cloud.", "ผลการฝึกล่าสุดจะซิงก์ขึ้นคลาวด์อัตโนมัติ")

    private fun achievementsSubtitleText(unlockedCount: Int, totalCount: Int): String =
        localText("已解锁 $unlockedCount / $totalCount", "Unlocked $unlockedCount / $totalCount", "$unlockedCount / $totalCount débloqués", "ปลดล็อกแล้ว $unlockedCount / $totalCount")

    private fun profileBestScoreLabel(): String =
        localText("全局最佳", "Overall best", "Meilleur score", "ดีที่สุดรวม")

    private fun streakLabel(): String =
        localText("最长连练", "Best streak", "Meilleure série", "ต่อเนื่องสูงสุด")

    private fun activeDaysLabel(): String =
        localText("活跃天数", "Active days", "Jours actifs", "วันที่ใช้งาน")

    private fun nextTierLabel(): String =
        localText("下一段位", "Next tier", "Rang suivant", "ระดับถัดไป")

    private fun displayAppVersion(): String {
        val parts = BuildConfig.VERSION_NAME.split('.')
        return if (parts.size >= 2) {
            "V${parts[0]}.${parts[1]}"
        } else {
            "V${BuildConfig.VERSION_NAME}"
        }
    }

    private fun openDeveloperEmail() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DEVELOPER_EMAIL"))
        intent.putExtra(Intent.EXTRA_SUBJECT, DEVELOPER_EMAIL_SUBJECT)
        try {
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun loadAssetText(assetFile: String): String =
        try {
            assets.open(assetFile).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            ""
        }

    private fun championLabel(): String =
        localText("已达最高段位", "Top tier reached", "Rang maximal atteint", "ถึงระดับสูงสุดแล้ว")

    private fun tierLabelForLevel(level: Int): String = tierLabelForKey(tierKeyForLevel(level))

    private fun tierKeyForLevel(level: Int): String =
        when (level.coerceIn(1, 9)) {
            1 -> "beginner"
            2 -> "prospect"
            3 -> "contender"
            4 -> "striker"
            5 -> "challenger"
            6 -> "elite"
            7 -> "master"
            8 -> "legend"
            else -> "champion"
        }

    private fun tierLabelForKey(key: String?): String =
        when (key) {
            "beginner" -> localText("拳坛新丁", "New Blood", "Débutant", "มือใหม่")
            "prospect" -> localText("热血新秀", "Rising Rookie", "Espoir montant", "ดาวรุ่ง")
            "contender" -> localText("擂台争锋者", "Arena Contender", "Prétendant", "ผู้ท้าชิง")
            "striker" -> localText("铁拳出击手", "Iron Fist Striker", "Frappeur d'acier", "หมัดเหล็ก")
            "challenger" -> localText("风暴挑战者", "Storm Challenger", "Challenger tempête", "ผู้ท้าชิงพายุ")
            "elite" -> localText("荣耀精英", "Glory Elite", "Élite glorieuse", "ยอดฝีมือ")
            "master" -> localText("宗师", "Grand Master", "Grand maître", "ปรมาจารย์")
            "legend" -> localText("不朽传奇", "Immortal Legend", "Légende immortelle", "ตำนาน")
            "champion" -> localText("至尊拳王", "Supreme Champion", "Champion suprême", "แชมป์สูงสุด")
            else -> localText("拳坛新丁", "New Blood", "Débutant", "มือใหม่")
        }

    private fun achievementDisplayName(key: String): String =
        when (key) {
            "duration_5m" -> localText("锻炼 60 分钟", "60-Min Training", "60 min d'entraînement", "ฝึก 60 นาที")
            "duration_15m" -> localText("锻炼 300 分钟", "300-Min Training", "300 min d'entraînement", "ฝึก 300 นาที")
            "duration_30m" -> localText("锻炼 600 分钟", "600-Min Training", "600 min d'entraînement", "ฝึก 600 นาที")
            "duration_60m" -> localText("锻炼 2000 分钟", "2000-Min Training", "2000 min d'entraînement", "ฝึก 2000 นาที")
            "hits_100" -> localText("百拳试锋", "100-Hit Trial", "Essai 100 coups", "ทดสอบ 100 หมัด")
            "hits_500" -> localText("五百重击", "500 Heavy Hits", "500 frappes", "500 หมัดหนัก")
            "hits_1000" -> localText("千拳风暴", "1K Punch Storm", "Tempête 1K coups", "พายุ 1K หมัด")
            "hits_5000" -> localText("万击宗匠", "5K Master", "Maître 5K coups", "ปรมาจารย์ 5K")
            "peak_force_50" -> localText("最大力度 500N", "Peak 500N", "Force max 500N", "แรงสูงสุด 500N")
            "peak_force_100" -> localText("最大力度 1000N", "Peak 1000N", "Force max 1000N", "แรงสูงสุด 1000N")
            "peak_force_150" -> localText("最大力度 1300N", "Peak 1300N", "Force max 1300N", "แรงสูงสุด 1300N")
            "peak_force_200" -> localText("最大力度 1600N", "Peak 1600N", "Force max 1600N", "แรงสูงสุด 1600N")
            "avg_force_30" -> localText("平均力度 500N", "Avg 500N", "Force moy. 500N", "แรงเฉลี่ย 500N")
            "avg_force_60" -> localText("平均力度 800N", "Avg 800N", "Force moy. 800N", "แรงเฉลี่ย 800N")
            "avg_force_90" -> localText("平均力度 1000N", "Avg 1000N", "Force moy. 1000N", "แรงเฉลี่ย 1000N")
            "avg_force_120" -> localText("平均力度 1200N", "Avg 1200N", "Force moy. 1200N", "แรงเฉลี่ย 1200N")
            "calories_30" -> localText("消耗 500 kcal", "500 kcal Burned", "500 kcal brûlées", "เผาผลาญ 500 kcal")
            "calories_100" -> localText("消耗 1000 kcal", "1000 kcal Burned", "1000 kcal brûlées", "เผาผลาญ 1000 kcal")
            "calories_300" -> localText("消耗 2000 kcal", "2000 kcal Burned", "2000 kcal brûlées", "เผาผลาญ 2000 kcal")
            "calories_600" -> localText("消耗 4000 kcal", "4000 kcal Burned", "4000 kcal brûlées", "เผาผลาญ 4000 kcal")
            "fat_5" -> localText("等效燃脂 100g", "100g Equivalent Fat", "100 g graisse équiv.", "ไขมันเทียบเท่า 100g")
            "fat_15" -> localText("等效燃脂 500g", "500g Equivalent Fat", "500 g graisse équiv.", "ไขมันเทียบเท่า 500g")
            "fat_40" -> localText("等效燃脂 1000g", "1000g Equivalent Fat", "1000 g graisse équiv.", "ไขมันเทียบเท่า 1000g")
            "fat_80" -> localText("等效燃脂 2000g", "2000g Equivalent Fat", "2000 g graisse équiv.", "ไขมันเทียบเท่า 2000g")
            else -> key
        }

    private fun achievementBadgeCompactName(key: String): String =
        when (key) {
            "duration_5m" -> localText("60 分钟", "60 min", "60 min", "60 นาที")
            "duration_15m" -> localText("300 分钟", "300 min", "300 min", "300 นาที")
            "duration_30m" -> localText("600 分钟", "600 min", "600 min", "600 นาที")
            "duration_60m" -> localText("2000 分钟", "2000 min", "2000 min", "2000 นาที")
            "peak_force_50" -> "500N"
            "peak_force_100" -> "1000N"
            "peak_force_150" -> "1300N"
            "peak_force_200" -> "1600N"
            "avg_force_30" -> "500N"
            "avg_force_60" -> "800N"
            "avg_force_90" -> "1000N"
            "avg_force_120" -> "1200N"
            "calories_30" -> "500 kcal"
            "calories_100" -> "1000 kcal"
            "calories_300" -> "2000 kcal"
            "calories_600" -> "4000 kcal"
            "fat_5" -> "100g"
            "fat_15" -> "500g"
            "fat_40" -> "1000g"
            "fat_80" -> "2000g"
            else -> achievementDisplayName(key)
        }

    private fun achievementBadgeCode(key: String): String =
        when (key) {
            "duration_5m" -> "60M"
            "duration_15m" -> "300M"
            "duration_30m" -> "600M"
            "duration_60m" -> "2000M"
            "hits_100" -> "H100"
            "hits_500" -> "H500"
            "hits_1000" -> "1K"
            "hits_5000" -> "5K"
            "peak_force_50" -> "500N"
            "peak_force_100" -> "1000N"
            "peak_force_150" -> "1300N"
            "peak_force_200" -> "1600N"
            "avg_force_30" -> "500N"
            "avg_force_60" -> "800N"
            "avg_force_90" -> "1000N"
            "avg_force_120" -> "1200N"
            "calories_30" -> "500"
            "calories_100" -> "1000"
            "calories_300" -> "2000"
            "calories_600" -> "4000"
            "fat_5" -> "100g"
            "fat_15" -> "500g"
            "fat_40" -> "1000g"
            "fat_80" -> "2000g"
            else -> "BADGE"
        }

    private fun achievementBadgeImageRes(key: String): Int? =
        when (key) {
            "duration_5m" -> R.drawable.achievement_milestone_01
            "duration_15m" -> R.drawable.achievement_milestone_02
            "duration_30m" -> R.drawable.achievement_milestone_03
            "duration_60m" -> R.drawable.achievement_milestone_04
            "hits_100" -> R.drawable.achievement_hits_01
            "hits_500" -> R.drawable.achievement_hits_02
            "hits_1000" -> R.drawable.achievement_hits_03
            "hits_5000" -> R.drawable.achievement_hits_04
            "peak_force_50" -> R.drawable.achievement_best30_05
            "peak_force_100" -> R.drawable.achievement_best30_06
            "peak_force_150" -> R.drawable.achievement_best30_07
            "peak_force_200" -> R.drawable.achievement_best30_08
            "avg_force_30" -> R.drawable.achievement_best60_09
            "avg_force_60" -> R.drawable.achievement_best60_10
            "avg_force_90" -> R.drawable.achievement_best60_11
            "avg_force_120" -> R.drawable.achievement_best60_12
            "calories_30" -> R.drawable.achievement_burst_13
            "calories_100" -> R.drawable.achievement_burst_14
            "calories_300" -> R.drawable.achievement_burst_15
            "calories_600" -> R.drawable.achievement_burst_16
            "fat_5" -> R.drawable.achievement_streak_17
            "fat_15" -> R.drawable.achievement_streak_18
            "fat_40" -> R.drawable.achievement_streak_19
            "fat_80" -> R.drawable.achievement_streak_20
            else -> null
        }

    private fun achievementProgressText(item: CloudAchievementItem): String =
        when (item.metric) {
            "total_training_seconds" -> "${formatTrainingDuration(item.progress)} / ${formatTrainingDuration(item.goal)}"
            "best_peak_force_n", "best_avg_force_n" -> "${item.progress} N / ${item.goal} N"
            "total_calories_burned" -> "${item.progress} kcal / ${item.goal} kcal"
            "total_fat_burned_grams" -> "${item.progress} g / ${item.goal} g"
            else -> "${item.progress} / ${item.goal}"
        }

    private fun achievementAccentColor(key: String): String =
        when {
            key.startsWith("duration_") -> "#10BDAA"
            key.startsWith("hits_") -> "#16C8B5"
            key.startsWith("peak_force_") -> "#E65A4F"
            key.startsWith("avg_force_") -> "#2CB7A4"
            key.startsWith("calories_") -> "#FF8A32"
            key.startsWith("fat_") -> "#00BFA8"
            else -> "#10BDAA"
        }

    private data class MetallicPalette(
        val highlight: String,
        val base: String,
        val stroke: String,
        val text: String,
    )

    private fun metallicBackground(
        highlight: String,
        base: String,
        stroke: String,
        cornerDp: Int = 18,
    ): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor(highlight),
                Color.parseColor(base),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun achievementMetalPalette(
        key: String,
        unlocked: Boolean,
    ): MetallicPalette =
        when {
            key.startsWith("duration_") ->
                if (unlocked) MetallicPalette("#FFE7A2", "#C8942C", "#FBE2A0", "#FFF8E6") else MetallicPalette("#32404B", "#1A232A", "#465B69", "#B88A54")
            key.startsWith("hits_") ->
                if (unlocked) MetallicPalette("#A6F3E5", "#23927C", "#CAFCEF", "#EFFFFB") else MetallicPalette("#2E4044", "#162328", "#425761", "#B88A54")
            key.startsWith("peak_force_") ->
                if (unlocked) MetallicPalette("#FFD1BE", "#B13A38", "#F8B999", "#FFF3EC") else MetallicPalette("#413631", "#211A19", "#5B4A44", "#B88A54")
            key.startsWith("avg_force_") ->
                if (unlocked) MetallicPalette("#C8FFE0", "#2C8476", "#C7F9F2", "#F2FFFD") else MetallicPalette("#324049", "#1A2329", "#465963", "#B88A54")
            key.startsWith("calories_") ->
                if (unlocked) MetallicPalette("#FFD1BE", "#B7653E", "#F8B999", "#FFF3EC") else MetallicPalette("#413631", "#211A19", "#5B4A44", "#B88A54")
            key.startsWith("fat_") ->
                if (unlocked) MetallicPalette("#E0D0FF", "#6D4BC7", "#CDBBFF", "#F7F2FF") else MetallicPalette("#383645", "#1E1E27", "#55556A", "#B88A54")
            else ->
                if (unlocked) MetallicPalette("#D2F4F0", "#2C8476", "#C7F9F2", "#F2FFFD") else MetallicPalette("#324049", "#1A2329", "#465963", "#B88A54")
        }

    private fun achievementBadgeCard(item: CloudAchievementItem): LinearLayout {
        val unlocked = item.unlocked
        val accentColor = achievementAccentColor(item.key)
        val fillColor = if (unlocked) "#11242F" else "#0C1822"
        val strokeColor = if (unlocked) accentColor else "#233A4B"
        val progressFraction = if (item.goal > 0) item.progress.toFloat() / item.goal.toFloat() else 0f
        return detailCard(fillColor = fillColor, strokeColor = strokeColor, cornerDp = 18).apply {
            val codeView =
                TextView(this@MainActivity).apply {
                    text = achievementBadgeCode(item.key)
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(if (unlocked) Color.parseColor("#FFF8E8") else Color.parseColor("#B88A54"))
                    background = roundedBackground(if (unlocked) accentColor else "#12222E", strokeColor, 999)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
            val titleView =
                bodyText(achievementBadgeCompactName(item.key)).apply {
                    setTextColor(Color.parseColor("#FFF5E6"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setPadding(0, dp(10), 0, 0)
                }
            val progressView =
                bodyText(achievementProgressText(item)).apply {
                    setTextColor(if (unlocked) Color.parseColor(accentColor) else Color.parseColor("#CAA26A"))
                    setPadding(0, dp(6), 0, 0)
                }
            val progressBar =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    minimumHeight = dp(7)
                    background = roundedBackground("#10212E", "#1B3446", 999)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(7),
                        ).apply {
                            topMargin = dp(10)
                        }
                    val safeProgress = progressFraction.coerceIn(0f, 1f)
                    if (safeProgress > 0f) {
                        addView(
                            View(this@MainActivity).apply {
                                background = roundedBackground(accentColor, accentColor, 999)
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        safeProgress,
                                    )
                            },
                        )
                    }
                    if (safeProgress < 1f) {
                        addView(
                            View(this@MainActivity).apply {
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        (1f - safeProgress).coerceAtLeast(0.0001f),
                                    )
                            },
                        )
                    }
                }
            addView(codeView)
            addView(titleView)
            addView(progressBar)
            addView(progressView)
        }
    }

    private fun shouldCelebrateTier(
        tier: CloudTierProgress?,
        promotedHint: Boolean,
        previousLevel: Int,
    ): Boolean {
        if (tier == null || previousLevel <= 0) {
            return false
        }
        return (promotedHint || tier.level > previousLevel) && tier.level > previousLevel
    }

    private fun syncSeenTier(tier: CloudTierProgress?) {
        if (tier == null) {
            return
        }
        val previousLevel = prefs.getInt(KEY_LAST_SEEN_TIER, 0)
        if (tier.level != previousLevel) {
            prefs.edit().putInt(KEY_LAST_SEEN_TIER, tier.level).apply()
        }
    }

    private fun computeNewlyUnlockedAchievements(
        previousUnlockedKeys: Set<String>,
        incoming: List<CloudAchievementItem>,
    ): List<CloudAchievementItem> =
        incoming
            .filter { it.unlocked && !previousUnlockedKeys.contains(it.key) }
            .sortedBy { it.sortOrder }

    private fun dismissCelebrationBeforeTraining() {
        dismissingCelebrationForTraining = true
        activeCelebrationDialog?.dismiss()
        activeCelebrationDialog = null
        celebrationShowing = false
        dismissingCelebrationForTraining = false
        tts?.stop()
        resetCelebrationVoice()
    }

    private fun maybeShowTrainingOutcomeCelebration(
        report: TrainingReport,
        outcome: TrainingCoachOutcome,
    ) {
        enqueueCelebration { showTrainingOutcomeDialog(report, outcome) }
    }

    private fun showTrainingOutcomeDialog(
        report: TrainingReport,
        outcome: TrainingCoachOutcome,
    ) {
        val title =
            when (outcome.playMode) {
                TrainingPlayMode.LevelChallenge ->
                    if (outcome.goalMet) {
                        localText("闯关成功", "Level Cleared", "Niveau réussi", "ผ่านด่าน")
                    } else {
                        localText("闯关继续", "Keep Challenging", "Continuez le défi", "ท้าทายต่อ")
                    }

                TrainingPlayMode.DailyChallenge ->
                    if (outcome.goalMet) {
                        localText("挑战成功", "Challenge Complete", "Défi réussi", "ทำภารกิจสำเร็จ")
                    } else {
                        localText("每日挑战进行中", "Daily Challenge Progress", "Défi du jour en cours", "ภารกิจวันนี้กำลังคืบหน้า")
                    }

                TrainingPlayMode.Burst10,
                TrainingPlayMode.Burst15,
                ->
                    if (outcome.goalMet) {
                        localText("爆发达成", "Burst Target Hit", "Objectif explosif atteint", "ถึงเป้าระเบิด")
                    } else {
                        localText("爆发训练完成", "Burst Session Complete", "Séance explosive terminée", "จบรอบระเบิด")
                    }

                TrainingPlayMode.Classic30,
                TrainingPlayMode.Classic60,
                -> localText("训练完成", "Training Complete", "Entraînement terminé", "ฝึกเสร็จแล้ว")
            }
        val chips =
            buildList {
                add("XP +${outcome.xpGain}" to "#FFD060")
                add(
                    localText(
                        "连练 ${outcome.streak} 天",
                        "${outcome.streak}-day streak",
                        "Série ${outcome.streak} j",
                        "ต่อเนื่อง ${outcome.streak} วัน",
                    ) to "#FFB347",
                )
                add(displayModeLabel(report.mode) to "#FF9A30")
                if (outcome.goalMet) {
                    add(localText("任务完成", "Task done", "Tâche terminée", "ภารกิจสำเร็จ") to "#E07010")
                }
            }
        showCelebrationDialog(
            accentColor = if (outcome.goalMet) "#FFD060" else "#FF9A30",
            eyebrow = if (outcome.goalMet) "VICTORY" else "GOOD WORK",
            title = title,
            body = buildCoachMessage(report, outcome),
            chips = chips,
        )
    }

    private fun maybeShowPostTrainingCelebrations(
        unlockedAchievements: List<CloudAchievementItem>,
        promotedTier: CloudTierProgress?,
    ) {
        if (unlockedAchievements.isNotEmpty()) {
            enqueueCelebration { showAchievementUnlockDialog(unlockedAchievements) }
        }
        promotedTier?.let { tier ->
            enqueueCelebration { showTierPromotionDialog(tier) }
        }
    }

    private fun enqueueCelebration(action: () -> Unit) {
        if (trainingJob?.isActive == true) {
            celebrationQueue.addLast(action)
            return
        }
        if (celebrationShowing) {
            celebrationQueue.addLast(action)
        } else {
            celebrationShowing = true
            action()
        }
    }

    private fun onCelebrationDismissed() {
        activeCelebrationDialog = null
        resetCelebrationVoice()
        if (dismissingCelebrationForTraining || trainingJob?.isActive == true) {
            celebrationShowing = false
            return
        }
        celebrationShowing = false
        showNextCelebrationIfIdle()
    }

    private fun showNextCelebrationIfIdle() {
        if (celebrationShowing || trainingJob?.isActive == true || celebrationQueue.isEmpty()) {
            return
        }
        celebrationShowing = true
        celebrationQueue.removeFirst().invoke()
    }

    private fun showTierPromotionBanner(tier: CloudTierProgress) {
        val message =
            localText(
                "段位升级：${tierLabelForKey(tier.key)} Lv.${tier.level}",
                "Rank Up! ${tierLabelForKey(tier.key)} Lv.${tier.level}",
                "Rang supérieur ! ${tierLabelForKey(tier.key)} Lv.${tier.level}",
                "เลื่อนระดับ! ${tierLabelForKey(tier.key)} Lv.${tier.level}",
            )
        promotionBannerView.text = message
        promotionBannerView.visibility = View.VISIBLE
        promotionBannerView.alpha = 0f
        promotionBannerView.translationY = -dp(12).toFloat()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(promotionBannerView, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(promotionBannerView, View.TRANSLATION_Y, -dp(12).toFloat(), 0f),
            )
            duration = 320L
            start()
        }
        promotionBannerView.postDelayed({
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(promotionBannerView, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(promotionBannerView, View.TRANSLATION_Y, 0f, -dp(8).toFloat()),
                )
                duration = 320L
                start()
            }
            promotionBannerView.postDelayed({ promotionBannerView.visibility = View.GONE }, 340L)
        }, 2200L)
    }

    private fun showAchievementUnlockDialog(items: List<CloudAchievementItem>) {
        val chips =
            items.take(3).map { achievementDisplayName(it.key) to achievementAccentColor(it.key) }
        val title =
            localText("新徽章解锁", "New badges unlocked", "Nouveaux badges débloqués", "ปลดล็อกเหรียญใหม่")
        val body =
            localText(
                "本次训练解锁 ${items.size} 枚徽章，继续保持节奏，冲击更高段位。",
                "You unlocked ${items.size} new badges in this session. Keep pushing for the next tier.",
                "Vous avez débloqué ${items.size} badge(s). Gardez le rythme vers le rang suivant.",
                "คุณปลดล็อกเหรียญใหม่ ${items.size} เหรียญ รักษาจังหวะเพื่อระดับถัดไป",
            )
        showCelebrationDialog(
            accentColor = "#FFB347",
            eyebrow = "NEW BADGES",
            title = title,
            body = body,
            chips = chips,
        )
    }

    private fun showTierPromotionDialog(tier: CloudTierProgress) {
        val title =
            localText(
                "段位升级：${tierLabelForKey(tier.key)}",
                "Rank Up: ${tierLabelForKey(tier.key)}",
                "Rang supérieur : ${tierLabelForKey(tier.key)}",
                "เลื่อนระดับ: ${tierLabelForKey(tier.key)}",
            )
        val body =
            localText(
                "当前最佳单回合成绩提升至 ${tier.bestHits}，成功晋升为 ${tierLabelForKey(tier.key)}。",
                "Your best round score is now ${tier.bestHits}. You have been promoted to ${tierLabelForKey(tier.key)}.",
                "Votre meilleur score par round atteint ${tier.bestHits}. Promotion en ${tierLabelForKey(tier.key)}.",
                "สถิติรอบดีที่สุดเป็น ${tier.bestHits} เลื่อนเป็น ${tierLabelForKey(tier.key)} แล้ว",
            )
        showCelebrationDialog(
            accentColor = "#FFD060",
            eyebrow = "RANK UP",
            title = title,
            body = body,
            chips = listOf("Lv.${tier.level}" to "#FFD060"),
        )
    }

    private fun showCelebrationDialog(
        accentColor: String,
        eyebrow: String,
        title: String,
        body: String,
        chips: List<Pair<String, String>>,
    ) {
        val overlay =
            FrameLayout(this).apply {
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(Color.argb(155, 4, 10, 18))
            }
        val card =
            detailCard(fillColor = "#0F2130", strokeColor = accentColor, cornerDp = 26).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
                minimumHeight = dp(220)
            }
        card.addView(
            badgeText(
                text = eyebrow,
                textColor = "#140800",
                fillColor = accentColor,
            ),
        )
        card.addView(
            titleText(title, 24f).apply {
                setTextColor(Color.parseColor("#FFF8E8"))
                setPadding(0, dp(16), 0, 0)
            },
        )
        card.addView(
            bodyText(body).apply {
                setTextColor(Color.parseColor("#C7D6E4"))
                setPadding(0, dp(10), 0, 0)
            },
        )
        if (chips.isNotEmpty()) {
            val chipRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    setPadding(0, dp(16), 0, 0)
                }
            chips.forEachIndexed { index, chip ->
                chipRow.addView(
                    badgeText(
                        text = chip.first,
                        textColor = "#FFF8E8",
                        fillColor = chip.second,
                    ).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                if (index > 0) {
                                    leftMargin = dp(8)
                                }
                            }
                    },
                )
            }
            card.addView(chipRow)
        }
        card.addView(
            bodyText(localText("点击任意位置继续，或 10 秒后自动关闭", "Tap anywhere to continue, or it closes in 10 seconds", "Touchez n'importe où pour continuer, ou fermeture dans 10 secondes", "แตะที่ใดก็ได้เพื่อดำเนินการต่อ หรือปิดอัตโนมัติใน 10 วินาที")).apply {
                setTextColor(Color.parseColor("#7F97AA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(18), 0, 0)
            },
        )
        overlay.addView(card)

        val dialog =
            AlertDialog.Builder(this)
                .setView(overlay)
                .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { onCelebrationDismissed() }
        overlay.setOnClickListener { dialog.dismiss() }
        dialog.show()
        activeCelebrationDialog = dialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        speakCelebration(celebrationVoiceText(title))

        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.translationY = dp(20).toFloat()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(card, View.SCALE_X, 0.9f, 1f),
                ObjectAnimator.ofFloat(card, View.SCALE_Y, 0.9f, 1f),
                ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, dp(20).toFloat(), 0f),
            )
            duration = 280L
            start()
        }
        overlay.postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }, 10_000L)
    }

    private fun celebrationVoiceText(title: String): String =
        if (usesEnglishSpeechFallback()) {
            "Amazing! $title! Keep going strong!"
        } else {
            when (selectedLanguage) {
                AppLanguage.Chinese -> "太棒了！$title！继续保持，向更强进发！"
                AppLanguage.English -> "Amazing! $title! Keep going strong!"
                AppLanguage.French -> "Magnifique ! $title ! Continuez comme ça !"
                AppLanguage.Thai -> "ยอดเยี่ยม! $title! ลุยต่อไป!"
            }
        }

    private fun speakCelebration(text: String) {
        val speaker = tts ?: return
        if (!ttsReady || text.isBlank()) {
            return
        }
        runCatching {
            speaker.setPitch(1.35f)
            speaker.setSpeechRate(1.08f)
            speaker.speak(spokenCueText(text), TextToSpeech.QUEUE_ADD, null, "celebration-${UUID.randomUUID()}")
            promotionBannerView.postDelayed({
                resetCelebrationVoice()
            }, 2_800L)
        }
    }

    private fun resetCelebrationVoice() {
        runCatching {
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
        }
    }

    private fun rankLabel(rank: Int): String =
        when (rank) {
            1 -> "#1"
            2 -> "#2"
            3 -> "#3"
            else -> "#$rank"
        }

    private fun buildPodiumEntries(topThree: List<CloudLeaderboardEntry>): List<CloudLeaderboardEntry> =
        when (topThree.size) {
            0 -> emptyList()
            1 -> topThree
            2 -> listOf(topThree[0], topThree[1])
            else -> listOf(topThree[1], topThree[0], topThree[2])
        }

    private fun podiumAccentForRank(rank: Int): String =
        when (rank) {
            1 -> "#FFB84D"
            2 -> "#8FB4C8"
            3 -> "#D99662"
            else -> "#10BDAA"
        }

    private fun podiumFillForRank(rank: Int): String =
        when (rank) {
            1 -> "#FFF8E7"
            2 -> "#F4FAFC"
            3 -> "#FFF1E7"
            else -> "#F7FFFD"
        }

    private fun podiumChipTextForRank(rank: Int): String =
        when (rank) {
            1 -> "#17343B"
            else -> "#FFFFFF"
        }

    private fun leaderboardAccentColor(board: LeaderboardBoard = leaderboardBoard): String =
        when (board) {
            LeaderboardBoard.TrainingDuration -> "#10BDAA"
            LeaderboardBoard.TotalHits -> "#16C8B5"
            LeaderboardBoard.PeakForce -> "#E65A4F"
            LeaderboardBoard.AvgForce -> "#2CB7A4"
            LeaderboardBoard.Calories -> "#FF8A32"
            LeaderboardBoard.FatBurned -> "#00BFA8"
        }

    private fun leaderboardAccentFill(board: LeaderboardBoard = leaderboardBoard): String =
        when (board) {
            LeaderboardBoard.TrainingDuration -> "#DFFFF7"
            LeaderboardBoard.TotalHits -> "#E5FBF7"
            LeaderboardBoard.PeakForce -> "#FFEDE9"
            LeaderboardBoard.AvgForce -> "#E7FAF5"
            LeaderboardBoard.Calories -> "#FFF2DD"
            LeaderboardBoard.FatBurned -> "#E0FFF8"
        }

    private fun sanitizeAvatarColor(colorHex: String?): String {
        val normalized = colorHex?.trim().orEmpty()
        return if (normalized.matches(Regex("^#[0-9A-Fa-f]{6}$"))) normalized.uppercase(Locale.US) else "#10BDAA"
    }

    private fun avatarBackground(colorHex: String): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(sanitizeAvatarColor(colorHex)))
            setStroke(dp(2), Color.parseColor("#DFFFF0"))
        }

    private fun heroBackground(primaryColor: String): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor(primaryColor),
                Color.parseColor(selectedPalette.surfaceBottom),
                Color.parseColor(selectedPalette.backgroundBottom),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), Color.parseColor(selectedPalette.strokeStrong))
        }

    private fun detailCard(
        fillColor: String = selectedPalette.card,
        strokeColor: String = selectedPalette.stroke,
        cornerDp: Int = 18,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(cornerDp).toFloat()
                    setColor(Color.parseColor(fillColor))
                    setStroke(dp(1), Color.parseColor(strokeColor))
                }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }

    private fun badgeText(
        text: String,
        textColor: String = selectedPalette.textPrimary,
        fillColor: String = selectedPalette.cardAlt,
    ): TextView =
        bodyText(text).apply {
            setTextColor(Color.parseColor(textColor))
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(fillColor, fillColor, 999)
        }

    private fun roundedBackground(
        fillColor: String,
        strokeColor: String = fillColor,
        cornerDp: Int = 14,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(Color.parseColor(fillColor))
            setStroke(dp(1), Color.parseColor(strokeColor))
        }

    private fun historySessionCard(item: CloudTrainingHistoryItem): LinearLayout {
        val card = detailCard(fillColor = "#FFFFFF", strokeColor = "#CDEFE8")
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val modeChip = badgeText(displayModeLabel(secondsToMode(item.modeSeconds)), textColor = "#096D65", fillColor = "#DFFFF7")
        val hitsChip = badgeText("${item.totalHits} ${tr("hits")}", textColor = "#FFFFFF", fillColor = "#FF8A32")
        val headerSpacer =
            View(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        1,
                        1.0f,
                    )
            }
        header.addView(modeChip)
        header.addView(headerSpacer)
        header.addView(hitsChip)

        val titleLine =
            bodyText(formatHistoryTime(item.endedAt ?: item.startedAt)).apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#17343B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(12), 0, 0)
            }
        val metricsRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(12), 0, 0)
            }
        val avgChip =
            badgeText(
                text = String.format(Locale.US, "%.2f %s", item.averageFrequency, tr("hits_per_second")),
                textColor = "#096D65",
                fillColor = "#DFFFF7",
            )
        val burstChip =
            badgeText(
                text = "${tr("best_burst")}: ${item.bestBurstCount}",
                textColor = "#9A560F",
                fillColor = "#FFF2DD",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = dp(10)
                    }
            }
        metricsRow.addView(avgChip)
        metricsRow.addView(burstChip)
        val detailLine =
            bodyText(
                "${localText("锻炼时间", "Duration", "Durée", "เวลา")}: ${formatTrainingDuration(item.durationSeconds)}  |  ${localText("最大力度", "Peak", "Max", "สูงสุด")}: ${forceDisplay(item.peakForceN)}  |  ${localText("平均力度", "Avg", "Moy.", "เฉลี่ย")}: ${forceDisplay(item.avgForceN)}\n${tr("calories_burned")}: ${formatCalories(item.caloriesBurned)}  |  ${tr("fat_burned")}: ${formatFatGrams(item.fatBurnedGrams)}",
            ).apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(8), 0, 0)
            }
        card.addView(header)
        card.addView(titleLine)
        card.addView(metricsRow)
        card.addView(detailLine)
        if (item.roundReports.isNotEmpty()) {
            card.addView(
                bodyText(localText("云端回合明细", "Cloud round details", "Détails des rounds", "รายละเอียดรอบบนคลาวด์")).apply {
                    setTextColor(Color.parseColor("#17343B"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(10), 0, 0)
                },
            )
            item.roundReports.sortedBy { it.roundIndex }.forEach { round ->
                card.addView(
                    bodyText(
                        localText(
                            "第 ${round.roundIndex}/${round.totalRounds} 回合：本回合 ${round.roundHits} 拳 | 累计 ${round.cumulativeHits} 拳 | ${formatCalories(round.cumulativeCaloriesBurned)} | 等效燃脂 ${formatFatGrams(round.cumulativeFatBurnedGrams)}",
                            "Round ${round.roundIndex}/${round.totalRounds}: ${round.roundHits} hits | ${round.cumulativeHits} total | ${formatCalories(round.cumulativeCaloriesBurned)} | ${formatFatGrams(round.cumulativeFatBurnedGrams)} equivalent fat",
                            "Round ${round.roundIndex}/${round.totalRounds} : ${round.roundHits} coups | ${round.cumulativeHits} total | ${formatCalories(round.cumulativeCaloriesBurned)} | ${formatFatGrams(round.cumulativeFatBurnedGrams)} graisse équiv.",
                            "รอบ ${round.roundIndex}/${round.totalRounds}: ${round.roundHits} หมัด | รวม ${round.cumulativeHits} | ${formatCalories(round.cumulativeCaloriesBurned)} | ไขมันเทียบเท่า ${formatFatGrams(round.cumulativeFatBurnedGrams)}",
                        ),
                    ).apply {
                        setTextColor(Color.parseColor("#557A7D"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setPadding(0, dp(6), 0, 0)
                    },
                )
            }
        }
        return card
    }

    private fun podiumCard(
        entry: CloudLeaderboardEntry,
        accentColor: String,
        elevated: Boolean,
        leftMargin: Int,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f,
                ).apply {
                    this.leftMargin = leftMargin
                    topMargin = if (elevated) 0 else dp(18)
                }
            addView(
                detailCard(fillColor = "#FFFFFF", strokeColor = accentColor, cornerDp = 22).apply {
                    minimumHeight = if (elevated) dp(176) else dp(148)
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(
                        badgeText(
                            text = "TOP ${entry.rank}",
                            textColor = "#140800",
                            fillColor = accentColor,
                        ),
                    )
                    addView(
                        bodyText(rankLabel(entry.rank)).apply {
                            gravity = Gravity.CENTER
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextColor(Color.parseColor(accentColor))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (elevated) 28f else 22f)
                            setPadding(0, dp(14), 0, 0)
                        },
                    )
                    addView(
                        titleText(entry.nickname, if (elevated) 20f else 18f).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(6), 0, 0)
                            setTextColor(Color.parseColor("#17343B"))
                        },
                    )
                    addView(
                        badgeText(
                            text = tierLabelForKey(entry.tierKey),
                            textColor = "#096D65",
                            fillColor = "#DFFFF7",
                        ).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                            setPadding(dp(10), dp(5), dp(10), dp(5))
                        },
                    )
                    addView(
                        badgeText(
                            text = leaderboardBoardLabel(leaderboardBoard),
                            textColor = "#557A7D",
                            fillColor = "#F0F8F6",
                        ).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                        },
                    )
                    addView(
                        bodyText(leaderboardPrimaryValueText(entry)).apply {
                            gravity = Gravity.CENTER
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (elevated) 20f else 18f)
                            setTextColor(Color.parseColor(accentColor))
                            setPadding(0, dp(10), 0, 0)
                        },
                    )
                    addView(
                        bodyText(leaderboardSecondaryValueText(entry)).apply {
                            gravity = Gravity.CENTER
                            setTextColor(Color.parseColor("#557A7D"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            setPadding(0, dp(8), 0, 0)
                        },
                    )
                },
            )
            addView(
                View(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            when (entry.rank) {
                                1 -> dp(52)
                                2 -> dp(36)
                                else -> dp(28)
                            },
                        ).apply {
                            topMargin = dp(10)
                        }
                    background = roundedBackground(fillColor = accentColor, strokeColor = accentColor, cornerDp = 18)
                },
            )
        }

    private fun leaderboardRowCard(entry: CloudLeaderboardEntry): LinearLayout {
        val accentColor = leaderboardAccentColor(leaderboardBoard)
        val card = detailCard(fillColor = "#FFFFFF", strokeColor = "#CDEFE8")
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val rankView =
            bodyText(rankLabel(entry.rank)).apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor(accentColor))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        rightMargin = dp(12)
                    }
            }
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1.0f,
                    )
            }
        content.addView(
            bodyText(entry.nickname).apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#17343B"))
            },
        )
        content.addView(
            badgeText(tierLabelForKey(entry.tierKey), textColor = "#096D65", fillColor = "#DFFFF7").apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            },
        )
        content.addView(
            bodyText(
                "${leaderboardPrimaryValueText(entry)} | ${leaderboardSecondaryValueText(entry)}",
            ).apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            },
        )
        val serialBadge =
            badgeText(entry.serialMasked, textColor = "#557A7D", fillColor = "#F0F8F6").apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        row.addView(rankView)
        row.addView(content)
        row.addView(serialBadge)
        card.addView(row)
        return card
    }

    private fun achievementTierHeroCardPremium(
        tier: CloudTierProgress,
        unlockedCount: Int,
        totalCount: Int,
    ): LinearLayout =
        detailCard(fillColor = "#EFFFFA", strokeColor = "#BFEFE5", cornerDp = 24).apply {
            background = roundedBackground("#EFFFFA", "#BFEFE5", 24)
            addView(
                TextView(this@MainActivity).apply {
                    text = localText("荣誉段位", "Honor Tier", "Rang d'honneur", "ระดับเกียรติยศ")
                    setTextColor(Color.parseColor("#096D65"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    background = roundedBackground("#DFFFF7", "#BFEFE5", 999)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                },
            )
            addView(
                titleText(tierLabelForKey(tier.key), 24f).apply {
                    gravity = Gravity.START
                    setPadding(0, dp(14), 0, 0)
                    setTextColor(Color.parseColor("#17343B"))
                },
            )
            addView(
                bodyText(achievementsSubtitleText(unlockedCount, totalCount)).apply {
                    setTextColor(Color.parseColor("#557A7D"))
                    setPadding(0, dp(6), 0, 0)
                },
            )
            addView(
                bodyText(tierHeroProgressText(tier)).apply {
                    setTextColor(Color.parseColor("#0CA99A"))
                    setPadding(0, dp(10), 0, 0)
                },
            )
        }

    private fun achievementBadgeCardPremium(item: CloudAchievementItem): LinearLayout {
        val unlocked = item.unlocked
        val accentColor = achievementAccentColor(item.key)
        val palette = achievementMetalPalette(item.key, unlocked)
        val badgeImageRes = achievementBadgeImageRes(item.key)
        val progressFraction = if (item.goal > 0) item.progress.toFloat() / item.goal.toFloat() else 0f
        return detailCard(fillColor = "#FFFFFF", strokeColor = if (unlocked) palette.stroke else "#D6ECE8", cornerDp = 20).apply {
            background = roundedBackground(if (unlocked) "#F8FFFC" else "#F4F8F7", if (unlocked) palette.stroke else "#D6ECE8", 20)
            val medal =
                FrameLayout(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            topMargin = dp(10)
                        }
                    background =
                        if (badgeImageRes == null) {
                            metallicBackground(
                                if (unlocked) palette.highlight else "#243441",
                                if (unlocked) palette.base else "#121D26",
                                if (unlocked) palette.stroke else "#314755",
                                999,
                            )
                        } else {
                            roundedBackground(
                                if (unlocked) "#10252D" else "#DCE9E6",
                                if (unlocked) palette.stroke else "#D6ECE8",
                                999,
                            )
                        }
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    elevation = dp(2).toFloat()
                    if (badgeImageRes != null) {
                        addView(
                            ImageView(this@MainActivity).apply {
                                setImageResource(badgeImageRes)
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                alpha = if (unlocked) 1f else 0.7f
                                colorFilter = vividAssetColorFilter()
                                contentDescription = achievementDisplayName(item.key)
                                outlineProvider =
                                    object : ViewOutlineProvider() {
                                        override fun getOutline(view: View, outline: Outline) {
                                            outline.setOval(0, 0, view.width, view.height)
                                        }
                                    }
                                clipToOutline = true
                                layoutParams =
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                            },
                        )
                    } else {
                        addView(
                            TextView(this@MainActivity).apply {
                                text = achievementBadgeCode(item.key)
                                gravity = Gravity.CENTER
                                setTypeface(Typeface.DEFAULT_BOLD)
                                setTextColor(Color.parseColor(if (unlocked) palette.text else "#B88A54"))
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                                layoutParams =
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                            },
                        )
                    }
                }
            addView(
                bodyText(achievementBadgeCompactName(item.key)).apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#17343B"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    includeFontPadding = false
                },
            )
            addView(
                bodyText(
                    if (unlocked) {
                        localText("已解锁", "Unlocked", "Débloqué", "ปลดล็อกแล้ว")
                    } else {
                        localText("成长中", "In Progress", "En progression", "กำลังพัฒนา")
                    },
                ).apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(if (unlocked) palette.stroke else "#7FA0A3"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(5), 0, 0)
                },
            )
            addView(medal)

            val progressBar =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    minimumHeight = dp(8)
                    background = roundedBackground("#E7F5F1", "#D6ECE8", 999)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(8),
                        ).apply {
                            topMargin = dp(12)
                        }
                    val safeProgress = progressFraction.coerceIn(0f, 1f)
                    if (safeProgress > 0f) {
                        addView(
                            View(this@MainActivity).apply {
                                background =
                                    metallicBackground(
                                        if (unlocked) palette.highlight else accentColor,
                                        if (unlocked) palette.base else "#203545",
                                        if (unlocked) palette.stroke else accentColor,
                                        999,
                                    )
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        safeProgress,
                                    )
                            },
                        )
                    }
                    if (safeProgress < 1f) {
                        addView(
                            View(this@MainActivity).apply {
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        (1f - safeProgress).coerceAtLeast(0.0001f),
                                    )
                            },
                        )
                    }
                }
            addView(progressBar)
            addView(
                bodyText(achievementProgressText(item)).apply {
                    setTextColor(if (unlocked) Color.parseColor(accentColor) else Color.parseColor("#557A7D"))
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }
    }

    private fun podiumCardPremium(
        entry: CloudLeaderboardEntry,
        accentColor: String,
        elevated: Boolean,
        leftMargin: Int,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f,
                ).apply {
                    this.leftMargin = leftMargin
                    topMargin = if (elevated) 0 else dp(18)
                }
            addView(
                detailCard(fillColor = "#FFFFFF", strokeColor = accentColor, cornerDp = 24).apply {
                    background =
                        roundedBackground(
                            podiumFillForRank(entry.rank),
                            accentColor,
                            24,
                        )
                    minimumHeight = if (elevated) dp(186) else dp(156)
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "TOP ${entry.rank}"
                            setTextColor(Color.parseColor(podiumChipTextForRank(entry.rank)))
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                            background = roundedBackground(accentColor, accentColor, 999)
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                        },
                    )
                    addView(
                        bodyText(rankLabel(entry.rank)).apply {
                            gravity = Gravity.CENTER
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextColor(Color.parseColor(accentColor))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (elevated) 28f else 22f)
                            setPadding(0, dp(16), 0, 0)
                        },
                    )
                    addView(
                        titleText(entry.nickname, if (elevated) 20f else 18f).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(8), 0, 0)
                            setTextColor(Color.parseColor("#17343B"))
                        },
                    )
                    addView(
                        badgeText(
                            text = tierLabelForKey(entry.tierKey),
                            textColor = "#096D65",
                            fillColor = "#DFFFF7",
                        ).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                            setPadding(dp(10), dp(5), dp(10), dp(5))
                        },
                    )
                    addView(
                        bodyText(leaderboardPrimaryValueText(entry)).apply {
                            gravity = Gravity.CENTER
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (elevated) 21f else 18f)
                            setTextColor(Color.parseColor(accentColor))
                            setPadding(0, dp(12), 0, 0)
                        },
                    )
                    addView(
                        bodyText(leaderboardSecondaryValueText(entry)).apply {
                            gravity = Gravity.CENTER
                            setTextColor(Color.parseColor("#557A7D"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            setPadding(0, dp(8), 0, 0)
                        },
                    )
                },
            )
            addView(
                View(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            when (entry.rank) {
                                1 -> dp(54)
                                2 -> dp(38)
                                else -> dp(30)
                            },
                        ).apply {
                            topMargin = dp(10)
                        }
                    background = roundedBackground(accentColor, accentColor, 18)
                },
            )
        }

    private fun leaderboardRowCardPremium(entry: CloudLeaderboardEntry): LinearLayout {
        val accentColor = leaderboardAccentColor(leaderboardBoard)
        val card = detailCard(fillColor = "#FFFFFF", strokeColor = "#CDEFE8", cornerDp = 20)
        card.background = roundedBackground("#FFFFFF", "#D7F0EA", 20)
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val rankView =
            bodyText(rankLabel(entry.rank)).apply {
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                background = roundedBackground(accentColor, accentColor, 999)
                layoutParams =
                    LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        rightMargin = dp(12)
                    }
            }
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1.0f,
                    )
            }
        content.addView(
            bodyText(entry.nickname).apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#17343B"))
            },
        )
        content.addView(
            bodyText("${leaderboardPrimaryValueText(entry)} | ${leaderboardSecondaryValueText(entry)}").apply {
                setTextColor(Color.parseColor("#557A7D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            },
        )
        val sideColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = dp(10)
                    }
            }
        sideColumn.addView(
            badgeText(tierLabelForKey(entry.tierKey), textColor = "#096D65", fillColor = "#DFFFF7").apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            },
        )
        sideColumn.addView(
            badgeText(entry.serialMasked, textColor = "#557A7D", fillColor = "#F0F8F6").apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
            },
        )
        row.addView(rankView)
        row.addView(content)
        row.addView(sideColumn)
        card.addView(row)
        return card
    }

    private fun localeForLanguage(): Locale =
        when (selectedLanguage) {
            AppLanguage.Chinese -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.English -> Locale.US
            AppLanguage.French -> Locale.FRANCE
            AppLanguage.Thai -> Locale("th", "TH")
        }

    private fun parseCloudDate(value: String): Date? {
        val patterns =
            listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
            )
        patterns.forEach { pattern ->
            runCatching {
                val parser = SimpleDateFormat(pattern, Locale.US)
                if (pattern.contains("'Z'") || pattern == "yyyy-MM-dd'T'HH:mm:ss.SSS" || pattern == "yyyy-MM-dd'T'HH:mm:ss" || pattern == "yyyy-MM-dd HH:mm:ss") {
                    parser.timeZone = TimeZone.getTimeZone("UTC")
                }
                return parser.parse(value)
            }
        }
        return null
    }

    private fun titleText(
        text: String,
        sizeSp: Float,
    ): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#17343B"))
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            letterSpacing = 0.01f
        }

    private fun sectionTitle(text: String): TextView =
        bodyText(text).apply {
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#17343B"))
            setPadding(0, dp(10), 0, dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            letterSpacing = 0.01f
        }

    private fun sectionSubtitle(text: String): TextView =
        bodyText(text).apply {
            setTextColor(Color.parseColor("#557A7D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, 0, 0, dp(12))
        }

    private fun sectionLabel(text: String): TextView =
        bodyText(text).apply {
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#17343B"))
            setPadding(0, 0, 0, dp(6))
        }

    private fun activationInput(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor(selectedPalette.textPrimary))
            setHintTextColor(Color.parseColor(selectedPalette.textMuted))
            background = roundedBackground(selectedPalette.surfaceBottom, selectedPalette.stroke, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#557A7D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setLineSpacing(0f, 1.18f)
        }

    private fun surfaceCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = surfaceCardBackground()
            setPadding(dp(20), dp(20), dp(20), dp(20))
            elevation = dp(3).toFloat()
        }

    private fun surfaceCardBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#FFFFFF"))
            setStroke(dp(1), Color.parseColor("#CDEFE8"))
        }

    private fun chipBackground(accentColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(Color.argb(38, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
            setStroke(dp(1), accentColor)
        }

    private fun actionButton(
        text: String,
        color: String,
    ): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.parseColor(if (color.equals(selectedPalette.button, ignoreCase = true)) selectedPalette.buttonText else selectedPalette.textPrimary))
            background = roundedBackground(color, selectedPalette.textSecondary, 22)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            textSize = 15f
            isAllCaps = false
            elevation = dp(3).toFloat()
            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f,
                )
            applyRippleOverlay()
        }

    private fun compactActionButton(
        text: String,
        color: String,
    ): Button =
        Button(this).apply {
            this.text = text
            val useLightText =
                listOf("#10BDAA", "#16C8B5", "#FF8A32", "#E07010", selectedPalette.button, selectedPalette.accentSoft)
                    .any { color.equals(it, ignoreCase = true) }
            setTextColor(Color.parseColor(if (useLightText) "#FFFFFF" else "#17343B"))
            background = roundedBackground(color, selectedPalette.textSecondary, 20)
            setTypeface(Typeface.DEFAULT_BOLD)
            minWidth = 0
            minimumWidth = 0
            textSize = 13f
            isAllCaps = false
            setPadding(dp(16), dp(11), dp(16), dp(11))
            elevation = dp(2).toFloat()
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            applyRippleOverlay()
        }

    private fun horizontalSpace(width: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, 1)
        }

    private fun spacer(height: Int): View =
        View(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    height,
                )
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private companion object {
        const val PREFS_NAME = "reflex_ball_settings"
        const val KEY_LANGUAGE = "language"
        const val KEY_COLOR_PALETTE = "app_color_palette"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_AUTH_SERIAL = "auth_serial"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_AUTH_INSTALL_ID = "auth_install_id"
        const val KEY_AUTH_DEVICE_HASH = "auth_device_hash"
        const val KEY_AUTH_ACTIVATED_AT = "auth_activated_at"
        const val KEY_AUTH_LAST_CHECK_AT = "auth_last_check_at"
        const val KEY_LAST_SEEN_TIER = "last_seen_tier"
        const val KEY_PROFILE_AVATAR_URI = "profile_avatar_uri"
        const val KEY_LOCAL_BACKGROUND_PROFILES = "local_background_noise_profiles"
        const val KEY_SELECTED_PLAY_MODE = "selected_play_mode"
        const val KEY_TRAINING_LEVEL = "training_play_level"
        const val KEY_TRAINING_XP = "training_play_xp"
        const val KEY_TRAINING_LAST_DATE = "training_last_date"
        const val KEY_TRAINING_STREAK = "training_current_streak"
        const val KEY_BEST_TRAINING_STREAK = "training_best_streak"
        const val KEY_DAILY_TASK_DATE = "daily_task_date"
        const val KEY_DAILY_TASK_TRAINED = "daily_task_trained"
        const val KEY_DAILY_TASK_TARGET_DONE = "daily_task_target_done"
        const val KEY_DAILY_TASK_SHARED = "daily_task_shared"
        const val KEY_LOCAL_TRAINING_SESSIONS = "local_training_sessions"
        const val KEY_RHYTHM_MODE = "training_rhythm_mode"
        const val KEY_RHYTHM_MODE_ENABLED_ONCE = "training_rhythm_mode_enabled_once"
        const val KEY_IMMERSIVE_AUDIO_ENABLED_ONCE = "training_immersive_audio_enabled_once"
        const val KEY_BEAT_BPM = "training_beat_bpm"
        const val KEY_SOUND_PACK = "training_sound_pack"
        const val KEY_TRAINING_SETUP_WORK_MINUTES = "training_setup_work_minutes"
        const val KEY_TRAINING_SETUP_REST_HALF_MINUTES = "training_setup_rest_half_minutes"
        const val KEY_TRAINING_SETUP_ROUNDS = "training_setup_rounds"
        const val KEY_TRAINING_SETUP_RHYTHM_MODE = "training_setup_rhythm_mode"
        const val KEY_TRAINING_SETUP_BPM = "training_setup_bpm"
        const val KEY_TRAINING_SETUP_BEGINNER_DEFAULT_APPLIED = "training_setup_beginner_default_applied"
        const val KEY_TRAINING_SETUP_BEGINNER_20260522_APPLIED = "training_setup_beginner_20260522_applied"
        const val KEY_CLOUD_SOUND_EFFECT_ID = "training_cloud_sound_effect_id"
        const val KEY_CLOUD_SOUND_EFFECT_NAME = "training_cloud_sound_effect_name"
        const val KEY_CLOUD_SOUND_EFFECT_URL = "training_cloud_sound_effect_url"
        const val KEY_BACKGROUND_MUSIC_ID = "training_background_music_id"
        const val KEY_BACKGROUND_MUSIC_NAME = "training_background_music_name"
        const val KEY_BACKGROUND_MUSIC_URL = "training_background_music_url"
        const val KEY_BACKGROUND_MUSIC_NONE_DEFAULT_APPLIED = "training_background_music_none_default_applied"
        const val BACKGROUND_MUSIC_NONE_ID = "htr_music_none"
        const val LEGACY_AUTO_BACKGROUND_MUSIC_ID = "htr_music_champion_rush"
        const val REST_BACKGROUND_MUSIC_ID = "htr_music_rest_relax"
        const val REST_BACKGROUND_MUSIC_URL = "asset://music/00_htr_music_rest_relax.wav"
        const val KEY_LAST_BLUETOOTH_NAME = "last_bluetooth_name"
        const val KEY_LAST_BLUETOOTH_ADDRESS = "last_bluetooth_address"
        const val KEY_LAST_BLUETOOTH_TRANSPORT = "last_bluetooth_transport"
        const val KEY_LAST_BLUETOOTH_HAS_BLE = "last_bluetooth_has_ble"
        const val KEY_LAST_BLUETOOTH_HAS_CLASSIC = "last_bluetooth_has_classic"
        const val KEY_LAST_BLUETOOTH_BLE_ADDRESS = "last_bluetooth_ble_address"
        const val KEY_LAST_BLUETOOTH_CLASSIC_ADDRESS = "last_bluetooth_classic_address"
        const val KEY_BLUETOOTH_FIRST_USE_GUIDE_SHOWN = "bluetooth_first_use_guide_shown"
        const val DEVELOPER_COMPANY_NAME_ZH = "绍兴维脉科技有限公司"
        const val DEVELOPER_COMPANY_NAME_EN = "Shaoxing Weimai Technology Co., Ltd."
        const val DEVELOPER_COMPANY_NAME_FR = "Société Shaoxing Weimai Technology Co., Ltd."
        const val DEVELOPER_COMPANY_NAME_TH = "บริษัท Shaoxing Weimai Technology Co., Ltd."
        const val DEVELOPER_EMAIL = "zclei@vip.sina.com"
        const val DEVELOPER_EMAIL_SUBJECT = "HitRise APP咨询"
        const val DEFAULT_BODY_WEIGHT_KG = 70f
        const val BASE_BOXING_MET = 7.0f
        const val FORCE_REFERENCE_N = 800f
        const val MIN_DYNAMIC_MET = 4.0f
        const val MAX_DYNAMIC_MET = 10.5f
        const val KCAL_PER_FAT_GRAM = 7.7f
        const val CLOUD_EFFECT_PREVIEW_MIN_MS = 5_000L
        const val BACKGROUND_MUSIC_PREVIEW_MIN_MS = 6_000L
        const val BACKGROUND_MUSIC_PREVIEW_MAX_MS = 9_000L
        const val CLOUD_AUDIO_MAX_VISIBLE_ROWS = 5
        const val CLOUD_AUDIO_ROW_HEIGHT_DP = 78
    }
}
