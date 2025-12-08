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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import android.preference.PreferenceManager

class CellTowerLoggingActivity : AppCompatActivity() {

    // Telephony
    private lateinit var telephonyManager: TelephonyManager

    // GPS
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private var isGpsInitialized = false
    private var pendingStart = false

    // UI
    private lateinit var txtStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // --- OSMDroid 지도 관련 ---
    private lateinit var map: MapView
    private val pathPoints = mutableListOf<GeoPoint>()

    // 로깅 제어
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

        // OSMDroid 설정 로드
        Configuration.getInstance()
            .load(this, PreferenceManager.getDefaultSharedPreferences(this))

        setContentView(R.layout.activity_celltowerlogging)

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        txtStatus = findViewById(R.id.txtStatus)
        btnStart = findViewById(R.id.btnStartLogging)
        btnStop = findViewById(R.id.btnStopLogging)

        // 지도 바인딩
        map = findViewById(R.id.osmMap)
        initMap()

        btnStart.setOnClickListener {
            startLogging()
        }

        btnStop.setOnClickListener {
            stopLogging()
        }

        // 위치 권한 체크 및 요청
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                requiredPermissions,
                PERMISSION_REQUEST_CODE
            )
        } else {
            initGps()
        }
    }

    /** OSMDroid 지도 초기화 */
    private fun initMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)
        // 초기 위치: 서울 시청 근처 (임시)
        map.controller.setCenter(GeoPoint(37.5665, 126.9780))
    }

    /** GPS 업데이트 요청 (SensorData와 동일하게 LocationManager 사용) */
    @SuppressLint("MissingPermission")
    private fun initGps() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,   // 1초 주기
            1f,      // 1m 이상 이동 시
            gpsListener
        )
    }

    /** GPS 콜백 (SensorData 패턴 + 지도 경로 그리기) */
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            currentLocation = loc

            if (!isGpsInitialized) {
                isGpsInitialized = true
                Toast.makeText(this@CellTowerLoggingActivity, "GPS 초기화 완료", Toast.LENGTH_SHORT)
                    .show()

                // startLogging() 호출 당시 GPS 미완료였다면, 여기서 실제 로깅 시작
                if (pendingStart) {
                    pendingStart = false
                    actualStartLogging()
                }

                // 최초 GPS 위치로 지도 중심 이동
                val first = GeoPoint(loc.latitude, loc.longitude)
                map.controller.setCenter(first)
            }

            // 로깅 중일 때만 경로 그리기
            if (isLogging) {
                val gp = GeoPoint(loc.latitude, loc.longitude)
                pathPoints.add(gp)
                drawPath()
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** 경로 폴리라인 그리기 */
    private fun drawPath() {
        map.overlays.removeAll { it is Polyline }
        val line = Polyline().apply {
            setPoints(pathPoints)
            width = 5f
        }
        map.overlays.add(line)
        pathPoints.lastOrNull()?.let { map.controller.animateTo(it) }
        map.invalidate()
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Start 버튼 눌렀을 때 호출 */
    private fun startLogging() {
        if (isLogging) return

        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                requiredPermissions,
                PERMISSION_REQUEST_CODE
            )
            return
        }

        btnStart.isEnabled = false

        if (isGpsInitialized) {
            // 이미 GPS fix가 된 상태이면 바로 로깅 시작
            actualStartLogging()
        } else {
            // GPS 아직이면, 플래그만 세워두고 GPS 콜백에서 시작
            pendingStart = true
            txtStatus.text = "상태: GPS 초기화 대기..."
            btnStop.isEnabled = false
        }
    }

    /** 실제 로깅 시작 */
    private fun actualStartLogging() {
        // CSV 헤더 초기화
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
        btnStop.isEnabled = true

        // 경로 초기화
        pathPoints.clear()

        // 1초마다 로그
        handler.post(logRunnable)

        Toast.makeText(this, "셀+GPS 로깅 시작", Toast.LENGTH_SHORT).show()
    }

    /** Stop 버튼 눌렀을 때 */
    private fun stopLogging() {
        if (!isLogging) return

        isLogging = false
        handler.removeCallbacks(logRunnable)
        btnStop.isEnabled = false
        btnStart.isEnabled = true

        // 수집 종료 시 Downloads 폴더에 저장
        if (csvBuffer.isNotEmpty()) {
            val filename = "cell_log_${System.currentTimeMillis()}.csv"
            saveCsvToDownloads(filename, csvBuffer.toString())
            txtStatus.text = "상태: 수집 종료 (저장: Download/$filename)"
        } else {
            txtStatus.text = "상태: 수집 종료 (저장할 데이터 없음)"
        }
    }

    /** MediaStore를 이용해 Download 폴더에 CSV 저장 */
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

    /** 1초마다 logOnce 실행 */
    private val logRunnable = object : Runnable {
        override fun run() {
            if (!isLogging) return
            logOnce()
            handler.postDelayed(this, 1000L) // 1초 간격
        }
    }

    /** 1회 로깅 (GPS + 셀 정보) */
    private fun logOnce() {
        // 권한 체크
        if (!hasAllPermissions()) return

        // GPS가 아직 없다면 스킵
        val location = currentLocation ?: return

        val lat = location.latitude
        val lon = location.longitude
        val alt = location.altitude
        val now = System.currentTimeMillis()

        // 셀 정보 가져오기
        val cellInfos = try {
            telephonyManager.allCellInfo
        } catch (e: SecurityException) {
            null
        } ?: return

        if (cellInfos.isEmpty()) return

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
                        ci = id.nci       // NR에서는 nci 사용
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

                // 4) LTE 전용
                if (cellNet == "LTE") rsrp?.toString() ?: "" else "",   // LTE_RSRP
                if (cellNet == "LTE") rsrq?.toString() ?: "" else "",   // LTE_RSRQ
                if (cellNet == "LTE") dbm?.toString() ?: "" else "",    // LTE_RSSI
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initGps()
                startLogging()
            } else {
                txtStatus.text = "상태: 권한 거부됨"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::map.isInitialized) {
            map.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::map.isInitialized) {
            map.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(logRunnable)
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(gpsListener)
        }
    }
}
