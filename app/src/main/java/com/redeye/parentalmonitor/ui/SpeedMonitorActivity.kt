package com.redeye.parentalmonitor.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.utils.NetSpeed

class SpeedMonitorActivity : AppCompatActivity() {

    private lateinit var downValue: TextView
    private lateinit var downUnit: TextView
    private lateinit var upText: TextView
    private lateinit var totalText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L
    private var sessionRx = 0L
    private var sessionTx = 0L

    private val sampler = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speed_monitor)
        downValue = findViewById(R.id.downValue)
        downUnit = findViewById(R.id.downUnit)
        upText = findViewById(R.id.upText)
        totalText = findViewById(R.id.totalText)
        if (savedInstanceState != null) {
            sessionRx = savedInstanceState.getLong("sessionRx", 0L)
            sessionTx = savedInstanceState.getLong("sessionTx", 0L)
            lastRx = savedInstanceState.getLong("lastRx", -1L)
            lastTx = savedInstanceState.getLong("lastTx", -1L)
            lastAt = savedInstanceState.getLong("lastAt", 0L)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("sessionRx", sessionRx)
        outState.putLong("sessionTx", sessionTx)
        outState.putLong("lastRx", lastRx)
        outState.putLong("lastTx", lastTx)
        outState.putLong("lastAt", lastAt)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(sampler)
        if (lastRx < 0L || lastTx < 0L || lastAt <= 0L) {
            val totals = NetSpeed.totals()
            lastRx = totals.first
            lastTx = totals.second
            lastAt = SystemClock.elapsedRealtime()
        }
        handler.post(sampler)
    }

    override fun onPause() {
        handler.removeCallbacks(sampler)
        super.onPause()
    }

    private fun sample() {
        val totals = NetSpeed.totals()
        val rx = totals.first
        val tx = totals.second
        if (rx < 0 || tx < 0) {
            upText.text = getString(R.string.speed_unavailable)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastRx >= 0 && lastTx >= 0 && now > lastAt) {
            val dt = (now - lastAt).coerceAtLeast(1) / 1000.0
            val down = ((rx - lastRx).coerceAtLeast(0) / dt).toLong()
            val up = ((tx - lastTx).coerceAtLeast(0) / dt).toLong()
            sessionRx += (rx - lastRx).coerceAtLeast(0)
            sessionTx += (tx - lastTx).coerceAtLeast(0)
            val parts = NetSpeed.splitRate(down)
            downValue.text = parts.first
            downUnit.text = parts.second
            upText.text = "↑ " + NetSpeed.formatRate(up)
            totalText.text = getString(R.string.netspeed_session_fmt, NetSpeed.formatTotal(sessionRx), NetSpeed.formatTotal(sessionTx))
        }
        lastRx = rx
        lastTx = tx
        lastAt = now
    }
}
