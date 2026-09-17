# HitRise / SENBALL# 蓝牙通讯协议与 APP 接入示例

版本：v1.0
适用产品：HitRise / HTR01 / `SENBALL#` 系列拳击速度球
适用对象：Android / iOS / 跨平台 APP 开发工程师
最后更新：2026-08-11

## 1. 文档目的

本文档用于指导第三方 APP 快速接入 `SENBALL#` 蓝牙拳击速度球，实现设备扫描、连接、训练计数、相对力量评分、电量、充电状态、回合开始/休息/结束控制等核心能力。

本文档描述的是当前 HitRise 项目正在使用的稳定协议。开发时建议先按本文档完成最小闭环：

1. 扫描并连接符合名称规则的蓝牙设备。
2. 开启通知通道，接收 11 字节训练数据帧。
3. 发送陀螺仪开启/关闭指令，控制训练计数。
4. 根据 `数据2` 计算新增拳数。
5. 根据 `数据6`、`数据7` 计算无物理单位的相对力量评分。
6. 根据 `数据1` 与 `数据3` 显示电量和充电状态。

## 2. 设备识别规则

### 2.1 蓝牙名称规则

APP 扫描时只接受满足以下规则的设备：

- 蓝牙名称以前缀 `SENBALL#` 开头。
- 蓝牙名称最后 1 位必须是英文字母 `A-Z` 或 `a-z`。

示例：

| 蓝牙名称 | 是否接受 | 说明 |
| --- | --- | --- |
| `SENBALL#00000G` | 是 | 前缀正确，末位为英文字母 |
| `SENBALL#A1B2C` | 是 | 前缀正确，末位为英文字母 |
| `SENBALL#000001` | 否 | 末位不是英文字母 |
| `BOXING#00000G` | 否 | 前缀不匹配 |

Kotlin 示例：

```kotlin
private const val DEVICE_PREFIX = "SENBALL#"

fun isSenballDeviceName(name: String?): Boolean {
    val normalized = name?.trim().orEmpty()
    val last = normalized.lastOrNull()
    return normalized.startsWith(DEVICE_PREFIX, ignoreCase = true) &&
        last != null &&
        (last in 'A'..'Z' || last in 'a'..'z')
}
```

### 2.2 扫描名称来源建议

BLE 扫描时，部分手机可能无法从 `BluetoothDevice.name` 立即读取名称，建议按以下顺序获取：

1. `scanRecord.deviceName`
2. `BluetoothDevice.name`
3. 广播原始字节中解析出的可读设备名称

## 3. Android 权限建议

Android APP 建议声明以下权限：

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

运行时权限建议：

- Android 12 及以上：申请 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`。
- Android 11 及以下：BLE 扫描通常需要定位权限。
- APP 不应主动调用 `createBond()`，设备连接应优先使用免配对 BLE 连接，避免弹出系统配对窗口。

## 4. 蓝牙传输通道

### 4.1 推荐通道：BLE

HitRise 当前推荐使用 BLE 连接。

Android 连接示例：

```kotlin
val gatt = device.connectGatt(
    context,
    false,
    gattCallback,
    BluetoothDevice.TRANSPORT_LE
)
```

连接成功后建议：

1. 请求高连接优先级。
2. 稍作延迟后执行服务发现。
3. 查找通知特征与写入特征。
4. 开启通知。
5. 通知通道可用后再进入训练计数流程。

```kotlin
override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    if (newState == BluetoothProfile.STATE_CONNECTED) {
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        Handler(Looper.getMainLooper()).postDelayed({
            gatt.discoverServices()
        }, 350L)
    }
}
```

### 4.2 兼容通道：经典蓝牙 SPP

如设备或手机 BLE 通道异常，可在工程调试或兼容场景下使用经典蓝牙 SPP。

经典蓝牙可能触发系统配对流程，因此正式 APP 建议优先使用 BLE；经典蓝牙仅作为工程调试或特殊兼容通道，不建议作为默认自动连接方式。

## 5. BLE GATT 特征选择

当前协议不强制依赖完整固定 UUID，APP 可通过服务与特征的 UUID 片段自动识别。

### 5.1 通知特征

用于接收拳击数据帧。

推荐优先级：

1. 特征 UUID 包含 `FFE4`。
2. 所属服务 UUID 包含 `FFE0`。
3. 所属服务 UUID 包含 `FFE5`。
4. 特征属性包含 `NOTIFY` 或 `INDICATE`。

APP 应为通知特征写入 CCCD。

CCCD UUID：

```text
00002902-0000-1000-8000-00805f9b34fb
```

Kotlin 示例：

```kotlin
private val CCCD_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

fun enableNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
    gatt.setCharacteristicNotification(characteristic, true)
    val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
    val enableValue =
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
    descriptor.value = enableValue
    gatt.writeDescriptor(descriptor)
}
```

### 5.2 写入特征

用于发送训练控制指令。

推荐优先级：

1. 特征 UUID 包含 `FFE9`。
2. 特征 UUID 包含 `FFE1`。
3. 所属服务 UUID 包含 `FFE0`。
4. 特征属性包含 `WRITE` 或 `WRITE_NO_RESPONSE`。

Kotlin 示例：

```kotlin
fun isWritableCharacteristic(c: BluetoothGattCharacteristic): Boolean {
    val props = c.properties
    val canWrite =
        (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    return canWrite
}

fun writeScore(c: BluetoothGattCharacteristic): Int {
    val uuid = c.uuid.toString()
    val serviceUuid = c.service?.uuid?.toString().orEmpty()
    var score = 0
    if (uuid.contains("ffe9", ignoreCase = true)) score += 80
    if (uuid.contains("ffe1", ignoreCase = true)) score += 40
    if (serviceUuid.contains("ffe0", ignoreCase = true)) score += 20
    if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) score += 8
    if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) score += 4
    return score
}
```

## 6. 训练控制指令

APP 通过写入特征发送陀螺仪开启/关闭指令。

| 功能 | 指令十六进制 | 说明 |
| --- | --- | --- |
| 开启训练计数 | `C5 5C 04 01` | 回合开始、恢复训练时发送 |
| 关闭训练计数 | `C5 5C 04 00` | 回合休息、训练结束、断开前发送 |

Kotlin 示例：

```kotlin
fun gyroscopeCommandPayload(enabled: Boolean): ByteArray {
    return byteArrayOf(
        0xC5.toByte(),
        0x5C.toByte(),
        0x04,
        if (enabled) 0x01 else 0x00
    )
}
```

推荐控制流程：

```text
连接设备
  -> 开启通知
  -> 用户点击开始
  -> 倒计时
  -> 发送 C5 5C 04 01
  -> 进入训练回合
  -> 回合结束，发送 C5 5C 04 00
  -> 休息结束，发送 C5 5C 04 01
  -> 全部训练结束，发送 C5 5C 04 00
```

若用户点击开始后写入通道暂时未准备好，APP 不应直接结束训练，应进入“计数通道准备中”状态，自动重试或重连，确认指令发送成功后再进入正式训练计时。

## 7. 训练数据帧

### 7.1 数据帧格式

设备通过通知特征持续上报 11 字节训练数据帧。

帧头固定：

```text
D5 5D 03
```

完整帧长度：11 字节。

| 字节位置 | 字段 | 含义 | 类型 |
| --- | --- | --- | --- |
| 0 | 帧头1 | 固定 `D5` | UInt8 |
| 1 | 帧头2 | 固定 `5D` | UInt8 |
| 2 | 命令 | 固定 `03`，表示训练数据 | UInt8 |
| 3 | 序号 | 数据包序号，循环变化 | UInt8 |
| 4 | 数据1 | 电量百分比，范围 `0..100` | UInt8 |
| 5 | 数据2 | 设备累计拳数，按单字节循环 | UInt8 |
| 6 | 数据3 | 充电标志：`0` 未充电，`1` 正在充电 | UInt8 |
| 7 | 数据4 | 8 位评分备用原始数据 | UInt8 |
| 8 | 数据5 | 8 位评分备用原始数据 | UInt8 |
| 9 | 数据6 | 16 位评分低字节 | UInt8 |
| 10 | 数据7 | 16 位评分高字节 | UInt8 |

### 7.2 数据帧解析原则

APP 接收 BLE 通知时，一次通知中可能包含：

- 一个完整数据帧。
- 多个连续数据帧。
- 前后带有少量无关字节的数据。

因此 APP 不应假定通知内容一定从第 0 字节开始就是完整帧，而应在字节流中搜索 `D5 5D 03`，并在剩余长度足够时解析 11 字节。

## 8. 电量与充电状态

当前协议中：

- `数据1` 表示电量百分比，范围 `0..100`。
- `数据3` 表示充电状态。
  - `0`：未充电。
  - `1`：正在充电。

显示建议：

| 数据1 | 数据3 | APP 显示 |
| --- | --- | --- |
| `45` | `0` | `电量 45%` |
| `45` | `1` | `正在充电 45%` |
| `100` | `0` | `电量 100%` |
| `100` | `1` | `正在充电 100%` |

Kotlin 示例：

```kotlin
data class BatteryState(
    val percent: Int?,
    val charging: Boolean
)

fun parseBatteryState(batteryRaw: Int, chargingFlagRaw: Int): BatteryState {
    val percent = batteryRaw.takeIf { it in 0..100 }
    val charging = chargingFlagRaw == 1
    return BatteryState(percent = percent, charging = charging)
}

fun formatBatteryText(state: BatteryState): String {
    val percentText = state.percent?.let { "$it%" } ?: "--"
    return if (state.charging) {
        "正在充电 $percentText"
    } else {
        "电量 $percentText"
    }
}
```

## 9. 拳击次数计算

`数据2` 是设备端累计拳数的单字节值，范围按 UInt8 循环。APP 应通过相邻两帧的差值计算新增拳数。

原则：

1. 第一次收到 `数据2` 时，只作为基准值，不计入拳数。
2. 当前值大于或等于上一次值：新增拳数 = 当前值 - 上一次值。
3. 当前值小于上一次值时，如判断为单字节循环，则新增拳数 = 当前值 + 256 - 上一次值。
4. 异常跳变应做保护，避免噪声导致拳数暴增。

Kotlin 示例：

```kotlin
fun hitDelta(previousRaw: Int?, currentRaw: Int): Int {
    if (previousRaw == null) return 0

    val delta = when {
        currentRaw >= previousRaw -> currentRaw - previousRaw
        previousRaw >= 240 && currentRaw <= 15 -> currentRaw + 256 - previousRaw
        else -> 0
    }

    return delta.coerceIn(0, 32)
}
```

训练开始时建议重置 APP 内部训练计数，并重新建立设备拳数基准，避免把训练前的设备累计值计入本次训练。

## 10. 相对力量评分计算（无单位）

相对力量评分优先使用 `数据6`、`数据7` 组成的 16 位小端整数，并直接采用硬件上报的原始值。该评分未经过标准测力设备标定，不代表公斤、公斤力或牛顿，也不应用于绝对力量或跨设备比较。

计算规则：

```text
relativePowerScore = 数据6 + 数据7 * 256
```

单位：无。显示名称：`相对力量评分（Relative Power Score）`。

如果 `数据6`、`数据7` 组成的值为 0，可使用 `数据4`、`数据5` 中较大的原始值作为备用评分：

```text
relativePowerScore = max(数据4, 数据5)
```

Kotlin 示例：

```kotlin
fun readUInt16LittleEndian(low: Int, high: Int): Int {
    return (low and 0xFF) or ((high and 0xFF) shl 8)
}

fun calculateRelativePowerScore(
    forceLow: Int,
    forceHigh: Int,
    gyroForceRaw: Int,
    pressureForceRaw: Int
): Int {
    val protocolPowerScore = readUInt16LittleEndian(forceLow, forceHigh)
    val relativePowerScore =
        if (protocolPowerScore > 0) protocolPowerScore
        else maxOf(gyroForceRaw, pressureForceRaw)
    return relativePowerScore
}
```

示例：

```text
数据6 = E8
数据7 = 03
relativePowerScore = 0x03E8 = 1000
```

## 11. 完整数据模型示例

```kotlin
data class SenballTelemetry(
    val sequence: Int,
    val batteryPercent: Int?,
    val charging: Boolean,
    val rawHitCount: Int,
    val hitDelta: Int,
    val relativePowerScore: Int,
    val rawBytes: ByteArray
)
```

## 12. 数据帧解析代码示例

```kotlin
class SenballPacketParser {
    private var previousHitRaw: Int? = null

    fun parsePackets(value: ByteArray): List<SenballTelemetry> {
        val result = mutableListOf<SenballTelemetry>()
        var index = 0

        while (index <= value.size - 11) {
            val isHeader =
                (value[index].toInt() and 0xFF) == 0xD5 &&
                    (value[index + 1].toInt() and 0xFF) == 0x5D &&
                    (value[index + 2].toInt() and 0xFF) == 0x03

            if (!isHeader) {
                index += 1
                continue
            }

            val sequence = value[index + 3].toInt() and 0xFF
            val batteryRaw = value[index + 4].toInt() and 0xFF
            val hitRaw = value[index + 5].toInt() and 0xFF
            val chargingFlag = value[index + 6].toInt() and 0xFF
            val gyroForceRaw = value[index + 7].toInt() and 0xFF
            val pressureForceRaw = value[index + 8].toInt() and 0xFF
            val forceLow = value[index + 9].toInt() and 0xFF
            val forceHigh = value[index + 10].toInt() and 0xFF

            val delta = hitDelta(previousHitRaw, hitRaw)
            previousHitRaw = hitRaw

            val relativePowerScore = calculateRelativePowerScore(
                forceLow = forceLow,
                forceHigh = forceHigh,
                gyroForceRaw = gyroForceRaw,
                pressureForceRaw = pressureForceRaw
            )

            val battery = parseBatteryState(batteryRaw, chargingFlag)

            result += SenballTelemetry(
                sequence = sequence,
                batteryPercent = battery.percent,
                charging = battery.charging,
                rawHitCount = hitRaw,
                hitDelta = delta,
                relativePowerScore = relativePowerScore,
                rawBytes = value.copyOfRange(index, index + 11)
            )

            index += 11
        }

        return result
    }

    fun resetHitBaseline() {
        previousHitRaw = null
    }
}
```

## 13. BLE 接入骨架示例

```kotlin
class SenballBleClient(
    private val context: Context,
    private val callback: SenballCallback
) {
    private var gatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val parser = SenballPacketParser()

    fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun startCounting() {
        sendCommand(gyroscopeCommandPayload(true))
        parser.resetHitBaseline()
    }

    fun stopCounting() {
        sendCommand(gyroscopeCommandPayload(false))
    }

    private fun sendCommand(payload: ByteArray) {
        val g = gatt ?: return
        val c = writeCharacteristic ?: return
        c.value = payload
        c.writeType =
            if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        g.writeCharacteristic(c)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                callback.onConnected()
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                Handler(Looper.getMainLooper()).postDelayed({
                    gatt.discoverServices()
                }, 350L)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                callback.onDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristics = gatt.services.flatMap { it.characteristics }

            notifyCharacteristic = characteristics
                .filter { c ->
                    val props = c.properties
                    (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                        (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }
                .maxByOrNull { c ->
                    val uuid = c.uuid.toString()
                    val serviceUuid = c.service?.uuid?.toString().orEmpty()
                    var score = 0
                    if (uuid.contains("ffe4", ignoreCase = true)) score += 80
                    if (serviceUuid.contains("ffe0", ignoreCase = true)) score += 30
                    if (serviceUuid.contains("ffe5", ignoreCase = true)) score += 20
                    score
                }

            writeCharacteristic = characteristics
                .filter(::isWritableCharacteristic)
                .maxByOrNull(::writeScore)

            notifyCharacteristic?.let { enableNotify(gatt, it) }
            callback.onReady(
                notifyReady = notifyCharacteristic != null,
                writeReady = writeCharacteristic != null
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val packets = parser.parsePackets(characteristic.value ?: return)
            packets.forEach { telemetry ->
                callback.onTelemetry(telemetry)
                if (telemetry.hitDelta > 0) {
                    callback.onPunch(
                        countDelta = telemetry.hitDelta,
                        relativePowerScore = telemetry.relativePowerScore
                    )
                }
                callback.onBattery(
                    percent = telemetry.batteryPercent,
                    charging = telemetry.charging
                )
            }
        }
    }
}
```

回调接口示例：

```kotlin
interface SenballCallback {
    fun onConnected()
    fun onDisconnected()
    fun onReady(notifyReady: Boolean, writeReady: Boolean)
    fun onTelemetry(telemetry: SenballTelemetry)
    fun onPunch(countDelta: Int, relativePowerScore: Int)
    fun onBattery(percent: Int?, charging: Boolean)
}
```

## 14. 典型数据帧解析示例

### 示例一：正在充电，相对力量评分较高

原始帧：

```text
D5 5D 03 21 64 2A 01 18 20 E8 03
```

解析：

| 字段 | 值 | 说明 |
| --- | --- | --- |
| 帧头 | `D5 5D 03` | 训练数据帧 |
| 序号 | `21` | 数据包序号 |
| 数据1 | `64` | 电量 `100%` |
| 数据2 | `2A` | 设备累计拳数原始值 `42` |
| 数据3 | `01` | 正在充电 |
| 数据4 | `18` | 备用评分原始值 |
| 数据5 | `20` | 备用评分原始值 |
| 数据6/7 | `E8 03` | 硬件原始评分 `1000` |

显示结果：

```text
电量：正在充电 100%
相对力量评分：1000
```

### 示例二：未充电，新增 3 拳

原始帧：

```text
D5 5D 03 22 55 2D 00 12 17 58 02
```

假设上一帧 `数据2 = 42`。

解析：

```text
当前 数据2 = 45
新增拳数 = 45 - 42 = 3
数据1 = 85
数据3 = 0
relativePowerScore = 0x0258 = 600
relativePowerScore = 600
```

显示结果：

```text
电量：电量 85%
新增拳数：3
相对力量评分：600
```

## 15. 训练状态机建议

APP 建议使用以下状态机管理训练：

```text
Idle
  -> Scanning
  -> Connecting
  -> Connected
  -> ChannelReady
  -> Countdown
  -> CountingPreparing
  -> Training
  -> Rest
  -> Training
  -> Finished
```

关键规则：

- `ChannelReady` 前不要进入正式训练计时。
- 倒计时结束后如写入通道未就绪，应进入 `CountingPreparing`，重试发送开启指令。
- `Training` 中蓝牙短暂断开时，不建议直接结束训练，应提示重连并尽快恢复。
- `Rest` 中应发送关闭指令，避免休息时误计拳数。
- 下一回合开始前应重新发送开启指令。
- `Finished` 或用户主动结束时应发送关闭指令。

## 16. UI 与数据刷新建议

训练期间：

- 拳数、BPM、相对力量评分、卡路里等核心训练数据应实时刷新。
- 电量、连接状态、设备名称等辅助区域可降低刷新频率，避免影响训练数据处理。
- 所有蓝牙解析与状态更新应避免长时间阻塞主线程。

数据上传与蓝牙训练应解耦：

- 网络异常不应影响本地训练计数。
- 训练结束后可将训练记录加入本地待上传队列。
- 网络恢复后再同步云端。

## 17. 防配对弹窗建议

正式 APP 建议：

- 优先使用 BLE `connectGatt`。
- 不主动调用配对或绑定接口。
- 不把经典蓝牙作为默认自动连接方式。
- 对 `SENBALL#` 设备的系统配对请求可在业务层拦截并取消。

示例：

```kotlin
val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
        val device =
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (isSenballDeviceName(device?.name)) {
            abortBroadcast()
        }
    }
}
```

## 18. 接入测试清单

### 18.1 扫描测试

- 只显示 `SENBALL#` 开头且末位为英文字母的设备。
- 复杂蓝牙环境下不会连接到非目标设备。
- 扫描不到设备时有明确提示。

### 18.2 连接测试

- BLE 连接成功后能开启通知。
- 写入通道可用后才能开始训练计数。
- APP 打开时自动连接不会弹出系统配对窗口。

### 18.3 训练计数测试

- 第一次收到 `数据2` 只建立基准，不增加拳数。
- 连续击打时拳数按新增值累加。
- `数据2` 循环后拳数仍能正确累计。
- 休息期间关闭计数，不误计拳数。

### 18.4 相对力量评分测试

- `数据6`、`数据7` 有值时优先使用 16 位原始评分。
- 相对力量评分不显示物理单位。
- APP 直接显示硬件原始值，不乘以 `0.6`，不得标识为 kg、kgf 或 N。

### 18.5 电量与充电测试

- `数据1` 为 `0..100` 时正常显示百分比。
- `数据3 = 0` 时显示未充电状态。
- `数据3 = 1` 时显示正在充电并同时显示电量百分比。
- 插入或拔出电源后，APP 能根据后续数据帧更新充电状态。

### 18.6 稳定性测试

- 训练过程中短暂断开蓝牙后可自动重连。
- 写入通道暂时未准备好时不会直接结束训练。
- 一次通知包含多个数据帧时可以全部解析。
- 通知前后带噪声字节时可以找到正确帧头。

## 19. 常见问题

### Q1：为什么第一次收到拳数不计入训练？

因为 `数据2` 是设备累计拳数原始值，APP 需要先建立基准。否则连接前已经存在的设备累计值会被误算进本次训练。

### Q2：Relative Power Score 是否代表实际力量？

不代表。APP 直接显示硬件上报的原始值，不乘以 `0.6`。由于尚未通过标准测力设备完成标定，该值仅用于同一设备、相近安装与动作条件下观察相对变化，不是公斤、公斤力或牛顿。

### Q3：为什么建议训练开始前确认写入通道可用？

训练计数依赖开启指令。如果倒计时结束后写入通道尚未准备好，直接进入训练可能导致设备未开始上报有效计数。正确做法是进入准备状态，重试开启指令，成功后再开始正式训练。

### Q4：网络异常是否会影响蓝牙训练？

不应影响。蓝牙训练、训练记录保存、云端上传应解耦。网络异常时可以本地保存训练结果，稍后再同步。

## 20. 最小接入接口建议

为了便于业务层调用，建议封装如下接口：

```kotlin
interface SenballClient {
    fun startScan()
    fun stopScan()
    fun connect(device: BluetoothDevice)
    fun disconnect()
    fun startTrainingCounting()
    fun stopTrainingCounting()
    fun resetTrainingBaseline()
}
```

业务层只需要关注：

```kotlin
onConnected()
onDisconnected()
onPunch(countDelta, relativePowerScore)
onBattery(percent, charging)
onTelemetry(telemetry)
```

这样可将蓝牙扫描、GATT 特征选择、通知开启、指令发送、数据帧解析等复杂细节封装在 SDK 或蓝牙模块内部，方便新 APP 快速接入。
