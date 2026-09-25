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
    }

    override fun onResume() {
        super.onResume()
        lastRx = -1L
        lastTx = -1L
        lastAt = 0L
        sessionRx = 0L
        sessionTx = 0L
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
