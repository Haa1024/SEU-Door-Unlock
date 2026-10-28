package com.unlock.door

import android.nfc.NfcAdapter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unlock.door.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页面 — 填入门锁参数并持久化保存
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onWriteNfcTag: () -> Unit,
) {

    var deviceId by remember { mutableStateOf(SettingsManager.deviceId.toString()) }
    var projectId by remember { mutableStateOf(SettingsManager.projectId.toString()) }
    var credentialId by remember { mutableStateOf(SettingsManager.credentialId.toString()) }
    var chainKey by remember { mutableStateOf(SettingsManager.chainKey) }
    var deviceMac by remember { mutableStateOf(SettingsManager.deviceMac) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // 拦截系统返回键 / 侧滑 → 回到主界面
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                "门锁设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // ── 表单 ──
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 在线登录（自动获取参数） ──
            var loginPhone by remember { mutableStateOf(SettingsManager.loginPhone) }
            var loginPassword by remember { mutableStateOf(SettingsManager.loginPassword) }
            var loginStatus by remember { mutableStateOf(
                if (SettingsManager.isLoggedIn()) "已登录" else ""
            ) }
            var loginLoading by remember { mutableStateOf(false) }

            // 图形验证码：只有被服务端风控拦住时才出现（换手机、异地登录、密码试错过多都会撞上）。
            // 官方 H5 的处理也是同一套：登录返回「本次登录需要进行验证」→ 拉 codeImg → 带 code 重登。
            var needCaptcha by remember { mutableStateOf(false) }
            var captchaSvg by remember { mutableStateOf("") }
            var captchaCode by remember { mutableStateOf("") }

            // 拉参数的状态提到这里：「登录 → 自动获取参数」要合成一步，
            // 登录按钮那边也得能写这两个状态
            var fetching by remember { mutableStateOf(false) }
            var fetchResult by remember { mutableStateOf("") }

            // 把一次拉取结果同时写进输入框和本地存储，两个入口（登录后自动 / 手动点按钮）共用
            val applyPulled: (PullOutcome) -> Unit = { outcome ->
                val a = outcome.info
                if (a != null) {
                    deviceId = a.deviceId.toString()
                    deviceMac = a.mac
                    credentialId = a.credentialId.toString()
                    projectId = SettingsManager.projectId.toString()
                    chainKey = a.chainKey
                }
                fetchResult = outcome.message
            }

            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, SeuLine),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "登录你的住理生活账号自动获取门锁参数",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = loginPhone,
                        onValueChange = {
                            loginPhone = it
                            SettingsManager.loginPhone = it.trim()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("手机号", fontSize = 13.sp) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SeuGreen, cursorColor = SeuGreen,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = loginPassword,
                        onValueChange = {
                            loginPassword = it
                            SettingsManager.loginPassword = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("密码", fontSize = 13.sp) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SeuGreen, cursorColor = SeuGreen,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))

                    // ── 图形验证码（服务端风控要求时才出现） ──
                    if (needCaptcha) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = captchaCode,
                                onValueChange = { v ->
                                    captchaCode = v.filter { it.isLetterOrDigit() }.take(4)
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("图形验证码", fontSize = 13.sp) },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = SeuGreen, cursorColor = SeuGreen,
                                ),
                            )
                            CaptchaView(
                                svg = captchaSvg,
                                modifier = Modifier.size(width = 118.dp, height = 44.dp),
                                onRefresh = {
                                    scope.launch {
                                        val svg = runCatching {
                                            withContext(Dispatchers.IO) {
                                                ServerApi.fetchLoginCaptcha(loginPhone.trim())
                                            }
                                        }.getOrNull()
                                        if (svg != null) {
                                            captchaSvg = svg
                                            captchaCode = ""
                                        }
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Button(
                            onClick = {
                                if (loginLoading) return@Button
                                loginLoading = true
                                loginStatus = ""
                                fetchResult = ""
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        ServerApi.login(loginPhone.trim(), loginPassword, captchaCode)
                                    }
                                    if (!result.success) {
                                        if (result.needCaptcha) {
                                            // 风控拦截：先把输入框亮出来，再拉一张新图
                                            needCaptcha = true
                                            captchaCode = ""
                                            loginStatus = "需要输入图形验证码"
                                            val svg = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    ServerApi.fetchLoginCaptcha(loginPhone.trim())
                                                }
                                            }.getOrNull()
                                            if (svg != null) captchaSvg = svg
                                            else loginStatus = "验证码获取失败，请检查网络后重试"
                                        } else {
                                            loginStatus = if (SettingsManager.showLogs)
                                                result.error else "登录失败"
                                        }
                                        loginLoading = false
                                        return@launch
                                    }
                                    needCaptcha = false
                                    captchaSvg = ""
                                    captchaCode = ""
                                    loginStatus = if (SettingsManager.showLogs)
                                        "已登录\n${result.projectName}" else "登录成功"

                                    // 登录成功就直接把参数接上 —— 以前要再点一次「自动获取参数」
                                    fetching = true
                                    val outcome = withContext(Dispatchers.IO) { pullDoorLockParams() }
                                    applyPulled(outcome)
                                    fetching = false
                                    loginLoading = false
                                }
                            },
                            enabled = loginPhone.isNotBlank() && loginPassword.isNotBlank() && !loginLoading &&
                                (!needCaptcha || captchaCode.length >= 4),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SeuGreenDeep,
                                disabledContainerColor = SeuGreenDeep.copy(alpha = 0.4f),
                            ),
                        ) {
                            if (loginLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = SeuSurface, strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                if (loginLoading) "登录中..." else "登录",
                                fontSize = 14.sp,
                            )
                        }

                        if (loginStatus.isNotBlank()) {
                            Text(
                                loginStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (loginStatus.startsWith("已登录")) SeuGreenDeep else SeuError,
                                lineHeight = 16.sp,
                            )
                        }
                    }

                    // ── 支付宝一键登录（本变体占用了官方包名，直接复用官方 App 的支付宝授权） ──
                    Spacer(Modifier.height(10.dp))
                    var alipayLoading by remember { mutableStateOf(false) }
                    var alipayStatus by remember { mutableStateOf("") }

                    OutlinedButton(
                        onClick = {
                            if (alipayLoading) return@OutlinedButton
                            val act = ctx as? android.app.Activity
                            if (act == null) {
                                alipayStatus = "无法获取当前界面"
                                return@OutlinedButton
                            }
                            alipayLoading = true
                            alipayStatus = ""
                            fetchResult = ""
                            scope.launch {
                                try {
                                    val authInfo = ServerApi.fetchAlipayAuthInfo()
                                    val code = AlipayLogin.auth(act, authInfo)
                                    val result = ServerApi.loginByAlipayAuthCode(code)
                                    if (result.success) {
                                        loginPhone = SettingsManager.loginPhone
                                        loginStatus = if (SettingsManager.showLogs)
                                            "已登录\n${result.projectName}" else "登录成功"
                                        // 同上：登录完直接接上拉参数
                                        fetching = true
                                        val outcome = withContext(Dispatchers.IO) { pullDoorLockParams() }
                                        applyPulled(outcome)
                                        fetching = false
                                    } else {
                                        alipayStatus = result.error
                                    }
                                } catch (e: Exception) {
                                    alipayStatus = e.message ?: e.javaClass.simpleName
                                }
                                alipayLoading = false
                            }
                        },
                        enabled = !alipayLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, SeuGreen),
                    ) {
                        if (alipayLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = SeuGreen, strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (alipayLoading) "唤起支付宝中…" else "支付宝一键登录",
                            fontSize = 14.sp,
                            color = SeuGreenDeep,
                        )
                    }
                    if (alipayStatus.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            alipayStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = SeuError,
                            lineHeight = 16.sp,
                        )
                    }

                    // 登录成功后显示自动获取按钮
                    if (SettingsManager.isLoggedIn()) {
                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                fetching = true
                                fetchResult = ""
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) { pullDoorLockParams() }
                                    applyPulled(outcome)
                                    fetching = false
                                }
                            },
                            enabled = !fetching,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(containerColor = SeuGreen),
                        ) {
                            if (fetching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = SeuSurface, strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                if (fetching) "获取中..." else "自动获取参数",
                                fontSize = 14.sp,
                            )
                        }

                        if (fetchResult.isNotBlank()) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(
                                    fetchResult,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (fetchResult.startsWith("已获取")) SeuGreenDeep else SeuError,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }
            }

            // 手动参数区提示
            Text(
                "在上方填写手机号和密码登录后自动获取，或手动填入下方参数",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )

            // 设备 ID
            ParamField(
                label = "设备 ID (Device ID)",
                value = deviceId,
                onValue = { deviceId = it },
                hint = "门锁蓝牙名称 XN- 后面的数字",
                keyboardType = KeyboardType.Number,
            )

            // 项目 ID
            ParamField(
                label = "项目 ID (Project ID)",
                value = projectId,
                onValue = { projectId = it },
                hint = "宿舍楼项目编号",
                keyboardType = KeyboardType.Number,
            )

            // 凭证 ID
            ParamField(
                label = "凭证 ID",
                value = credentialId,
                onValue = { credentialId = it },
                hint = "数字凭证编号",
                keyboardType = KeyboardType.Number,
            )

            // BLE MAC
            ParamField(
                label = "门锁 MAC 地址（可选）",
                value = deviceMac,
                onValue = { deviceMac = it },
                hint = "格式 AA:BB:CC:DD:EE:FF，留空则自动扫描",
                keyboardType = KeyboardType.Ascii,
            )

            // chainKey（多行）
            Text(
                "Chain Key（32 字节 hex）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            OutlinedTextField(
                value = chainKey,
                onValueChange = { chainKey = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                minLines = 2,
                maxLines = 3,
                placeholder = {
                    Text("32 字节 hex 字符串，从 doorLockAddress[].credential 提取",
                        fontSize = 12.sp)
                },
                singleLine = false,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SeuGreen,
                    cursorColor = SeuGreen,
                ),
            )

            Spacer(Modifier.height(4.dp))

            // ── 日志开关 ──
            var showLogs by remember { mutableStateOf(SettingsManager.showLogs) }
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, SeuLine),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "运行日志",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "出现 Bug 时开启，将日志截图发给开发者协助排查",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            lineHeight = 16.sp,
                        )
                    }
                    Switch(
                        checked = showLogs,
                        onCheckedChange = {
                            showLogs = it
                            SettingsManager.showLogs = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SeuSurface,
                            checkedTrackColor = SeuGreen,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── NFC 开锁 ──
            val context = LocalContext.current
            val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
            var showHceLog by remember { mutableStateOf(false) }

            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, SeuLine),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "NFC 开锁",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    // ① 磁标签开门 (即写即用)
                    Text(
                        "① 磁标签开门",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "把写好指令的 NFC 标签贴在门边，手机碰一下自动开门。",
                        style = MaterialTheme.typography.labelSmall,
                        color = SeuInkSoft,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onWriteNfcTag,
                        enabled = nfcAdapter != null,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SeuGreenFill,
                            disabledContainerColor = SeuGreenFill.copy(alpha = 0.4f),
                        ),
                    ) {
                        Text("写入 NFC 标签", fontSize = 14.sp)
                    }

                    Spacer(Modifier.height(18.dp))

                    // ② 手机碰锁感应区开门 (官方 0xB1 协议已破解并移植) —— App 前台时走读卡器模式
                    Text(
                        "② 手机碰锁感应区开门",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "App 打开时，手机直接碰门锁感应区即可开门。",
                        style = MaterialTheme.typography.labelSmall,
                        color = SeuInkSoft,
                        lineHeight = 18.sp,
                    )
                    PrincipleNote(
                        title = "协议流程",
                        body = "读锁标签 NDEF 取设备 ID（无 NDEF 则用已配置设备 ID）\n" +
                            "→ 写入 0xB1[RC4(projectId + chainKey)]\n" +
                            "→ 锁返回新密钥（自动轮换）",
                    )
                    TextButton(
                        onClick = { showHceLog = true },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        Text("查看 NFC 通讯日志", fontSize = 13.sp, color = SeuGreen)
                    }

                    if (nfcAdapter == null) {
                        Text(
                            "本机不支持 NFC，NFC 相关功能不可用，BLE 开门不受影响",
                            style = MaterialTheme.typography.labelSmall,
                            color = SeuError,
                            lineHeight = 16.sp,
                        )
                    } else if (!nfcAdapter.isEnabled) {
                        Text(
                            "NFC 已关闭，请到系统设置开启",
                            style = MaterialTheme.typography.labelSmall,
                            color = SeuError,
                        )
                    }
                }
            }

            // NFC 通讯日志弹窗（每 0.5 秒自动刷新，碰锁时开着即可实时看到指令）
            if (showHceLog) {
                var logs by remember { mutableStateOf(NfcDoorLockManager.snapshotLog()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        logs = NfcDoorLockManager.snapshotLog()
                        kotlinx.coroutines.delay(500)
                    }
                }
                val clipboard = LocalClipboardManager.current
                AlertDialog(
                    onDismissRequest = { showHceLog = false },
                    title = { Text("NFC 通讯日志", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            if (logs.isEmpty()) {
                                Text(
                                    "暂无日志（每 0.5 秒自动刷新）。\n\n" +
                                        "实验步骤：保持本 App 停留在本页面，手机贴住门锁感应区 3~5 秒。\n" +
                                        "正常应出现『═══ 标签发现 ═══』和 0xB1 指令记录；\n" +
                                        "若始终无日志，请确认 NFC 已开启且手机碰的是锁的 NFC 感应区。",
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 20.sp,
                                )
                            } else {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Column {
                                        logs.takeLast(30).forEach { line ->
                                            Text(
                                                line,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 16.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Row {
                            if (logs.isNotEmpty()) {
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                                }) { Text("复制全部", fontSize = 13.sp) }
                                Spacer(Modifier.width(4.dp))
                            }
                            TextButton(onClick = { showHceLog = false }) { Text("关闭") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { NfcDoorLockManager.clearLog() }) { Text("清空") }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.large,
                )
            }

            // 保存按钮
            Button(
                onClick = {
                    SettingsManager.deviceId = deviceId.toIntOrNull() ?: SettingsManager.deviceId
                    SettingsManager.projectId = projectId.toIntOrNull() ?: SettingsManager.projectId
                    SettingsManager.credentialId = credentialId.toIntOrNull() ?: SettingsManager.credentialId
                    SettingsManager.chainKey = chainKey.trim()
                    SettingsManager.deviceMac = deviceMac.trim()
                    scope.launch {
                        snackbarHostState.showSnackbar("已保存  ·  BLE: XN-${SettingsManager.deviceId}")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SeuGreen,
                ),
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("保存", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(24.dp))
            }
        }

        // Snackbar 固定在底部
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ParamField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType,
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(hint, fontSize = 13.sp) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SeuGreen,
                cursorColor = SeuGreen,
            ),
        )
    }
}

// ── 可折叠的「原理」说明 ──

/**
 * 设置页不该是说明书。
 *
 * 长段解释默认收起 —— 折叠态只占一行，把版面还给真正要动手填的输入项；
 * 想知道原理的人点一下展开，看完再收起来。
 */
@Composable
private fun PrincipleNote(
    body: String,
    title: String = "原理与注意事项",
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = SeuGreenDeep,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = SeuGreenDeep,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                body,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp, end = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = SeuInkSoft,
                lineHeight = 19.sp,
            )
        }
    }
}

// ── 「拉参数」的统一实现 ──

private data class PullOutcome(
    val message: String,
    val info: ServerApi.AddressInfo? = null,
)

/**
 * 从服务器拉取门锁参数并落盘。
 *
 * 走 [ServerApi.fetchAddressAuto]：token 过期会**自动重登再重试一次**，
 * 所以「凭证更新」这件事不再依赖用户记得先点登录。
 */
private suspend fun pullDoorLockParams(): PullOutcome = try {
    val addr = ServerApi.fetchAddressAuto()
    if (addr.deviceId == 0) {
        PullOutcome("未找到门锁设备")
    } else {
        SettingsManager.deviceId = addr.deviceId
        SettingsManager.deviceMac = addr.mac
        SettingsManager.credentialId = addr.credentialId
        // serverAppId 才是项目 ID；拿不到就保留原值，别把已有配置写成 0
        SettingsManager.serverAppId.toIntOrNull()?.let { SettingsManager.projectId = it }
        SettingsManager.chainKey = addr.chainKey
        PullOutcome(
            "已获取: XN-${addr.deviceId} (${addr.buildingName} ${addr.roomName})",
            addr,
        )
    }
} catch (e: Exception) {
    PullOutcome("获取失败: ${e.message}")
}

// ─────────────────────── 图形验证码渲染 ───────────────────────

/**
 * 服务端下发的验证码是一段 SVG（150×50，六个 path，无 transform、无文字）。
 *
 * 所以不引 WebView、也不加 SVG 依赖：直接用 Compose 自带的 [PathParser] 把 `d` 属性
 * 解析成 Path，再按比例缩放画到 Canvas 上。整个开销就是几十个 Path 对象，
 * 比拉一个 WebView 实例（几十 MB 内存 + 首次初始化延迟）轻得多。
 */
private data class CaptchaShape(
    val path: Path,
    val color: Color,
    val filled: Boolean,
)

@Composable
private fun CaptchaView(
    svg: String,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
) {
    val shapes = remember(svg) { parseCaptchaSvg(svg) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SeuSurface)
            .clickable { onRefresh() },
        contentAlignment = Alignment.Center,
    ) {
        if (shapes.isEmpty()) {
            Text("点击获取", fontSize = 12.sp, color = SeuGreen)
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
                val sx = size.width / 150f
                val sy = size.height / 50f
                withTransform({ scale(sx, sy, pivot = Offset.Zero) }) {
                    shapes.forEach { s ->
                        if (s.filled) {
                            drawPath(s.path, s.color)
                        } else {
                            drawPath(s.path, s.color, style = Stroke(width = 1.2f))
                        }
                    }
                }
            }
        }
    }
}

private fun parseCaptchaSvg(svg: String): List<CaptchaShape> {
    if (svg.isBlank()) return emptyList()
    val out = mutableListOf<CaptchaShape>()
    val pathTag = Regex("<path\\b([^>]*)>", RegexOption.IGNORE_CASE)
    val dAttr = Regex("\\bd=\"([^\"]+)\"")
    val fillAttr = Regex("\\bfill=\"([^\"]+)\"")
    val strokeAttr = Regex("\\bstroke=\"([^\"]+)\"")

    for (m in pathTag.findAll(svg)) {
        val attrs = m.groupValues[1]
        val d = dAttr.find(attrs)?.groupValues?.get(1) ?: continue
        val fill = fillAttr.find(attrs)?.groupValues?.get(1) ?: "#333333"
        val stroke = strokeAttr.find(attrs)?.groupValues?.get(1)
        val path = runCatching { PathParser().parsePathString(d).toPath() }.getOrNull() ?: continue
        if (fill == "none") {
            out.add(CaptchaShape(path, captchaColor(stroke ?: "#999999"), filled = false))
        } else {
            out.add(CaptchaShape(path, captchaColor(fill), filled = true))
        }
    }
    return out
}

private fun captchaColor(v: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(v)) }.getOrDefault(Color(0xFF333333))
