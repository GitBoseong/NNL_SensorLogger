package com.example.nll_sensortotext

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

class PDRWithBLE_WiFi : AppCompatActivity(), SensorEventListener {

    // ============================================================
    // 1. UI
    // ============================================================

    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private lateinit var swBleScan: SwitchCompat
    private lateinit var swWifiScan: SwitchCompat
    private lateinit var swBleTjOnly: SwitchCompat


    // ============================================================
    // 2. Sensor
    // ============================================================

    private lateinit var sensorManager: SensorManager

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var gameRotationVector: Sensor? = null

    private val accelValues = FloatArray(3) { 0f }
    private val gyroValues = FloatArray(3) { 0f }
    private val magValues = FloatArray(3) { 0f }

    // GameRotationVector는 CSV에 w, x, y, z 순서로 저장
    private var grvW = 1f
    private var grvX = 0f
    private var grvY = 0f
    private var grvZ = 0f


    // ============================================================
    // 3. BLE
    // ============================================================

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null

    private var isBleEnabledByToggle = false
    private var isBleTjOnlyEnabled = false

    // key: BLE_MAC/SSID, value: RSSI
    private val latestBleRssiMap = linkedMapOf<String, Int>()


    // ============================================================
    // 4. Wi-Fi
    // ============================================================

    private lateinit var wifiManager: WifiManager

    private var isWifiEnabledByToggle = false

    // key: WIFI_BSSID/SSID, value: RSSI
    private val latestWifiRssiMap = linkedMapOf<String, Int>()

    private var wifiScanExecutor: ScheduledExecutorService? = null


    // ============================================================
    // 5. Logging
    // ============================================================

    private var sensorExecutor: ScheduledExecutorService? = null
    private var isCollecting = false

    private val logRows = mutableListOf<LogRow>()

    private val baseHeaders = listOf(
        "time",
        "acc_x", "acc_y", "acc_z",
        "gyro_x", "gyro_y", "gyro_z",
        "mag_x", "mag_y", "mag_z",
        "grv_w", "grv_x", "grv_y", "grv_z"
    )


    // ============================================================
    // 6. Data class
    // ============================================================

    data class LogRow(
        val time: String,

        val accX: Float,
        val accY: Float,
        val accZ: Float,

        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float,

        val magX: Float,
        val magY: Float,
        val magZ: Float,

        val grvW: Float,
        val grvX: Float,
        val grvY: Float,
        val grvZ: Float,

        val rfMap: Map<String, Int>
    )


    // ============================================================
    // 7. Activity lifecycle
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 세로 모드 고정
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setContentView(R.layout.activity_pdrwithble_wifi)

        // 화면 항상 켜짐 유지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bindViews()
        initSensors()
        initBle()
        initWifi()
        requestNeededPermissions()

        btnStart.setOnClickListener {
            startCollection()
        }

        btnStop.setOnClickListener {
            stopCollectionAndSave()
        }

        swBleScan.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            isBleEnabledByToggle = checked

            if (isCollecting) {
                if (checked) {
                    startBleScan()
                } else {
                    stopBleScan()
                    synchronized(latestBleRssiMap) {
                        latestBleRssiMap.clear()
                    }
                }
            }
        }

        swWifiScan.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            isWifiEnabledByToggle = checked

            if (isCollecting) {
                if (checked) {
                    startWifiScanLoop()
                } else {
                    stopWifiScanLoop()
                    synchronized(latestWifiRssiMap) {
                        latestWifiRssiMap.clear()
                    }
                }
            }
        }

        swBleTjOnly.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            isBleTjOnlyEnabled = checked

            // TJ 필터를 켰을 때 기존 BLE 결과 중 TJ가 아닌 값이 남지 않도록 초기화
            if (checked) {
                synchronized(latestBleRssiMap) {
                    latestBleRssiMap.clear()
                }
            }
        }

        btnStop.isEnabled = false
        tvStatus.text = "상태: 대기 중"
    }

    override fun onDestroy() {
        super.onDestroy()

        stopCollectionOnly()
        stopBleScan()
        stopWifiScanLoop()

        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (_: Exception) {
        }

        sensorManager.unregisterListener(this)
    }


    // ============================================================
    // 8. Init
    // ============================================================

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        swBleScan = findViewById(R.id.swBleScan)
        swWifiScan = findViewById(R.id.swWifiScan)
        swBleTjOnly = findViewById(R.id.swBleTjOnly)
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        // 50Hz = 20ms = 20,000 microseconds
        val samplingPeriodUs = 20_000

        accelerometer?.let {
            sensorManager.registerListener(this, it, samplingPeriodUs)
        }

        gyroscope?.let {
            sensorManager.registerListener(this, it, samplingPeriodUs)
        }

        magnetometer?.let {
            sensorManager.registerListener(this, it, samplingPeriodUs)
        }

        gameRotationVector?.let {
            sensorManager.registerListener(this, it, samplingPeriodUs)
        }
    }

    private fun initBle() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    private fun initWifi() {
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, filter)
    }


    // ============================================================
    // 9. Permission
    // ============================================================

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()

        // BLE 스캔, Wi-Fi 스캔 둘 다 위치 권한 필요
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                notGranted.toTypedArray(),
                1001
            )
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }


    // ============================================================
    // 10. Start / Stop
    // ============================================================

    private fun startCollection() {
        if (isCollecting) return

        logRows.clear()

        synchronized(latestBleRssiMap) {
            latestBleRssiMap.clear()
        }

        synchronized(latestWifiRssiMap) {
            latestWifiRssiMap.clear()
        }

        isBleEnabledByToggle = swBleScan.isChecked
        isWifiEnabledByToggle = swWifiScan.isChecked
        isBleTjOnlyEnabled = swBleTjOnly.isChecked

        isCollecting = true

        btnStart.isEnabled = false
        btnStop.isEnabled = true

        tvStatus.text = "상태: 수집 중"

        if (isBleEnabledByToggle) {
            startBleScan()
        }

        if (isWifiEnabledByToggle) {
            startWifiScanLoop()
        }

        sensorExecutor = Executors.newSingleThreadScheduledExecutor()

        sensorExecutor?.scheduleAtFixedRate(
            {
                logCurrentRow()
            },
            0,
            20,
            TimeUnit.MILLISECONDS
        )

        Toast.makeText(this, "수집 시작", Toast.LENGTH_SHORT).show()
    }

    private fun stopCollectionAndSave() {
        if (!isCollecting) return

        stopCollectionOnly()
        stopBleScan()
        stopWifiScanLoop()

        val savedFile = saveCsvFile()

        btnStart.isEnabled = true
        btnStop.isEnabled = false

        tvStatus.text = "상태: 저장 완료"

        Toast.makeText(
            this,
            "CSV 저장 완료: ${savedFile.absolutePath}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun stopCollectionOnly() {
        isCollecting = false

        sensorExecutor?.shutdownNow()
        sensorExecutor = null
    }


    // ============================================================
    // 11. Sensor + RF row logging
    // ============================================================

    private fun logCurrentRow() {
        val time = getNowStringForRow()

        val rfSnapshot = linkedMapOf<String, Int>()

        if (isBleEnabledByToggle) {
            synchronized(latestBleRssiMap) {
                rfSnapshot.putAll(latestBleRssiMap)
            }
        }

        if (isWifiEnabledByToggle) {
            synchronized(latestWifiRssiMap) {
                rfSnapshot.putAll(latestWifiRssiMap)
            }
        }

        val row = LogRow(
            time = time,

            accX = accelValues[0],
            accY = accelValues[1],
            accZ = accelValues[2],

            gyroX = gyroValues[0],
            gyroY = gyroValues[1],
            gyroZ = gyroValues[2],

            magX = magValues[0],
            magY = magValues[1],
            magZ = magValues[2],

            grvW = grvW,
            grvX = grvX,
            grvY = grvY,
            grvZ = grvZ,

            rfMap = rfSnapshot
        )

        synchronized(logRows) {
            logRows.add(row)
        }
    }


    // ============================================================
    // 12. BLE Scan
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                runOnUiThread {
                    Toast.makeText(this, "BLE 스캔 권한이 없습니다.", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        if (bluetoothAdapter?.isEnabled != true) {
            runOnUiThread {
                Toast.makeText(this, "블루투스가 꺼져 있습니다.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        try {
            bleScanner?.startScan(bleScanCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
            }

            bleScanner?.stopScan(bleScanCallback)
        } catch (_: Exception) {
        }
    }

    private val bleScanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleBleResult(result)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                handleBleResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                Toast.makeText(
                    this@PDRWithBLE_WiFi,
                    "BLE 스캔 실패: $errorCode",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBleResult(result: ScanResult) {
        if (!isCollecting || !isBleEnabledByToggle) return

        val mac = result.device?.address ?: return

        val deviceName = getBleDeviceName(result)

        if (isBleTjOnlyEnabled && !deviceName.startsWith("TJ")) {
            return
        }

        val safeName = sanitizeHeader(deviceName)

        // CSV 열 헤더 예시:
        // BLE_AA:BB:CC:DD:EE:FF/TJ_001
        val key = "BLE_${mac}/${safeName}"

        synchronized(latestBleRssiMap) {
            latestBleRssiMap[key] = result.rssi
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBleDeviceName(result: ScanResult): String {
        val scanRecordName = result.scanRecord?.deviceName

        if (!scanRecordName.isNullOrBlank()) {
            return scanRecordName
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                result.device?.name ?: "UNKNOWN"
            } else {
                "UNKNOWN"
            }
        } else {
            result.device?.name ?: "UNKNOWN"
        }
    }


    // ============================================================
    // 13. Wi-Fi Scan
    // ============================================================

    private fun startWifiScanLoop() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            runOnUiThread {
                Toast.makeText(this, "Wi-Fi 스캔 위치 권한이 없습니다.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        wifiScanExecutor?.shutdownNow()
        wifiScanExecutor = Executors.newSingleThreadScheduledExecutor()

        wifiScanExecutor?.scheduleAtFixedRate(
            {
                try {
                    wifiManager.startScan()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            0,
            1000,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopWifiScanLoop() {
        wifiScanExecutor?.shutdownNow()
        wifiScanExecutor = null
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isCollecting || !isWifiEnabledByToggle) return

            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return

            val results: List<WifiScanResult> = try {
                wifiManager.scanResults
            } catch (_: SecurityException) {
                emptyList()
            }

            synchronized(latestWifiRssiMap) {
                for (result in results) {
                    val bssid = result.BSSID ?: continue
                    val ssid = result.SSID ?: "UNKNOWN"

                    val safeSsid = sanitizeHeader(ssid)

                    // CSV 열 헤더 예시:
                    // WIFI_11:22:33:44:55:66/iptime
                    val key = "WIFI_${bssid}/${safeSsid}"

                    latestWifiRssiMap[key] = result.level
                }
            }
        }
    }


    // ============================================================
    // 14. CSV Save
    // ============================================================

    private fun saveCsvFile(): File {
        val rowsSnapshot = synchronized(logRows) {
            logRows.toList()
        }

        val rfHeaders = linkedSetOf<String>()

        for (row in rowsSnapshot) {
            rfHeaders.addAll(row.rfMap.keys)
        }

        val allHeaders = baseHeaders + rfHeaders.toList()

        val csvFile = createCsvFile()

        FileWriter(csvFile).use { writer ->
            writer.append(
                allHeaders.joinToString(",") {
                    csvEscape(it)
                }
            )
            writer.append("\n")

            for (row in rowsSnapshot) {
                val baseValues = listOf(
                    row.time,

                    row.accX.toString(),
                    row.accY.toString(),
                    row.accZ.toString(),

                    row.gyroX.toString(),
                    row.gyroY.toString(),
                    row.gyroZ.toString(),

                    row.magX.toString(),
                    row.magY.toString(),
                    row.magZ.toString(),

                    row.grvW.toString(),
                    row.grvX.toString(),
                    row.grvY.toString(),
                    row.grvZ.toString()
                )

                val rfValues = rfHeaders.map { header ->
                    row.rfMap[header]?.toString() ?: ""
                }

                val line = (baseValues + rfValues).joinToString(",") {
                    csvEscape(it)
                }

                writer.append(line)
                writer.append("\n")
            }

            writer.flush()
        }

        return csvFile
    }

    private fun createCsvFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }.format(Date())

        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )

        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        // 예: 20260603_153001_PDR_RF.csv
        return File(downloadsDir, "${stamp}_PDR_RF.csv")
    }


    // ============================================================
    // 15. SensorEventListener
    // ============================================================

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {

            Sensor.TYPE_ACCELEROMETER -> {
                accelValues[0] = event.values[0]
                accelValues[1] = event.values[1]
                accelValues[2] = event.values[2]
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroValues[0] = event.values[0]
                gyroValues[1] = event.values[1]
                gyroValues[2] = event.values[2]
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magValues[0] = event.values[0]
                magValues[1] = event.values[1]
                magValues[2] = event.values[2]
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                updateGameRotationVector(event.values)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    private fun updateGameRotationVector(values: FloatArray) {
        val x = values.getOrNull(0) ?: 0f
        val y = values.getOrNull(1) ?: 0f
        val z = values.getOrNull(2) ?: 0f

        val w = if (values.size >= 4) {
            values[3]
        } else {
            sqrt(max(0f, 1f - x * x - y * y - z * z))
        }

        grvW = w
        grvX = x
        grvY = y
        grvZ = z
    }


    // ============================================================
    // 16. Utils
    // ============================================================

    private fun getNowStringForRow(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }.format(Date())
    }

    private fun sanitizeHeader(text: String): String {
        return text
            .replace(",", "_")
            .replace("\n", "_")
            .replace("\r", "_")
            .replace("\"", "_")
            .trim()
            .ifEmpty { "UNKNOWN" }
    }

    private fun csvEscape(value: String): String {
        val needEscape = value.contains(",") ||
                value.contains("\"") ||
                value.contains("\n") ||
                value.contains("\r")

        return if (needEscape) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}