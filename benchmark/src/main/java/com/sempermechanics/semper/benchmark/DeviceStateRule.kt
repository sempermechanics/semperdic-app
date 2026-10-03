package com.sempermechanics.semper.benchmark

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * Records the phone's state at the start and end of each benchmark (TD-135).
 *
 * A startup time moves 30–40 % with heat, the charger and memory pressure, so a
 * median means little without the state it was taken in. This writes, next to
 * the benchmark JSON, `<package>-deviceState.json`: per test, the thermal status,
 * battery temperature and level, the charger and the free memory, before and
 * after. `scripts/ci_test_report.py --gates` reads it and does not gate a result
 * taken throttled or off the charger.
 *
 * The file goes where Macrobenchmark writes its own output: the
 * `additionalTestOutputDir` argument (set by Gradle's connected task), else the
 * app's first external media dir (an `am instrument` run). Both snapshots are
 * written when the test ends: a file written at the start did not survive the
 * benchmark's own output handling on a Pixel 6.
 */
class DeviceStateRule : TestWatcher() {

    private var startState: JSONObject? = null

    override fun starting(description: Description) {
        startState = runCatching { snapshot() }.getOrNull()
    }

    override fun finished(description: Description) {
        val name = "${description.testClass?.simpleName ?: "?"}.${description.methodName ?: "?"}"
        runCatching {
            synchronized(LOCK) {
                val file = outputFile()
                val root = if (file.exists()) JSONObject(file.readText()) else JSONObject()
                root.put("device", Build.DEVICE)
                val tests = root.optJSONObject("tests") ?: JSONObject().also { root.put("tests", it) }
                val test = JSONObject()
                startState?.let { test.put("start", it) }
                test.put("end", snapshot())
                tests.put(name, test)
                file.writeText(root.toString(2))
            }
        }.onFailure { error ->
            // The state is evidence for the gate, never a reason to fail a benchmark.
            android.util.Log.w(TAG, "could not record device state for $name", error)
        }
    }

    private fun snapshot(): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().context
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val swap = swapUsedMb()
        return JSONObject().apply {
            put("timeMs", System.currentTimeMillis())
            // PowerManager.THERMAL_STATUS_NONE is 0; −1 means the API (29+) is missing.
            put("thermalStatus", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else -1)
            battery?.let {
                put("batteryTempC", it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / TENTHS)
                put("batteryPct", it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                put("plugged", pluggedName(it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)))
            }
            put("powerSave", power.isPowerSaveMode)
            put("availMemMb", memory.availMem / MB)
            put("lowMemory", memory.lowMemory)
            if (swap != null) put("swapUsedMb", swap)
        }
    }

    /** SwapTotal − SwapFree from `/proc/meminfo`, in MB; null where it can't be read. */
    private fun swapUsedMb(): Long? = runCatching {
        val kb = File("/proc/meminfo").readLines().associate { line ->
            val parts = line.split(Regex("\\s+"))
            parts[0].trimEnd(':') to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
        }
        val total = kb["SwapTotal"] ?: return@runCatching null
        (total - (kb["SwapFree"] ?: 0L)) / KB_PER_MB
    }.getOrNull()

    private fun pluggedName(plugged: Int) = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "none"
        else -> "other"
    }

    private fun outputFile(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File)
            ?: instrumentation.context.externalMediaDirs.first()
        dir.mkdirs()
        return File(dir, "${instrumentation.context.packageName}-deviceState.json")
    }

    private companion object {
        const val TAG = "DeviceStateRule"
        const val TENTHS = 10.0
        const val MB = 1024L * 1024L
        const val KB_PER_MB = 1024L
        val LOCK = Any()
    }
}
