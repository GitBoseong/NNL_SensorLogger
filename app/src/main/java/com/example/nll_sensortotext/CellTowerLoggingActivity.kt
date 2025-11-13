package com.example.nll_sensortotext

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.telephony.*
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.io.File

class CellTowerLoggingActivity : AppCompatActivity() {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var txtStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val handler = Handler(Looper.getMainLooper())
    private var isLogging = false

    private val PERMISSION_REQUEST_CODE = 1001
    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,   // Cell info + GPS
        Manifest.permission.READ_PHONE_STATE       // 일부 단말에서 필요
    )

    private val csvBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_celltowerlogging)

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        txtStatus = findViewById(R.id.txtStatus)
        btnStart = findViewById(R.id.btnStartLogging)
        btnStop = findViewById(R.id.btnStopLogging)

        btnStart.setOnClickListener {
            startLogging()
        }

        btnStop.setOnClickListener {
            stopLogging()
        }
    }

    private fun startLogging() {
        if (isLogging) return

        // 권한 체크
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                requiredPermissions,
                PERMISSION_REQUEST_CODE
            )
            return
        }

// 🔹 CSV 버퍼 초기화 + 헤더 작성
        csvBuffer.clear()
        csvBuffer.append(
            "timestamp,latitude,longitude,altitude," +
                    "RAT,isServingCell," +
                    "MCC,MNC,OperatorName," +
                    "CellIdentity,TrackingAreaCode,PhysicalCellId,ARFCN," +
                    "SignalStrengthDbm,SignalStrengthAsu,SignalStrengthLevel," +
                    "LTE_RSRP,LTE_RSRQ,LTE_RSSI,LTE_SINR,LTE_CQI,TimingAdvance," +
                    "NR_SSB_RSRP,NR_SSB_RSRQ,NR_SSB_SINR," +
                    "NR_CSI_RSRP,NR_CSI_RSRQ,NR_CSI_SINR\n"
        )

        isLogging = true
        txtStatus.text = "상태: 수집 중..."
        handler.post(logRunnable)
    }

    private fun stopLogging() {
        isLogging = false
        handler.removeCallbacks(logRunnable)

        // 🔹 수집 종료 시 Downloads 폴더에 저장
        if (csvBuffer.isNotEmpty()) {
            val filename = "cell_log_${System.currentTimeMillis()}.csv"
            saveCsvToDownloads(filename, csvBuffer.toString())
            txtStatus.text = "상태: 수집 종료 (저장: Download/$filename)"
        } else {
            txtStatus.text = "상태: 수집 종료 (저장할 데이터 없음)"
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun saveCsvToDownloads(filename: String, content: String) {
        val resolver = contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            resolver.openOutputStream(uri).use { output ->
                output?.write(content.toByteArray())
                output?.flush()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startLogging()
            } else {
                txtStatus.text = "상태: 권한 거부됨"
            }
        }
    }

    private val logRunnable = object : Runnable {
        override fun run() {
            if (!isLogging) return
            logOnce()
            handler.postDelayed(this, 1000L) // 1초 간격
        }
    }

    private fun logOnce() {
        // 위치 가져오기
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0
                val alt = location?.altitude ?: 0.0

                val now = System.currentTimeMillis()

                // 셀 정보 가져오기
                val cellInfos = try {
                    telephonyManager.allCellInfo
                } catch (e: SecurityException) {
                    null
                } ?: return@addOnSuccessListener

                if (cellInfos.isEmpty()) return@addOnSuccessListener

                val operatorName = telephonyManager.networkOperatorName ?: ""
                val sb = StringBuilder()

                for (cellInfo in cellInfos) {
                    val isRegistered = if (cellInfo.isRegistered) 1 else 0

                    var cellNet = ""
                    var mcc: String? = null
                    var mnc: String? = null

                    var ci: Long? = null
                    var tac: Int? = null
                    var pci: Int? = null
                    var arfcn: Int? = null

                    var dbm: Int? = null
                    var asu: Int? = null
                    var level: Int? = null

                    var rsrp: Int? = null
                    var rsrq: Int? = null
                    var rssnr: Int? = null
                    var cqi: Int? = null
                    var timingAdvance: Int? = null

                    var ssRsrp: Int? = null
                    var ssRsrq: Int? = null
                    var ssSinr: Int? = null

                    var csiRsrp: Int? = null
                    var csiRsrq: Int? = null
                    var csiSinr: Int? = null

                    when (cellInfo) {
                        is CellInfoLte -> {
                            cellNet = "LTE"
                            val id = cellInfo.cellIdentity
                            val sig = cellInfo.cellSignalStrength

                            mcc = id.mccString
                            mnc = id.mncString
                            ci = id.ci.toLong()
                            tac = id.tac
                            pci = id.pci
                            arfcn = id.earfcn

                            dbm = sig.dbm
                            asu = sig.asuLevel
                            level = sig.level

                            rsrp = sig.rsrp
                            rsrq = sig.rsrq
                            rssnr = sig.rssnr
                            cqi = sig.cqi
                            timingAdvance = sig.timingAdvance
                        }

                        is CellInfoNr -> {
                            cellNet = "NR"
                            val id = cellInfo.cellIdentity as? CellIdentityNr
                            val sig = cellInfo.cellSignalStrength as? CellSignalStrengthNr

                            if (id != null) {
                                mcc = id.mccString
                                mnc = id.mncString
                                ci = id.nci
                                tac = id.tac
                                pci = id.pci
                                arfcn = id.nrarfcn
                            }

                            if (sig != null) {
                                dbm = sig.dbm
                                asu = sig.asuLevel
                                level = sig.level

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    ssRsrp = sig.ssRsrp
                                    ssRsrq = sig.ssRsrq
                                    ssSinr = sig.ssSinr

                                    csiRsrp = sig.csiRsrp
                                    csiRsrq = sig.csiRsrq
                                    csiSinr = sig.csiSinr
                                }
                            }
                        }

                        is CellInfoWcdma -> {
                            cellNet = "WCDMA"
                            val id = cellInfo.cellIdentity
                            val sig = cellInfo.cellSignalStrength

                            mcc = id.mccString
                            mnc = id.mncString
                            ci = id.cid.toLong()
                            tac = id.lac
                            pci = id.psc
                            arfcn = id.uarfcn

                            dbm = sig.dbm
                            asu = sig.asuLevel
                            level = sig.level
                        }

                        is CellInfoGsm -> {
                            cellNet = "GSM"
                            val id = cellInfo.cellIdentity
                            val sig = cellInfo.cellSignalStrength

                            mcc = id.mccString
                            mnc = id.mncString
                            ci = id.cid.toLong()
                            tac = id.lac
                            arfcn = id.arfcn

                            dbm = sig.dbm
                            asu = sig.asuLevel
                            level = sig.level
                        }

                        else -> {
                            cellNet = "UNKNOWN"
                        }
                    }

                    // 🔹 헤더 순서에 맞게 값 채우기
                    val line = listOf(
                        // 1) 위치 / 기본
                        now,                     // timestamp
                        lat,
                        lon,
                        alt,
                        cellNet,                 // RAT
                        isRegistered,            // isServingCell (1/0)

                        // 2) PLMN / 셀 식별
                        mcc ?: "",               // MCC
                        mnc ?: "",               // MNC
                        operatorName,            // OperatorName
                        ci?.toString() ?: "",    // CellIdentity
                        tac?.toString() ?: "",   // TrackingAreaCode
                        pci?.toString() ?: "",   // PhysicalCellId
                        arfcn?.toString() ?: "", // ARFCN

                        // 3) 공통 신호 세기
                        dbm?.toString() ?: "",   // SignalStrengthDbm
                        asu?.toString() ?: "",   // SignalStrengthAsu
                        level?.toString() ?: "", // SignalStrengthLevel

                        // 4) LTE 전용 (RAT == LTE일 때만 의미 있음 / 나머지는 빈칸)
                        if (cellNet == "LTE") rsrp?.toString() ?: "" else "",   // LTE_RSRP
                        if (cellNet == "LTE") rsrq?.toString() ?: "" else "",   // LTE_RSRQ
                        if (cellNet == "LTE") dbm?.toString() ?: "" else "",    // LTE_RSSI (여기서는 dbm 사용)
                        if (cellNet == "LTE") rssnr?.toString() ?: "" else "",  // LTE_SINR
                        if (cellNet == "LTE") cqi?.toString() ?: "" else "",    // LTE_CQI
                        if (cellNet == "LTE") timingAdvance?.toString() ?: "" else "", // TimingAdvance

                        // 5) NR SSB 기반
                        if (cellNet == "NR") ssRsrp?.toString() ?: "" else "",  // NR_SSB_RSRP
                        if (cellNet == "NR") ssRsrq?.toString() ?: "" else "",  // NR_SSB_RSRQ
                        if (cellNet == "NR") ssSinr?.toString() ?: "" else "",  // NR_SSB_SINR

                        // 6) NR CSI 기반
                        if (cellNet == "NR") csiRsrp?.toString() ?: "" else "", // NR_CSI_RSRP
                        if (cellNet == "NR") csiRsrq?.toString() ?: "" else "", // NR_CSI_RSRQ
                        if (cellNet == "NR") csiSinr?.toString() ?: "" else ""  // NR_CSI_SINR
                    ).joinToString(",")

                    sb.append(line)
                    sb.append("\n")
                }

                if (sb.isNotEmpty()) {
                    csvBuffer.append(sb.toString())
                }
            }
    }
}
