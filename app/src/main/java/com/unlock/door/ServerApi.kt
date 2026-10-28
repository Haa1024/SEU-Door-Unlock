package com.unlock.door

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * 住理生活服务器 API
 * 用于登录获取 token、在线创建开门命令（重置离线计数器）
 */
object ServerApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val FORM_TYPE = "application/x-www-form-urlencoded".toMediaType()

    // 签名密钥 (从原 App 提取, 所有 API 请求共用)
    private const val SIGN_KEY = "6d5dbb85b949447a95ff8fda9a9b759b"

    /** API 服务器: 项目专属服务器，登录时自动获取，未登录则用 pm */
    private fun apiServer(): String =
        SettingsManager.projectServerUrl.ifBlank { "https://pm.whxinna.com" }

    /**
     * 平台服务器 —— 固定 pm，不随项目走。
     * H5 里平台客户端 `je` 的 baseURL 就是硬编码的 pm.whxinna.com；
     * webapi/oauth 下的接口全是平台级，挂项目服务器会 400。
     */
    private const val PLATFORM_SERVER = "https://pm.whxinna.com"

    private fun generateTimestamp(): String = (System.currentTimeMillis() / 1000).toString()

    private fun generateNonce(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { chars.random() }.joinToString("")
    }

    /** 签名算法: MD5, 可指定密钥 */
    private fun sign(params: Map<String, String>, key: String = SIGN_KEY): String {
        val sorted = params.toSortedMap()
        val query = sorted.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") {
                val v = org.json.JSONObject.quote(it.value).replace("\"", "").replace(" ", "")
                "${it.key}=$v"
            }
        val raw = "$query&key=$key"
        android.util.Log.d("ServerApi", "签名原文: $raw")
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray())
        return md5.joinToString("") { "%02X".format(it) }
    }

    /** 解码 JS encode: btoa(encodeURIComponent(str)).replace(/\+/g, "-").replace(/\//g, "_") */
    private fun decodeBase64Data(data: String): String {
        val fixed = data.replace("-", "+").replace("_", "/")
        val decoded = android.util.Base64.decode(fixed, android.util.Base64.DEFAULT)
        return String(decoded, Charsets.UTF_8)
    }

    /** 解析 server_info 对象 */
    private fun parseServerInfo(info: JSONObject) {
        val srv = info.optString("server_addr", "")
        if (srv.isNotBlank()) SettingsManager.projectServerUrl = srv
        val secret = info.optString("appsecret", "")
        if (secret.isNotBlank()) SettingsManager.projectSignKey = secret
        val sessionSecret = info.optString("session_secret", "")
        if (sessionSecret.isNotBlank()) SettingsManager.projectSessionKey = sessionSecret
        val serverAppId = info.optString("server_appid", "")
        if (serverAppId.isNotBlank()) SettingsManager.serverAppId = serverAppId
        val serverId = info.optString("server_id", "")
        if (serverId.isNotBlank()) SettingsManager.serverId = serverId
    }

    /** gt 客户端签名: 自动注入 user_id/pid/appid/identitycode */
    private fun signGtRequest(
        vararg pairs: Pair<String, String>,
    ): Map<String, String> {
        val key = SettingsManager.projectSessionKey.ifBlank {
            SettingsManager.projectSignKey.ifBlank { SIGN_KEY }
        }
        val params = mutableMapOf(*pairs)
        val uid = SettingsManager.userId
        if (uid.isNotBlank()) params["user_id"] = uid
        val pid = SettingsManager.serverAppId
        if (pid.isNotBlank()) params["pid"] = pid
        val appid = SettingsManager.serverId
        if (appid.isNotBlank()) params["appid"] = appid
        val idCode = SettingsManager.identityCode
        if (idCode.isNotBlank()) params["identitycode"] = idCode
        params["timestamp"] = generateTimestamp()
        params["noncestr"] = generateNonce()
        params["sign"] = sign(params, key)
        return params
    }

    /** 给请求参数加上 timestamp / nonce / sign, 可指定密钥 */
    private fun signRequest(
        vararg pairs: Pair<String, String>,
        key: String = SIGN_KEY,
    ): Map<String, String> {
        val params = mutableMapOf(*pairs)
        params["timestamp"] = generateTimestamp()
        params["noncestr"] = generateNonce()
        params["sign"] = sign(params, key)
        return params
    }

    // ─── 登录图形验证码 ───

    /**
     * 取登录用的**图形验证码**（SVG 文本）。
     *
     * 官方 H5（account 登录页）在被风控拦截时的完整做法：
     *   `login` 返回 msg「本次登录需要进行验证」→ 调 `get_login_code?phone=`
     *   → 拿 `data.codeImg`（一段 SVG）显示出来
     *   → 用户输入 4 位 → 带 `code` 重新 login。
     *
     * 注意这个接口**必须带签名**，裸调返回「签名不能为空 」。
     *
     * @return SVG 字符串（150×50）
     */
    suspend fun fetchLoginCaptcha(phone: String): String = withContext(Dispatchers.IO) {
        val signed = signRequest("phone" to phone)
        val query = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "$PLATFORM_SERVER/webapi/users/get_login_code?$query"

        val response = client.newCall(Request.Builder().url(url).get().build()).execute()
        val bodyStr = response.body?.string() ?: ""
        android.util.Log.d("ServerApi", "验证码响应 code=${response.code} body=${bodyStr.take(120)}")

        if (!response.isSuccessful) throw Exception("验证码获取失败 (HTTP ${response.code})")
        val json = runCatching { JSONObject(bodyStr) }.getOrElse { throw Exception("验证码解析失败") }
        if (!json.optBoolean("result", false)) throw Exception(errOf(json, "验证码获取失败"))

        // data 是 base64 编码的 JSON，里面只有 codeImg 一个字段
        val svg = runCatching {
            val dataObj = json.optJSONObject("data")
            if (dataObj != null) {
                dataObj.optString("codeImg", "")
            } else {
                val raw = json.optString("data", "")
                if (raw.startsWith("<")) raw
                else JSONObject(decodeBase64Data(raw)).optString("codeImg", "")
            }
        }.getOrElse { "" }
        if (svg.isBlank()) throw Exception("验证码内容为空")
        svg
    }

    // ─── 登录 ───

    data class LoginResult(
        val success: Boolean,
        val token: String = "",
        val projectName: String = "",
        val error: String = "",
        /** 服务器要求输入图形验证码（风控拦截），界面据此显示验证码输入框 */
        val needCaptcha: Boolean = false,
    )

    suspend fun login(phone: String, password: String, code: String = ""): LoginResult = withContext(Dispatchers.IO) {
        try {
            // GET /webapi/users/login (原 App 实际走这个 — cA = Ve = je.get("/webapi/users/login"))
            // code 是图形验证码，为空表示本次还没走到验证那一步（官方同样固定带这个字段）
            val signed = signRequest(
                "phone" to phone,
                "pwd" to password,
                "code" to code.trim(),
            )
            val query = signed.entries.joinToString("&") { "${it.key}=${it.value}" }

            // 试所有服务器
            val servers = listOf(
                "https://pm.whxinna.com",
                "https://m5-zhuli.whxinna.com",
                "https://p4-zhuli.whxinna.com",
                "https://f5-zhuli.whxinna.com",
            )
            val results = mutableListOf<String>()
            // 平台服务器给出的第一条明确拒绝理由（账号不存在 / 密码错误 / 需要验证…）
            var firstRefusal = ""
            for (srv in servers) {
                val url = "$srv/webapi/users/login?$query"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string() ?: ""
                results.add("$srv → ${response.code} ${bodyStr.take(120)}")
                if (response.code == 404 || bodyStr.isBlank()) continue
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { continue }
                if (!json.optBoolean("result", false)) {
                    val msg = json.optString("msg", "").ifBlank { json.optString("message", "") }
                    android.util.Log.d("ServerApi", "$srv 拒绝登录: $msg")
                    // 风控要求图形验证码。必须立刻把 needCaptcha 交给界面去显示验证码输入框，
                    // 不能像以前那样 continue 到下一台服务器 —— 那样这个信号就被彻底吞掉了。
                    if (msg.contains("需要进行验证") || msg.contains("CAPTCHA") || msg.contains("验证码")) {
                        return@withContext LoginResult(false, error = msg, needCaptcha = true)
                    }
                    if (firstRefusal.isBlank()) firstRefusal = msg
                    // 平台服务器已明确答复，其余三台是同名项目服务器，没有意义
                    if (msg.isNotBlank()) break
                    continue
                }

                // 1. 先查顶层 platform_token
                var token = json.optString("platform_token", "")
                if (token.isBlank()) token = json.optJSONObject("data")?.optString("platform_token", "") ?: ""

                // 打印顶层 JSON 字段 (调试)
                android.util.Log.d("ServerApi", "login 顶层字段: ${json.keys().asSequence().toList()}")
                android.util.Log.d("ServerApi", "login 完整响应: ${bodyStr.take(800)}")

                // 2. 解析 data (可能是 base64 字符串, 也可能是 JSON 对象)
                var dataJson: JSONObject? = json.optJSONObject("data")
                if (dataJson == null) {
                    val dataB64 = json.optString("data", "")
                    if (dataB64.isNotBlank()) {
                        val decoded = decodeBase64Data(dataB64)
                        dataJson = try { JSONObject(decoded) } catch (_: Exception) { null }
                    }
                }
                if (dataJson != null) {
                    if (token.isBlank()) token = dataJson.optString("platform_token", "")
                    // 保存用户 ID 和 identity_code (项目服务器会话令牌)
                    val userInfo = dataJson.optJSONObject("user_info")
                    android.util.Log.d("ServerApi", "userInfo keys: ${userInfo?.keys()?.asSequence()?.toList()}")
                    val uid = userInfo?.optString("id", "") ?: ""
                    val idCode = userInfo?.optString("identity_code", "") ?: ""
                    android.util.Log.d("ServerApi", "uid='$uid' idCode='${idCode.take(30)}...'")
                    if (uid.isNotBlank()) {
                        SettingsManager.userId = uid
                        android.util.Log.d("ServerApi", "已保存 userId=$uid")
                    } else {
                        android.util.Log.d("ServerApi", "userId 为空!")
                    }
                    if (idCode.isNotBlank()) {
                        SettingsManager.identityCode = idCode
                    } else {
                        // 兜底: 直接在顶层的 id / user_id / staff_id
                        val fallbackUid = dataJson.optString("id", "").ifBlank {
                            dataJson.optString("user_id", "").ifBlank {
                                dataJson.optString("staff_id", "")
                            }
                        }
                        if (fallbackUid.isNotBlank()) SettingsManager.userId = fallbackUid
                    }
                    val info = dataJson.optJSONObject("server_info")
                    if (info != null) {
                        parseServerInfo(info)
                    }
                }

                // 3. 顶层 server_info
                val topInfo = json.optJSONObject("server_info")
                if (topInfo != null) parseServerInfo(topInfo)
                val topSrv = json.optString("server_addr", "")
                if (topSrv.isNotBlank()) SettingsManager.projectServerUrl = topSrv

                // 4. 尝试从顶层 user_info 提取 user_id (如果 data 用 JSON 对象非 base64)
                if (SettingsManager.userId.isBlank()) {
                    val topUserInfo = json.optJSONObject("user_info")
                    val topUid = topUserInfo?.optString("id", "") ?: json.optString("id", "").ifBlank {
                        json.optString("user_id", "").ifBlank {
                            json.optString("staff_id", "")
                        }
                    }
                    if (topUid.isNotBlank()) SettingsManager.userId = topUid
                }

                if (token.isNotBlank()) {
                    SettingsManager.platformToken = token
                    SettingsManager.tokenSavedAt = System.currentTimeMillis()
                    // 把完整响应保存供调试
                    val saved = "token前20=${token.take(20)}... | 服务器=${SettingsManager.projectServerUrl.ifBlank{"无"}} | 完整=${bodyStr.take(500)}"
                    return@withContext LoginResult(true, token, saved)
                }
            }
            return@withContext LoginResult(
                false,
                error = firstRefusal.ifBlank { "登录失败：服务器没有返回有效结果" } +
                    if (SettingsManager.showLogs) "\n\n" + results.joinToString("\n") else "",
            )
        } catch (e: Exception) {
            LoginResult(false, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ─── 在线开门命令 ───

    data class CreateResult(
        val success: Boolean,
        val payload: List<String> = emptyList(), // hex 包列表, 逗号分隔的原始响应
        val rawPayload: String = "",             // 原始逗号分隔字符串, 用于回传 parse
        val responseData: String = "",           // 原始响应 JSON, 用于 parse 请求
        val error: String = "",
    )

    suspend fun createCommand(
        deviceId: Int,
        credentialId: Int,
    ): CreateResult = withContext(Dispatchers.IO) {
        try {
            // ★ 项目级 API，用 gt 客户端签名方式
            val srv = SettingsManager.projectServerUrl.ifBlank { "https://pm.whxinna.com" }
            val key = SettingsManager.projectSessionKey.ifBlank {
                SettingsManager.projectSignKey.ifBlank { SIGN_KEY }
            }
            val signed = signGtRequest(
                "device_id" to deviceId.toString(),
                "command" to "1",
                "credential_id" to credentialId.toString(),
                "type" to "ble",
            )
            val query = signed.entries.joinToString("&") { (k, v) ->
                "${k}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val url = "$srv/webapi/v1/door_lock/command/create?$query"

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val json = JSONObject(bodyStr)

            if (response.isSuccessful && json.optBoolean("result", false)) {
                val data = json.optJSONObject("data")
                val rawPayload = data?.optString("payload", "") ?: ""
                val packets = if (rawPayload.isNotBlank()) rawPayload.split(",") else emptyList()
                CreateResult(true, packets, rawPayload, bodyStr)
            } else {
                val msg = json.optString("err_msg", "").ifBlank {
                    json.optString("msg", "创建命令失败 (${response.code})")
                }
                CreateResult(false, error = msg)
            }
        } catch (e: IOException) {
            CreateResult(false, error = "网络错误: ${e.message}")
        }
    }

    data class ParseResult(
        val success: Boolean,
        val chainKey: String = "",
        val credentialId: Int = 0,
        val error: String = "",
    )

    suspend fun parseCommand(
        requestData: String,
        responsesPayload: String,
    ): ParseResult = withContext(Dispatchers.IO) {
        try {
            // ★ 项目级 API，用 gt 客户端签名方式
            val srv = SettingsManager.projectServerUrl.ifBlank { "https://pm.whxinna.com" }
            val origJson = JSONObject(requestData)
            val origData = origJson.optJSONObject("data") ?: origJson
            val signed = signGtRequest(
                "device_id" to origData.optInt("device_id", 0).toString(),
                "command" to origData.optInt("command", 1).toString(),
                "credential_id" to origData.optInt("credential_id", 0).toString(),
                "type" to "ble",
                "payload" to responsesPayload,
            )
            val body = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
                .toRequestBody(FORM_TYPE)

            val request = Request.Builder()
                .url("$srv/webapi/v1/door_lock/command/parse")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val json = JSONObject(bodyStr)

            if (response.isSuccessful && json.optBoolean("result", false)) {
                val data = json.optJSONObject("data")
                val code = data?.optString("code", "") ?: ""
                val credId = data?.optInt("credential_id", 0) ?: 0
                if (code.isNotBlank()) {
                    ParseResult(true, code, credId)
                } else {
                    ParseResult(true) // 成功但无新 key
                }
            } else {
                val msg = json.optString("err_msg", "").ifBlank {
                    json.optString("msg", "解析失败 (${response.code})")
                }
                ParseResult(false, error = msg)
            }
        } catch (e: IOException) {
            ParseResult(false, error = "网络错误: ${e.message}")
        }
    }

    // ─── 从服务器获取参数 ───

    data class DoorLockInfo(
        val deviceId: Int,
        val deviceName: String,
        val mac: String,
    )

    suspend fun fetchDoorLockList(): List<DoorLockInfo> = withContext(Dispatchers.IO) {
        try {
            val token = SettingsManager.platformToken
            if (token.isBlank()) throw Exception("未登录")

            // ★ 原 App H5 源码分析:
            //   doorLockList 通过项目客户端调用 (非平台客户端 je)
            //   项目客户端: baseURL = projectInfo.server_addr, 不带 Authorization header
            //   签名密钥: 全局 SIGN_KEY

            // 项目服务器 + 平台服务器都要试
            val servers = mutableListOf<String>()
            SettingsManager.projectServerUrl.takeIf { it.isNotBlank() }?.let { servers.add(it) }
            servers.add("https://pm.whxinna.com")  // 始终作为兜底

            val errors = mutableListOf<String>()

            for (srv in servers.distinct()) {
                // 尝试两种方式: 带 Authorization 和不带
                for (useAuth in listOf(true, false)) {
                    try {
                        val signed = signRequest(key = SIGN_KEY)
                        val query = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
                        val url = "$srv/webapi/v1/door_lock/list?$query"

                        val builder = Request.Builder().url(url).get()
                        if (useAuth) builder.header("Authorization", token)
                        val request = builder.build()

                        val response = client.newCall(request).execute()
                        val bodyStr = response.body?.string() ?: ""
                        val label = if (useAuth) "带Auth" else "无Auth"
                        errors.add("$srv($label) → ${response.code} ${bodyStr.take(100)}")

                        if (response.code == 404) continue
                        if (!response.isSuccessful) continue

                        val json = try { JSONObject(bodyStr) } catch (_: Exception) { continue }
                        if (!json.optBoolean("result", false)) {
                            val msg = json.optString("msg", "").ifBlank {
                                json.optString("err_msg", "")
                            }
                            if (msg.isNotBlank()) errors.add("$srv($label) 返回: $msg")
                            continue
                        }

                        var data = json.optJSONObject("data")
                        if (data == null) {
                            val b64 = json.optString("data", "")
                            if (b64.isNotBlank()) {
                                val d = try { decodeBase64Data(b64) } catch (_: Exception) { b64 }
                                data = try { JSONObject(d) } catch (_: Exception) { null }
                            }
                        }
                        val rows = data?.optJSONArray("rows") ?: org.json.JSONArray()
                        if (rows.length() > 0) {
                            return@withContext (0 until rows.length()).map { i ->
                                val item = rows.getJSONObject(i)
                                DoorLockInfo(
                                    deviceId = item.optInt("device_id", 0),
                                    deviceName = item.optString("device_name", ""),
                                    mac = item.optString("mac", ""),
                                )
                            }
                        }
                    } catch (_: java.io.IOException) { continue }
                }
            }
            throw Exception(errors.joinToString("\n"))
        } catch (e: Exception) {
            throw Exception("门锁列表请求失败: ${e.message}")
        }
    }

    // ─── 地址 + 门锁信息 (原 App getAddress() 真正调用的端点) ───

    data class AddressInfo(
        val buildingName: String,
        val floorName: String,
        val roomName: String,
        val deviceId: Int = 0,
        val deviceName: String = "",
        val mac: String = "",
        val batteryLevel: Int = 0,
        val credentialId: Int = 0,
        val chainKey: String = "",
    )

    suspend fun fetchAddress(): AddressInfo = withContext(Dispatchers.IO) {
        try {
            val token = SettingsManager.platformToken
            if (token.isBlank()) throw TokenExpiredException("尚未登录")

            // 调试: 打印所有 SettingsManager 值
            android.util.Log.d("ServerApi", "Settings: userId=${SettingsManager.userId}, " +
                "serverAppId=${SettingsManager.serverAppId}, serverId=${SettingsManager.serverId}, " +
                "identityCode=${SettingsManager.identityCode.take(20)}..., " +
                "projectServerUrl=${SettingsManager.projectServerUrl}, " +
                "projectSignKey=${SettingsManager.projectSignKey.take(10)}...")

            // ★ 原 App 的 getAddress() 实际调用的是 keyList 端点 (bN = Ca = () => gt.get(ht.keyList)):
            //   ht.keyList = "/webapi/v1/staff/credentials"  → 返回门锁地址数组 (含 device_id / credential)
            //   注意: 不是 /student/accommodation/details (那个现在只返回住宿地址, 不含门锁)
            val srv = SettingsManager.projectServerUrl.ifBlank {
                throw Exception("未绑定项目")
            }

            val signed = signGtRequest()
            val query = signed.entries.joinToString("&") { (k, v) ->
                "${k}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val url = "$srv/webapi/v1/staff/credentials?$query"

            // gt 客户端不送 Authorization header
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            android.util.Log.d("ServerApi", "fetchAddress 响应: $bodyStr")

            if (response.code == 401) throw TokenExpiredException("HTTP 401")
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(200)}")

            val json = try { JSONObject(bodyStr) } catch (_: Exception) {
                throw Exception("JSON 解析失败: ${bodyStr.take(200)}")
            }
            if (!json.optBoolean("result", false)) {
                // token 过期和「没绑门锁」「项目不匹配」这类业务错误必须分开，
                // 否则调用方会拿着一串作废的 token 反复重试，永远恢复不了
                if (isTokenDead(json, response.code)) {
                    throw TokenExpiredException(errOf(json, "登录信息已失效"))
                }
                val msg = json.optString("msg", "").ifBlank {
                    json.optString("err_msg", "")
                }
                throw Exception(msg.ifBlank { bodyStr.take(200) })
            }

            // ★ 原 App getAddress() 返回的 data 是「门锁地址」数组:
            //   r.data[0].building_name / device_id / ble_name / ble_mac / credential / credential_id ...
            //   字段是扁平结构, 直接平铺在数组元素上 (见 localStorage 的 doorLockAddress)
            var item: JSONObject? = json.optJSONArray("data")?.optJSONObject(0)

            // 兼容: data 是单个 JSONObject
            if (item == null) {
                item = json.optJSONObject("data")
            }
            // 兼容: data 是 base64 字符串
            if (item == null) {
                val dataB64 = json.optString("data", "")
                if (dataB64.isNotBlank()) {
                    val decoded = decodeBase64Data(dataB64)
                    item = try { JSONObject(decoded) } catch (_: Exception) {
                        try { JSONArray(decoded).optJSONObject(0) } catch (_: Exception) { null }
                    }
                }
            }
            if (item == null) {
                // data 为空数组/无门锁地址 → 返回全 0, 上层显示「未找到门锁设备」
                android.util.Log.d("ServerApi", "fetchAddress: data 为空, 完整响应: $bodyStr")
                return@withContext AddressInfo(buildingName = "", floorName = "", roomName = "")
            }

            // 兼容旧结构: 字段若嵌套在 door_lock / accommodation 子对象里则取子对象, 否则取元素本身
            val doorLock = item.optJSONObject("door_lock") ?: item
            val acc = item.optJSONObject("accommodation") ?: item

            val building = acc.optString("building_name", "")
            val floor = acc.optString("floor_name", "")
            val room = acc.optString("room_name", "")

            val devId = doorLock.optInt("device_id", 0)
            val devName = doorLock.optString("ble_name", "")
            val mac = doorLock.optString("ble_mac", "")
            val battery = doorLock.optInt("battery_level", 0)
            val credId = doorLock.optInt("credential_id", 0)
            val chainKey = doorLock.optString("credential", "")
            android.util.Log.d("ServerApi", "fetchAddress 解析: deviceId=$devId ble_name=$devName mac=$mac credential_id=$credId credential=${chainKey.take(8)}...")

            return@withContext AddressInfo(
                buildingName = building,
                floorName = floor,
                roomName = room,
                deviceId = devId,
                deviceName = devName,
                mac = mac,
                batteryLevel = battery,
                credentialId = credId,
                chainKey = chainKey,
            )
        } catch (e: TokenExpiredException) {
            throw e   // 必须原样放行：包成普通异常后，重登逻辑就再也认不出它了
        } catch (e: Exception) {
            throw Exception("地址信息请求失败: ${e.message}")
        }
    }

    data class CredentialInfo(
        val credentialId: Int,
        val chainKey: String,
        val projectId: Int,
        val isActive: Boolean,
    )

    suspend fun fetchCredentials(deviceId: Int): List<CredentialInfo> = withContext(Dispatchers.IO) {
        try {
            val token = SettingsManager.platformToken
            if (token.isBlank()) return@withContext emptyList()

            val signed = signRequest("device_id" to deviceId.toString())
            val query = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
            val request = Request.Builder()
                .url("${apiServer()}/webapi/v1/staff/door_lock/credentials?$query")
                .get()
                .header("Authorization", token)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val json = JSONObject(bodyStr)
            var data = json.optJSONObject("data")
            if (data == null) {
                val b64 = json.optString("data", "")
                if (b64.isNotBlank()) {
                    val d = try { decodeBase64Data(b64) } catch (_: Exception) { b64 }
                    data = try { JSONObject(d) } catch (_: Exception) { null }
                }
            }
            val rows = data?.optJSONArray("rows") ?: org.json.JSONArray()
            if (rows.length() == 0 && bodyStr.isNotBlank()) {
                throw Exception("空列表, 服务器返回: ${bodyStr.take(300)}")
            }

            (0 until rows.length()).map { i ->
                val item = rows.getJSONObject(i)
                CredentialInfo(
                    credentialId = item.optInt("credential_id", 0),
                    chainKey = item.optString("credential", ""),
                    projectId = item.optInt("project_id", 0),
                    isActive = item.optInt("is_active", 0) == 1,
                )
            }
        } catch (e: IOException) {
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  支付宝快捷登录
    //  完全复刻官方 H5 登录页 (public/js/phone.D9sxfyEG.js) 的三步：
    //    ① GET /webapi/oauth/alipay/auth_info  → authStr
    //    ② 支付宝 SDK AuthTask.authV2(authStr) → auth_code
    //    ③ GET /webapi/oauth/login             → platform_token
    //  ① 免 token；③ 的请求体是 base64_* 形式，见 jsEncode 注释。
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 还原 H5 客户端的 `encode()`：
     *   btoa(encodeURIComponent(str).replace(/%XX/g, 对应字节)) 再把 + / 换成 - _
     *
     * 对纯 ASCII 的 JSON 而言，「encodeURIComponent 再逐字节还原」恒等于原串，
     * 所以这里等价于 base64(UTF-8)，只是把 URL-safe 字符换掉。
     */
    private fun jsEncode(json: String): String =
        android.util.Base64
            .encodeToString(json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            .replace('+', '-')
            .replace('/', '_')

    /** 还原 H5 客户端的 `decode()`：base64 → UTF-8；data 里可能还套了一层 JSON 引号 */
    private fun decodeDataText(data: String): String {
        val decoded = decodeBase64Data(data).trim()
        if (decoded.length >= 2 && decoded.startsWith("\"") && decoded.endsWith("\"")) {
            return runCatching { org.json.JSONTokener(decoded).nextValue() as String }
                .getOrElse { decoded }
        }
        return decoded
    }

    /** 响应里的 data 字段（base64 过的 JSON 对象）→ JSONObject */
    private fun decodeDataObject(json: JSONObject): JSONObject? {
        json.optJSONObject("data")?.let { return it }
        val b64 = json.optString("data", "")
        if (b64.isBlank()) return null
        return runCatching { JSONObject(decodeDataText(b64)) }.getOrNull()
    }

    private fun errOf(json: JSONObject, fallback: String): String =
        json.optString("msg", "").ifBlank { json.optString("err_msg", fallback) }

    // ═══════════════════════════════════════════════════════════════════
    //  会话有效性
    //
    //  官方 H5 的 axios 拦截器里，token 失效只有两个信号（它本身也没有 refresh token）：
    //    · 响应 msg === "登录信息已失效，请重新登录！"
    //    · HTTP 401
    //  命中后官方直接 logout(true) 踢回登录页；我们则改成静默重登，把这一环补上。
    // ═══════════════════════════════════════════════════════════════════

    /** token 已过期 —— 与业务错误分开，好让调用方决定是否重登重试 */
    class TokenExpiredException(message: String = "登录信息已失效") : Exception(message)

    private fun isTokenDead(json: JSONObject, httpCode: Int): Boolean {
        if (httpCode == 401) return true
        if (json.optBoolean("result", false)) return false
        val msg = errOf(json, "") + json.optString("code", "")
        return msg.contains("登录信息已失效") ||
            msg.contains("重新登录") ||
            msg.contains("login_timeout") ||
            msg.contains("error_user_id")
    }

    /**
     * 用本地保存的手机号 + 密码静默重新登录。
     *
     * 走的是和设置页「登录」按钮完全相同的 [login]，所以只要那个按钮点得通，
     * 这里就一定也通 —— 早先自动恢复失败纯粹是根本没走到这一步。
     */
    suspend fun relogin(): Boolean {
        if (!SettingsManager.canAutoRelogin) {
            android.util.Log.d("ServerApi", "relogin 跳过: 本地没有手机号+密码（支付宝登录用户）")
            return false
        }
        android.util.Log.d("ServerApi", "relogin: 用本地账号静默重登 ${SettingsManager.loginPhone}")
        // login() 内部会依次试 4 台服务器，最坏情况能拖到 40 秒 —— 而这时候
        // 用户多半正站在门口等开门。给个总超时，宁可早点报失败。
        val r = withTimeoutOrNull(18_000) {
            login(SettingsManager.loginPhone, SettingsManager.loginPassword)
        }
        if (r == null) {
            android.util.Log.d("ServerApi", "relogin 超时")
            return false
        }
        android.util.Log.d("ServerApi", "relogin 结果: success=${r.success} err=${r.error.take(120)}")
        return r.success
    }

    /** [fetchAddress] 的健壮版本：token 失效时自动重登并重试一次 */
    suspend fun fetchAddressAuto(): AddressInfo = try {
        fetchAddress()
    } catch (e: TokenExpiredException) {
        android.util.Log.d("ServerApi", "fetchAddress 命中 token 过期，准备静默重登")
        SettingsManager.clearSession()
        if (!relogin()) throw e
        fetchAddress()
    }

    /**
     * 启动时的静默预检：让 token 在日常使用里被续上，别拖到开门那一刻才炸。
     *
     * 只做登录，**不碰 chainKey** —— 服务器侧的凭证可能落后于门锁（本地轮换出来的
     * key 从来没回传过），无条件覆盖会把一个能用的 key 换成废的。
     *
     * @param maxAgeHours 距上次确认可用不足这么多小时就直接跳过，避免每次冷启动都打网络
     * @return true 表示当前 token 可用
     */
    suspend fun prewarmToken(maxAgeHours: Long = 24): Boolean {
        if (!SettingsManager.canAutoRelogin) return SettingsManager.isLoggedIn()

        val age = System.currentTimeMillis() - SettingsManager.tokenSavedAt
        if (SettingsManager.isLoggedIn() && SettingsManager.tokenSavedAt > 0 &&
            age < maxAgeHours * 3600_000L
        ) {
            return true
        }

        // 太旧或已过期：先探测一次（顺带确认网络与项目服务器都通），再决定要不要重登
        return try {
            fetchAddress()
            SettingsManager.tokenSavedAt = System.currentTimeMillis()
            true
        } catch (_: TokenExpiredException) {
            SettingsManager.clearSession()
            relogin()
        } catch (e: Exception) {
            // 网络抖动之类不改动登录态，下次启动再试
            android.util.Log.d("ServerApi", "prewarm 探测失败（登录态保持不动）: ${e.message}")
            SettingsManager.isLoggedIn()
        }
    }

    /**
     * ① 取支付宝授权串 authStr。
     * 实测：该端点**不需要登录 token**，也不校验包名 / 签名 / versionName，
     * 任何人带正确签名都能拿到一份服务端 RSA2 签名过的授权串。
     */
    suspend fun fetchAlipayAuthInfo(): String = withContext(Dispatchers.IO) {
        val signed = signRequest()
        val query = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "$PLATFORM_SERVER/webapi/oauth/alipay/auth_info?$query"
        android.util.Log.d("ServerApi", "auth_info 请求: $url")

        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: ""
        android.util.Log.d("ServerApi", "auth_info 响应 code=${response.code} body=${bodyStr.take(300)}")

        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            throw Exception("授权信息解析失败 (${response.code})")
        }
        if (!json.optBoolean("result", false)) throw Exception(errOf(json, "获取授权信息失败"))

        val authStr = decodeDataText(json.optString("data", ""))
        if (authStr.isBlank()) throw Exception("授权信息为空")
        authStr
    }

    /** ③ 拿支付宝返回的 auth_code 换平台 token */
    suspend fun loginByAlipayAuthCode(authCode: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val systemInfo = JSONObject()
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("systemType", "android")
                .put("systemVersion", android.os.Build.VERSION.RELEASE)
                .put("deviceModel", android.os.Build.MODEL)
                .put("deviceToken", "")
            val authInfo = JSONObject()
                .put("auth_code", authCode)
                .put("oauth_type", "alipay_app")
                .put("sign_type", "RSA")

            val signed = signRequest(
                "base64_systemInfo" to jsEncode(systemInfo.toString()),
                "base64_authInfo" to jsEncode(authInfo.toString()),
                "app_version" to BuildConfig.VERSION_NAME,
            )
            val query = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
            val url = "$PLATFORM_SERVER/webapi/oauth/login?$query"
            android.util.Log.d("ServerApi", "支付宝登录请求: $url")

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            android.util.Log.d("ServerApi", "支付宝登录响应 code=${response.code} body=${bodyStr.take(600)}")

            val json = runCatching { JSONObject(bodyStr) }.getOrElse {
                return@withContext LoginResult(false, error = "响应解析失败 (${response.code})")
            }
            if (!json.optBoolean("result", false)) {
                return@withContext LoginResult(false, error = errOf(json, "支付宝登录失败"))
            }
            val data = decodeDataObject(json)
                ?: return@withContext LoginResult(false, error = "返回数据为空")

            val token = data.optString("platform_token", "")
            if (token.isBlank()) {
                return@withContext LoginResult(false, error = "未返回 platform_token")
            }

            data.optJSONObject("user_info")?.let { ui ->
                ui.optString("id", "").takeIf { it.isNotBlank() }?.let { SettingsManager.userId = it }
                ui.optString("identity_code", "").takeIf { it.isNotBlank() }?.let {
                    SettingsManager.identityCode = it
                }
                ui.optString("phone", "").takeIf { it.isNotBlank() }?.let {
                    SettingsManager.loginPhone = it
                }
            }
            data.optJSONObject("server_info")?.let { parseServerInfo(it) }

            SettingsManager.platformToken = token
            SettingsManager.tokenSavedAt = System.currentTimeMillis()
            val pname = data.optJSONObject("server_info")?.optString("projectname", "") ?: ""
            LoginResult(true, token, pname.ifBlank { "支付宝登录成功" })
        } catch (e: Exception) {
            LoginResult(false, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
