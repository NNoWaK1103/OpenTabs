package org.opentabs.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Authored timing only. Empty spans are rests; string 0 is the lowest entry in [PracticeScore.tuning]. */
@Serializable
data class PracticeNote(
    val startTick: Long,
    val durationTicks: Long,
    val string: Int,
    val fret: Int,
    val midiPitch: Int,
    val measure: Int,
    val velocity: Int = 88,
)

@Serializable
data class PracticeTempo(val tick: Long, val bpm: Double)

/** A bounded, versioned practice approximation, not a full Guitar Pro performance engine. */
@Serializable
data class PracticeScore(
    val title: String,
    val trackName: String,
    val tuning: List<Int>,
    val capo: Int,
    val notes: List<PracticeNote>,
    val tempos: List<PracticeTempo>,
    val durationTicks: Long,
    val ticksPerQuarter: Int = TICKS_PER_QUARTER,
    val warnings: List<String> = emptyList(),
    val version: Int = 1,
) {
    companion object {
        const val TICKS_PER_QUARTER = 960
        const val MAX_NOTES = 25_000
        const val MAX_DURATION_TICKS = 960L * 4 * 32 * 500
        const val MAX_DURATION_MILLIS = 2 * 60 * 60 * 1000L
    }

    init {
        require(version == 1) { "This playback score needs a newer version of OpenTabs." }
        require(title.length <= 200 && trackName.length <= 160) { "Playback title is too long." }
        require(ticksPerQuarter == TICKS_PER_QUARTER) { "Unsupported playback timing resolution." }
        require(tuning.size == 6 && tuning.all { it in 0..127 } && capo in 0..24) { "Invalid playback tuning or capo." }
        require(durationTicks in 1..MAX_DURATION_TICKS) { "Playback duration is outside supported limits." }
        require(notes.isNotEmpty() && notes.size <= MAX_NOTES) { "Playback requires 1–25,000 authored notes." }
        require(warnings.size <= 32 && warnings.all { it.length <= 400 }) { "Invalid playback notices." }
        require(tempos.isNotEmpty() && tempos.size <= 2_000 && tempos.first().tick == 0L) { "Playback needs an initial tempo." }
        require(tempos.all { it.bpm.isFinite() && it.bpm in 20.0..400.0 && it.tick in 0 until durationTicks } &&
            tempos.zipWithNext().all { (a, b) -> a.tick < b.tick }) { "Invalid playback tempo map." }
        notes.forEach { note ->
            require(note.startTick in 0 until durationTicks && note.durationTicks in 1..(durationTicks - note.startTick)) {
                "Invalid playback note timing."
            }
            require(note.string in 0..5 && note.fret in 0..36 && note.midiPitch in 0..127 &&
                note.midiPitch == tuning[note.string] + capo + note.fret && note.measure in 1..500 && note.velocity in 1..127) {
                "Invalid authored playback position or pitch."
            }
        }
        notes.groupBy { it.string }.values.forEach { stringNotes ->
            require(stringNotes.sortedBy { it.startTick }.zipWithNext().all { (a, b) -> a.startTick + a.durationTicks <= b.startTick }) {
                "Overlapping notes on the same string are not supported for timed playback."
            }
        }
        require(durationMillis() <= MAX_DURATION_MILLIS) { "Timed practice is limited to two hours per score." }
    }

    fun durationMillis(speed: Double = 1.0): Long {
        require(speed.isFinite() && speed in 0.25..2.0) { "Playback speed must be between 25% and 200%." }
        return tempos.mapIndexed { index, tempo ->
            val end = tempos.getOrNull(index + 1)?.tick ?: durationTicks
            (end - tempo.tick).toDouble() * midiTempo(tempo.bpm, speed) / ticksPerQuarter / 1_000.0
        }.sum().roundToLong()
    }
}

internal fun midiTempo(bpm: Double, speed: Double) = (60_000_000.0 / (bpm * speed)).roundToInt()

/** Standard MIDI format 0; Android MediaPlayer can play the resulting local .mid without network access. */
object PracticeMidi {
    fun encode(score: PracticeScore, speed: Double = 1.0): ByteArray {
        require(speed.isFinite() && speed in 0.25..2.0) { "Playback speed must be between 25% and 200%." }
        score.copy() // Revalidate in case a caller supplied mutable lists and later changed them.
        data class Event(val tick: Long, val priority: Int, val bytes: ByteArray)
        val events = mutableListOf<Event>()
        // One channel per string prevents equal pitches on different strings from cutting each other off.
        repeat(6) { string -> events += Event(0, 1, byteArrayOf((0xc0 + string).toByte(), 24)) }
        score.tempos.forEach {
            val micros = midiTempo(it.bpm, speed)
            events += Event(it.tick, 0, byteArrayOf(0xff.toByte(), 0x51, 3,
                (micros shr 16).toByte(), (micros shr 8).toByte(), micros.toByte()))
        }
        score.notes.forEach {
            events += Event(it.startTick, 3, byteArrayOf((0x90 + it.string).toByte(), it.midiPitch.toByte(), it.velocity.toByte()))
            events += Event(it.startTick + it.durationTicks, 2, byteArrayOf((0x80 + it.string).toByte(), it.midiPitch.toByte(), 0))
        }
        repeat(6) { string -> events += Event(score.durationTicks, 4, byteArrayOf((0xb0 + string).toByte(), 123, 0)) }
        events += Event(score.durationTicks, 5, byteArrayOf(0xff.toByte(), 0x2f, 0))
        val track = ByteArrayOutputStream()
        var previous = 0L
        events.sortedWith(compareBy<Event> { it.tick }.thenBy { it.priority }).forEach {
            variableLength(track, it.tick - previous)
            track.write(it.bytes)
            previous = it.tick
        }
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeBytes("MThd"); out.writeInt(6); out.writeShort(0); out.writeShort(1); out.writeShort(score.ticksPerQuarter)
                out.writeBytes("MTrk"); out.writeInt(track.size()); track.writeTo(out)
            }
        }.toByteArray()
    }

    private fun variableLength(out: ByteArrayOutputStream, value: Long) {
        require(value in 0..0x0fffffff) { "MIDI event gap is too long." }
        var remaining = value
        val bytes = mutableListOf((remaining and 0x7f).toInt())
        while (remaining > 127) { remaining = remaining shr 7; bytes += ((remaining and 0x7f).toInt() or 0x80) }
        bytes.asReversed().forEach(out::write)
    }
}
