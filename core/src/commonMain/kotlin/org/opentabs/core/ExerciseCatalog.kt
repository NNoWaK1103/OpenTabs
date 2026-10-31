package org.opentabs.core

/** Small, synchronous Swift-friendly API. No storage, network, or platform handles cross it. */
data class PracticeExercise(val id: String, val title: String, val text: String)

class ExerciseCatalog {
    fun exercises(): List<PracticeExercise> = Examples.exercises.mapIndexed { index, (title, text) ->
        PracticeExercise("example-$index", title, text)
    }
}
