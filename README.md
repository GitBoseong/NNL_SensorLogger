# 📡 NLL_SensorToText

### 📱 Android 기반 센서 로깅 & Wi-Fi/BLE 라디오맵 생성 도구

---

## 🧭 앱 목적

이 앱은 실내 위치 측위 및 PDR(Pedestrian Dead Reckoning) 연구를 위한 **센서 기반 데이터 수집 도구**입니다.

스마트폰의 다양한 센서 데이터를 로깅하고, Wi-Fi RSSI 및 BLE RSSI 값을 함께 수집하여 실내항법 연구에 활용할 수 있습니다.  
기존 IMU/GPS 로깅, Wi-Fi 라디오맵 생성, Arduino BLE 센서 로깅, PDR 시연 기능에 더해,  
최근에는 **PDR + RF 통합 데이터 수집 기능**이 추가되었습니다.

---

## ⚙️ 주요 기능

---

### 1️⃣ IMU + GPS 센서 데이터 로깅

- ⏱ **수집 주기**
  - 센서 데이터: 50Hz (`20ms` 간격)
  - GPS: 1Hz (`1초 간격`으로 최신 값 유지)

- 📦 **저장되는 센서 종류**

| 센서명 | Sensor Type | 설명 |
|---|---|---|
| Accelerometer | TYPE_ACCELEROMETER | x, y, z 축의 가속도 |
| Gyroscope | TYPE_GYROSCOPE | x, y, z 축의 각속도 |
| Magnetometer | TYPE_MAGNETIC_FIELD | 지자기 필드 값 |
| Orientation / Game RV | TYPE_GAME_ROTATION_VECTOR | 방향성 정보 |
| Pressure Sensor | TYPE_PRESSURE | 기압 센서 |
| GPS | LocationManager | 위도, 경도, 고도, 속도 |

- 🗂 **CSV 저장**
  - 저장 경로: `Downloads/SensorData/`
  - 파일명: `sensor_data_yyyyMMdd_HHmmss.csv`

---

### 2️⃣ Wi-Fi 라디오맵 생성

- 📶 **Wi-Fi 스캔 및 RSSI 측정**
  - 측정 시작 시 가장 강한 AP 자동 선정
  - 선택된 AP에 대해서 RSSI 값 수집
  - 각 RP(Reference Point)에서 반복 측정 가능
  - 신호가 없는 AP는 `-100`으로 저장 가능

- 📂 **CSV 저장**
  - 저장 경로: `Downloads/`
  - 파일명: 사용자 지정 이름 + 시간 정보

- 📄 **저장 형식 예시**

```csv
Index,AP1,AP2,AP3,AP4,AP5
1,-42,-51,-60,-100,-100
```

- `Index`: 측정 위치 번호
- `APn`: AP별 RSSI 값
- 열 이름에는 MAC Address 또는 SSID 정보 포함 가능

---

### 3️⃣ Arduino Nano 33 BLE 센서 로깅

- 🔗 **BLE(Bluetooth Low Energy) 통신** 기반 실시간 센서 수신
- ✅ 스마트폰에서 `start / stop` 명령을 전송하여 Arduino 센서 수집 제어
- ⏱ **수집 주기**: 약 20Hz
- 📄 **저장 형식**

```csv
timestamp,ax,ay,az,gx,gy,gz
```

- 📂 **저장 경로**

```text
Downloads/sensordata_yyyyMMdd_HHmmss.csv
```

> ※ 센서 측정은 Arduino Nano 33 BLE 또는 BLE 센서 보드 기준이며,  
> BLE UUID 및 수신 포맷은 앱과 Arduino 코드에서 커스터마이징 가능함.

---

### 4️⃣ IMU + RSSI 동시 로깅 기능

- 🕒 **센서 50Hz 로깅**
  - Accelerometer
  - Gyroscope
  - Magnetometer
  - Orientation / GameRotationVector
  - Pressure Sensor

- 📶 **Wi-Fi 스캔**
  - 일정 주기마다 Wi-Fi 스캔 실행
  - AP별 BSSID, SSID, RSSI, 주파수 대역 수집
  - 센서 로그와 Wi-Fi 로그를 분리 저장하거나 후처리 가능

- 📂 **파일 저장 경로**
  - `Downloads/`
  - `SensorData_yyyyMMdd_HHmmss.csv`
  - `WifiData_yyyyMMdd_HHmmss.csv`

---

### 5️⃣ PDR Path 시연 기능

- 👣 **걸음 검출**
  - Accelerometer threshold 기반 걸음 검출
  - Android StepDetector API 지원 기기에서 활용 가능
  - 보폭 기준으로 이동 거리 업데이트

- 🧭 **Heading 추정**
  - `TYPE_GAME_ROTATION_VECTOR` 센서 기반 방위각 계산
  - 실제 왼쪽/오른쪽 회전 방향과 일치하도록 좌표계 보정 적용

- 🗺 **경로 시각화**
  - `SurfaceView` 기반 실시간 경로 표시
  - 1m 단위 Grid 및 좌표 label 표시
  - 현재 위치를 화면 중심에 표시하여 미니맵처럼 경로 확인 가능

- 📊 **화면 표시**
  - 현재 Step 수
  - 누적 Step 수
  - Heading 값
  - 실시간 이동 경로

---

### 6️⃣ PDR + RF 통합 데이터 수집 기능

최근 추가된 기능입니다.

기존 센서 로깅 기능에 BLE/Wi-Fi RSSI 수집 기능을 결합하여,  
PDR 연구와 RF Fingerprinting 연구에 함께 사용할 수 있는 통합 CSV 데이터를 생성합니다.

#### ✅ 주요 기능

- 📱 **스마트폰 센서 50Hz 로깅**
  - Accelerometer
  - Gyroscope
  - Magnetometer
  - GameRotationVector

- 🔄 **GameRotationVector 저장 형식**
  - `grv_w`
  - `grv_x`
  - `grv_y`
  - `grv_z`

- 📶 **BLE RSSI 수집 토글**
  - 화면의 토글 버튼으로 BLE 수집 활성화 / 비활성화 가능
  - BLE 스캔 결과를 같은 시간 행의 RF 열로 저장

- 📡 **Wi-Fi RSSI 수집 토글**
  - 화면의 토글 버튼으로 Wi-Fi 수집 활성화 / 비활성화 가능
  - Wi-Fi 스캔 결과를 같은 시간 행의 RF 열로 저장

- 🔍 **TJ BLE 필터 기능**
  - BLE 이름이 `"TJ"`로 시작하는 장치만 저장하는 필터 기능 추가
  - 토글 버튼으로 활성화 / 비활성화 가능

- 🧾 **동적 CSV 헤더 생성**
  - BLE 및 Wi-Fi 스캔 중 새로 등장한 장치를 자동으로 열에 추가
  - 수집 종료 시 전체 헤더를 구성하여 CSV 저장

- 🖥 **화면 설정**
  - 수집 중 화면 항상 켜짐 유지
  - 화면 세로 모드 고정
  - 메인 화면 최상단에 `PDR + RF 통합 데이터 수집` 버튼 추가

#### 📄 CSV 저장 형식 예시

```csv
time,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z,grv_w,grv_x,grv_y,grv_z,BLE_AA:BB:CC:DD:EE:FF/TJ_001,WIFI_11:22:33:44:55:66/iptime
2026-06-03 15:30:01.020,0.1,9.7,0.2,0.01,0.02,0.00,32.1,-12.3,45.0,0.99,0.01,0.02,0.03,-72,-55
```

#### 📂 저장 경로 및 파일명

- 저장 경로: `Downloads/`
- 파일명 형식:

```text
yyyyMMdd_HHmmss_PDR_RF.csv
```

예시:

```text
20260603_153001_PDR_RF.csv
```

#### 📝 사용 방법

1. 메인 화면에서 `PDR + RF 통합 데이터 수집` 선택
2. BLE 수집 여부 선택
3. Wi-Fi 수집 여부 선택
4. 필요 시 `TJ BLE만 수집` 옵션 활성화
5. `수집 시작` 버튼 클릭
6. 이동 또는 실험 진행
7. `수집 종료` 버튼 클릭
8. `Downloads/` 폴더에 CSV 파일 자동 저장

---

## 📄 CSV 저장 형식 정리

---

### ✅ IMU + GPS 데이터

```csv
timestamp,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z,yaw,pitch,roll,latitude,longitude
1712345678912,0.01,9.81,0.05,-0.02,0.01,0.00,30.1,2.0,0.3,37.12345,127.12345
```

- `timestamp`: 측정 시간
- `acc_*`: x, y, z 축 가속도
- `gyro_*`: x, y, z 축 각속도
- `mag_*`: x, y, z 지자기
- `orientation_*`: 방향 정보
- `pressure`: 기압
- `GPS`: 위도, 경도, 고도, 속도

---

### ✅ Wi-Fi 라디오맵 데이터

```csv
Index,AP1,AP2,AP3,AP4,AP5,AP6,AP7,AP8,AP9,AP10
1,-42,-51,-60,-70,-100,-100,-100,-100,-100,-100
```

- `Index`: 측정 위치 번호
- `APn`: AP별 RSSI 값
- 신호가 없을 경우 `-100` 기록
- 반복 측정 후 평균 RSSI 저장 가능

---

### ✅ PDR + RF 통합 데이터

```csv
time,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z,grv_w,grv_x,grv_y,grv_z,BLE_MAC/SSID,WIFI_BSSID/SSID
2026-06-03 15:30:01.020,0.1,9.7,0.2,0.01,0.02,0.00,32.1,-12.3,45.0,0.99,0.01,0.02,0.03,-72,-55
```

- `time`: 측정 시간
- `acc_*`: 스마트폰 가속도
- `gyro_*`: 스마트폰 자이로스코프
- `mag_*`: 스마트폰 지자기
- `grv_*`: GameRotationVector quaternion 값
- `BLE_MAC/SSID`: BLE 장치 RSSI
- `WIFI_BSSID/SSID`: Wi-Fi AP RSSI

---

## 🔐 Android 권한

Android 버전에 따라 다음 권한이 필요할 수 있습니다.

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />

<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

> Android 12 이상에서는 BLE 스캔을 위해 `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` 권한이 필요함.  
> Wi-Fi 스캔은 Android 버전 및 제조사 정책에 따라 실제 스캔 주기가 제한될 수 있음.

---

## 🧪 활용 분야

- 실내 위치 추정 연구
- PDR(Pedestrian Dead Reckoning)
- Wi-Fi Fingerprinting
- BLE Fingerprinting
- RF 기반 라디오맵 구축
- 스마트폰 센서 기반 보행 데이터 수집
- IMU + RF 융합 위치 추정 연구

---

## 🧩 최근 변경사항

### vNext

- `PDRWithBLE_WiFi` Activity 추가
- 메인 화면 최상단에 `PDR + RF 통합 데이터 수집` 카드 추가
- BLE 수집 활성화 / 비활성화 토글 추가
- Wi-Fi 수집 활성화 / 비활성화 토글 추가
- `"TJ"`로 시작하는 BLE 장치만 저장하는 필터 옵션 추가
- Accelerometer, Gyroscope, Magnetometer, GameRotationVector 50Hz 로깅 추가
- BLE/Wi-Fi RSSI를 같은 시간 행에 저장하는 CSV 구조 추가
- BLE/Wi-Fi 장치가 새로 등장해도 CSV 헤더가 자동 확장되도록 개선
- 수집 종료 시 `yyyyMMdd_HHmmss_PDR_RF.csv` 형식으로 저장
- 수집 화면 항상 켜짐 유지
- 수집 화면 세로 모드 고정

---

## 👨‍💻 개발자

- 김보성 (Boseong Kim)
- GitHub: [GitBosung](https://github.com/GitBosung)
