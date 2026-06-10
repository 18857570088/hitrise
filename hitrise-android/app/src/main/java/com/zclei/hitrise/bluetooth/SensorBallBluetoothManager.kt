package com.zclei.hitrise.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.roundToInt

data class SensorBallDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val transport: SensorBallTransport = SensorBallTransport.Ble,
    val hasBle: Boolean = transport == SensorBallTransport.Ble,
    val hasClassic: Boolean = transport == SensorBallTransport.Classic,
    val bleAddress: String? = if (transport == SensorBallTransport.Ble) address else null,
    val classicAddress: String? = if (transport == SensorBallTransport.Classic) address else null,
)

enum class SensorBallTransport {
    Ble,
    Classic,
}

data class SensorBallTelemetry(
    val packetIndex: Int,
    val batteryRaw: Int,
    val hitCount: Int,
    val pressureHitCount: Int,
    val gyroForceRaw: Int,
    val pressureForceRaw: Int,
    val forceLow: Int,
    val forceHigh: Int,
    val forceN: Int,
) {
    val peak: Int
        get() = forceN

    val batteryText: String =
        when (batteryRaw) {
            101 -> "充电"
            102 -> "充满"
            in 0..100 -> "$batteryRaw%"
            else -> "--"
        }
}

interface SensorBallBluetoothCallback {
    fun onStatus(message: String)
    fun onDevicesChanged(devices: List<SensorBallDevice>)
    fun onConnected(device: SensorBallDevice)
    fun onDisconnected()
    fun onTelemetry(telemetry: SensorBallTelemetry)
}

class SensorBallBluetoothManager(
    context: Context,
    private val callback: SensorBallBluetoothCallback,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private val scanSettings =
        ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
    private val devices = linkedMapOf<String, SensorBallDevice>()
    private var gatt: BluetoothGatt? = null
    private var classicSocket: BluetoothSocket? = null
    private var connectedDevice: SensorBallDevice? = null
    private var pendingFallbackDevice: SensorBallDevice? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val pendingNotificationDescriptors = ArrayDeque<BluetoothGattDescriptor>()
    private val classicTelemetryBuffer = ArrayDeque<Byte>()
    private var bleSetupInProgress = false
    private var bleWriteInFlight = false
    private var bleWriteSequence = 0
    private var pendingGyroscopeCommand: Boolean? = null
    private var scanning = false
    private var classicFallbackAllowed = true
    @Volatile
    private var classicReadLoopActive = false
    @Volatile
    private var suppressNextBleDisconnectCallback = false

    private val classicReceiver =
        object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        val name = device.name ?: return
                        if (!isBoxingDeviceName(name)) {
                            return
                        }
                        val item =
                            SensorBallDevice(
                                name = name,
                                address = device.address,
                                rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt(),
                                transport = SensorBallTransport.Classic,
                            )
                        addOrMergeDevice(item)
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (scanning) {
                            callback.onStatus("扫描完成，发现 ${devices.size} 个 SENBALL# 设备")
                        }
                    }
                }
            }
        }

    private val pairingRequestReceiver =
        object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action != BluetoothDevice.ACTION_PAIRING_REQUEST && action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    return
                }
                val device =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                if (!device.isKnownBoxingDeviceForPairing()) {
                    return
                }
                if (action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                    runCatching {
                        if (isOrderedBroadcast) {
                            abortBroadcast()
                        }
                    }
                    runCatching { device.setPairingConfirmation(false) }
                    runCatching { device.javaClass.getMethod("cancelBondProcess").invoke(device) }
                    callback.onStatus("已取消蓝牙配对请求，HitRise 将继续使用免配对 BLE 连接")
                } else {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (bondState == BluetoothDevice.BOND_BONDING) {
                        runCatching { device.javaClass.getMethod("cancelBondProcess").invoke(device) }
                        callback.onStatus("已阻止 SENBALL# 设备进入配对流程")
                    }
                }
            }
        }

    init {
        registerPairingRequestReceiver()
    }

    private val scanCallback =
        object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.extractDeviceName() ?: return
                if (!isBoxingDeviceName(name)) {
                    return
                }
                val item = SensorBallDevice(name = name, address = device.address, rssi = result.rssi, transport = SensorBallTransport.Ble)
                addOrMergeDevice(item)
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                callback.onStatus("扫描失败：$errorCode")
            }
        }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter == null || adapter?.isEnabled != true) {
            callback.onStatus("蓝牙未开启")
            return
        }
        if (scanning) {
            stopScan()
        }
        devices.clear()
        callback.onDevicesChanged(emptyList())
        scanning = true
        registerClassicReceiver()
        addBondedBoxingDevices()
        adapter?.cancelDiscovery()
        scanner?.startScan(null, scanSettings, scanCallback)
        callback.onStatus("正在扫描 SENBALL# 设备...")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) {
            return
        }
        scanner?.stopScan(scanCallback)
        adapter?.cancelDiscovery()
        scanning = false
    }

    @SuppressLint("MissingPermission")
    fun connect(
        device: SensorBallDevice,
        allowClassicFallback: Boolean = false,
    ) {
        stopScan()
        disconnectInternal(notify = false)
        classicFallbackAllowed = allowClassicFallback
        val targetTransport =
            when {
                device.hasBle && device.bleAddress != null -> SensorBallTransport.Ble
                allowClassicFallback && device.hasClassic && device.classicAddress != null -> SensorBallTransport.Classic
                else -> device.transport
            }
        if (!allowClassicFallback && targetTransport == SensorBallTransport.Classic) {
            callback.onStatus("自动连接仅使用 BLE，请在设置中手动连接经典蓝牙设备")
            return
        }
        val targetAddress = device.connectAddress(targetTransport)
        val remoteDevice =
            try {
                adapter?.getRemoteDevice(targetAddress)
            } catch (exc: IllegalArgumentException) {
                null
            }
        if (remoteDevice == null) {
            callback.onStatus("设备地址无效")
            return
        }
        val targetDevice = device.copy(transport = targetTransport)
        connectedDevice = targetDevice
        callback.onStatus("正在连接 ${device.name}...")
        if (targetTransport == SensorBallTransport.Classic) {
            connectClassic(remoteDevice, targetDevice)
        } else {
            pendingFallbackDevice =
                if (classicFallbackAllowed && device.hasClassic && device.classicAddress != null) {
                    device.copy(transport = SensorBallTransport.Classic)
                } else {
                    null
                }
            connectBle(remoteDevice)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        disconnectInternal(notify = true)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(notify: Boolean) {
        val hadConnection = connectedDevice != null || classicSocket != null || gatt != null
        val targetGatt = gatt
        writeCharacteristic = null
        pendingNotificationDescriptors.clear()
        classicTelemetryBuffer.clear()
        bleSetupInProgress = false
        bleWriteInFlight = false
        bleWriteSequence += 1
        pendingGyroscopeCommand = null
        connectedDevice = null
        pendingFallbackDevice = null
        classicFallbackAllowed = true
        classicReadLoopActive = false
        runCatching { classicSocket?.close() }
        classicSocket = null
        if (targetGatt != null) {
            suppressNextBleDisconnectCallback = true
            targetGatt.disconnect()
            targetGatt.close()
        }
        gatt = null
        if (notify && hadConnection) {
            callback.onDisconnected()
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        unregisterClassicReceiver()
        unregisterPairingRequestReceiver()
        disconnectInternal(notify = false)
    }

    @SuppressLint("MissingPermission")
    fun setGyroscopeEnabled(
        enabled: Boolean,
        reportStatus: Boolean = true,
    ): Boolean {
        val targetSocket = classicSocket
        if (targetSocket != null && targetSocket.isConnected) {
            return try {
                targetSocket.outputStream.write(gyroscopeCommandPayload(enabled))
                targetSocket.outputStream.flush()
                reportGyroscopeCommandStatus(enabled, true, reportStatus)
                true
            } catch (_: IOException) {
                reportGyroscopeCommandStatus(enabled, false, reportStatus)
                false
            }
        }

        val targetGatt =
            gatt ?: return false.also {
                if (reportStatus) callback.onStatus("请先连接蓝牙设备")
            }
        val characteristic =
            writeCharacteristic ?: return false.also {
                if (reportStatus) callback.onStatus("未找到可写入的蓝牙通道")
            }
        if (bleSetupInProgress || bleWriteInFlight) {
            pendingGyroscopeCommand = enabled
            if (reportStatus) {
                callback.onStatus(if (enabled) "开启陀螺仪指令等待蓝牙通道就绪" else "关闭陀螺仪指令等待蓝牙通道就绪")
            }
            return true
        }
        return writeGyroscopeCommand(targetGatt, characteristic, enabled, reportStatus)
    }

    @SuppressLint("MissingPermission")
    private fun writeGyroscopeCommand(
        targetGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
        reportStatus: Boolean,
    ): Boolean {
        val payload = gyroscopeCommandPayload(enabled)
        if (bleWriteInFlight) {
            pendingGyroscopeCommand = enabled
            return true
        }
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetGatt.writeCharacteristic(characteristic, payload, characteristic.writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.value = payload
                targetGatt.writeCharacteristic(characteristic)
            }
        if (result) {
            bleWriteInFlight = true
            val sequence = ++bleWriteSequence
            mainHandler.postDelayed(
                {
                    if (this@SensorBallBluetoothManager.gatt === targetGatt && bleWriteInFlight && bleWriteSequence == sequence) {
                        Log.w(TAG, "gyro command write callback timeout; releasing BLE write queue")
                        bleWriteInFlight = false
                        flushPendingGyroscopeCommand(targetGatt)
                    }
                },
                BLE_WRITE_CALLBACK_TIMEOUT_MS,
            )
        }
        Log.d(TAG, "gyro command enabled=$enabled result=$result characteristic=${characteristic.uuid}")
        reportGyroscopeCommandStatus(enabled, result, reportStatus)
        return result
    }

    private fun gyroscopeCommandPayload(enabled: Boolean): ByteArray =
        byteArrayOf(0xC5.toByte(), 0x5C.toByte(), 0x04, if (enabled) 0x01 else 0x00)

    private fun reportGyroscopeCommandStatus(
        enabled: Boolean,
        success: Boolean,
        reportStatus: Boolean,
    ) {
        if (!reportStatus) {
            return
        }
        callback.onStatus(
            if (success) {
                if (enabled) "已发送开启陀螺仪指令" else "已发送关闭陀螺仪指令"
            } else {
                "陀螺仪指令未发送，请保持设备连接后重试"
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectBle(device: BluetoothDevice) {
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun connectClassic(device: BluetoothDevice, item: SensorBallDevice) {
        thread(name = "sensorball-classic-connect") {
            try {
                adapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                classicSocket = socket
                connectedDevice = item
                pendingFallbackDevice = null
                classicTelemetryBuffer.clear()
                callback.onConnected(item)
                callback.onStatus("蓝牙串口已就绪")
                startClassicReadLoop(socket)
            } catch (exc: IOException) {
                if (tryClassicFallbacks(device, item)) {
                    return@thread
                }
                classicSocket = null
                callback.onStatus("经典蓝牙连接失败")
                callback.onDisconnected()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryClassicFallbacks(device: BluetoothDevice, item: SensorBallDevice): Boolean {
        val candidates =
            listOf(
                runCatching { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }.getOrNull(),
                runCatching {
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, 1) as BluetoothSocket
                }.getOrNull(),
            ).filterNotNull()

        for (socket in candidates) {
            try {
                socket.connect()
                classicSocket = socket
                connectedDevice = item
                pendingFallbackDevice = null
                classicTelemetryBuffer.clear()
                callback.onConnected(item)
                callback.onStatus("蓝牙串口已就绪")
                startClassicReadLoop(socket)
                return true
            } catch (exc: IOException) {
                runCatching { socket.close() }
            }
        }
        return false
    }

    private fun startClassicReadLoop(socket: BluetoothSocket) {
        classicReadLoopActive = true
        thread(name = "sensorball-classic-read") {
            val buffer = ByteArray(256)
            while (classicReadLoopActive && socket.isConnected) {
                try {
                    val count = socket.inputStream.read(buffer)
                    if (count > 0) {
                        val packet = buffer.copyOf(count)
                        parseTelemetryStream(packet).forEach(callback::onTelemetry)
                    }
                } catch (_: IOException) {
                    break
                }
            }
            val shouldNotify = classicReadLoopActive
            classicReadLoopActive = false
            if (shouldNotify) {
                callback.onDisconnected()
            }
        }
    }

    private val gattCallback =
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val currentGatt = this@SensorBallBluetoothManager.gatt
                if (currentGatt == null && newState == BluetoothProfile.STATE_DISCONNECTED && !suppressNextBleDisconnectCallback) {
                    Log.w(TAG, "ignore disconnected callback after BLE session already closed status=$status")
                    runCatching { gatt.close() }
                    return
                }
                if (currentGatt != null && currentGatt !== gatt) {
                    Log.w(TAG, "ignore stale BLE state status=$status newState=$newState")
                    runCatching { gatt.close() }
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "BLE connected status=$status")
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            handleBleDisconnected(gatt, status, "BLE connected with non-success status")
                            return
                        }
                        runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                        callback.onStatus("已连接，正在发现服务...")
                        mainHandler.postDelayed(
                            {
                                if (this@SensorBallBluetoothManager.gatt === gatt) {
                                    gatt.discoverServices()
                                }
                            },
                            BLE_SERVICE_DISCOVERY_DELAY_MS,
                        )
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        handleBleDisconnected(gatt, status, "BLE disconnected")
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (this@SensorBallBluetoothManager.gatt !== gatt) {
                    Log.w(TAG, "ignore stale BLE services status=$status")
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    callback.onStatus("服务发现失败：$status")
                    tryPendingClassicFallback("BLE service discovery failed status=$status")
                    return
                }
                writeCharacteristic = null
                pendingNotificationDescriptors.clear()
                bleSetupInProgress = true
                bleWriteInFlight = false
                bleWriteSequence += 1
                var notifyCount = 0
                val notifyCandidates = mutableListOf<BluetoothGattCharacteristic>()
                val writeCandidates = mutableListOf<BluetoothGattCharacteristic>()
                gatt.services.orEmpty().forEach { service ->
                    service.characteristics.orEmpty().forEach { characteristic ->
                        val props = characteristic.properties
                        if (props.hasAny(BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) {
                            characteristic.writeType =
                                if (props.hasAny(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) {
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                } else {
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                }
                            writeCandidates += characteristic
                        }
                        if (props.hasAny(BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PROPERTY_INDICATE)) {
                            notifyCandidates += characteristic
                        }
                    }
                }
                writeCharacteristic = writeCandidates.maxByOrNull(::writeCharacteristicScore)
                val orderedNotifyCandidates =
                    notifyCandidates
                        .filter(::isTelemetryNotifyCharacteristic)
                        .ifEmpty { notifyCandidates.filterNot(::isSystemServiceChangedCharacteristic) }
                        .sortedByDescending { characteristic ->
                            characteristic.uuid.toString().contains("ffe4", ignoreCase = true)
                        }
                orderedNotifyCandidates.forEach { characteristic ->
                    if (queueNotification(gatt, characteristic)) {
                        notifyCount += 1
                    }
                }
                if (writeCharacteristic == null) {
                    if (tryPendingClassicFallback("BLE writable characteristic missing")) {
                        return
                    }
                }
                connectedDevice?.let(callback::onConnected)
                callback.onStatus("蓝牙已就绪，通知通道 $notifyCount 个")
                continueBleSetup(gatt)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (this@SensorBallBluetoothManager.gatt !== gatt) {
                    return
                }
                val value = characteristic.value
                parseTelemetryPackets(value).forEach(callback::onTelemetry)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (this@SensorBallBluetoothManager.gatt !== gatt) {
                    return
                }
                parseTelemetryPackets(value).forEach(callback::onTelemetry)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (this@SensorBallBluetoothManager.gatt !== gatt) {
                    return
                }
                Log.d(TAG, "gyro command write completed status=$status characteristic=${characteristic.uuid}")
                bleWriteInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    callback.onStatus("蓝牙写入确认异常 status=$status，继续保持连接")
                }
                flushPendingGyroscopeCommand(gatt)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (this@SensorBallBluetoothManager.gatt !== gatt) {
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "notify descriptor write status=$status descriptor=${descriptor.uuid}")
                }
                continueBleSetup(gatt)
            }
        }

    @SuppressLint("MissingPermission")
    private fun handleBleDisconnected(
        gatt: BluetoothGatt,
        status: Int,
        reason: String,
    ) {
        Log.w(TAG, "$reason status=$status")
        writeCharacteristic = null
        pendingNotificationDescriptors.clear()
        bleSetupInProgress = false
        bleWriteInFlight = false
        bleWriteSequence += 1
        pendingGyroscopeCommand = null
        if (suppressNextBleDisconnectCallback) {
            suppressNextBleDisconnectCallback = false
            runCatching { gatt.close() }
            if (this.gatt === gatt) {
                this.gatt = null
            }
            return
        }
        if (this.gatt === gatt) {
            this.gatt = null
        }
        runCatching { gatt.close() }
        connectedDevice = null
        if (!tryPendingClassicFallback("BLE disconnected status=$status")) {
            callback.onStatus("BLE连接断开 status=$status")
            callback.onDisconnected()
        }
    }

    private fun writeCharacteristicScore(characteristic: BluetoothGattCharacteristic): Int {
        val uuid = characteristic.uuid.toString()
        val serviceUuid = characteristic.service?.uuid?.toString().orEmpty()
        val props = characteristic.properties
        var score = 0
        if (uuid.contains("ffe9", ignoreCase = true)) score += 80
        if (uuid.contains("ffe1", ignoreCase = true)) score += 40
        if (serviceUuid.contains("ffe0", ignoreCase = true)) score += 20
        if (props.hasAny(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) score += 8
        if (props.hasAny(BluetoothGattCharacteristic.PROPERTY_WRITE)) score += 4
        return score
    }

    private fun isTelemetryNotifyCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        val uuid = characteristic.uuid.toString()
        val serviceUuid = characteristic.service?.uuid?.toString().orEmpty()
        return uuid.contains("ffe4", ignoreCase = true) ||
            serviceUuid.contains("ffe0", ignoreCase = true) ||
            serviceUuid.contains("ffe5", ignoreCase = true)
    }

    private fun isSystemServiceChangedCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean =
        characteristic.uuid.toString().contains("2a05", ignoreCase = true)

    @SuppressLint("MissingPermission")
    private fun continueBleSetup(gatt: BluetoothGatt) {
        if (writeNextNotificationDescriptor(gatt)) {
            return
        }
        finishBleSetup(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun finishBleSetup(gatt: BluetoothGatt) {
        if (!bleSetupInProgress) {
            return
        }
        bleSetupInProgress = false
        flushPendingGyroscopeCommand(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun flushPendingGyroscopeCommand(gatt: BluetoothGatt) {
        if (bleSetupInProgress || bleWriteInFlight) {
            return
        }
        val command = pendingGyroscopeCommand ?: return
        pendingGyroscopeCommand = null
        writeCharacteristic?.let { characteristic ->
            writeGyroscopeCommand(gatt, characteristic, command, reportStatus = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun queueNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val localEnabled = gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
        if (descriptor != null) {
            descriptor.value =
            if (characteristic.properties.hasAny(BluetoothGattCharacteristic.PROPERTY_INDICATE)) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            pendingNotificationDescriptors.add(descriptor)
        }
        return localEnabled
    }

    @SuppressLint("MissingPermission")
    private fun writeNextNotificationDescriptor(gatt: BluetoothGatt): Boolean {
        while (true) {
            val descriptor = pendingNotificationDescriptors.removeFirstOrNull() ?: return false
            if (gatt.writeDescriptor(descriptor)) {
                return true
            }
            Log.w(TAG, "notify descriptor write request rejected descriptor=${descriptor.uuid}")
        }
    }

    private fun parseTelemetryPackets(value: ByteArray?): List<SensorBallTelemetry> {
        if (value == null || value.size < TELEMETRY_PACKET_SIZE) {
            return emptyList()
        }
        val packets = mutableListOf<SensorBallTelemetry>()
        for (index in 0..(value.size - TELEMETRY_PACKET_SIZE)) {
            if ((value[index].toInt() and 0xFF) == 0xD5 && (value[index + 1].toInt() and 0xFF) == 0x5D && (value[index + 2].toInt() and 0xFF) == 0x03) {
                packets += parseTelemetryPacket(value, index)
            }
        }
        return packets
    }

    private fun parseTelemetryStream(value: ByteArray): List<SensorBallTelemetry> {
        value.forEach { byte -> classicTelemetryBuffer.add(byte) }
        val packets = mutableListOf<SensorBallTelemetry>()
        while (classicTelemetryBuffer.size >= TELEMETRY_PACKET_SIZE) {
            val first = classicTelemetryBuffer.first().toInt() and 0xFF
            if (first != 0xD5) {
                classicTelemetryBuffer.removeFirst()
                continue
            }
            val snapshot = classicTelemetryBuffer.take(TELEMETRY_PACKET_SIZE).toByteArray()
            val second = snapshot[1].toInt() and 0xFF
            val command = snapshot[2].toInt() and 0xFF
            if (second != 0x5D || command != 0x03) {
                classicTelemetryBuffer.removeFirst()
                continue
            }
            packets += parseTelemetryPacket(snapshot, 0)
            repeat(TELEMETRY_PACKET_SIZE) { classicTelemetryBuffer.removeFirst() }
        }
        while (classicTelemetryBuffer.size > TELEMETRY_PACKET_SIZE * 2) {
            classicTelemetryBuffer.removeFirst()
        }
        return packets
    }

    private fun parseTelemetryPacket(value: ByteArray, index: Int): SensorBallTelemetry {
        val gyroForceRaw = value[index + 7].toInt() and 0xFF
        val pressureForceRaw = value[index + 8].toInt() and 0xFF
        val forceLow = value[index + 9].toInt() and 0xFF
        val forceHigh = value[index + 10].toInt() and 0xFF
        val protocolForceN = readUInt16LittleEndian(value, index + 9)
        val rawForceN = if (protocolForceN > 0) protocolForceN else maxOf(gyroForceRaw, pressureForceRaw)
        val forceN = (rawForceN * SENSOR_FORCE_SCALE).roundToInt()
        val telemetry =
            SensorBallTelemetry(
                packetIndex = value[index + 3].toInt() and 0xFF,
                batteryRaw = value[index + 4].toInt() and 0xFF,
                hitCount = value[index + 5].toInt() and 0xFF,
                pressureHitCount = value[index + 6].toInt() and 0xFF,
                gyroForceRaw = gyroForceRaw,
                pressureForceRaw = pressureForceRaw,
                forceLow = forceLow,
                forceHigh = forceHigh,
                forceN = forceN,
            )
        Log.d(
            TAG,
            "telemetry packet=${telemetry.packetIndex} battery=${telemetry.batteryRaw} data2=${telemetry.hitCount} data3=${telemetry.pressureHitCount} data4=$gyroForceRaw data5=$pressureForceRaw data6=$forceLow data7=$forceHigh rawForceN=$rawForceN forceN=$forceN",
        )
        return telemetry
    }

    private fun readUInt16LittleEndian(value: ByteArray, offset: Int): Int {
        val low = value[offset].toInt() and 0xFF
        val high = value[offset + 1].toInt() and 0xFF
        return low or (high shl 8)
    }

    @SuppressLint("MissingPermission")
    private fun tryPendingClassicFallback(reason: String): Boolean {
        val fallback = pendingFallbackDevice ?: return false
        val address = fallback.classicAddress ?: return false
        pendingFallbackDevice = null
        bleSetupInProgress = false
        bleWriteInFlight = false
        bleWriteSequence += 1
        pendingGyroscopeCommand = null
        runCatching { gatt?.close() }
        gatt = null
        val remoteDevice =
            try {
                adapter?.getRemoteDevice(address)
            } catch (exc: IllegalArgumentException) {
                null
            } ?: return false
        callback.onStatus("BLE连接失败，尝试经典蓝牙...")
        connectClassic(remoteDevice, fallback.copy(transport = SensorBallTransport.Classic))
        return true
    }

    @SuppressLint("MissingPermission")
    private fun addBondedBoxingDevices() {
        adapter?.bondedDevices.orEmpty().forEach { device ->
            val name = device.name ?: return@forEach
            if (!isBoxingDeviceName(name)) {
                return@forEach
            }
            val item =
                SensorBallDevice(
                    name = name,
                    address = device.address,
                    rssi = 0,
                    transport = SensorBallTransport.Classic,
                )
            addOrMergeDevice(item)
        }
    }

    private fun ScanResult.extractDeviceName(): String? {
        val advertisedName = scanRecord?.deviceName
        if (!advertisedName.isNullOrBlank()) {
            return advertisedName
        }
        val cachedName =
            try {
                device?.name
            } catch (_: SecurityException) {
                null
            }
        if (!cachedName.isNullOrBlank()) {
            return cachedName
        }
        val rawText =
            runCatching {
                scanRecord?.bytes?.toString(Charsets.ISO_8859_1)
            }.getOrNull()
        return rawText?.let { DEVICE_NAME_REGEX.find(it)?.value }
    }

    private fun isBoxingDeviceName(name: String): Boolean {
        val normalized = name.trim()
        return normalized.startsWith(DEVICE_PREFIX, ignoreCase = true) &&
            normalized.lastOrNull()?.isEnglishLetter() == true
    }

    private fun Char.isEnglishLetter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z'

    private fun addOrMergeDevice(item: SensorBallDevice) {
        val existingKey =
            devices.entries.firstOrNull { (_, existing) ->
                existing.address.equals(item.address, ignoreCase = true) || existing.normalizedName() == item.normalizedName()
            }?.key
        if (existingKey == null) {
            devices[item.deviceKey()] = item
        } else {
            val existing = devices.getValue(existingKey)
            val preferredTransport = choosePreferredTransport(existing, item)
            val mergedBleAddress =
                when {
                    !existing.bleAddress.isNullOrBlank() -> existing.bleAddress
                    !item.bleAddress.isNullOrBlank() -> item.bleAddress
                    item.transport == SensorBallTransport.Ble -> item.address
                    existing.transport == SensorBallTransport.Ble -> existing.address
                    else -> null
                }
            val mergedClassicAddress =
                when {
                    !existing.classicAddress.isNullOrBlank() -> existing.classicAddress
                    !item.classicAddress.isNullOrBlank() -> item.classicAddress
                    item.transport == SensorBallTransport.Classic -> item.address
                    existing.transport == SensorBallTransport.Classic -> existing.address
                    else -> null
                }
            devices[existingKey] =
                SensorBallDevice(
                    name = bestDisplayName(existing.name, item.name),
                    address =
                        if (preferredTransport == SensorBallTransport.Classic) {
                            mergedClassicAddress ?: mergedBleAddress ?: existing.address
                        } else {
                            mergedBleAddress ?: mergedClassicAddress ?: existing.address
                        },
                    rssi = maxOf(existing.rssi, item.rssi),
                    transport = preferredTransport,
                    hasBle = existing.hasBle || item.hasBle || item.transport == SensorBallTransport.Ble,
                    hasClassic = existing.hasClassic || item.hasClassic || item.transport == SensorBallTransport.Classic,
                    bleAddress = mergedBleAddress,
                    classicAddress = mergedClassicAddress,
                )
        }
        callback.onDevicesChanged(devices.values.toList())
    }

    private fun choosePreferredTransport(existing: SensorBallDevice, item: SensorBallDevice): SensorBallTransport =
        when {
            existing.transport == SensorBallTransport.Classic -> SensorBallTransport.Classic
            item.transport == SensorBallTransport.Classic -> SensorBallTransport.Classic
            else -> SensorBallTransport.Ble
        }

    private fun bestDisplayName(first: String, second: String): String =
        listOf(first, second).maxByOrNull { it.length }.orEmpty().ifBlank { first }

    private fun SensorBallDevice.deviceKey(): String = normalizedName().ifBlank { address.uppercase() }

    private fun SensorBallDevice.normalizedName(): String = name.trim().uppercase()

    private fun SensorBallDevice.connectAddress(targetTransport: SensorBallTransport = transport): String =
        if (targetTransport == SensorBallTransport.Classic) {
            classicAddress ?: address
        } else {
            bleAddress ?: address
        }

    private fun registerClassicReceiver() {
        runCatching {
            unregisterClassicReceiver()
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(classicReceiver, filter)
            }
        }
    }

    private fun unregisterClassicReceiver() {
        runCatching { appContext.unregisterReceiver(classicReceiver) }
    }

    private fun registerPairingRequestReceiver() {
        runCatching {
            unregisterPairingRequestReceiver()
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    priority = 1_000
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(pairingRequestReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(pairingRequestReceiver, filter)
            }
        }
    }

    private fun unregisterPairingRequestReceiver() {
        runCatching { appContext.unregisterReceiver(pairingRequestReceiver) }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.isKnownBoxingDeviceForPairing(): Boolean {
        val deviceName = runCatching { name }.getOrNull()
        if (!deviceName.isNullOrBlank() && isBoxingDeviceName(deviceName)) {
            return true
        }
        return listOfNotNull(
            connectedDevice?.address,
            connectedDevice?.bleAddress,
            connectedDevice?.classicAddress,
            pendingFallbackDevice?.address,
            pendingFallbackDevice?.bleAddress,
            pendingFallbackDevice?.classicAddress,
        ).any { it.equals(address, ignoreCase = true) }
    }

    private fun Int.hasAny(vararg flags: Int): Boolean = flags.any { flag -> this and flag != 0 }

    private companion object {
        const val TAG = "SensorBallBT"
        const val DEVICE_PREFIX = "SENBALL#"
        const val TELEMETRY_PACKET_SIZE = 11
        const val SENSOR_FORCE_SCALE = 0.6f
        const val BLE_SERVICE_DISCOVERY_DELAY_MS = 350L
        const val BLE_WRITE_CALLBACK_TIMEOUT_MS = 900L
        val DEVICE_NAME_REGEX = Regex("SENBALL#[A-Za-z0-9_-]*[A-Za-z]", RegexOption.IGNORE_CASE)
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
