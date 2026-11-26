package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaRecorder
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class KeyException(msg: String) : Exception(msg)

class MainActivity : Activity() {

    private lateinit var orb: JarvisOrb
    private lateinit var status: TextView
    private lateinit var result: TextView
    private var recorder: MediaRecorder? = null
    private var recording = false
    private var busy = false
    private val audioFile by lazy { File(cacheDir, "rec.m4a") }
    private val cyan = Color.parseColor("#00E5FF")

    private val chatModel = "openai/gpt-oss-120b"
    private val history = ArrayList<Pair<String, String>>()

    private val systemPrompt =
        "You are Jarvis, a smart, polite and witty voice assistant like the one in Iron Man. " +
        "The user speaks to you, so keep answers short and clear: 1 to 3 sentences unless asked for more. " +
        "Do not use markdown, bullet points or emojis. Reply in the same language the user speaks."

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("jarvis", MODE_PRIVATE)

    private fun getKeys(): List<String> {
        val saved = prefs().getString("keys", null) ?: prefs().getString("key", "") ?: ""
        return saved.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#020B14")
        window.navigationBarColor = Color.parseColor("#020B14")

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#020B14"))
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(20), dp(40), dp(20), dp(20))

        val title = TextView(this)
        title.text = "J.A.R.V.I.S"
        title.setTextColor(cyan)
        title.textSize = 26f
        title.typeface = Typeface.MONOSPACE
        title.letterSpacing = 0.3f
        title.gravity = Gravity.CENTER
        root.addView(title)

        status = TextView(this)
        status.text = "TAP THE CORE TO SPEAK"
        status.setTextColor(Color.parseColor("#4DB6C4"))
        status.textSize = 12f
        status.typeface = Typeface.MONOSPACE
        status.letterSpacing = 0.15f
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(8), 0, 0)
        root.addView(status)

        orb = JarvisOrb(this)
        root.addView(orb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val scroll = ScrollView(this)
        result = TextView(this)
        result.setTextColor(Color.WHITE)
        result.textSize = 16f
        result.typeface = Typeface.MONOSPACE
        result.gravity = Gravity.CENTER
        scroll.addView(result)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))

        val keyBtn = TextView(this)
        keyBtn.text = "[ SET API KEYS ]"
        keyBtn.setTextColor(cyan)
        keyBtn.textSize = 12f
        keyBtn.typeface = Typeface.MONOSPACE
        keyBtn.gravity = Gravity.CENTER
        keyBtn.setPadding(dp(10), dp(14), dp(10), dp(14))
        keyBtn.setOnClickListener { askKey() }
        root.addView(keyBtn)

        orb.setOnClickListener { toggle() }
        setContentView(root)
    }

    private fun askKey() {
        val input = EditText(this)
        input.hint = "gsk_...\ngsk_..."
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.setMinLines(4)
        input.gravity = Gravity.TOP
        input.setText(getKeys().joinToString("\n"))
        AlertDialog.Builder(this)
            .setTitle("Groq API keys")
            .setMessage("One key per line. Use keys from different Groq accounts. Get them at console.groq.com")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs().edit()
                    .putString("keys", input.text.toString())
                    .putInt("idx", 0)
                    .apply()
                Toast.makeText(this, "${getKeys().size} key(s) saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggle() {
        if (busy) return
        if (getKeys().isEmpty()) {
            askKey()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!recording) startRecording() else stopRecording()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            status.text = "MIC READY. TAP THE CORE"
        } else {
            status.text = "MIC PERMISSION DENIED"
        }
    }

    // Updates the screen from any thread.
    private fun setUi(orbState: Int, statusText: String, resultText: String?, free: Boolean) {
        runOnUiThread {
            if (free) {
                busy = false
            }
            orb.state = orbState
            status.text = statusText
            if (resultText != null) {
                result.text = resultText
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        try {
            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(audioFile.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recording = true
            orb.state = 1
            status.text = "LISTENING... TAP AGAIN TO SEND"
            result.text = ""
        } catch (e: Exception) {
            status.text = "MIC ERROR"
            result.text = e.message ?: "unknown error"
        }
    }

    private fun stopRecording() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
        }
        recorder?.release()
        recorder = null
        recording = false
        busy = true
        orb.state = 2
        status.text = "PROCESSING..."
        val keys = getKeys()
        Thread {
            try {
                val heard = withFailover(keys) { transcribe(it) }
                if (heard.isEmpty()) {
                    setUi(0, "TAP THE CORE TO SPEAK", "(nothing heard)", true)
                } else {
                    setUi(2, "THINKING...", "YOU: $heard", false)
                    val answer = withFailover(keys) { askGroq(it, heard) }
                    history.add(Pair("user", heard))
                    history.add(Pair("assistant", answer))
                    while (history.size > 8) {
                        history.removeAt(0)
                    }
                    val used = prefs().getInt("idx", 0) + 1
                    setUi(0, "JARVIS  (KEY $used OF ${keys.size})", "YOU: $heard\n\nJARVIS: $answer", true)
                }
            } catch (e: Exception) {
                setUi(0, "ERROR", e.message ?: "unknown error", true)
            }
        }.start()
    }

    // Runs "block" with each key in turn, starting from the last one that worked.
    private fun <T> withFailover(keys: List<String>, block: (String) -> T): T {
        val start = prefs().getInt("idx", 0) % keys.size
        var lastError = ""
        for (i in keys.indices) {
            val idx = (start + i) % keys.size
            try {
                val value = block(keys[idx])
                prefs().edit().putInt("idx", idx).apply()
                return value
            } catch (e: KeyException) {
                lastError = "Key ${idx + 1}: ${e.message}"
            }
        }
        throw Exception("All keys failed.\n$lastError")
    }

    private fun askGroq(key: String, question: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (item in history) {
            messages.put(JSONObject().put("role", item.first).put("content", item.second))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))

        val payload = JSONObject()
        payload.put("model", chatModel)
        payload.put("messages", messages)
        payload.put("temperature", 0.6)
        payload.put("max_tokens", 1500)
        payload.put("reasoning_effort", "low")

        val conn = URL("https://api.groq.com/openai/v1/chat/completions")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "application/json")
        val os = conn.outputStream
        os.write(payload.toString().toByteArray())
        os.close()

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream.bufferedReader().readText()
        if (code == 429 || code == 401 || code == 403) throw KeyException("Error $code")
        if (code !in 200..299) throw Exception("Error $code: $body")
        val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
        return choice.getJSONObject("message").getString("content").trim()
    }

    private fun transcribe(key: String): String {
        val boundary = "----jarvis" + System.currentTimeMillis()
        val conn = URL("https://api.groq.com/openai/v1/audio/transcriptions")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        val out = DataOutputStream(conn.outputStream)
        fun field(name: String, value: String) {
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        field("model", "whisper-large-v3-turbo")
        field("response_format", "json")
        out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"rec.m4a\"\r\nContent-Type: audio/m4a\r\n\r\n")
        out.write(audioFile.readBytes())
        out.writeBytes("\r\n--$boundary--\r\n")
        out.flush()
        out.close()

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream.bufferedReader().readText()
        if (code == 429 || code == 401 || code == 403) throw KeyException("Error $code")
        if (code !in 200..299) throw Exception("Error $code: $body")
        return JSONObject(body).getString("text").trim()
    }
}
