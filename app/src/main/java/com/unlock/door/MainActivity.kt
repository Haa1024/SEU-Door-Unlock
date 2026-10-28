package com.unlock.door

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.unlock.door.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private var bleManager: BleUnlockManager? = null
    private val logMessages = mutableStateListOf<String>()

    /** 当前开门的协程 Job，用于取消上一次「3 秒后重置」，避免打断新一次开门 */
    private var unlockJob: Job? = null

    private val requestBluetoothEnable =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val allGranted = perms.values.all { it }
            if (!allGranted) {
                Toast.makeText(this, "需要蓝牙权限才能开门", Toast.LENGTH_LONG).show()
            }
        }

    // ── NFC ──
    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }
    /** 是否处于写卡模式（控制写卡对话框显示） */
    private val writeTagMode = mutableStateOf(false)
    /** NFC 门锁开门管理器 (读卡器模式, 官方 0xB1 协议, 逆向自官方 H5) */
    private val nfcDoorLock = NfcDoorLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SettingsManager.init(this)

        bleManager = BleUnlockManager(this) { msg ->
            logMessages.add(msg)
            if (logMessages.size > 100) logMessages.removeAt(0)
        }

        // NFC 门锁开门结果回调 (含磁标签触发 BLE 开门的特殊分支)
        nfcDoorLock.onUnlockResult = { ok, msg ->
            if (msg == NfcDoorLockManager.STICKER_UNLOCK) {
                Toast.makeText(this, "NFC 标签触发开门...", Toast.LENGTH_SHORT).show()
                doUnlock()
            } else {
                Toast.makeText(this, if (ok) "✓ $msg" else "✗ $msg", Toast.LENGTH_SHORT).show()
            }
        }

        setContent {
            SimpleUnlockTheme {
                var showSettings by remember { mutableStateOf(false) }
                var showDisclaimer by remember { mutableStateOf(!SettingsManager.agreedDisclaimer) }

                // 首次启动：免责弹窗
                if (showDisclaimer) {
                    DisclaimerDialog(
                        onAgree = {
                            SettingsManager.agreedDisclaimer = true
                            showDisclaimer = false
                        },
                        onDisagree = { finish() },
                    )
                }

                // NFC 写卡模式对话框
                if (writeTagMode.value) {
                    NfcWriteTagDialog(onCancel = { disableTagWriteMode() })
                }

                // 主界面铺的是浅绿感应场，系统栏必须跟着走 —— 否则顶上会留一条
                // #F7F9F5 的断层，整块"场"就破了。
                val barColor = if (showSettings) SeuBg else SeuGreenField
                SideEffect {
                    window.statusBarColor = barColor.toArgb()
                    window.navigationBarColor = barColor.toArgb()
                }

                // 主界面 ↔ 设置 侧滑切换
                AnimatedContent(
                    targetState = showSettings,
                    transitionSpec = {
                        if (targetState) {
                            // 进入设置：从右滑入
                            slideInHorizontally(tween(300)) { it } togetherWith
                                slideOutHorizontally(tween(300)) { -it }
                        } else {
                            // 回到主界面：从左滑入
                            slideInHorizontally(tween(300)) { -it } togetherWith
                                slideOutHorizontally(tween(300)) { it }
                        }
                    },
                ) { inSettings ->
                    if (inSettings) {
                        SettingsScreen(
                            onBack = { showSettings = false },
                            onWriteNfcTag = { enableTagWriteMode() },
                        )
                    } else {
                        val showLogs = SettingsManager.showLogs
                        UnlockScreen(
                            onUnlock = { doUnlock() },
                            onOpenSettings = { showSettings = true },
                            state = bleManager?.state?.collectAsState()?.value
                                ?: BleUnlockManager.State.IDLE,
                            statusText = bleManager?.statusText?.collectAsState()?.value ?: "就绪",
                            logs = if (showLogs) logMessages.toList() else emptyList(),
                        )
                    }
                }
            }
        }

        checkPermissions()
        createNotificationChannel()

        // NFC 磁标签触发: 若本次启动由碰标签唤起（冷启动），直接开门
        handleNfcIntent(intent)

        // 静默预检登录会话。
        //
        // platform_token 会过期，而官方 H5 根本没有 refresh token —— 它只会在收到
        // 「登录信息已失效」时把用户踢回登录页。我们本地没有登录页可踢，所以必须
        // 自己续：打开 App 时顺带确认一次，过期就悄悄用存的账号密码重登。
        //
        // 放后台跑，绝不阻塞启动；开门时若仍然撞上过期，BleUnlockManager 里
        // 还有一层兜底（错误码 24/25 → 静默重登 → 重试开门）。
        if (SettingsManager.canAutoRelogin) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = ServerApi.prewarmToken()
                android.util.Log.d("MainActivity", "会话预检: ${if (ok) "可用" else "需手动登录"}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // App 存活期间再次碰标签
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // App 前台时开启 NFC 读卡器模式:
        // 官方架构: 门锁感应区 = NDEF 标签(含设备ID), 手机 = 读卡器, 碰锁即发 0xB1 指令开门
        enableDoorLockReaderMode()
    }

    override fun onPause() {
        runCatching { nfcAdapter?.disableReaderMode(this) }
        super.onPause()
    }

    /** 开启读卡器模式 (覆盖 Type A/B/F, NDEF 由本 App 自行解析以便写卡/识别门锁标签) */
    private fun enableDoorLockReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return
        val options = Bundle().apply {
            // 在场检测要克制。这个检测和我们的 transceive 走同一条 HAL 通道，250ms 太激进：
            // 检测命令一旦插在 0xB1 收发之间，芯片报一次失败就会把标签判为离场，
            // 句柄随即失效，后面的重试全部打在一个死句柄上 —— 表现为「NFC 未成功」。
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1_000)
        }
        runCatching {
            adapter.enableReaderMode(
                this, tagCallback,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                options,
            )
        }
    }

    // ─── NFC: 磁标签 / 碰门锁 ───

    /** 处理 NFC 磁标签分发: 本 App 写入的 unlockdoor://open 标签 → 立即开门 (后台/冷启动场景) */
    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            Toast.makeText(this, "NFC 触发开门...", Toast.LENGTH_SHORT).show()
            doUnlock()
        }
    }

    /** 进入写卡模式: 前台期间碰到的 NFC 标签将被写入开门指令 (读卡器模式已由 onResume 常开) */
    private fun enableTagWriteMode() {
        val adapter = nfcAdapter
        if (adapter == null) {
            Toast.makeText(this, "本机不支持 NFC", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "NFC 已关闭, 请先到系统设置开启", Toast.LENGTH_LONG).show()
            return
        }
        nfcDoorLock.writeCardMode = true
        writeTagMode.value = true
    }

    private fun disableTagWriteMode() {
        nfcDoorLock.writeCardMode = false
        writeTagMode.value = false
    }

    /** ReaderMode 回调: 写卡模式优先写标签, 否则交给门锁协议处理 (含磁标签识别) */
    private val tagCallback = NfcAdapter.ReaderCallback { tag ->
        if (nfcDoorLock.writeCardMode) {
            // 回调在 binder 线程, 切主线程做 UI 反馈
            runOnUiThread {
                val error = NfcTagHelper.writeUnlockTag(tag)
                if (error == null) {
                    Toast.makeText(
                        this,
                        "✓ 标签写入成功! 把它贴在门边, 手机碰一下即可开门",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(this, "✗ $error", Toast.LENGTH_LONG).show()
                }
                disableTagWriteMode()
            }
            return@ReaderCallback
        }

        // 门锁协议处理 (NDEF 识别 + 0xB1 开锁 + 全量日志, 结果经 onUnlockResult 回主线程)
        nfcDoorLock.handleTag(tag)
    }

    // ─── 磁标签写卡 ───

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            "door_unlock", "共享开门", android.app.NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(android.app.NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val needRequest = perms.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest) requestPermissions.launch(perms.toTypedArray())
    }

    private fun doUnlock() {
        val manager = bleManager ?: return
        if (!manager.hasPermissions()) { checkPermissions(); return }
        if (!manager.isBluetoothEnabled()) {
            requestBluetoothEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        logMessages.clear()
        // 若上一次开门还在「3 秒展示期」内又点了一次，取消旧的延迟重置，避免打断本次动画
        unlockJob?.cancel()
        unlockJob = lifecycleScope.launch(Dispatchers.Main) {
            try { withContext(Dispatchers.IO) { manager.unlock() } }
            catch (_: Exception) {}
            // 成功后 3 秒自动恢复初始状态
            delay(3000)
            manager.cancel()
        }
    }
}

// ─── 主界面：感应场 ───
//
// 设计意图：不堆控件，而是把整屏铺成一块浅绿的「感应场」。
// 同心波纹直接对应 NFC / BLE 开门的物理动作 —— 靠近感应。
// 波纹随状态呼吸、收缩、荡开，承担一部分原本由文字承担的反馈。

@Composable
fun UnlockScreen(
    onUnlock: () -> Unit,
    onOpenSettings: () -> Unit,
    state: BleUnlockManager.State,
    statusText: String,
    logs: List<String>,
) {
    val isProcessing = state == BleUnlockManager.State.SCANNING ||
            state == BleUnlockManager.State.CONNECTING ||
            state == BleUnlockManager.State.UNLOCKING
    val isSuccess = state == BleUnlockManager.State.SUCCESS
    val isFailed  = state == BleUnlockManager.State.FAILED

    var showLogs by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SeuGreenField)
    ) {
        // ── 感应场：精确锚在整屏正中央 ──
        // 状态文字用固定偏移单独放，不参与居中计算 —— 否则它会把整组内容往上顶，
        // 场子越大顶得越明显（460dp 的场会被顶掉 25dp 左右）。
        Box(
            modifier = Modifier.align(Alignment.Center),
            contentAlignment = Alignment.Center,
        ) {
            SenseField(
                state = state,
                isProcessing = isProcessing,
                isSuccess = isSuccess,
                isFailed = isFailed,
                onClick = { if (!isProcessing) onUnlock() },
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 258.dp),
            contentAlignment = Alignment.Center,
        ) {
            StatusText(state = state, text = statusText)
        }

        // ── 顶部：设置入口 + 门锁身份 ──
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = SeuGreenDeep,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // 门锁身份
            Text(
                SettingsManager.bleName(),
                style = MaterialTheme.typography.titleLarge,
                color = SeuGreenDeep,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                SettingsManager.deviceMac.ifBlank { "自动扫描" },
                style = MaterialTheme.typography.labelSmall,
                color = SeuInkOnField,
            )
        }

        // ── 底部：主操作 + 备注意 ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            // 主操作：点击开门（走 BLE）
            Button(
                onClick = { if (!isProcessing) onUnlock() },
                enabled = !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SeuGreenFill,
                    contentColor = SeuSurface,
                    disabledContainerColor = SeuGreenFill.copy(alpha = 0.4f),
                    disabledContentColor = SeuSurface,
                ),
            ) {
                Text(
                    when {
                        isSuccess -> "已开门"
                        isFailed -> "重试"
                        isProcessing -> "开门中…"
                        else -> "点击开门"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(14.dp))

            // 备注意：另一条不用打开 App 的路。刻意压低存在感 —— 它是说明，不是操作。
            Text(
                "贴到门锁感应区，NFC 自动解锁",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                color = SeuInkOnField.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            if (logs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                LogSection(logs = logs, expanded = showLogs, onToggle = { showLogs = !showLogs })
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 感应场：同心波纹 + 中心门锁 ──

/** 待机 / 开锁时的呼吸周期（毫秒，一个完整来回） */
private const val IDLE_BREATH_MS = 4200f
private const val BUSY_BREATH_MS = 1600f

/** 待机 / 开锁时的收缩周期（毫秒，一圈从最外走到盘边） */
private const val IDLE_CONTRACT_MS = 7000f
private const val BUSY_CONTRACT_MS = 1500f

/** 从待机速度过渡到开锁速度（或反向）所用的时间 */
private const val SPEED_RAMP_MS = 1100

/** 2π —— 余弦折返用的整周期（PI.toFloat() 是函数调用，不能用 const） */
private val TWO_PI = 2f * PI.toFloat()

@Composable
private fun SenseField(
    state: BleUnlockManager.State,
    isProcessing: Boolean,
    isSuccess: Boolean,
    isFailed: Boolean,
    onClick: () -> Unit,
) {
    val accent by animateColorAsState(
        targetValue = when {
            isSuccess -> SeuGreenDeep
            isFailed  -> SeuError
            else      -> SeuGreenFill
        },
        animationSpec = tween(400),
    )

    // 速度系数：0 = 待机，1 = 开锁中。它本身用 tween 平滑过渡，
    // 所以"提速"是一个过程，而不是换挡。
    // 持 State 对象而不是用 by：下面的帧循环要读 .value，不需要参与重组。
    val speedState = animateFloatAsState(
        targetValue = if (isProcessing) 1f else 0f,
        animationSpec = tween(durationMillis = SPEED_RAMP_MS, easing = FastOutSlowInEasing),
    )

    // 相位手动推进。
    //
    // 为什么不用 InfiniteTransition：它只能整个替换 animationSpec，换的那一瞬速度
    // 是硬跳的（7000ms 直接变 1500ms），看着像突然被拽了一下。改成每帧累积相位、
    // 周期由 speed 连续插值，加速与减速就都成了渐变。
    //
    // 写入的是 MutableFloatState，且只在 Canvas 的绘制作用域里读 —— 每帧只重绘。
    val breathState = remember { mutableFloatStateOf(0f) }
    val contractState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var breathPhase = 0f
        while (true) {
            val now = withFrameNanos { it }
            val dtMs = (now - last) / 1_000_000f
            last = now

            val s = speedState.value
            val breathPeriod = IDLE_BREATH_MS + (BUSY_BREATH_MS - IDLE_BREATH_MS) * s
            val contractPeriod = IDLE_CONTRACT_MS + (BUSY_CONTRACT_MS - IDLE_CONTRACT_MS) * s

            // 呼吸：相位单向走，用余弦折成 0→1→0 的平滑来回。
            // ⚠️ 必须是 2π 不是 π：breathPhase 扫 0→1，乘 π 只覆盖 [0, π]，
            //    而 cos 在 [0, π] 上是单调的 → 折出来是 0→1 单向爬升，到点归零就成了锯齿。
            //    乘 2π 才扫满一整个余弦周期，得到真正的"呼出去再吸回来"。
            breathPhase = (breathPhase + dtMs / breathPeriod) % 1f
            breathState.floatValue = (1f - cos(TWO_PI * breathPhase)) / 2f

            // 收缩：相位单向走，跑到头自然回到最外圈
            contractState.floatValue = (contractState.floatValue + dtMs / contractPeriod) % 1f
        }
    }

    // 成功后中心盘轻轻弹一下
    val discScale by animateFloatAsState(
        targetValue = if (isSuccess) 1.06f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
    )

    // 按下反馈：只让中心实心盘缩一点，不用系统涟漪。
    //
    // 为什么不能用默认涟漪：这块是素 Box，没有 Surface 提供 contentColor，
    // LocalContentColor 会退回默认的黑色 —— 涟漪就是黑的。场子在 460dp 下
    // 会铺出一整个黑圆，看着像屏幕上糊了一块。压反馈限制在实心盘内最干净。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = tween(140),
    )

    Box(
        modifier = Modifier
            // 用 requiredSize 而不是 size：size 会被父级约束夹回屏幕宽度内，
            // 这样写才能真正溢出去，让最外圈的波纹跑出屏幕边缘。
            .requiredSize(460.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,          // 不要系统涟漪，见上面 pressScale 的说明
                enabled = !isProcessing,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 在绘制作用域里才读动画值：只重绘，不重组
            val breath = breathState.value
            val contract = contractState.value

            val base = size.minDimension / 2f - 1.dp.toPx()
            val discR = base * 0.32f * discScale * pressScale * (1f + 0.05f * breath)

            // ── 光晕：盘外一圈柔光，随呼吸明灭 ──
            // 16 层递减透明度的同心圆叠加，不用 Modifier.blur —— 后者要 API 31+，
            // 而且离屏高斯模糊的开销远高于画几个圆。
            val haloPulse = 0.35f + 0.65f * breath
            val haloLayers = 16
            for (i in haloLayers downTo 1) {
                val t = i / haloLayers.toFloat()          // 1 = 最外圈
                drawCircle(
                    color = accent.copy(alpha = 0.055f * (1f - t) * haloPulse),
                    radius = discR * (1f + 0.65f * t),
                )
            }
            // 贴着盘边的一圈细光环，给光晕一个明确的"边"，否则会糊成一团
            drawCircle(
                color = accent.copy(alpha = 0.18f + 0.22f * haloPulse),
                radius = discR * (1.14f + 0.05f * breath),
                style = Stroke(width = 1.5.dp.toPx()),
            )

            // ── 三圈波纹：只往里收，不来回荡 ──
            // 每一圈都在 [最外圈 → 盘边] 之间单向推进。透明度走正弦钟形曲线：
            // 起点 0 → 中段最亮 → 终点 0，所以新线是在最外圈慢慢浮现、再慢慢隐去，
            // 而不是在边缘"啪"地凭空出现。
            val ringCount = 3
            val ringOuter = base
            val ringInner = base * 0.44f
            repeat(ringCount) { i ->
                val phase = (contract + i / ringCount.toFloat()) % 1f
                val fade = sin(PI.toFloat() * phase)
                drawCircle(
                    color = accent.copy(alpha = 0.5f * fade),
                    radius = ringInner + (ringOuter - ringInner) * (1f - phase),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // 中心实心盘
            drawCircle(color = accent, radius = discR)
        }

        Icon(
            imageVector = when (state) {
                BleUnlockManager.State.SUCCESS -> Icons.Default.LockOpen
                BleUnlockManager.State.FAILED  -> Icons.Default.Refresh
                else -> Icons.Default.Lock
            },
            contentDescription = null,
            tint = SeuSurface,
            modifier = Modifier.size(46.dp),
        )
    }
}

// ── 状态文字 ──

@Composable
private fun StatusText(state: BleUnlockManager.State, text: String) {
    val color = when (state) {
        BleUnlockManager.State.SUCCESS -> SeuGreenDeep
        BleUnlockManager.State.FAILED  -> SeuError
        BleUnlockManager.State.UNLOCKING,
        BleUnlockManager.State.SCANNING,
        BleUnlockManager.State.CONNECTING -> SeuGreen
        else -> SeuInkOnField
    }

    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
    ) { t ->
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                t,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            fontWeight = if (state != BleUnlockManager.State.IDLE) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
        }
    }
}

// ── 日志区域 ──

@Composable
private fun LogSection(logs: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        // 半透明白：浅绿场上不抢戏，又能把日志和波纹分层
        color = SeuSurface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, SeuGreen.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "运行日志",
                    style = MaterialTheme.typography.labelMedium,
                    color = SeuInkOnField,
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = SeuInkOnField,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        logs.takeLast(15).forEach { msg ->
                            Text(
                                msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = SeuInkOnField,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 首次启动免责弹窗 ──

@Composable
private fun DisclaimerDialog(onAgree: () -> Unit, onDisagree: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 不可点外部关闭 */ },
        title = {
            Text(
                "免责声明",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                "本软件仅供学习研究蓝牙低功耗（BLE）通信协议使用，不会收集、上传或分享任何个人信息，" +
                    "所有数据仅存储在设备本地。\n\n" +
                    "在使用本软件获取门锁凭证及开门前，请确认你对该门锁设备拥有合法使用权（如已获授权、为合法租户等）。" +
                    "请勿将本软件用于非法侵入他人住所或任何违法违规用途。\n\n" +
                    "因使用本软件产生的一切后果由使用者自行承担。点击「同意」即表示你已知晓并接受以上条款。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp,
            )
        },
        confirmButton = {
            Button(onClick = onAgree, colors = ButtonDefaults.buttonColors(containerColor = SeuGreen)) {
                Text("同意")
            }
        },
        dismissButton = {
            TextButton(onClick = onDisagree) {
                Text("不同意", color = SeuError)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}

// ── NFC 标签写卡模式对话框 ──

@Composable
private fun NfcWriteTagDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                "写入 NFC 标签",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Nfc,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = SeuGreen,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "请将手机背面贴稳空白 NFC 标签\n（推荐 NTAG213 / NTAG215）\n\n" +
                        "会把「碰一碰开门」指令写入标签。\n" +
                        "写好的标签贴在门边，之后手机碰一下即可自动开门。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消", color = SeuError)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}
