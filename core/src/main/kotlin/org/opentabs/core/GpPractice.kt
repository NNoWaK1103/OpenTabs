package org.opentabs.core

import kotlin.math.pow
import kotlin.math.roundToLong

/** Converts GPIF written order, without guessing rhythmic values from the rendered text. */
internal object GpPractice {
    fun build(title: String, track: GuitarTrack, measures: List<Pair<String, List<String>>>,
        bars: Map<String, List<String>>, voices: Map<String, List<String>>, beats: Map<String, GpBeat>,
        notes: Map<String, GpNote>, rhythms: Map<String, GpRhythm>, tempos: List<GpTempo>): PracticeScore {
        require(track.tuning.size == 6 && track.staves == 1) { "Timed playback supports single-staff, six-string arrangements only." }
        require(track.capo in 0..24) { "Invalid or unsupported capo for timed playback." }
        require(track.playbackIssue == null) { track.playbackIssue.orEmpty() }
        require(measures.size in 1..500) { "Timed playback supports 1–500 measures." }
        val warnings = linkedSetOf(
            "Written order only: repeats, alternate endings, jumps and fine are not performed.",
            "Synthesized picked guitar: expressive techniques, dynamics and sustain are simplified; ties are re-picked.",
            "Straight written durations: swing/shuffle and fermatas are not performed; tuplets are rounded within one MIDI tick.",
        )
        val durations = measures.map { (time, _) ->
            val signature = time.split('/')
            val numerator = signature.getOrNull(0)?.toIntOrNull()
            val denominator = signature.getOrNull(1)?.toIntOrNull()
            require(signature.size == 2 && numerator != null && numerator in 1..32 && denominator in listOf(1, 2, 4, 8, 16, 32, 64)) {
                "Missing or unsupported time signature; timed playback is unavailable."
            }
            PracticeScore.TICKS_PER_QUARTER.toLong() * 4 * numerator / requireNotNull(denominator)
        }
        val starts = durations.runningFold(0L, Long::plus)
        val result = mutableListOf<PracticeNote>()
        measures.forEachIndexed { measure, (_, barIds) ->
            val barId = barIds.getOrNull(track.barOffset) ?: error("Missing measure reference for timed playback.")
            if (barId == "-1") return@forEachIndexed
            val voiceIds = bars[barId] ?: error("Missing score bar for timed playback.")
            require(voiceIds.size <= 4) { "Too many simultaneous voices for timed playback." }
            voiceIds.filter { it != "-1" }.forEach { voiceId ->
                val beatIds = voices[voiceId] ?: error("Missing score voice for timed playback.")
                require(beatIds.size <= 128) { "Too many beats in a measure for timed playback." }
                var offset = 0.0
                beatIds.forEach { beatId ->
                    val beat = beats[beatId] ?: error("Missing score beat for timed playback.")
                    if (beat.grace) {
                        warnings += "Grace notes are omitted without changing the duration of the written measure."
                    } else {
                        val rhythm = rhythms[beat.rhythm] ?: error("Missing authored rhythm; timed playback is unavailable.")
                        val ticks = rhythmTicks(rhythm)
                        // GPIF writes a lone whole rest for an entire measure, including non-4/4 meters.
                        val duration = if (beatIds.size == 1 && beat.notes.isEmpty() && rhythm.value == "Whole" &&
                            rhythm.dots == 0 && rhythm.numerator == 1 && rhythm.denominator == 1) durations[measure].toDouble() else ticks
                        val start = offset.roundToLong()
                        offset += duration
                        val end = offset.roundToLong()
                        require(end <= durations[measure] + 1 && end > start) {
                            "Authored beats exceed their time signature; timed playback is unavailable."
                        }
                        if (beat.techniques) warnings += "Strums, tremolo, ottava and other beat effects are played as simultaneous plain fretted notes."
                        val usedStrings = mutableSetOf<Int>()
                        beat.notes.forEach { noteId ->
                            val note = notes[noteId] ?: error("Missing score note for timed playback.")
                            val string = note.string ?: error("Timed playback requires authored string positions.")
                            val fret = note.fret ?: error("Timed playback requires authored fret positions.")
                            require(string in 0..5 && fret in 0..36 && usedStrings.add(string)) { "Invalid or duplicate string/fret position." }
                            val pitch = track.tuning[string] + track.capo + fret
                            require(pitch in 0..127) { "Authored pitch is outside MIDI range." }
                            if (note.muted) {
                                warnings += "Dead/muted notes are silent; their written durations are preserved."
                            } else {
                                if (note.techniques) warnings += "Bends, slides, harmonics, vibrato and legato use the written fret pitch without their effects."
                                require(result.size < PracticeScore.MAX_NOTES) { "Too many notes for timed playback." }
                                result += PracticeNote(starts[measure] + start, minOf(end, durations[measure]) - start,
                                    string, fret, pitch, measure + 1)
                            }
                        }
                    }
                }
                // A shorter voice is followed by silence, never by an invented note or expanded beat.
            }
        }
        require(tempos.size in 1..2_000) { "No supported authored tempo; timed playback is unavailable." }
        val tempoEvents = tempos.map { tempo ->
            val bar = tempo.bar
            val position = tempo.position
            val bpm = tempo.bpm
            require(bar != null && bar in measures.indices && position != null && position.isFinite() && position in 0.0..1.0 &&
                bpm != null && bpm.isFinite() && bpm in 20.0..400.0 && tempo.unit == "2") {
                "Invalid or unsupported authored tempo; timed playback is unavailable."
            }
            if (tempo.linear) warnings += "Gradual tempo changes are approximated as immediate changes at their authored positions."
            PracticeTempo(starts[bar] + (durations[bar] * position).roundToLong(), bpm)
        }.filter { it.tick < starts.last() }.sortedBy { it.tick }
        // Last authored automation wins at the same position; the saved map is strictly increasing.
        val tempoMap = tempoEvents.associateBy { it.tick }.values.toList()
        require(tempoMap.firstOrNull()?.tick == 0L) { "No authored starting tempo; timed playback is unavailable." }
        return PracticeScore(title, track.name, track.tuning, track.capo, result.sortedBy { it.startTick }, tempoMap, starts.last(),
            warnings = warnings.toList())
    }

    private fun rhythmTicks(rhythm: GpRhythm): Double {
        val quarters = when (rhythm.value) {
            "Long" -> 16.0
            "DoubleWhole" -> 8.0
            "Whole" -> 4.0
            "Half" -> 2.0
            "Quarter" -> 1.0
            "Eighth" -> 0.5
            "16th" -> 0.25
            "32nd" -> 0.125
            "64th" -> 0.0625
            "128th" -> 0.03125
            else -> error("Unsupported authored note duration: ${rhythm.value.take(24)}.")
        }
        val dots = rhythm.dots
        val numerator = rhythm.numerator
        val denominator = rhythm.denominator
        require(dots != null && dots in 0..3 && numerator != null && numerator in 1..32 && denominator != null && denominator in 1..32 &&
            !rhythm.secondaryTuplet) { "Invalid or unsupported dotted/tuplet rhythm." }
        return quarters * PracticeScore.TICKS_PER_QUARTER * (2.0 - 0.5.pow(dots)) * denominator / numerator
    }
}
