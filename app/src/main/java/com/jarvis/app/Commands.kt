package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import org.json.JSONObject

object Commands {

    // Runs a command. Returns a short note if something went wrong, or null if all is fine.
    fun run(a: Activity, action: String, o: JSONObject): String? {
        when (action) {
            "open_app" -> {
                val name = o.optString("app").trim()
                val intent = findApp(a, name) ?: return "I could not find an app called $name"
                a.startActivity(intent)
            }
            "youtube_search" -> {
                val q = o.optString("query").trim()
                if (q.isEmpty()) return "No search words heard"
                val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                val yt = Intent(Intent.ACTION_VIEW, uri)
                yt.setPackage("com.google.android.youtube")
                if (!startSafe(a, yt) && !startSafe(a, Intent(Intent.ACTION_VIEW, uri))) {
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
                if (!startSafe(a, Intent(Intent.ACTION_VIEW, Uri.parse(u)))) {
                    return "Could not open the website"
                }
            }
            "call" -> return doCall(a, o.optString("contact"))
            "set_alarm" -> {
                val h = o.optInt("hour", -1)
                val m = o.optInt("minute", 0)
                if (h !in 0..23 || m !in 0..59) return "I did not understand the alarm time"
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                i.putExtra(AlarmClock.EXTRA_HOUR, h)
                i.putExtra(AlarmClock.EXTRA_MINUTES, m)
                i.putExtra(AlarmClock.EXTRA_MESSAGE, o.optString("label", "Jarvis"))
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!startSafe(a, i)) return "Could not set the alarm"
            }
            "set_timer" -> {
                val s = o.optInt("seconds", 0)
                if (s !in 1..86400) return "I did not understand the timer length"
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                i.putExtra(AlarmClock.EXTRA_LENGTH, s)
                i.putExtra(AlarmClock.EXTRA_MESSAGE, o.optString("label", "Jarvis"))
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!startSafe(a, i)) return "Could not set the timer"
            }
            "maps_search" -> {
                val q = o.optString("query").trim()
                if (q.isEmpty()) return "No place heard"
                val uri = Uri.parse("geo:0,0?q=" + Uri.encode(q))
                val maps = Intent(Intent.ACTION_VIEW, uri)
                maps.setPackage("com.google.android.apps.maps")
                if (!startSafe(a, maps) && !startSafe(a, Intent(Intent.ACTION_VIEW, uri))) {
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
                if (!startSafe(a, nav) && !startSafe(a, web)) return "Could not open Maps"
            }
            else -> {
            }
        }
        return null
    }

    private fun startSafe(a: Activity, i: Intent): Boolean {
        return try {
            a.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    // Finds an installed app whose name matches what you said.
    private fun findApp(a: Activity, name: String): Intent? {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return null
        val pm = a.packageManager
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

    private fun doCall(a: Activity, target: String): String? {
        val t = target.trim()
        if (t.isEmpty()) return "Who should I call?"
        val hasLetters = t.any { it.isLetter() }
        val haveCall = a.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val haveContacts = a.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!haveCall || (hasLetters && !haveContacts)) {
            a.requestPermissions(
                arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS),
                2
            )
            return "Allow the permissions, then ask me again"
        }
        var number = t
        if (hasLetters) {
            number = findContactNumber(a, t) ?: return "No contact found for $t"
        }
        val clean = number.filter { it.isDigit() || it == '+' }
        if (clean.isEmpty()) return "That number does not look right"
        a.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + clean)))
        return null
    }

    private fun findContactNumber(a: Activity, name: String): String? {
        val cursor = a.contentResolver.query(
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
        }
        return null
    }
}
