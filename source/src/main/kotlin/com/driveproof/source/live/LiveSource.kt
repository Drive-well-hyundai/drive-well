package com.driveproof.source.live

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.driveproof.model.Brake
import com.driveproof.model.Caution
import com.driveproof.model.CautionType
import com.driveproof.model.Lamp
import com.driveproof.model.RoadLimit
import com.driveproof.model.Gear
import com.driveproof.model.NaviState
import com.driveproof.model.Odometer
import com.driveproof.model.Signal
import com.driveproof.model.Speed
import com.driveproof.model.Steering
import com.driveproof.model.Tbt
import com.driveproof.model.TurnSignal
import com.driveproof.model.Telltale
import com.driveproof.model.TurnType
import com.driveproof.source.DemoLog
import com.driveproof.source.SignalSink
import com.driveproof.source.SignalThread
import com.driveproof.source.SourceKind
import com.driveproof.source.VehicleDataSource

/**
 * 실차 신호. 순서와 스레드는 implementation.md "SDK 초기화", "스레드와 통지", "오류 처리"를 따른다.
 *
 * - AAOS: 속도(`PERF_VEHICLE_SPEED`, 10Hz), 기어(`GEAR_SELECTION`). car service 콜백은 dp-signal로 온다.
 * - Pleos Vehicle: 깜빡이, 브레이크, 조향, 주행거리, 경고등(Safety `registerTelltales`). 메인 스레드에서 초기화·등록한다.
 * - NaviHelper: 회전 안내(`onTBTInfo` 첫 항목), 안내 상태(`onNavigationStatus`), 주의 지점(`onCautionPointInfo`),
 *   스쿨존 단속 카메라 제한속도(`onCameraAlert`), 도로 제한속도(`onCurrentRoadSpeedLimit`).
 *
 * Pleos·NaviHelper 콜백 스레드는 문서에 없다(확인 불가). 받은 시각을 먼저 찍고 dp-signal로 넘긴다.
 * 모든 신호의 `t`는 콜백 도착 시각(`SystemClock.elapsedRealtime()`)이다.
 */
class LiveSource(context: Context) : VehicleDataSource {

    override val kind = SourceKind.LIVE

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val token = Any()

    // dp-signal 스레드에서만 쓴다
    private var sink: SignalSink? = null
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var carRetry = 0

    // 메인 스레드에서만 쓴다
    private var mainActive = false
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private val vehicleRetries = HashMap<String, Int>()

    override fun start(sink: SignalSink) {
        SignalThread.run {
            this.sink = sink
            carRetry = 0
            connectCar()
        }
        main.post {
            mainActive = true
            startVehicle()
            startNavi()
        }
    }

    override fun stop() {
        SignalThread.run {
            sink = null
            SignalThread.handler.removeCallbacksAndMessages(token)
            SignalThread.handler.removeCallbacks(steeringTimeout)
            disconnectCar()
        }
        main.post {
            mainActive = false
            main.removeCallbacksAndMessages(token)
            stopVehicle()
            stopNavi()
        }
    }

    private fun emit(s: Signal) {
        SignalThread.run { sink?.onSignal(s) }
    }

    private fun now() = SystemClock.elapsedRealtime()

    // ---------- AAOS (dp-signal 스레드) ----------

    private fun connectCar() {
        // 기다리지 않고 만든다. 준비되면(또는 car service가 다시 뜨면) 리스너가 dp-signal에서 불린다.
        // 20초 기다림을 dp-signal에서 하면 그동안 재생·엔진이 멈춘다.
        val c = try {
            Car.createCar(app, SignalThread.handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { carObj, ready ->
                // 연결 콜백은 Handler를 넘겨도 메인 스레드로 왔다(11/11 에뮬레이터). dp-signal로 옮긴다
                SignalThread.run { if (ready) onCarReady(carObj) else onCarLost() }
            }
        } catch (e: RuntimeException) {
            DemoLog.warn("car service 연결 실패: $e")
            null
        }
        if (c == null) {
            scheduleCarRetry()
            return
        }
        car = c
    }

    /** 오류 처리 표: 1, 2, 4, 8, 16, 32, 60초 간격, 이후 60초마다 */
    private fun scheduleCarRetry() {
        val delaySec = minOf(60L, 1L shl minOf(carRetry, 6))
        carRetry++
        DemoLog.warn("car service 없음. ${delaySec}초 뒤 다시 연결 (${carRetry}번째)")
        SignalThread.handler.postAtTime({ if (sink != null) connectCar() }, token, SystemClock.uptimeMillis() + delaySec * 1000)
    }

    private fun onCarReady(c: Car) {
        if (sink == null) return
        unregisterCar()
        val m = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (m == null) {
            DemoLog.warn("car service 준비 콜백 뒤에도 CarPropertyManager가 없다")
            return
        }
        cpm = m
        carRetry = 0
        speedThreadLogged = false
        val speedOk = register(m, speedCb, VehiclePropertyIds.PERF_VEHICLE_SPEED, SPEED_HZ, "속도")
        val gearOk = register(m, gearCb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE, "기어")
        DemoLog.sdk("car service 연결: 속도 ${if (speedOk) "등록" else "실패"}, 기어 ${if (gearOk) "등록" else "실패"}")
    }

    private fun register(m: CarPropertyManager, cb: CarPropertyManager.CarPropertyEventCallback, prop: Int, rate: Float, name: String): Boolean =
        try {
            m.registerCallback(cb, prop, rate)
        } catch (e: SecurityException) {
            DemoLog.warn("$name 등록 실패: 권한 없음 (${e.message})")
            false
        } catch (e: IllegalArgumentException) {
            DemoLog.warn("$name 등록 실패: $e")
            false
        }

    private fun onCarLost() {
        // car service가 죽었다. Car 객체가 다시 연결하면 onCarReady가 다시 불린다
        cpm = null
        DemoLog.warn("car service 끊김. 다시 뜨기를 기다린다")
    }

    private fun unregisterCar() {
        cpm?.let { m ->
            runCatching { m.unregisterCallback(speedCb) }
            runCatching { m.unregisterCallback(gearCb) }
        }
        cpm = null
    }

    private fun disconnectCar() {
        unregisterCar()
        car?.let { runCatching { it.disconnect() } }
        car = null
    }

    @Volatile private var speedThreadLogged = false

    private val speedCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) {
            val t = now()
            if (!speedThreadLogged) {
                // 메인 스레드에서 받으면 ANR이 난다(driving-record.md "신호 처리"). 처음 한 번 스레드를 남긴다
                speedThreadLogged = true
                DemoLog.sdk("속도 콜백 스레드: ${Thread.currentThread().name}")
            }
            if (v.status != CarPropertyValue.STATUS_AVAILABLE) return
            val mps = v.value as? Float ?: return
            emit(Speed(t, LiveMapping.kmh(mps)))
        }

        override fun onErrorEvent(propId: Int, zone: Int) = DemoLog.warn("속도 오류 이벤트")
    }

    private val gearCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) {
            val t = now()
            if (v.status != CarPropertyValue.STATUS_AVAILABLE) return
            val raw = v.value as? Int ?: return
            val g = LiveMapping.gear(raw) ?: return DemoLog.warn("모르는 기어 값 $raw")
            emit(Gear(t, g))
        }

        override fun onErrorEvent(propId: Int, zone: Int) = DemoLog.warn("기어 오류 이벤트")
    }

    // ---------- Pleos Vehicle (메인 스레드) ----------

    private fun startVehicle() {
        val v = Vehicle(app)
        try {
            v.initialize()
        } catch (e: RuntimeException) {
            DemoLog.warn("Pleos Vehicle 초기화 실패: $e. 60초 뒤 다시 시도")
            main.postAtTime({ if (mainActive) startVehicle() }, token, SystemClock.uptimeMillis() + 60_000)
            return
        }
        vehicle = v
        vehicleRetries.clear()
        // 초기화 완료 콜백이 없다(확인 불가). 테스트 앱의 경험값 1.5초를 기다린다
        main.postAtTime({ registerVehicleAll() }, token, SystemClock.uptimeMillis() + VEHICLE_INIT_WAIT_MS)
    }

    private fun registerVehicleAll() {
        registerVehicle("깜빡이") { it.getTurnSignal().registerTurnSignalState(turnL) }
        registerVehicle("브레이크") { it.getBrake().registerBrakePedalStatus(brakeL) }
        registerVehicle("조향") { it.getSteeringWheel().registerSteeringWheelAngle(steerL) }
        registerVehicle("주행거리") { it.getOdometer().registerDistanceDriven(odoL) }
        registerVehicle("경고등") { it.getSafety().registerTelltales(telltaleL) }
        DemoLog.sdk("Pleos Vehicle 등록: 깜빡이, 브레이크, 조향, 주행거리, 경고등")
    }

    private fun registerVehicle(name: String, block: (Vehicle) -> Unit) {
        if (!mainActive) return
        val v = vehicle ?: return
        try {
            block(v)
        } catch (e: RuntimeException) {
            onVehicleFailed(name, e)
        }
    }

    /** onFailed가 오면 1초 뒤 다시 등록한다. 5번 넘게 실패하면 60초 간격으로 늦춘다 */
    private fun onVehicleFailed(name: String, e: Exception) {
        main.post {
            if (!mainActive) return@post
            val n = (vehicleRetries[name] ?: 0) + 1
            vehicleRetries[name] = n
            val delay = if (n <= 5) 1_000L else 60_000L
            DemoLog.warn("$name 실패 ($n): $e. ${delay / 1000}초 뒤 다시 등록")
            main.postAtTime({ retryVehicle(name) }, token, SystemClock.uptimeMillis() + delay)
        }
    }

    private fun retryVehicle(name: String) = when (name) {
        "깜빡이" -> registerVehicle(name) { it.getTurnSignal().registerTurnSignalState(turnL) }
        "브레이크" -> registerVehicle(name) { it.getBrake().registerBrakePedalStatus(brakeL) }
        "조향" -> registerVehicle(name) { it.getSteeringWheel().registerSteeringWheelAngle(steerL) }
        "주행거리" -> registerVehicle(name) { it.getOdometer().registerDistanceDriven(odoL) }
        "경고등" -> registerVehicle(name) { it.getSafety().registerTelltales(telltaleL) }
        else -> Unit
    }

    private fun stopVehicle() {
        val v = vehicle ?: return
        runCatching { v.getTurnSignal().unregisterTurnSignalState(turnL) }
        runCatching { v.getBrake().unregisterBrakePedalStatus(brakeL) }
        runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(steerL) }
        runCatching { v.getOdometer().unregisterDistanceDriven(odoL) }
        runCatching { v.getSafety().unregisterTelltales(telltaleL) }
        runCatching { v.release() }
        vehicle = null
    }

    private val turnL = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            val t = now()
            val b = LiveMapping.blink(state?.name) ?: return
            emit(TurnSignal(t, b))
        }

        override fun onFailed(e: Exception) = onVehicleFailed("깜빡이", e)
    }

    private val brakeL = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            val t = now()
            emit(Brake(t, pressed ?: return))
        }

        override fun onFailed(e: Exception) = onVehicleFailed("브레이크", e)
    }

    private val steeringTimeout = Runnable { sink?.onSignal(Steering(now(), 0.0, available = false)) }

    private val steerL = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            val t = now()
            SignalThread.run {
                if (sink == null) return@run
                SignalThread.handler.removeCallbacks(steeringTimeout)
                val value = angle?.takeIf(Float::isFinite)
                sink?.onSignal(Steering(t, value?.let(LiveMapping::steeringDeg) ?: 0.0, value != null))
                // 같은 각도도 약 3초마다 갱신된다(sdk.md).
                if (value != null) SignalThread.handler.postDelayed(steeringTimeout, 6_000)
            }
        }

        override fun onFailed(e: Exception) {
            onSteeringWheelAngleUpdated(null)
            onVehicleFailed("조향", e)
        }
    }

    private val odoL = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            val t = now()
            // 단위는 확인 불가다. km로 보고 넘긴다. 실측 뒤 sdk.md에 적는다
            emit(Odometer(t, (value ?: return).toDouble()))
        }

        override fun onFailed(e: Exception) = onVehicleFailed("주행거리", e)
    }

    // dp-signal 스레드에서만 쓴다: 직전에 켜져 있던 경고등
    private var telltales: Map<String, Lamp> = emptyMap()

    /** 켜진 경고등 목록 전체가 온다. 직전과 비교해 바뀐 것만 낸다(꺼진 것은 OFF) */
    private val telltaleL = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(info: Telltales?) {
            val t = now()
            val list = info?.telltales.orEmpty().map { it.type.name to it.state?.name }
            SignalThread.run {
                val (next, changes) = LiveMapping.telltaleChanges(telltales, list)
                telltales = next
                changes.forEach { (name, lamp) -> sink?.onSignal(Telltale(t, name, lamp)) }
            }
        }

        override fun onFailed(e: Exception) = onVehicleFailed("경고등", e)
    }

    // ---------- NaviHelper (메인 스레드) ----------

    /** false면 실제 `onTBTInfo`를 엔진에 넘기지 않는다. 하이브리드가 합성 TBT를 대신 낸다 */
    @Volatile internal var emitTbt = true

    /** 회전 목록 전체를 받는다. 하이브리드가 목록을 고정할 때 쓴다. 콜백 스레드는 확인 불가다 */
    @Volatile internal var onTbtList: ((List<TBTInfo>) -> Unit)? = null

    private var naviReady = false

    /** NaviHelper가 연결돼 있으면 메인 스레드에서 [block]을 돌린다. 연결 전이면 false */
    internal fun withNavi(block: (NaviHelper) -> Unit) {
        main.post {
            val n = navi
            if (mainActive && naviReady && n != null) block(n)
        }
    }

    private fun startNavi() {
        val n = NaviHelper(app)
        navi = n
        try {
            n.initialize(
                { main.post { if (mainActive && navi === n) { n.addListener(naviL); naviReady = true; DemoLog.sdk("NaviHelper 연결") } } },
                { e -> main.post { onNaviFailed(n, e) } },
            )
        } catch (e: RuntimeException) {
            onNaviFailed(n, e)
        }
    }

    /** 오류 처리 표: 차량 신호 경로만 쓰고 60초마다 다시 연결한다 */
    private fun onNaviFailed(n: NaviHelper, e: Throwable) {
        if (navi !== n) return
        DemoLog.warn("NaviHelper 연결 실패: $e. 차량 신호 경로만 쓰고 60초 뒤 다시 연결")
        runCatching { n.release() }
        navi = null
        naviReady = false
        main.postAtTime({ if (mainActive) startNavi() }, token, SystemClock.uptimeMillis() + 60_000)
    }

    private fun stopNavi() {
        val n = navi ?: return
        runCatching { n.removeListener(naviL) }
        runCatching { n.release() }
        navi = null
        naviReady = false
    }

    private val naviL = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val t = now()
            onTbtList?.invoke(tbtInfoList)
            if (!emitTbt) return
            val first = tbtInfoList.firstOrNull() ?: return
            emit(Tbt(t, TurnType.fromName(first.type.name), first.distance))
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            val t = now()
            emit(NaviState(t, LiveMapping.routeDrive(status.name)))
        }

        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            val t = now()
            val points = cautionPointList.map { it.type.name to it.remainDistance }
            SignalThread.run { onCaution(t, LiveMapping.nearestCaution(points)) }
        }

        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
            val t = now()
            val cams = cameraAlertList.map { Triple(it.type.name, it.speedLimit, it.remainDistance) }
            SignalThread.run { onSchoolLimit(t, LiveMapping.schoolZoneLimit(cams)) }
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            val t = now()
            // 약 1초마다 같은 값이 온다(11/10, 값 0). 바뀔 때만 낸다
            SignalThread.run {
                if (speedLimit != roadLimit) {
                    roadLimit = speedLimit
                    sink?.onSignal(RoadLimit(t, speedLimit))
                }
            }
        }

        override fun onError(errorCode: NaviErrorCode) = DemoLog.warn("NaviHelper 오류 $errorCode")
    }

    // dp-signal 스레드에서만 쓴다
    private var caution: Pair<CautionType, Int>? = null
    private var schoolLimit: Int? = null
    private var roadLimit: Int? = null

    /** 가장 가까운 스쿨존·과속방지턱. 없어지면 type null로 한 번 낸다 */
    private fun onCaution(t: Long, nearest: Pair<CautionType, Int>?) {
        val prev = caution
        caution = nearest
        when {
            nearest != null -> sink?.onSignal(cautionSignal(t, nearest))
            prev != null -> sink?.onSignal(Caution(t, null, null))
        }
    }

    /** 스쿨존 카메라 제한속도가 바뀌면 스쿨존 안에서는 다시 낸다 */
    private fun onSchoolLimit(t: Long, limit: Int?) {
        if (limit == schoolLimit) return
        schoolLimit = limit
        caution?.takeIf { it.first == CautionType.SCHOOL_ZONE }?.let { sink?.onSignal(cautionSignal(t, it)) }
    }

    private fun cautionSignal(t: Long, c: Pair<CautionType, Int>) =
        Caution(t, c.first, c.second.toDouble(), if (c.first == CautionType.SCHOOL_ZONE) schoolLimit else null)

    private companion object {
        /** driving-record.md "신호 처리": 10Hz로 등록한다 */
        const val SPEED_HZ = 10f
        const val VEHICLE_INIT_WAIT_MS = 1_500L
    }
}
