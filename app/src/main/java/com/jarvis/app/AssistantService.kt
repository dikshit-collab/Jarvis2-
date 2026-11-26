package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

// MainActivity writes the time here whenever it comes to the front.
object JarvisState {
    @Volatile var resumedAt = 0L
}

// Makes Jarvis show up in Android's "default assistant" list.
class JarvisInteractionService : VoiceInteractionService()

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisSession(this)
    }
}

// Runs when you do the assistant gesture.
class JarvisSession(ctx: Context) : VoiceInteractionSession(ctx) {

    private val handler = Handler(Looper.getMainLooper())
    private var label: TextView? = null
    private var startedAt = 0L
    private var attempts = 0

    private fun dp(v: Int): Int {
        return (v * context.resources.displayMetrics.density).toInt()
    }

    override fun onCreateContentView(): View {
        val c = context
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setBackgroundColor(Color.parseColor("#F2020B14"))
        root.setPadding(dp(20), dp(24), dp(20), dp(24))

        val title = TextView(c)
        title.text = "J.A.R.V.I.S"
        title.setTextColor(Color.parseColor("#00E5FF"))
        title.textSize = 22f
        title.typeface = Typeface.MONOSPACE
        title.letterSpacing = 0.3f
        title.gravity = Gravity.CENTER
        root.addView(title)

        val orb = JarvisOrb(c)
        root.addView(orb, LinearLayout.LayoutParams(dp(240), dp(240)))

        val t = TextView(c)
        t.text = "AT YOUR SERVICE"
        t.setTextColor(Color.parseColor("#4DB6C4"))
        t.textSize = 12f
        t.typeface = Typeface.MONOSPACE
        t.letterSpacing = 0.15f
        t.gravity = Gravity.CENTER
        root.addView(t)
        label = t

        root.setOnClickListener {
            attempts = 0
            openJarvis()
        }
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        attempts = 0
        label?.text = "AT YOUR SERVICE"
        handler.postDelayed({ openJarvis() }, 500)
    }

    override fun onHide() {
        super.onHide()
        handler.removeCallbacksAndMessages(null)
    }

    private fun openJarvis() {
        val intent = Intent(context, MainActivity::class.java)
        intent.putExtra("auto_listen", true)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        startedAt = System.currentTimeMillis()
        attempts++
        try {
            if (attempts == 2) {
                startAssistantActivity(intent)
            } else {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            label?.text = "COULD NOT OPEN: " + (e.message ?: "error")
        }
        handler.postDelayed({ checkOpened() }, 900)
    }

    private fun checkOpened() {
        if (JarvisState.resumedAt >= startedAt) {
            hide()
        } else if (attempts < 2) {
            openJarvis()
        } else {
            label?.text = "TAP ANYWHERE TO OPEN JARVIS"
        }
    }
}

// Android requires every assistant to include this. Jarvis does not use it.
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: Exception) {
        }
    }

    override fun onCancel(listener: Callback?) {
    }

    override fun onStopListening(listener: Callback?) {
    }
}
