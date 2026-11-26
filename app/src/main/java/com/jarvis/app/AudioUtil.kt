package com.jarvis.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

object AudioUtil {

    private fun le16(b: ByteArray, i: Int): Int {
        return (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(b: ByteArray, i: Int): Int {
        return (b[i].toInt() and 0xFF) or
            ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or
            ((b[i + 3].toInt() and 0xFF) shl 24)
    }

    // Reads the WAV header by hand and plays the raw sound data.
    fun playWav(wav: ByteArray, isStopped: () -> Boolean) {
        if (wav.size < 44) throw Exception("audio too short")
        var channels = 1
        var rate = 48000
        var bits = 16
        var dataStart = 44
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = le32(wav, pos + 4)
            if (id == "fmt " && pos + 24 <= wav.size) {
                channels = le16(wav, pos + 10)
                rate = le32(wav, pos + 12)
                bits = le16(wav, pos + 22)
            }
            if (id == "data") {
                dataStart = pos + 8
                break
            }
            if (size < 0) break
            pos += 8 + size
        }
        if (bits != 16) throw Exception("unsupported audio ($bits-bit)")
        if (channels < 1 || channels > 2) channels = 1
        if (rate < 8000 || rate > 96000) rate = 48000

        val frameSize = 2 * channels
        val end = dataStart + ((wav.size - dataStart) / frameSize) * frameSize
        val totalFrames = (end - dataStart) / frameSize
        if (totalFrames <= 0) throw Exception("no audio data")

        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()
            var off = dataStart
            while (off < end && !isStopped()) {
                val n = minOf(8192, end - off)
                val w = track.write(wav, off, n)
                if (w <= 0) break
                off += w
            }
            // wait until the sound has finished playing
            val durationMs = totalFrames * 1000L / rate
            val deadline = System.currentTimeMillis() + durationMs + 3000
            while (!isStopped() && track.playbackHeadPosition < totalFrames &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
        } finally {
            try {
                track.stop()
            } catch (e: Exception) {
            }
            track.release()
        }
    }

    // The voice model accepts about 200 characters per request, so cut the text into pieces.
    fun splitForTts(raw: String): List<String> {
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
}
