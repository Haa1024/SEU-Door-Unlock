package com.unlock.door

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE 开门管理器 - 事件驱动版
 *
 * 零轮询，零盲等。所有异步操作通过回调 → CompletableDeferred 精确触发。
 */
class BleUnlockManager(
    private val context: Context,
    private val onLog: (String) -> Unit
) {
    init {
        // 确保 SettingsManager 已初始化（无论从 Activity 还是 Service 进入）
        if (!SettingsManager.isInitialized()) {
            SettingsManager.init(context.applicationContext)
        }
    }

    companion object {
        // 通过 SettingsManager 动态读取，不再硬编码
    }

    enum class State { IDLE, SCANNING, CONNECTING, UNLOCKING, SUCCESS, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _statusText = MutableStateFlow("就绪")
    val statusText: StateFlow<String> = _statusText

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    // 事件驱动：每个 BLE 操作对应一个 CompletableDeferred
    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var servicesDeferred: CompletableDeferred<Unit>? = null
    private val responseList = mutableListOf<ByteArray>()
    private var responseDeferred: CompletableDeferred<ByteArray>? = null
    private var mtuDeferred: CompletableDeferred<Unit>? = null
    private var cccdDeferred: CompletableDeferred<Unit>? = null

    private val serviceUuid = UUID.fromString(Crypto.SERVICE_UUID)
    private val writeUuid = UUID.fromString(Crypto.WRITE_UUID)
    private val readUuid = UUID.fromString(Crypto.READ_UUID)

    private fun log(msg: String) {
        onLog(msg)
        android.util.Log.d("BleUnlock", msg)
    }

    fun hasPermissions(): Boolean {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            log("权限不足: ${perms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }}")
        }
        return allGranted
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    // ─── 开门主流程 ───
    @SuppressLint("MissingPermission")
    suspend fun unlock() {
        _state.value = State.UNLOCKING
        _statusText.value = "准备中..."

        try {
            log("当前参数: deviceId=${SettingsManager.deviceId}, projectId=${SettingsManager.projectId}, credId=${SettingsManager.credentialId}, chainKey长度=${SettingsManager.chainKey.length}")
            if (!isBluetoothEnabled()) throw Exception("蓝牙未开启")

            // Step 0: 连接设备
            val mac = SettingsManager.deviceMac.takeIf { it.isNotBlank() }
            if (mac != null) {
                _statusText.value = "连接门锁..."
                _state.value = State.CONNECTING
                connectAndDiscover(mac)
            } else {
                _statusText.value = "扫描门锁..."
                _state.value = State.SCANNING
                val addr = scanDevice()
                    ?: throw Exception("未发现门锁设备 (XN-${SettingsManager.deviceId})")
                _statusText.value = "连接门锁..."
                _state.value = State.CONNECTING
                connectAndDiscover(addr)
            }

            // 调试：打印派生密钥
            val testKey = Crypto.deriveDeviceKey(SettingsManager.deviceId)
            log("派生密钥: ${Crypto.bytesToHex(testKey)}")
            log("已连接门锁，开始开门流程")

            // Step 1: 凭证头 (0x74)
            _statusText.value = "发送凭证..."
            val headPkt = Crypto.buildCredentialHead(SettingsManager.chainKey, SettingsManager.deviceId, SettingsManager.projectId)
            val headResp = sendPacketAndWait(headPkt)
                ?: throw Exception("0x74 命令超时")
            val headResult = Crypto.parseResponse(headResp, SettingsManager.deviceId)
            if (!headResult.success)
                throw Exception("0x74 解析失败: ${headResult.error}")
            val ran = headResult.ran
            log("Step 1 ok, ran=0x${ran.toString(16).uppercase()}")

            // Step 2: 凭证分包 (0x75)
            _statusText.value = "传输凭证..."
            val packs = Crypto.buildCredentialPacks(SettingsManager.chainKey, ran, SettingsManager.deviceId, SettingsManager.projectId)
            for ((i, pack) in packs.withIndex()) {
                val resp = sendPacketAndWait(pack)
                    ?: throw Exception("0x75 分包 ${i + 1} 超时")
                val result = Crypto.parseResponse(resp, SettingsManager.deviceId)
                if (!result.isSuccess)
                    throw Exception("0x75 分包 ${i + 1} 失败")
            }
            log("Step 2 ok (${packs.size} 包)")

            // Step 3: 开锁命令 (0x78)
            _statusText.value = "开锁中..."
            val unlockPkt = Crypto.buildUnlockCommand(SettingsManager.deviceId)
            val unlockResp = sendPacketAndWait(unlockPkt)
                ?: throw Exception("0x78 命令超时")
            val unlockResult = Crypto.parseResponse(unlockResp, SettingsManager.deviceId)
            log("0x78 响应: resultCode=${unlockResult.resultCode}")

            when {
                unlockResult.isSuccess -> {
                    log("开门成功! 🔓")
                    _state.value = State.SUCCESS
                    _statusText.value = if (unlockResult.doorAlreadyOpen) "门已打开 🔓" else "开门成功! 🔓"
                }
                unlockResult.needUpdateKey -> {
                    // 27: 门已开, 顺带更新密钥
                    log("门已开, 后台更新密钥...")
                    _state.value = State.SUCCESS
                    _statusText.value = "开门成功 🔓"
                    val newKey = updateChainKey()
                    if (newKey != null && !newKey.all { it == '0' }) {
                        SettingsManager.chainKey = newKey
                        log("chainKey 已更新")
                    }
                }
                unlockResult.resultCode == 25 -> {
                    // 25: 离线次数已用完 → 从服务器刷新 chainKey 后重试
                    log("离线次数已用完, 从服务器刷新凭证...")
                    // 判定条件从「有没有 token」换成「能不能重新登录」：
                    // 留着一串过期 token 没有意义，能拿到密码才算有救
                    if (!SettingsManager.canAutoRelogin && !SettingsManager.isLoggedIn()) {
                        throw Exception("离线次数已用完，且登录已失效\n请到设置页重新登录")
                    }
                    _statusText.value = "刷新凭证中..."
                    val newKey = refreshChainKeyFromServer()
                        ?: throw Exception("自动刷新凭证失败\n$lastRefreshError")
                    // 重试完整开门流程
                    retryUnlockWithNewKey()
                }
                unlockResult.resultCode == 24 -> {
                    // 24: 凭证脱链, 从服务器取回当前链位置后重试
                    log("凭证脱链(0x18), 从服务器刷新凭证...")
                    _statusText.value = "刷新凭证中..."
                    // 重登已经内建在 refreshChainKeyFromServer 里（fetchAddressAuto）：
                    // 先直连试一次，只有真的报 token 失效才去重登，少一次多余登录
                    val newKey = refreshChainKeyFromServer()
                        ?: throw Exception("自动刷新凭证失败\n$lastRefreshError")
                    // 重试完整开门流程
                    retryUnlockWithNewKey()
                }
                else -> throw Exception("开锁失败: 错误码 ${unlockResult.resultCode}")
            }
            return
        } catch (e: Exception) {
            log("失败: ${e.message}")
            _state.value = State.FAILED
            _statusText.value = e.message ?: "开门失败"
            throw e
        } finally {
            disconnect()
        }
    }

    // ─── 密钥更新 ───
    @SuppressLint("MissingPermission")
    private suspend fun updateChainKey(): String? {
        val reqPkt = Crypto.buildRechainKeyRequest(SettingsManager.credentialId, SettingsManager.deviceId)
        val resp = sendPacketAndWait(reqPkt) ?: return null
        val result = Crypto.parseResponse(resp, SettingsManager.deviceId)
        if (!result.isSuccess) return null

        val packCount = result.packCount
        val totalLen = result.totalLength
        val expectedCrc = result.crc8Val
        log("密钥分包: $packCount, 总长: $totalLen")

        val packs = mutableListOf<Crypto.PackData>()
        for (i in 0 until packCount) {
            val readPkt = Crypto.buildPackReadRequest(i, SettingsManager.deviceId)
            val readResp = sendPacketAndWait(readPkt) ?: return null
            val readResult = Crypto.parseResponse(readResp, SettingsManager.deviceId)
            if (!readResult.success) return null
            packs.add(Crypto.PackData(readResult.packIndex, readResult.resultData))
        }

        return try {
            Crypto.bytesToHex(Crypto.assembleChainKey(packs, totalLen, expectedCrc))
        } catch (e: Exception) {
            log("chainKey 拼装失败: ${e.message}")
            null
        }
    }

    // ─── 从服务器刷新 chainKey（错误 24/25 自动恢复） ───

    /** 最近一次刷新失败的原因 —— 把「为什么失败」如实报出来，而不是笼统一句"刷新失败" */
    private var lastRefreshError: String? = null

    /**
     * 从服务器取回当前有效的 chainKey。
     *
     * 走 [ServerApi.fetchAddressAuto]：内部会在识别到 token 过期时**静默重登再重试一次**。
     * 早先这里直接调 fetchAddress()，token 一过期就必然拿到 null，于是表现成
     * 「必须手动去设置页点一下登录」—— 现在这条自动恢复链才算闭环。
     */
    private suspend fun refreshChainKeyFromServer(): String? {
        lastRefreshError = null
        try {
            val addr = ServerApi.fetchAddressAuto()
            val key = addr.chainKey.trim()

            if (key.isBlank()) {
                lastRefreshError = "服务器没有返回凭证（该账号下可能没有这台门锁的记录）"
                log("刷新凭证失败: $lastRefreshError")
                return null
            }
            // 格式把关：自动流程只认 32 字节 hex。写进一个长度不对的值，
            // 会让下次开门连 0x74 都构造不出来，比"刷新失败"更糟。
            if (key.length != 64 || key.any { it !in "0123456789abcdefABCDEF" }) {
                lastRefreshError = "服务器返回的凭证格式异常（长度 ${key.length}），已放弃覆盖本地"
                log("刷新凭证失败: $lastRefreshError")
                return null
            }
            // 拉回来的和本地一模一样 → 服务器侧也没更新过，重试必然还失败。
            // 这种情况只有官方 App 开门时才会把链追上去，老实告诉用户比反复重试有用。
            if (key.equals(SettingsManager.chainKey, ignoreCase = true)) {
                lastRefreshError =
                    "服务器上的凭证和本地相同，说明服务器侧也已落后于门锁\n" +
                        "请用住理生活官方 App 开一次门追链后再试"
                log("刷新凭证失败: 服务器凭证未更新")
                return null
            }

            SettingsManager.chainKey = key
            SettingsManager.credentialId = addr.credentialId
            log("已从服务器获取新 chainKey: ${key.take(16)}...")
            return key
        } catch (e: ServerApi.TokenExpiredException) {
            lastRefreshError = if (SettingsManager.canAutoRelogin) {
                "登录已失效，静默重登也没成功（密码可能改过，请到设置页重新登录一次）"
            } else {
                "登录已失效，且本地没保存密码，无法自动重登（请到设置页点一次「支付宝一键登录」）"
            }
            log("刷新凭证失败: $lastRefreshError")
            return null
        } catch (e: Exception) {
            lastRefreshError = e.message?.take(150) ?: "网络异常"
            log("刷新凭证异常: $lastRefreshError")
            return null
        }
    }

    private suspend fun retryUnlockWithNewKey() {
        log("使用新 chainKey 重试开门...")
        val head2 = Crypto.buildCredentialHead(SettingsManager.chainKey, SettingsManager.deviceId, SettingsManager.projectId)
        val headR2 = sendPacketAndWait(head2) ?: throw Exception("重试 0x74 超时")
        val headP2 = Crypto.parseResponse(headR2, SettingsManager.deviceId)
        if (!headP2.success) throw Exception("重试 0x74 失败")
        val packs2 = Crypto.buildCredentialPacks(SettingsManager.chainKey, headP2.ran, SettingsManager.deviceId, SettingsManager.projectId)
        for ((i, p) in packs2.withIndex()) {
            val r = sendPacketAndWait(p) ?: throw Exception("重试 0x75 分包${i+1} 超时")
            if (!Crypto.parseResponse(r, SettingsManager.deviceId).isSuccess)
                throw Exception("重试 0x75 分包${i+1} 失败")
        }
        val unl2 = sendPacketAndWait(Crypto.buildUnlockCommand(SettingsManager.deviceId))
            ?: throw Exception("重试 0x78 超时")
        val unlR2 = Crypto.parseResponse(unl2, SettingsManager.deviceId)
        if (unlR2.isSuccess || unlR2.needUpdateKey) {
            log("刷新凭证后开门成功! 🔓")
            _state.value = State.SUCCESS
            _statusText.value = "开门成功 🔓"
        } else {
            throw Exception("重试失败: 错误码 ${unlR2.resultCode}")
        }
    }

    // ─── 在线重置离线计数器（错误 25 旧方案，已废弃） ───

    private suspend fun onlineReset(): Boolean {
        try {
            // 1. 调服务器创建在线命令
            log("请求服务器创建在线命令...")
            val createResult = ServerApi.createCommand(
                SettingsManager.deviceId, SettingsManager.credentialId
            )
            if (!createResult.success || createResult.payload.isEmpty()) {
                log("服务器返回失败: ${createResult.error}")
                return false
            }
            log("服务器返回 ${createResult.payload.size} 个数据包")

            // 2. 逐包发送并收集响应
            val responses = mutableListOf<String>()
            for ((i, hex) in createResult.payload.withIndex()) {
                val bytes = Crypto.hexToBytes(hex.trim())
                val resp = sendPacketAndWait(bytes)
                    ?: run { log("在线包 ${i + 1} 超时"); return false }
                responses.add(resp)
            }
            log("在线命令发送完成, 收集到 ${responses.size} 个响应")

            // 3. 提交响应给服务器解析
            log("提交响应到服务器解析...")
            val parseResult = ServerApi.parseCommand(
                createResult.responseData,
                responses.joinToString(","),
            )
            if (!parseResult.success) {
                log("服务器解析失败: ${parseResult.error}")
                return false
            }

            // 4. 保存新 chainKey
            if (parseResult.chainKey.isNotBlank()) {
                SettingsManager.chainKey = parseResult.chainKey
                log("在线重置成功, 新 chainKey 已保存")
            }
            return true
        } catch (e: Exception) {
            log("在线重置异常: ${e.message}")
            return false
        }
    }

    // ─── BLE 底层 (全事件驱动，零盲等) ───

    @SuppressLint("MissingPermission")
    private suspend fun scanDevice(): String? = withContext(Dispatchers.Main) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return@withContext null
        val targetName = "XN-${SettingsManager.deviceId}"

        suspendCancellableCoroutine { cont ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
                    val addr = result.device.address
                    // 打印所有发现的设备（调试用）
                    if (name.isNotEmpty()) {
                        log("扫描发现: [$name] $addr")
                    }
                    if (name == targetName) {
                        log(">> 命中目标: $name ($addr)")
                        scanner.stopScan(this)
                        cont.resume(addr)
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    log("扫描失败, 错误码: $errorCode")
                }
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            log("开始扫描 BLE 设备 (目标: $targetName) ...")
            try {
                scanner.startScan(emptyList(), settings, callback)
            } catch (e: SecurityException) {
                log("扫描被拒: 缺少 BLE 权限")
                cont.resume(null)
            }

            // 5 秒超时
            val timeoutJob = launch {
                delay(5000)
                scanner.stopScan(callback)
                if (cont.isActive) cont.resume(null)
            }

            // 取消时清理扫描和超时
            cont.invokeOnCancellation {
                scanner.stopScan(callback)
                timeoutJob.cancel()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndDiscover(address: String) = withContext(Dispatchers.Main) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
            ?: throw Exception("无法获取远程设备")

        // 事件驱动的连接 + 服务发现
        val gatt = device.connectGatt(context, false, gattCallback)
        bluetoothGatt = gatt

        // 等待服务发现完成 (GATT 回调触发)
        servicesDeferred = CompletableDeferred()
        try {
            withTimeout(5000) { servicesDeferred!!.await() }
        } catch (e: TimeoutCancellationException) {
            throw Exception("服务发现超时")
        }

        val service = bluetoothGatt?.getService(serviceUuid)
            ?: throw Exception("未找到 BLE 服务")
        writeCharacteristic = service.getCharacteristic(writeUuid)
            ?: throw Exception("未找到 write characteristic")
        val readChar = service.getCharacteristic(readUuid)
            ?: throw Exception("未找到 read characteristic")

        // 启用通知
        bluetoothGatt?.setCharacteristicNotification(readChar, true)
        val cccd = readChar.getDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        )
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            cccdDeferred = CompletableDeferred()
            bluetoothGatt?.writeDescriptor(cccd)
            try {
                withTimeout(1500) { cccdDeferred!!.await() }
                log("CCCD 通知已启用")
            } catch (e: TimeoutCancellationException) {
                log("CCCD 写入超时，尝试继续...")
            }
        }

        // 不请求 MTU — 门锁固定 23 字节，请求 512 必定超时 3 秒
        log("BLE 连接就绪 (MTU=23)")
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendPacketAndWait(packet: ByteArray): String? = withContext(Dispatchers.Main) {
        val char = writeCharacteristic ?: return@withContext null

        responseList.clear()
        val deferred = CompletableDeferred<ByteArray>()
        responseDeferred = deferred

        char.value = packet
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val writeOk = bluetoothGatt?.writeCharacteristic(char) ?: false
        if (!writeOk) {
            log("GATT writeCharacteristic 返回 false")
            responseDeferred = null
            return@withContext null
        }

        try {
            val resp = withTimeout(3000) { deferred.await() }
            Crypto.bytesToHex(resp)
        } catch (e: TimeoutCancellationException) {
            log("等待响应超时")
            null
        }
    }

    fun disconnect() {
        responseList.clear()
        servicesDeferred = null
        mtuDeferred = null
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
    }

    fun cancel() {
        disconnect()
        _state.value = State.IDLE
        _statusText.value = "就绪"
    }

    // ─── GATT 回调 (精确触发对应事件，不做任何额外等待) ───
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("已连接, 发现服务...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("已断开")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("服务发现完成")
                servicesDeferred?.complete(Unit)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            if (data.size >= 20) {
                responseList.add(data)
                responseDeferred?.complete(data)
                responseDeferred = null
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("CCCD 写入成功")
            } else {
                log("CCCD 写入失败: $status")
            }
            cccdDeferred?.complete(Unit)
            cccdDeferred = null
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU: $mtu")
            mtuDeferred?.complete(Unit)
            mtuDeferred = null
        }
    }
}
