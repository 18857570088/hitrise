# HitRise / SENBALL# Bluetooth Communication Protocol and APP Integration Guide

Version: v1.0
Applicable Product: HitRise / HTR01 / `SENBALL#` boxing speed ball series
Audience: Android / iOS / cross-platform APP developers
Last Updated: 2026-08-11

## 1. Purpose

This document helps third-party APP developers integrate with the `SENBALL#` Bluetooth boxing speed ball. It covers device scanning, connection, training count, Relative Power Score, battery level, charging state, round start/rest/end control, and the minimum APP-side implementation required for stable operation.

This document describes the current stable protocol used by the HitRise project. Developers should first complete the following minimum integration loop:

1. Scan and connect to Bluetooth devices whose names match the required rule.
2. Enable the notification channel and receive 11-byte training telemetry frames.
3. Send gyroscope ON/OFF commands to control training counting.
4. Calculate newly added punches from `Data 2`.
5. Calculate the unitless Relative Power Score from `Data 6` and `Data 7`.
6. Display battery level and charging state from `Data 1` and `Data 3`.

## 2. Device Identification

### 2.1 Bluetooth Name Rule

The APP should only accept devices that satisfy both rules below:

- The Bluetooth name starts with the prefix `SENBALL#`.
- The last character of the Bluetooth name is an English letter from `A-Z` or `a-z`.

Examples:

| Bluetooth Name | Accepted | Description |
| --- | --- | --- |
| `SENBALL#00000G` | Yes | Correct prefix and the last character is an English letter |
| `SENBALL#A1B2C` | Yes | Correct prefix and the last character is an English letter |
| `SENBALL#000001` | No | The last character is not an English letter |
| `BOXING#00000G` | No | Prefix does not match |

Kotlin example:

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

### 2.2 Recommended Name Sources During Scanning

During BLE scanning, some Android phones may not immediately expose the device name through `BluetoothDevice.name`. The APP should try the following sources in order:

1. `scanRecord.deviceName`
2. `BluetoothDevice.name`
3. A readable device name parsed from raw advertising bytes

## 3. Android Permission Recommendations

The Android APP should declare the following permissions:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

Runtime permission recommendations:

- Android 12 and above: request `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
- Android 11 and below: BLE scanning usually requires location permission.
- The APP should not actively call `createBond()`. Prefer pairing-free BLE connection to avoid system pairing popups.

## 4. Bluetooth Transport

### 4.1 Recommended Transport: BLE

HitRise currently recommends BLE as the primary connection transport.

Android connection example:

```kotlin
val gatt = device.connectGatt(
    context,
    false,
    gattCallback,
    BluetoothDevice.TRANSPORT_LE
)
```

After connection succeeds, the APP should:

1. Request high connection priority.
2. Wait briefly, then discover services.
3. Find the notification characteristic and write characteristic.
4. Enable notifications.
5. Enter the training-counting flow only after the notification channel is available.

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

### 4.2 Compatibility Transport: Classic Bluetooth SPP

If the BLE channel is abnormal on a specific device or phone, Classic Bluetooth SPP may be used for engineering debugging or compatibility testing.

Classic Bluetooth may trigger the system pairing flow. Therefore, the production APP should still use BLE as the default transport. Classic Bluetooth should only be kept as an engineering or special compatibility channel, not as the default automatic connection method.

## 5. BLE GATT Characteristic Selection

The current protocol does not require one fixed full UUID. The APP can identify the required characteristics by matching UUID fragments in services and characteristics.

### 5.1 Notification Characteristic

The notification characteristic is used to receive punch telemetry frames.

Recommended priority:

1. Characteristic UUID contains `FFE4`.
2. Parent service UUID contains `FFE0`.
3. Parent service UUID contains `FFE5`.
4. Characteristic properties contain `NOTIFY` or `INDICATE`.

The APP should write the CCCD descriptor for the selected notification characteristic.

CCCD UUID:

```text
00002902-0000-1000-8000-00805f9b34fb
```

Kotlin example:

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

### 5.2 Write Characteristic

The write characteristic is used to send training control commands.

Recommended priority:

1. Characteristic UUID contains `FFE9`.
2. Characteristic UUID contains `FFE1`.
3. Parent service UUID contains `FFE0`.
4. Characteristic properties contain `WRITE` or `WRITE_NO_RESPONSE`.

Kotlin example:

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

## 6. Training Control Commands

The APP sends gyroscope ON/OFF commands through the write characteristic.

| Function | Hex Command | Description |
| --- | --- | --- |
| Enable training counting | `C5 5C 04 01` | Send when a round starts or training resumes |
| Disable training counting | `C5 5C 04 00` | Send during rest, at training end, or before disconnecting |

Kotlin example:

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

Recommended control flow:

```text
Connect device
  -> Enable notification
  -> User taps Start
  -> Countdown
  -> Send C5 5C 04 01
  -> Enter training round
  -> Round ends, send C5 5C 04 00
  -> Rest ends, send C5 5C 04 01
  -> All rounds end, send C5 5C 04 00
```

If the write channel is not ready after the countdown, the APP should not end the training immediately. It should enter a "counting channel preparing" state, retry the command or reconnect automatically, and start official timing only after the command is sent successfully.

## 7. Training Telemetry Frame

### 7.1 Frame Format

The device reports 11-byte training telemetry frames through the notification characteristic.

Fixed frame header:

```text
D5 5D 03
```

Full frame length: 11 bytes.

| Byte Index | Field | Meaning | Type |
| --- | --- | --- | --- |
| 0 | Header 1 | Fixed `D5` | UInt8 |
| 1 | Header 2 | Fixed `5D` | UInt8 |
| 2 | Command | Fixed `03`, training telemetry | UInt8 |
| 3 | Sequence | Packet sequence number, cyclic | UInt8 |
| 4 | Data 1 | Battery percentage, range `0..100` | UInt8 |
| 5 | Data 2 | Device cumulative punch count, single-byte cyclic value | UInt8 |
| 6 | Data 3 | Charging flag: `0` not charging, `1` charging | UInt8 |
| 7 | Data 4 | 8-bit backup force data | UInt8 |
| 8 | Data 5 | 8-bit backup force data | UInt8 |
| 9 | Data 6 | Low byte of 16-bit force | UInt8 |
| 10 | Data 7 | High byte of 16-bit force | UInt8 |

### 7.2 Parsing Principles

In one BLE notification, the APP may receive:

- One complete telemetry frame.
- Multiple consecutive telemetry frames.
- A valid telemetry frame with extra unrelated bytes before or after it.

Therefore, the APP should not assume that every notification starts with a complete frame at byte index 0. Instead, it should scan the byte stream for `D5 5D 03` and parse an 11-byte frame only when enough bytes remain.

## 8. Battery Level and Charging State

In the current protocol:

- `Data 1` is the battery percentage, range `0..100`.
- `Data 3` is the charging flag.
  - `0`: not charging.
  - `1`: charging.

Display recommendations:

| Data 1 | Data 3 | APP Display |
| --- | --- | --- |
| `45` | `0` | `Battery 45%` |
| `45` | `1` | `Charging 45%` |
| `100` | `0` | `Battery 100%` |
| `100` | `1` | `Charging 100%` |

Kotlin example:

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
        "Charging $percentText"
    } else {
        "Battery $percentText"
    }
}
```

## 9. Punch Count Calculation

`Data 2` is the device-side cumulative punch count raw value. It is an unsigned single-byte cyclic value. The APP should calculate newly added punches by comparing adjacent frames.

Rules:

1. The first received `Data 2` value is used only as the baseline and should not be counted.
2. If the current value is greater than or equal to the previous value, delta = current - previous.
3. If the current value is smaller than the previous value and a single-byte rollover is detected, delta = current + 256 - previous.
4. Abnormal jumps should be capped to avoid false count bursts caused by noise.

Kotlin example:

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

When a new training session starts, the APP should reset its internal training counter and rebuild the device punch baseline. This prevents punches accumulated before the current training session from being counted into the current session.

## 10. Relative Power Score Calculation

Relative Power Score uses the 16-bit little-endian value composed of `Data 6` and `Data 7` directly. It is not calibrated with a standard force-measurement device, does not represent kilograms, kilogram-force, or newtons, and must not be used for absolute or cross-device force comparisons.

Formula:

```text
relativePowerScore = Data 6 + Data 7 * 256
```

Unit: none. Display label: `Relative Power Score`.

If the value composed of `Data 6` and `Data 7` is 0, the larger raw value of `Data 4` and `Data 5` can be used as the fallback score:

```text
relativePowerScore = max(Data 4, Data 5)
```

Kotlin example:

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

Example:

```text
Data 6 = E8
Data 7 = 03
relativePowerScore = 0x03E8 = 1000
```

## 11. Complete Data Model Example

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

## 12. Telemetry Frame Parser Example

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

## 13. BLE Integration Skeleton

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

Callback interface example:

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

## 14. Telemetry Frame Examples

### Example 1: Charging, High Force

Raw frame:

```text
D5 5D 03 21 64 2A 01 18 20 E8 03
```

Parsed result:

| Field | Value | Description |
| --- | --- | --- |
| Header | `D5 5D 03` | Training telemetry frame |
| Sequence | `21` | Packet sequence |
| Data 1 | `64` | Battery `100%` |
| Data 2 | `2A` | Device cumulative punch raw value `42` |
| Data 3 | `01` | Charging |
| Data 4 | `18` | Backup force raw value |
| Data 5 | `20` | Backup force raw value |
| Data 6/7 | `E8 03` | Raw force `1000` |

Display result:

```text
Battery: Charging 100%
Relative Power Score: 1000
```

### Example 2: Not Charging, 3 New Punches

Raw frame:

```text
D5 5D 03 22 55 2D 00 12 17 58 02
```

Assume the previous `Data 2` value was `42`.

Parsed result:

```text
Current Data 2 = 45
New punches = 45 - 42 = 3
Data 1 = 85
Data 3 = 0
relativePowerScore = 0x0258 = 600
relativePowerScore = 600
```

Display result:

```text
Battery: Battery 85%
New punches: 3
Relative Power Score: 600
```

## 15. Recommended Training State Machine

The APP should manage training with the following state machine:

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

Key rules:

- Do not enter official training timing before `ChannelReady`.
- If the write channel is not ready after countdown, enter `CountingPreparing` and retry the enable command.
- If Bluetooth disconnects briefly during `Training`, do not immediately end the training. Show reconnecting status and resume as soon as possible.
- During `Rest`, send the disable command to prevent false punch counts.
- Before the next round starts, send the enable command again.
- In `Finished` or when the user manually ends training, send the disable command.

## 16. UI and Data Refresh Recommendations

During training:

- Core training data such as punch count, BPM, force, and calories should refresh in real time.
- Auxiliary areas such as battery, connection state, and device name can refresh at a lower frequency to avoid interfering with training data processing.
- Bluetooth parsing and state updates should not block the main thread for a long time.

Cloud upload and Bluetooth training should be decoupled:

- Network errors should not affect local training counting.
- After training ends, save the training result into a local pending-upload queue.
- Sync to the cloud after the network recovers.

## 17. Avoiding Pairing Popups

The production APP should:

- Prefer BLE `connectGatt`.
- Not actively call pairing or bonding APIs.
- Not use Classic Bluetooth as the default automatic connection method.
- Cancel system pairing requests for `SENBALL#` devices at the business layer if necessary.

Example:

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

## 18. Integration Test Checklist

### 18.1 Scanning Test

- Only devices starting with `SENBALL#` and ending with an English letter are displayed.
- In a complex Bluetooth environment, the APP does not connect to unrelated devices.
- A clear message is shown when no device is found.

### 18.2 Connection Test

- BLE notification can be enabled after connection succeeds.
- Training counting can start only after the write channel is available.
- Automatic connection on APP startup does not trigger a system pairing popup.

### 18.3 Training Count Test

- The first received `Data 2` value is used only as a baseline and does not increase the punch count.
- During continuous punching, punch count increases by the calculated delta.
- Punch count remains correct after `Data 2` rolls over.
- During rest, counting is disabled and punches are not falsely counted.

### 18.4 Relative Power Score Test

- When `Data 6` and `Data 7` contain a valid value, the 16-bit raw score is used first.
- Relative Power Score is displayed without a physical unit.
- The displayed value is the hardware-reported raw value, with no multiplier and no physical unit.

### 18.5 Battery and Charging Test

- When `Data 1` is in `0..100`, the percentage is displayed normally.
- When `Data 3 = 0`, the APP displays not charging state.
- When `Data 3 = 1`, the APP displays charging and the battery percentage at the same time.
- After power is connected or disconnected, the APP updates charging state according to subsequent telemetry frames.

### 18.6 Stability Test

- The APP can automatically reconnect after a short Bluetooth disconnection during training.
- If the write channel is temporarily unavailable, training does not end immediately.
- If one notification contains multiple telemetry frames, all frames can be parsed.
- If noise bytes appear before or after a telemetry frame, the correct frame header can still be found.

## 19. FAQ

### Q1: Why is the first received punch count not counted into training?

Because `Data 2` is the device cumulative punch count raw value. The APP must first establish a baseline. Otherwise, punches accumulated before connection would be incorrectly counted into the current training session.

### Q2: Does Relative Power Score represent physical force?

No. The APP displays the hardware-reported raw value without multiplying it by `0.6`. It has not been calibrated with a standard force-measurement device, so it is not kilograms, kilogram-force, or newtons. Use it only to observe relative trends under comparable conditions.

### Q3: Why should the APP confirm the write channel before training starts?

Training counting depends on the enable command. If the write channel is not ready after countdown and the APP enters training directly, the device may not start valid counting. The correct behavior is to enter a preparing state, retry the enable command, and start official training only after success.

### Q4: Will network errors affect Bluetooth training?

They should not. Bluetooth training, training result storage, and cloud upload should be decoupled. If the network is unavailable, the APP can save the training result locally and sync it later.

## 20. Minimum Integration Interface Recommendation

For easier business-layer integration, wrap the Bluetooth module with the following interface:

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

The business layer only needs to handle:

```kotlin
onConnected()
onDisconnected()
onPunch(countDelta, relativePowerScore)
onBattery(percent, charging)
onTelemetry(telemetry)
```

This design keeps Bluetooth scanning, GATT characteristic selection, notification enabling, command sending, and telemetry parsing inside the SDK or Bluetooth module. The new APP can therefore integrate with the device quickly and safely.
