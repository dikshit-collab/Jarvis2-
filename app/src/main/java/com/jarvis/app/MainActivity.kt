package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    @Volatile private var ttsError: String? = null
    private val END = ByteArray(0)
    private val END_TEXT = "\u0000"

    private val audioFile by lazy { File(cacheDir, "rec.m4a") }
    private val cyan = Color.parseColor("#00E5FF")

    private val chatModel = "openai/gpt-oss-120b"
    private val ttsModel = "canopylabs/orpheus-v1-english"
    private val ttsVoice = "autumn"
    private val history = ArrayList<Pair<String, String>>()

    private val basePrompt = """
You are Jarvis, a smart, polite and witty voice assistant like the one in Iron Man. You were made by Dikshit. If the user asks who made, created, built or developed you, your "say" must be exactly: Dikshit.
The user talks to you by voice, so keep spoken replies short and clear: 1 to 3 sentences unless asked for more. No markdown, brackets or emojis. Reply in the same language the user speaks.

You MUST reply with ONE JSON object and nothing else (no code fences). Shape:
{"action":"<name>","say":"<what you say aloud>", ...extra fields}

Actions:
answer - for questions and normal chat. Put the full answer in "say".
open_app - open an app. Extra field: "app" (the app name, for example "YouTube").
youtube_search - search on YouTube. Extra field: "query".
open_website - open a website. Extra field: "url".
call - call someone. Extra field: "contact" (a name or a phone number).
set_alarm - Extra fields: "hour" (0 to 23), "minute" (0 to 59), "label".
set_timer - Extra fields: "seconds" (a number), "label".
maps_search - show a place on the map. Extra field: "query".
directions - navigate to a place. Extra field: "destination".

For commands, "say" is a short confirmation, like "Opening YouTube."
If the user asks for something you cannot do on the phone (like subscribing to a channel or sending messages), use action "answer" and politely say you cannot do that yet.
""".trimIndent()

    private fun buildSystemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        return basePrompt + "\nCurrent date and time on the user's phone: " + now + " (24-hour clock)."
    }

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
        volumeControlStream = AudioManager.STREAM_MUSIC
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

        orb.post { handleLaunch() }
    }

    override fun onResume() {
        super.onResume()
        JarvisState.resumedAt = System.currentTimeMillis()
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        if (newIntent != null) {
            setIntent(newIntent)
        }
        JarvisState.resumedAt = System.currentTimeMillis()
        handleLaunch()
    }

    // When opened by the assistant gesture, start listening right away.
    private fun handleLaunch() {
        val i = intent ?: return
        if (!i.getBooleanExtra("auto_listen", false)) return
        i.removeExtra("auto_listen")
        if (recording || busy || speaking) return
        if (getKeys().isEmpty()) {
            askKey()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startRecording()
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
        if (code == 1) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
                status.text = "MIC READY. TAP THE CORE"
            } else {
                status.text = "MIC PERMISSION DENIED"
            }
        } else {
            status.text = "PERMISSION SAVED. ASK AGAIN"
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
                    val voice = voiceOn()
                    val textQueue = LinkedBlockingQueue<String>()
                    var speaker: Thread? = null
                    if (voice) {
                        speaking = true
                        stopFlag = false
                        ttsError = null
                        speaker = Thread { speakStream(textQueue, keys) }
                        speaker.start()
                    }

                    // Sentences are shown and spoken while the model is still writing.
                    val head = "YOU: $heard\n\nJARVIS: "
                    val shown = StringBuilder()
                    val streamer = SayStreamer { piece ->
                        if (shown.isNotEmpty()) shown.append(' ')
                        shown.append(piece)
                        val text = head + shown.toString()
                        runOnUiThread { result.text = text }
                        if (voice) textQueue.put(piece)
                    }
                    val raw = withFailover(keys) { askGroqStream(it, heard, streamer) }
                    streamer.finish()

                    history.add(Pair("user", heard))
                    history.add(Pair("assistant", raw))
                    while (history.size > 8) {
                        history.removeAt(0)
                    }

                    val reply = parseReply(raw)
                    val action = reply.optString("action", "answer")
                    var say = reply.optString("say", "").trim()
                    if (say.isEmpty()) {
                        say = if (action == "answer") raw else "Done."
                    }
                    // Model ignored the JSON format: nothing was streamed, so speak it now.
                    if (!streamer.sayFound && voice) textQueue.put(say)

                    val used = prefs().getInt("idx", 0) + 1
                    val label = "JARVIS  (KEY $used OF ${keys.size})"
                    val full = head + say
                    val statusNow = if (voice && speaking) "SPEAKING... TAP CORE TO STOP" else label
                    setUi(0, statusNow, full, true)

                    // Run the command right away, while the voice is still talking.
                    runAction(action, reply)

                    if (voice) {
                        textQueue.put(END_TEXT)
                        speaker?.join()
                        val err = ttsError
                        runOnUiThread {
                            status.text = label
                            if (err != null) {
                                result.text = full + "\n\n(voice error: " + err + ")"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                stopFlag = true
                speaking = false
                setUi(0, "ERROR", e.message ?: "unknown error", true)
            }
        }.start()
    }

    // ---------- Commands ----------

    private fun parseReply(raw: String): JSONObject {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s >= 0 && e > s) {
            try {
                return JSONObject(raw.substring(s, e + 1))
            } catch (x: Exception) {
            }
        }
        return JSONObject().put("action", "answer").put("say", raw)
    }

    private fun runAction(action: String, o: JSONObject) {
        if (action == "answer") return
        runOnUiThread {
            val note = try {
                Commands.run(this, action, o)
            } catch (e: Exception) {
                "Action failed: " + (e.message ?: e.toString())
            }
            if (note != null) {
                result.text = result.text.toString() + "\n\n(" + note + ")"
            }
        }
    }

    // ---------- Voice ----------

    private fun stopSpeaking() {
        stopFlag = true
    }

    // Takes sentences from textQueue as they arrive, downloads each one's voice,
    // and plays them in order. A sentence is fetched while the previous one plays.
    private fun speakStream(textQueue: LinkedBlockingQueue<String>, keys: List<String>) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                ttsError = "media volume is 0. Press the volume up button"
            }

            val audioQueue = LinkedBlockingQueue<ByteArray>()

            Thread {
                try {
                    while (!stopFlag) {
                        val piece = textQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        if (piece == END_TEXT) break
                        for (chunk in AudioUtil.splitForTts(piece)) {
                            if (stopFlag) break
                            val bytes = withFailover(keys) { tts(it, chunk) }
                            if (stopFlag) break
                            if (bytes.size < 2000) {
                                throw Exception("voice audio too small (" + bytes.size + " bytes)")
                            }
                            audioQueue.put(bytes)
                        }
                    }
                } catch (e: Exception) {
                    ttsError = e.message ?: "voice error"
                } finally {
                    audioQueue.put(END)
                }
            }.start()

            while (!stopFlag) {
                val wav = audioQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (wav === END) break
                runOnUiThread { status.text = "SPEAKING... TAP CORE TO STOP" }
                try {
                    AudioUtil.playWav(wav) { stopFlag }
                } catch (e: Exception) {
                    ttsError = "player: " + (e.message ?: e.toString())
                    break
                }
            }
        } catch (e: Exception) {
            ttsError = e.message ?: "voice error"
        } finally {
            speaking = false
        }
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

    // Same request as before, but with stream=true. Each piece of the reply is
    // pushed into the streamer as soon as it arrives.
    private fun askGroqStream(key: String, question: String, streamer: SayStreamer): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", buildSystemPrompt()))
        for (item in history) {
            messages.put(JSONObject().put("role", item.first).put("content", item.second))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))

        val payload = JSONObject()
        payload.put("model", chatModel)
        payload.put("messages", messages)
        payload.put("temperature", 0.4)
        payload.put("max_tokens", 1500)
        payload.put("reasoning_effort", "low")
        payload.put("stream", true)

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
        if (code == 429 || code == 401 || code == 403) throw KeyException("Error $code")
        if (code !in 200..299) {
            val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw Exception("Error $code: $body")
        }
        conn.inputStream.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val delta = try {
                    JSONObject(data).getJSONArray("choices").getJSONObject(0).optJSONObject("delta")
                } catch (e: Exception) {
                    null
                } ?: continue
                if (delta.isNull("content")) continue
                streamer.feed(delta.getString("content"))
            }
        }
        return streamer.raw().trim()
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
