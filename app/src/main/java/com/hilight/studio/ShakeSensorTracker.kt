package com.hilight.studio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock

/**
 * Accelerometer adapter for shake-for-sparkles, owned by [ForegroundWatcher]; the recognition itself
 * is the JVM-tested [ShakeDetector].
 *
 * It listens only while the screen is on. That keeps the cost to nothing while the phone sleeps, and
 * it also avoids a trap: a non-wake-up sensor can hand over a batch of old samples when the phone
 * wakes, which would read as a shake that happened minutes ago.
 */
internal class ShakeSensorTracker(
    private val context: Context,
    private val handler: Handler,
    private val onShake: () -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val detector = ShakeDetector()
    @Volatile private var wanted = false
    private var listening = false
    private var receiverRegistered = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handler.post { listen(true) }
                Intent.ACTION_SCREEN_OFF -> handler.post { listen(false) }
            }
        }
    }

    @Synchronized
    fun start() {
        if (wanted) return
        wanted = true
        if (!receiverRegistered) {
            context.registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_ON).apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            }, null, handler)
            receiverRegistered = true
        }
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        listen(interactive)
    }

    /** Unregister before the owner quits [handler]'s looper. */
    @Synchronized
    fun stop() {
        wanted = false
        listen(false)
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(screen) }
            receiverRegistered = false
        }
    }

    @Synchronized
    private fun listen(on: Boolean) {
        val m = manager
        val s = sensor
        val should = on && wanted && s != null && m != null
        if (should == listening) return
        if (should && s != null && m != null) {
            detector.reset()
            listening = m.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, handler)
        } else {
            manager?.unregisterListener(this)
            listening = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening || event.values.size < 3) return
        if (detector.onSample(event.values[0], event.values[1], event.values[2], SystemClock.elapsedRealtime())) {
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
