package com.example.nll_sensortotext

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.telephony.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class CellTowerLoggingActivity : AppCompatActivity() {

    // Telephony & Location Managers
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager

    // Data State
    private var currentLocation: Location? = null
    private var isGpsInitialized = false
    private var pendingStart = false
    @Volatile private var latestCellInfos: List<CellInfo> = emptyList()
    private val requestingCellUpdate = AtomicBoolean(false)
    private val csvBuffer = StringBuilder()

    // UI Elements
    private lateinit var txtStatus: TextView
    private lateinit var txtServingCell: TextView
    private lateinit var txtNeighborCells: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // Handlers for Logging & UI Refresh
    private val logHandler = Handler(Looper.getMainLooper())
    private val uiRefreshHandler = Handler(Looper.getMainLooper())
    private var isLogging = false
    private val PERMISSION_REQUEST_CODE = 1001

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_celltowerlogging)

        // Initialize Managers
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Initialize Views
        txtStatus = findViewById(R.id.txtStatus)
        txtServingCell = findViewById(R.id.txtServingCell)
        txtNeighborCells = findViewById(R.id.txtNeighborCells)
        btnStart = findViewById(R.id.btnStartLogging)
        btnStop = findViewById(R.id.btnStopLogging)

        btnStart.setOnClickListener { startLogging() }
        btnStop.setOnClickListener { stopLogging() }

        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
        } else {
            initAppLogic()
        }
    }

    private fun initAppLogic() {
        initGps()
        registerTelephonyUpdates()
        startUiFastRefresh()
    }

    // --- 1. UI 실시간 최신화 로직 (1초 주기) ---
    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            forceCellInfoRefresh()
            uiRefreshHandler.postDelayed(this, 1000L)
        }
    }

    private fun startUiFastRefresh() {
        uiRefreshHandler.removeCallbacks(uiRefreshRunnable)
        uiRefreshHandler.post(uiRefreshRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun forceCellInfoRefresh() {
        if (!hasAllPermissions()) return

        // 즉시 스냅샷 획득
        latestCellInfos = telephonyManager.allCellInfo ?: emptyList()
        updateUi(latestCellInfos)

        // API 29+ 하드웨어 강제 갱신 요청
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (requestingCellUpdate.getAndSet(true)) return
            try {
                telephonyManager.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        latestCellInfos = cellInfo
                        updateUi(cellInfo)
                        requestingCellUpdate.set(false)
                    }
                })
            } catch (e: Exception) {
                requestingCellUpdate.set(false)
            }
        }
    }

    private fun updateUi(cellInfos: List<CellInfo>) {
        val servingSb = StringBuilder()
        val neighborSb = StringBuilder()

        for (info in cellInfos) {
            val detail = when (info) {
                is CellInfoLte -> {
                    val id = info.cellIdentity
                    val sig = info.cellSignalStrength
                    // LTE 상세 정보
                    "LTE | PCI: ${id.pci} | ARFCN: ${id.earfcn}\n" +
                            "TAC: ${id.tac} | CellID: ${id.ci}\n" +
                            "RSRP: ${sig.rsrp}dBm | RSRQ: ${sig.rsrq}dB\n" +
                            "RSSI: ${sig.dbm}dBm | SINR: ${sig.rssnr}" // RSSI는 sig.dbm으로 대체 확인
                }
                is CellInfoNr -> {
                    val id = info.cellIdentity as? CellIdentityNr
                    val sig = info.cellSignalStrength as? CellSignalStrengthNr
                    // 5G 상세 정보
                    "NR | PCI: ${id?.pci ?: "N/A"} | ARFCN: ${id?.nrarfcn ?: "N/A"}\n" +
                            "TAC: ${id?.tac ?: "N/A"} | NCI: ${id?.nci ?: "N/A"}\n" +
                            "SS-RSRP: ${sig?.ssRsrp ?: "N/A"}dBm | SS-RSRQ: ${sig?.ssRsrq ?: "N/A"}dB\n" +
                            "SS-SINR: ${sig?.ssSinr ?: "N/A"}dB"
                }
                else -> "Other RAT"
            }

            if (info.isRegistered) {
                servingSb.append("★ SERVING ★\n$detail\n\n")
            } else {
                neighborSb.append("$detail\n----------------\n")
            }
        }
        txtServingCell.text = if (servingSb.isEmpty()) "서빙셀 찾는 중..." else servingSb.toString()
        txtNeighborCells.text = if (neighborSb.isEmpty()) "이웃셀 없음" else neighborSb.toString()
    }

    // --- 2. GPS 로직 ---
    @SuppressLint("MissingPermission")
    private fun initGps() {
        if (!hasAllPermissions()) return
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, gpsListener)
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            currentLocation = loc
            if (!isGpsInitialized) {
                isGpsInitialized = true
                if (pendingStart) {
                    pendingStart = false
                    actualStartLogging()
                }
            }
        }
    }

    // --- 3. 데이터 로깅 (기존 28개 항목 유지) ---
    private val logRunnable = object : Runnable {
        override fun run() {
            if (!isLogging) return
            logOnce()
            logHandler.postDelayed(this, 1000L)
        }
    }

    private fun logOnce() {
        val location = currentLocation ?: return
        val cellInfos = latestCellInfos
        if (cellInfos.isEmpty()) return

        val now = System.currentTimeMillis()
        val opName = telephonyManager.networkOperatorName ?: ""

        for (cellInfo in cellInfos) {
            val isReg = if (cellInfo.isRegistered) 1 else 0
            var cellNet = "UNKNOWN"; var mcc = ""; var mnc = ""
            var ci = ""; var tac = ""; var pci = ""; var arfcn = ""
            var dbm = ""; var asu = ""; var level = ""
            var rsrp = ""; var rsrq = ""; var rssi = ""; var sinr = ""; var cqi = ""; var ta = ""
            var ssRsrp = ""; var ssRsrq = ""; var ssSinr = ""
            var csiRsrp = ""; var csiRsrq = ""; var csiSinr = ""

            when (cellInfo) {
                is CellInfoLte -> {
                    cellNet = "LTE"; val id = cellInfo.cellIdentity; val sig = cellInfo.cellSignalStrength
                    mcc = id.mccString ?: ""; mnc = id.mncString ?: ""; ci = id.ci.toString(); tac = id.tac.toString()
                    pci = id.pci.toString(); arfcn = id.earfcn.toString(); dbm = sig.dbm.toString()
                    asu = sig.asuLevel.toString(); level = sig.level.toString()
                    rsrp = sig.rsrp.toString(); rsrq = sig.rsrq.toString(); rssi = sig.dbm.toString()
                    sinr = sig.rssnr.toString(); cqi = sig.cqi.toString(); ta = sig.timingAdvance.toString()
                }
                is CellInfoNr -> {
                    cellNet = "NR"; val id = cellInfo.cellIdentity as? CellIdentityNr; val sig = cellInfo.cellSignalStrength as? CellSignalStrengthNr
                    mcc = id?.mccString ?: ""; mnc = id?.mncString ?: ""; ci = id?.nci?.toString() ?: ""
                    tac = id?.tac?.toString() ?: ""; pci = id?.pci?.toString() ?: ""; arfcn = id?.nrarfcn?.toString() ?: ""
                    dbm = sig?.dbm?.toString() ?: ""; asu = sig?.asuLevel?.toString() ?: ""; level = sig?.level?.toString() ?: ""
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sig != null) {
                        ssRsrp = sig.ssRsrp.toString(); ssRsrq = sig.ssRsrq.toString(); ssSinr = sig.ssSinr.toString()
                        csiRsrp = sig.csiRsrp.toString(); csiRsrq = sig.csiRsrq.toString(); csiSinr = sig.csiSinr.toString()
                    }
                }
            }

            val line = listOf(
                now, location.latitude, location.longitude, location.altitude,
                cellNet, isReg, mcc, mnc, opName, ci, tac, pci, arfcn,
                dbm, asu, level, rsrp, rsrq, rssi, sinr, cqi, ta,
                ssRsrp, ssRsrq, ssSinr, csiRsrp, csiRsrq, csiSinr
            ).joinToString(",")
            csvBuffer.append(line).append("\n")
        }
    }

    private fun startLogging() {
        if (isLogging) return
        if (!isGpsInitialized) {
            pendingStart = true
            txtStatus.text = "상태: GPS 초기화 대기 중..."
            return
        }
        actualStartLogging()
    }

    private fun actualStartLogging() {
        csvBuffer.setLength(0)
        csvBuffer.append("timestamp,latitude,longitude,altitude,RAT,isServingCell,MCC,MNC,OperatorName,CellIdentity,TAC,PCI,ARFCN,DBM,ASU,Level,LTE_RSRP,LTE_RSRQ,LTE_RSSI,LTE_SINR,LTE_CQI,TA,NR_SSB_RSRP,NR_SSB_RSRQ,NR_SSB_SINR,NR_CSI_RSRP,NR_CSI_RSRQ,NR_CSI_SINR\n")
        isLogging = true
        txtStatus.text = "상태: 수집 중..."
        btnStart.isEnabled = false; btnStop.isEnabled = true
        logHandler.post(logRunnable)
    }

    private fun stopLogging() {
        isLogging = false
        logHandler.removeCallbacks(logRunnable)
        btnStart.isEnabled = true; btnStop.isEnabled = false
        val filename = "cell_log_${System.currentTimeMillis()}.csv"
        saveCsvToDownloads(filename, csvBuffer.toString())
        txtStatus.text = "상태: 저장 완료 ($filename)"
    }

    private fun saveCsvToDownloads(filename: String, content: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            contentResolver.openOutputStream(uri).use { it?.write(content.toByteArray()) }
            Toast.makeText(this, "파일이 다운로드 폴더에 저장되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerTelephonyUpdates() {
        if (!hasAllPermissions()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    latestCellInfos = cellInfo
                    updateUi(cellInfo)
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, cb)
        }
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initAppLogic()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiRefreshHandler.removeCallbacks(uiRefreshRunnable)
        logHandler.removeCallbacks(logRunnable)
    }
}