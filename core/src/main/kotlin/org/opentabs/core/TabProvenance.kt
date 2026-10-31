package org.opentabs.core

import kotlinx.serialization.Serializable

/** Retained verbatim for saved v0.2/v0.3 tabs and their source/license information. */
@Serializable
data class Provenance(
    val provider: String,
    val sourceUrl: String,
    val license: String,
    val licenseUrl: String,
    val attribution: String,
    val arrangementId: String,
    val notice: String = "",
    val arrangement: String = "",
    val tags: Set<ArrangementTag> = emptySet(),
)

/** Used by imported GP7 tracks as well as existing saved tab provenance. */
@Serializable
enum class ArrangementTag(val label: String) {
    GUITAR("Guitar"), BASS("Bass"), ACOUSTIC("Acoustic"), LEAD("Lead"), RHYTHM("Rhythm"), CHORDS("Chords")
}
