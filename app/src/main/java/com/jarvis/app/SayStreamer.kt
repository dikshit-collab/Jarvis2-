package com.jarvis.app

// Reads the model's JSON reply while it is still arriving, and hands out the
// "say" text one sentence at a time so the voice can start early.
// The prompt makes "say" the FIRST field, so speech begins almost immediately.
class SayStreamer(private val emit: (String) -> Unit) {

    private val all = StringBuilder()
    private val pending = StringBuilder()
    private val marker = Regex("\"say\"\\s*:\\s*\"")
    private var scan = 0
    private var inSay = false
    private var esc = false
    private var done = false

    // True if a "say" field was found in the stream.
    var sayFound = false
        private set

    fun raw(): String = all.toString()

    fun feed(delta: String) {
        all.append(delta)
        if (done) return
        if (!inSay) {
            val m = marker.find(all) ?: return
            inSay = true
            sayFound = true
            scan = m.range.last + 1
        }
        while (scan < all.length && !done) {
            val c = all[scan]
            scan++
            if (esc) {
                if (c == 'u') {
                    if (scan + 4 > all.length) {
                        scan--      // wait for the rest of the \uXXXX
                        break
                    }
                    val code = all.substring(scan, scan + 4).toIntOrNull(16)
                    scan += 4
                    esc = false
                    if (code != null) pending.append(code.toChar())
                } else {
                    esc = false
                    pending.append(if (c == 'n' || c == 't' || c == 'r') ' ' else c)
                }
            } else if (c == '\\') {
                esc = true
            } else if (c == '"') {
                done = true
                flush()
            } else {
                pending.append(c)
                if (c == ' ') maybeFlush()
            }
        }
    }

    // Call when the stream ends, in case the closing quote never came.
    fun finish() {
        if (inSay && !done) {
            done = true
            flush()
        }
    }

    private fun maybeFlush() {
        val n = pending.length
        if (n < 2) return
        val prev = pending[n - 2]
        val sentenceEnd = prev == '.' || prev == '!' || prev == '?'
        if ((sentenceEnd && n >= 14) || n > 170) flush()
    }

    private fun flush() {
        val t = pending.toString().trim()
        pending.setLength(0)
        if (t.isNotEmpty()) emit(t)
    }
}
