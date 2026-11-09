package org.opentabs.core

/** Original OpenTabs practice arrangements, explicitly authored alongside the bundled text. */
internal object ExampleScores {
    fun forIndex(index: Int): PracticeScore? {
        val phrases: List<List<Pair<Int, Int>>> = when (index) {
            0 -> listOf(
                (0..5).map { it to 0 } + List(3) { 5 to 0 },
                listOf(1 to 1, 1 to 2) + (2..5).flatMap { string -> (1..4).map { string to it } },
                (5 downTo 2).flatMap { string -> (4 downTo 1).map { string to it } } + listOf(1 to 4, 1 to 3),
            )
            1 -> listOf(
                listOf(0 to 0, 2 to 2, 3 to 0, 4 to 0, 5 to 0, 4 to 0, 3 to 0, 2 to 2,
                    1 to 2, 2 to 2, 3 to 0, 4 to 0, 5 to 0, 4 to 0, 3 to 0, 2 to 2),
                List(2) { listOf(1 to 0, 2 to 2, 3 to 2, 4 to 1, 5 to 0, 4 to 1, 3 to 2, 2 to 2) }.flatten(),
                listOf(0 to 0, 2 to 2, 3 to 0, 4 to 0, 5 to 0, 4 to 0, 3 to 0, 2 to 2, 1 to 2, 0 to 0),
            )
            2 -> listOf(
                listOf(2, 3, 5, 3, 2, 3, 5, 3).flatMap { listOf(0 to 0, 1 to it) },
                listOf(2, 3, 5, 3, 2, 3, 5, 3).flatMap { listOf(1 to 0, 2 to it) },
                List(4) { listOf(1 to 0, 2 to 2, 3 to 0, 2 to 2) }.flatten(),
            )
            else -> return null
        }
        val tuning = listOf(40, 45, 50, 55, 59, 64)
        var position = 0L
        val notes = phrases.flatMapIndexed { phraseIndex, phrase ->
            phrase.map { (string, fret) ->
                val duration = if (index == 0 && phraseIndex == 0) 960L else 480L
                PracticeNote(position, duration, string, fret, tuning[string] + fret, phraseIndex + 1).also { position += duration }
            }
        }
        return PracticeScore(Examples.exercises[index].first, "Original exercise", tuning, 0, notes,
            listOf(PracticeTempo(0, 80.0)), position, warnings = listOf(
                "Original OpenTabs practice audio at 80 BPM; each written phrase is played once.",
                "Even eighth notes, with quarter notes for First steps’ open strings. Notes do not sustain across beats.",
            ))
    }
}
