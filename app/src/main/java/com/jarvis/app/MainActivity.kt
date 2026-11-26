package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class KeyException(msg: String) : Exception(msg)

class MainActivity : Activity() {

    private lateinit var orb: JarvisOrb
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var voiceBtn: TextView
    private var recorder: MediaRecorder? = null
    private var recording = false
    private var busy = false

    @Volatile private var speaking = false
    @Volatile private var stopFlag = false
    @Volatile private var currentLatch: CountDownLatch? = null
    @Volatile private var ttsError: String? = null
    private val END = File("END")

    private val audioFile by lazy { File(cacheDir, "rec.m4a") }
    private val cyan = Color.parseColor("#00E5FF")

    private val chatModel = "openai/gpt-oss-120b"
    private val ttsModel = "canopylabs/orpheus-v1-english"
    private val ttsVoice = "autumn"
    private val history = ArrayList<Pair<String, String>>()

    private val systemPrompt =
        "You are Jarvis, a smart, polite and witty voice assistant like the one in Iron Man. " +
        "The user speaks to you, so keep answers short and clear: 1 to 3 sentences unless asked for more. " +
        "Do not use markdown, bullet points, brackets or emojis. Reply in the same language the user speaks."

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("jarvis", MODE_PRIVATE)
    private fun voiceOn() = prefs().getBoolean("voice", true)

    private fun getKeys(): List<String> {
        val saved = prefs().getString("keys", null) ?: prefs().getString("key", "") ?: ""
        return saved.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun makeButton(label: String): TextView {
        val b = TextView(this)
        b.text = label
        b.setTextColor(cyan)
        b.textSize = 12f
        b.typeface = Typeface.MONOSPACE
        b.gravity = Gravity.CENTER
        b.setPadding(dp(10), dp(14), dp(10), dp(14))
        return b
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

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER

        voiceBtn = makeButton(if (voiceOn()) "[ VOICE: ON ]" else "[ VOICE: OFF ]")
        voiceBtn.setOnClickListener {
            val now = !voiceOn()
            prefs().edit().putBoolean("voice", now).apply()
            voiceBtn.text = if (now) "[ VOICE: ON ]" else "[ VOICE: OFF ]"
            if (!now) {
                stopSpeaking()
            }
        }
        bottom.addView(voiceBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val keyBtn = makeButton("[ SET API KEYS ]")
        keyBtn.setOnClickListener { askKey() }
        bottom.addView(keyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        orb.setOnClickListener { toggle() }
        setContentView(root)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpeaking()
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
        if (speaking) {
            stopSpeaking()
            return
        }
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
                    val label = "JARVIS  (KEY $used OF ${keys.size})"
                    val shown = "YOU: $heard\n\nJARVIS: $answer"
                    if (voiceOn()) {
                        speaking = true
                        stopFlag = false
                        ttsError = null
                        setUi(0, "SPEAKING... TAP CORE TO STOP", shown, true)
                        speak(answer, keys)
                        val err = ttsError
                        runOnUiThread {
                            status.text = label
                            if (err != null) {
                                result.text = shown + "\n\n(voice error: " + err + ")"
                            }
                        }
                    } else {
                        setUi(0, label, shown, true)
                    }
                }
            } catch (e: Exception) {
                speaking = false
                setUi(0, "ERROR", e.message ?: "unknown error", true)
            }
        }.start()
    }

    // ---------- Voice ----------

    private fun stopSpeaking() {
        stopFlag = true
        currentLatch?.countDown()
    }

    // Downloads the voice in pieces and plays them one after another.
    private fun speak(text: String, keys: List<String>) {
        try {
            val chunks = splitForTts(text)
            if (chunks.isEmpty()) return
            val queue = LinkedBlockingQueue<File>()
            val stamp = System.currentTimeMillis()

            Thread {
                try {
                    for ((i, chunk) in chunks.withIndex()) {
                        if (stopFlag) break
                        val bytes = withFailover(keys) { tts(it, chunk) }
                        if (stopFlag) break
                        val f = File(cacheDir, "tts_${stamp}_$i.wav")
                        f.writeBytes(bytes)
                        queue.put(f)
                    }
                } catch (e: Exception) {
                    ttsError = e.message ?: "voice error"
                } finally {
                    queue.put(END)
                }
            }.start()

            while (!stopFlag) {
                val f = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (f === END) break
                play(f)
                f.delete()
            }
        } catch (e: Exception) {
            ttsError = e.message ?: "voice error"
        } finally {
            speaking = false
        }
    }

    private fun play(file: File) {
        val latch = CountDownLatch(1)
        currentLatch = latch
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ ->
                latch.countDown()
                true
            }
            mp.prepare()
            mp.start()
            latch.await()
        } catch (e: Exception) {
        } finally {
            try {
                mp.release()
            } catch (e: Exception) {
            }
            currentLatch = null
        }
    }

    // The voice model accepts about 200 characters per request, so cut the text into pieces.
    private fun splitForTts(raw: String): List<String> {
        val clean = raw.replace(Regex("[\\[\\]*#_`]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = clean.split(Regex("(?<=[.!?])\\s+"))
        val chunks = ArrayList<String>()
        var cur = ""
        for (s0 in sentences) {
            var s = s0
            while (s.length > 190) {
                var cut = s.lastIndexOf(' ', 190)
                if (cut <= 0) cut = 190
                if (cur.isNotEmpty()) {
                    chunks.add(cur)
                    cur = ""
                }
                chunks.add(s.substring(0, cut).trim())
                s = s.substring(cut).trim()
            }
            if (cur.isEmpty()) {
                cur = s
            } else if (cur.length + 1 + s.length <= 190) {
                cur = cur + " " + s
            } else {
                chunks.add(cur)
                cur = s
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        return chunks.take(5)
    }

    private fun tts(key: String, text: String): ByteArray {
        val payload = JSONObject()
        payload.put("model", ttsModel)
        payload.put("input", text)
        payload.put("voice", ttsVoice)
        payload.put("response_format", "wav")

        val conn = URL("https://api.groq.com/openai/v1/audio/speech")
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
        if (code in 200..299) {
            return conn.inputStream.readBytes()
        }
        val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
        val short = body.take(200)
        if (code == 429 || code == 401 || code == 403 || body.contains("terms", true)) {
            throw KeyException("Error $code $short")
        }
        throw Exception("Error $code: $short")
    }

    // ---------- Groq: keys, chat, speech to text ----------

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
