package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
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
                    val raw = withFailover(keys) { askGroq(it, heard) }
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
                    val sayText = say

                    val used = prefs().getInt("idx", 0) + 1
                    val label = "JARVIS  (KEY $used OF ${keys.size})"
                    val shown = "YOU: $heard\n\nJARVIS: $sayText"
                    if (voiceOn()) {
                        speaking = true
                        stopFlag = false
                        ttsError = null
                        setUi(0, "LOADING VOICE...", shown, true)
                        speak(sayText, keys)
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
                    runAction(action, reply)
                }
            } catch (e: Exception) {
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
                doAction(action, o)
            } catch (e: Exception) {
                "Action failed: " + (e.message ?: e.toString())
            }
            if (note != null) {
                result.text = result.text.toString() + "\n\n(" + note + ")"
            }
        }
    }

    private fun startSafe(i: Intent): Boolean {
        return try {
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun doAction(action: String, o: JSONObject): String? {
        when (action) {
            "open_app" -> {
                val name = o.optString("app").trim()
                val intent = findApp(name) ?: return "I could not find an app called $name"
                startActivity(intent)
            }
            "youtube_search" -> {
                val q = o.optString("query").trim()
                if (q.isEmpty()) return "No search words heard"
                val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                val yt = Intent(Intent.ACTION_VIEW, uri)
                yt.setPackage("com.google.android.youtube")
                if (!startSafe(yt) && !startSafe(Intent(Intent.ACTION_VIEW, uri))) {
                    return "Could not open YouTube"
                }
            }
            "open_website" -> {
                var u = o.optString("url").trim()
                if (u.isEmpty()) return "No website given"
                if (!u.startsWith("http")) {
                    if (!u.contains(".")) u += ".com"
                    u = "https://" + u
                }
                if (!startSafe(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) {
                    return "Could not open the website"
                }
            }
            "call" -> return doCall(o.optString("contact"))
            "set_alarm" -> {
                val h = o.optInt("hour", -1)
                val m = o.optInt("minute", 0)
                if (h !in 0..23 || m !in 0..59) return "I did not understand the alarm time"
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                i.putExtra(AlarmClock.EXTRA_HOUR, h)
                i.putExtra(AlarmClock.EXTRA_MINUTES, m)
                i.putExtra(AlarmClock.EXTRA_MESSAGE, o.optString("label", "Jarvis"))
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!startSafe(i)) return "Could not set the alarm"
            }
            "set_timer" -> {
                val s = o.optInt("seconds", 0)
                if (s !in 1..86400) return "I did not understand the timer length"
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                i.putExtra(AlarmClock.EXTRA_LENGTH, s)
                i.putExtra(AlarmClock.EXTRA_MESSAGE, o.optString("label", "Jarvis"))
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!startSafe(i)) return "Could not set the timer"
            }
            "maps_search" -> {
                val q = o.optString("query").trim()
                if (q.isEmpty()) return "No place heard"
                val uri = Uri.parse("geo:0,0?q=" + Uri.encode(q))
                val maps = Intent(Intent.ACTION_VIEW, uri)
                maps.setPackage("com.google.android.apps.maps")
                if (!startSafe(maps) && !startSafe(Intent(Intent.ACTION_VIEW, uri))) {
                    return "Could not open Maps"
                }
            }
            "directions" -> {
                val d = o.optString("destination").trim()
                if (d.isEmpty()) return "No destination heard"
                val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(d)))
                nav.setPackage("com.google.android.apps.maps")
                val web = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(d))
                )
                if (!startSafe(nav) && !startSafe(web)) return "Could not open Maps"
            }
            else -> {
            }
        }
        return null
    }

    // Finds an installed app whose name matches what you said.
    private fun findApp(name: String): Intent? {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return null
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN)
        main.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        var best: String? = null
        var bestScore = 0
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString().lowercase()
            val score = when {
                label == q -> 3
                label.startsWith(q) -> 2
                label.contains(q) || (label.length >= 3 && q.contains(label)) -> 1
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = ri.activityInfo.packageName
            }
        }
        val pkg = best ?: return null
        return pm.getLaunchIntentForPackage(pkg)
    }

    private fun doCall(target: String): String? {
        val t = target.trim()
        if (t.isEmpty()) return "Who should I call?"
        val hasLetters = t.any { it.isLetter() }
        val haveCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val haveContacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!haveCall || (hasLetters && !haveContacts)) {
            requestPermissions(
                arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS),
                2
            )
            return "Allow the permissions, then ask me again"
        }
        var number = t
        if (hasLetters) {
            number = findContactNumber(t) ?: return "No contact found for $t"
        }
        val clean = number.filter { it.isDigit() || it == '+' }
        if (clean.isEmpty()) return "That number does not look right"
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + clean)))
        return null
    }

    private fun findContactNumber(name: String): String? {
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%" + name + "%"),
            null
        )
        if (cursor == null) return null
        try {
            if (cursor.moveToFirst()) return cursor.getString(0)
        } finally {
            cursor.close()
     
