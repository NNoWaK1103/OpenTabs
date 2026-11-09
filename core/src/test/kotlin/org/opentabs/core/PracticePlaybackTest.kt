package org.opentabs.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.ShortMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PracticePlaybackTest {
    @get:Rule val temporary = TemporaryFolder()
    private val tuning = listOf(40, 45, 50, 55, 59, 64)

    private fun sample() = PracticeScore("Practice", "Guitar", tuning, 0,
        listOf(PracticeNote(0, 960, 0, 0, 40, 1), PracticeNote(1920, 960, 5, 3, 67, 1)),
        listOf(PracticeTempo(0, 120.0), PracticeTempo(1920, 60.0)), 3840)

    private fun zip(xml: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("Content/score.gpif")); zip.write(xml.toByteArray()); zip.closeEntry() }
    }.toByteArray()

    private fun score(
        beats: String = "<Beat id='b'><Notes>n</Notes><Rhythm ref='r'/></Beat>",
        rhythms: String = "<Rhythm id='r'><NoteValue>Quarter</NoteValue></Rhythm>",
        notes: String = note("n", 0, 3),
        beatIds: String = "b",
        tempo: String = "<Automation><Type>Tempo</Type><Bar>0</Bar><Position>0</Position><Value>120 2</Value></Automation>",
        time: String = "4/4", capo: Int = 0, tuning: String = "40 45 50 55 59 64", anacrusis: Boolean = false,
        masterBars: String = "<MasterBar><Time>$time</Time><Bars>bar</Bars></MasterBar>",
        bars: String = "<Bar id='bar'><Voices>v</Voices></Bar>",
        voices: String = "<Voice id='v'><Beats>$beatIds</Beats></Voice>",
    ): GuitarProScore = GuitarProPreview.decode(zip("""
        <GPIF><Score><Title>Authored test</Title><Artist>OpenTabs</Artist></Score>
        <MasterTrack>${if (anacrusis) "<Anacrusis/>" else ""}<Automations>$tempo</Automations></MasterTrack>
        <Tracks><Track id="track"><Name>Guitar</Name><Staves><Staff><Properties>
        <Property name="Tuning"><Pitches>$tuning</Pitches></Property><Property name="CapoFret"><Fret>$capo</Fret></Property>
        </Properties></Staff></Staves></Track></Tracks>
        <MasterBars>$masterBars</MasterBars><Bars>$bars</Bars><Voices>$voices</Voices>
        <Beats>$beats</Beats><Notes>$notes</Notes><Rhythms>$rhythms</Rhythms></GPIF>
    """.trimIndent()).inputStream())

    private fun note(id: String, string: Int, fret: Int, properties: String = "", extra: String = "") =
        "<Note id='$id'><Properties><Property name='String'><String>$string</String></Property><Property name='Fret'><Fret>$fret</Fret></Property>$properties</Properties>$extra</Note>"

    @Test fun `rest dotted double dotted and triplet durations come from GPIF not text spacing`() {
        val gp = score(
            beats = "<Beat id='a'><Rhythm ref='quarter'/></Beat>" +
                "<Beat id='b'><Notes>n</Notes><Rhythm ref='dotted'/></Beat>" +
                "<Beat id='c'><Notes>n</Notes><Rhythm ref='double'/></Beat>" +
                "<Beat id='d'><Notes>n</Notes><Rhythm ref='triplet'/></Beat>",
            beatIds = "a b c d", time = "8/4",
            rhythms = "<Rhythm id='quarter'><NoteValue>Quarter</NoteValue></Rhythm>" +
                "<Rhythm id='dotted'><NoteValue>Eighth</NoteValue><AugmentationDot count='1'/></Rhythm>" +
                "<Rhythm id='double'><NoteValue>Quarter</NoteValue><AugmentationDot count='2'/></Rhythm>" +
                "<Rhythm id='triplet'><NoteValue>Eighth</NoteValue><PrimaryTuplet num='3' den='2'/></Rhythm>",
        ).practiceScore("track")
        assertEquals(listOf(960L, 1680L, 3360L), gp.notes.map { it.startTick })
        assertEquals(listOf(720L, 1680L, 320L), gp.notes.map { it.durationTicks })
        assertEquals(7680L, gp.durationTicks)
        assertEquals(4000L, gp.durationMillis())
    }

    @Test fun `tuplet rounding does not accumulate drift across a measure`() {
        val gp = score(beatIds = List(7) { "b" }.joinToString(" "), time = "1/4",
            rhythms = "<Rhythm id='r'><NoteValue>16th</NoteValue><PrimaryTuplet num='7' den='4'/></Rhythm>").practiceScore("track")
        assertEquals(960L, gp.notes.sumOf { it.durationTicks })
        assertEquals(960L, gp.notes.last().startTick + gp.notes.last().durationTicks)
    }

    @Test fun `authored drop tuning and capo affect pitch and remain in the score`() {
        val gp = score(capo = 2, tuning = "38 45 50 55 59 64").practiceScore("track")
        assertEquals(43, gp.notes.single().midiPitch)
        assertEquals(0, gp.notes.single().string)
        assertEquals(3, gp.notes.single().fret)
        assertEquals(2, gp.capo)
        assertEquals(38, gp.tuning.first())
    }

    @Test fun `whole measure rest observes time signature and following notes start in written order`() {
        val gp = score(
            beats = "<Beat id='rest'><Rhythm ref='whole'/></Beat><Beat id='b'><Notes>n</Notes><Rhythm ref='r'/></Beat>",
            rhythms = "<Rhythm id='whole'><NoteValue>Whole</NoteValue></Rhythm><Rhythm id='r'><NoteValue>Quarter</NoteValue></Rhythm>",
            masterBars = "<MasterBar><Time>3/4</Time><Bars>restbar</Bars><Repeat start='true'/></MasterBar>" +
                "<MasterBar><Time>2/4</Time><Bars>bar</Bars><Repeat end='true' count='8'/></MasterBar>",
            bars = "<Bar id='restbar'><Voices>restvoice</Voices></Bar><Bar id='bar'><Voices>v</Voices></Bar>",
            voices = "<Voice id='restvoice'><Beats>rest</Beats></Voice><Voice id='v'><Beats>b</Beats></Voice>",
        ).practiceScore("track")
        assertEquals(2880L, gp.notes.single().startTick)
        assertEquals(4800L, gp.durationTicks)
        assertEquals(2, gp.notes.single().measure)
        assertTrue(gp.warnings.any { "repeats" in it })
    }

    @Test fun `multiple voices start together and notes on separate strings remain distinct`() {
        val gp = score(beats = "<Beat id='b'><Notes>n</Notes><Rhythm ref='r'/></Beat><Beat id='c'><Notes>high</Notes><Rhythm ref='r'/></Beat>",
            notes = note("n", 0, 0) + note("high", 5, 0), bars = "<Bar id='bar'><Voices>v w</Voices></Bar>",
            voices = "<Voice id='v'><Beats>b</Beats></Voice><Voice id='w'><Beats>c</Beats></Voice>").practiceScore("track")
        assertEquals(listOf(0L, 0L), gp.notes.map { it.startTick })
        assertEquals(listOf(40, 64), gp.notes.map { it.midiPitch })
    }

    @Test fun `tempo changes have exact positions and linear ramps are disclosed`() {
        val gp = score(tempo = "<Automation><Type>Tempo</Type><Bar>0</Bar><Position>0</Position><Value>120 2</Value></Automation>" +
            "<Automation><Type>Tempo</Type><Bar>0</Bar><Position>0.5</Position><Value>60 2</Value><Linear>true</Linear></Automation>").practiceScore("track")
        assertEquals(listOf(PracticeTempo(0, 120.0), PracticeTempo(1920, 60.0)), gp.tempos)
        assertEquals(3000L, gp.durationMillis())
        assertEquals(6000L, gp.durationMillis(0.5))
        assertTrue(gp.warnings.any { "Gradual tempo" in it })
    }

    @Test fun `muted grace and expressive techniques are not silently claimed as faithful performance`() {
        val gp = score(
            beats = "<Beat id='grace'><Notes>n</Notes><GraceNotes>BeforeBeat</GraceNotes><Rhythm ref='r'/></Beat>" +
                "<Beat id='muted'><Notes>muted</Notes><Rhythm ref='r'/></Beat><Beat id='b'><Notes>n</Notes><Rhythm ref='r'/><Arpeggio>Up</Arpeggio></Beat>",
            beatIds = "grace muted b", notes = note("n", 0, 3, extra = "<Tie origin='true' destination='false'/>") +
                note("muted", 0, 0, "<Property name='Muted'><Enable/></Property>"),
        ).practiceScore("track")
        assertEquals(1, gp.notes.size)
        assertEquals(960L, gp.notes.single().startTick)
        assertTrue(gp.warnings.any { "Grace notes" in it })
        assertTrue(gp.warnings.any { "Dead/muted" in it })
        assertTrue(gp.warnings.any { "ties are re-picked" in it })
    }

    @Test fun `invalid rhythm tempo and out of range authored pitches disable audio instead of guessing`() {
        listOf(
            score(rhythms = "<Rhythm id='r'><NoteValue>Mystery</NoteValue></Rhythm>"),
            score(rhythms = "<Rhythm id='r'><NoteValue>Quarter</NoteValue><AugmentationDot count='4'/></Rhythm>"),
            score(rhythms = "<Rhythm id='r'><NoteValue>Quarter</NoteValue><PrimaryTuplet num='0' den='2'/></Rhythm>"),
            score(rhythms = "<Rhythm id='r'><NoteValue>Quarter</NoteValue><PrimaryTuplet num='3' den='0'/></Rhythm>"),
            score(rhythms = "<Rhythm id='r'><NoteValue>Quarter</NoteValue><SecondaryTuplet num='3' den='2'/></Rhythm>"),
            score(tempo = ""), score(capo = 25), score(tuning = "127 45 50 55 59 64"),
            score(rhythms = ""),
            score(tempo = "<Automation><Type>Tempo</Type><Bar>0</Bar><Position>0</Position><Value>120 1</Value></Automation>"),
            score(tempo = "<Automation><Type>Tempo</Type><Bar>0</Bar><Position>2</Position><Value>120 2</Value></Automation>"),
            score(beatIds = "b b b b b"), score(time = "0/4"),
            score(tempo = "<Automation><Type>Tempo</Type><Bar>0</Bar><Position>0</Position><Value>NaN 2</Value></Automation>"),
        ).forEach { gp ->
            assertTrue(runCatching { gp.practiceScore("track") }.isFailure)
            assertTrue(gp.document("track").lines.size > 6)
        }
    }

    @Test fun `pickup measure never gains invented silent beats and still imports as readable text`() = runTest {
        val gp = score(anacrusis = true)
        val failure = runCatching { gp.practiceScore("track") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("Pickup"))
        assertTrue(gp.document("track").lines.size > 6)
        val library = LibraryRepository(temporary.newFolder())
        library.initialize()
        val saved = library.importGuitarPro(gp, "track")
        assertNull(library.practiceScore(saved.id))
        assertTrue(saved.playbackUnavailableReason!!.contains("Pickup"))
        assertTrue(library.document(saved.id).lines.size > 6)
    }

    @Test fun `bounded score validates hostile serialized numbers and unsupported versions`() {
        val good = sample()
        listOf<() -> Any>(
            { good.copy(version = 2) }, { good.copy(ticksPerQuarter = 0) }, { good.copy(capo = -1) },
            { good.copy(durationTicks = Long.MAX_VALUE) }, { good.copy(tempos = listOf(PracticeTempo(0, Double.NaN))) },
            { good.copy(tempos = listOf(PracticeTempo(1, 120.0))) }, { good.copy(tempos = listOf(PracticeTempo(0, 0.0))) },
            { good.copy(notes = listOf(good.notes.first().copy(startTick = Long.MAX_VALUE))) },
            { good.copy(notes = listOf(good.notes.first().copy(durationTicks = -1))) },
            { good.copy(notes = listOf(good.notes.first().copy(midiPitch = 41))) },
            { good.copy(notes = listOf(good.notes.first(), good.notes.first())) },
            { good.copy(notes = List(25_001) { good.notes.first() }) },
            { PracticeMidi.encode(good, Double.NaN) }, { PracticeMidi.encode(good, 0.0) },
            { good.durationMillis(5.0) },
        ).forEach { assertTrue(runCatching(it).isFailure) }
        val encoded = Json.encodeToString(good)
        assertEquals(good, Json.decodeFromString<PracticeScore>(encoded))
        assertTrue(runCatching { Json.decodeFromString<PracticeScore>(encoded.replace("\"durationTicks\":3840", "\"durationTicks\":-1")) }.isFailure)
    }

    @Test fun `MIDI is valid format zero with tempo map guitar channels note offs and final silence`() {
        val bytes = PracticeMidi.encode(sample())
        assertEquals("MThd", bytes.take(4).toByteArray().toString(Charsets.US_ASCII))
        val sequence = MidiSystem.getSequence(bytes.inputStream())
        assertEquals(960, sequence.resolution)
        assertEquals(3840L, sequence.tickLength)
        assertEquals(3_000_000L, sequence.microsecondLength)
        assertEquals(1, sequence.tracks.size)
        val track = sequence.tracks.single()
        val events = (0 until track.size()).map { track.get(it) }
        val noteOns = events.filter { (it.message as? ShortMessage)?.command == ShortMessage.NOTE_ON }
        assertEquals(listOf(0L, 1920L), noteOns.map { it.tick })
        assertEquals(listOf(0, 5), noteOns.map { (it.message as ShortMessage).channel })
        assertEquals(listOf(40, 67), noteOns.map { (it.message as ShortMessage).data1 })
        assertEquals(6, events.count { (it.message as? ShortMessage)?.command == ShortMessage.PROGRAM_CHANGE })
        assertEquals(2, events.count { (it.message as? ShortMessage)?.command == ShortMessage.NOTE_OFF })
        assertEquals(0x2f, (events.last().message as MetaMessage).type)
        assertEquals(6_000_000L, MidiSystem.getSequence(PracticeMidi.encode(sample(), 0.5).inputStream()).microsecondLength)
    }

    @Test fun `MIDI turns repeated notes off before rearticulating the same string`() {
        val score = sample().copy(notes = listOf(PracticeNote(0, 960, 0, 0, 40, 1), PracticeNote(960, 960, 0, 0, 40, 1)))
        val track = MidiSystem.getSequence(PracticeMidi.encode(score).inputStream()).tracks.single()
        val atBoundary = (0 until track.size()).map { track.get(it) }.filter { it.tick == 960L }
        assertEquals(listOf(ShortMessage.NOTE_OFF, ShortMessage.NOTE_ON), atBoundary.map { (it.message as ShortMessage).command })
    }

    @Test fun `GP7 import saves offline playback and deleting it removes its sidecar`() = runTest {
        val folder = temporary.newFolder()
        val library = LibraryRepository(folder)
        library.initialize()
        val song = library.importGuitarPro(score(), "track")
        val restarted = LibraryRepository(folder)
        restarted.initialize()
        assertEquals(43, restarted.practiceScore(song.id)!!.notes.single().midiPitch)
        assertTrue(restarted.document(song.id).lines.size > 6)
        assertNull(restarted.state.value.songs.last().playbackUnavailableReason)
        restarted.delete(song.id)
        assertFalse(File(folder, "${song.id}.practice.json").exists())
    }

    @Test fun `missing timing keeps text import and plain text never invents playback`() = runTest {
        val library = LibraryRepository(temporary.newFolder())
        library.initialize()
        val song = library.importGuitarPro(score(tempo = ""), "track")
        assertNull(library.practiceScore(song.id))
        assertTrue(song.playbackUnavailableReason!!.contains("tempo"))
        assertTrue(library.document(song.id).lines.size > 6)
        val text = library.importTab("text.txt", Examples.exercises.first().second.byteInputStream())
        assertNull(library.practiceScore(text.id))
    }

    @Test fun `corrupt playback is reported separately and leaves saved text untouched`() = runTest {
        val folder = temporary.newFolder()
        val library = LibraryRepository(folder)
        library.initialize()
        val song = library.importGuitarPro(score(), "track")
        File(folder, "${song.id}.practice.json").writeText("broken playback")
        assertTrue(runCatching { library.practiceScore(song.id) }.isFailure)
        assertTrue(library.document(song.id).lines.size > 6)
        assertEquals("broken playback", File(folder, "${song.id}.practice.json").readText())
        File(folder, "${song.id}.practice.json").writeBytes(ByteArray(8 * 1024 * 1024 + 1))
        assertTrue(runCatching { library.practiceScore(song.id) }.isFailure)
        assertTrue(library.document(song.id).lines.size > 6)
    }

    @Test fun `legacy library adds authored exercise playback only to unchanged built in text`() = runTest {
        val folder = temporary.newFolder()
        File(folder, "library.json").writeText("""{"version":1,"songs":[{"id":"example-0","title":"Renamed","artist":"Me","imported":false},{"id":"old","title":"Legacy","artist":"Me"}],"currentSongId":"old"}""")
        File(folder, "example-0.txt").writeText(Examples.exercises.first().second)
        File(folder, "old.txt").writeText(Examples.exercises.first().second)
        val library = LibraryRepository(folder)
        library.initialize()
        assertNotNull(library.practiceScore("example-0"))
        assertNull(library.practiceScore("old"))
        assertEquals("Renamed", library.state.value.songs.first().title)
        File(folder, "example-0.practice.json").delete()
        File(folder, "example-0.txt").appendText("\nChanged by the user")
        LibraryRepository(folder).initialize()
        assertFalse(File(folder, "example-0.practice.json").exists())
    }

    @Test fun `optional exercise migration write failure leaves the existing library readable and corrupt data untouched`() = runTest {
        val folder = temporary.newFolder()
        val songs = Examples.exercises.mapIndexed { index, (title, content) ->
            File(folder, "example-$index.txt").writeText(content)
            Song("example-$index", title, "OpenTabs", imported = false)
        }
        val originalIndex = Json.encodeToString(LibraryState(songs = songs, currentSongId = "example-0"))
        File(folder, "library.json").writeText(originalIndex)
        // A directory at the temporary output path reliably fails the optional write, even when tests run as root.
        val blockedTemporary = File(folder, "example-0.practice.json.tmp").apply { mkdir() }
        File(blockedTemporary, "preserve.txt").writeText("Existing data")
        val corruptSidecar = File(folder, "example-1.practice.json").apply { writeText("corrupt existing playback") }
        val library = LibraryRepository(folder)
        library.initialize()
        assertEquals(songs, library.state.value.songs)
        assertEquals("example-0", library.state.value.currentSongId)
        songs.forEach { assertTrue(library.document(it.id).lines.size > 6) }
        assertNull(library.practiceScore("example-0"))
        assertNotNull(library.practiceScore("example-2"))
        assertEquals(originalIndex, File(folder, "library.json").readText())
        assertEquals(Examples.exercises.first().second, File(folder, "example-0.txt").readText())
        assertEquals("corrupt existing playback", corruptSidecar.readText())
        assertTrue(runCatching { library.practiceScore("example-1") }.isFailure)
        assertEquals("Existing data", File(blockedTemporary, "preserve.txt").readText())
    }

    @Test fun `licensed legacy save upgrades unchanged text without duplicating metadata`() = runTest {
        val library = LibraryRepository(temporary.newFolder())
        library.initialize()
        val result = TabSearchResult("original", "demo", "Original", "OpenTabs", "Original", "https://example.org", "Guitar",
            rights = ContentRights(true, true, true, "CC0", "https://creativecommons.org/publicdomain/zero/1.0/", "OpenTabs"))
        val document = score().document("track")
        val saved = library.saveAuthorized(AuthorizedTab(result, document))
        library.toggleFavorite(saved.id)
        assertNull(library.practiceScore(saved.id))
        val practice = score().practiceScore("track")
        assertEquals(saved.id, library.saveAuthorized(AuthorizedTab(result, document, practice)).id)
        assertEquals(practice, library.practiceScore(saved.id))
        assertEquals(4, library.state.value.songs.size)
        assertTrue(library.state.value.songs.last().favorite)
    }

    @Test fun `all bundled exercise scores are playable and all reviewed six string fixture tracks retain audio`() = runTest {
        val library = LibraryRepository(temporary.newFolder())
        library.initialize()
        library.state.value.songs.forEach { song ->
            val practice = requireNotNull(library.practiceScore(song.id))
            assertTrue(practice.notes.size > 20)
            assertTrue(PracticeMidi.encode(practice).size > 100)
        }
        val gp = GuitarProPreview.decode(requireNotNull(javaClass.getResourceAsStream("/tabforge-ashen-meridian.gp")))
        gp.tracks.filter { gp.nativeSupported(it) }.forEach { track ->
            val practice = gp.practiceScore(track.id)
            assertTrue(practice.notes.maxOf { it.measure } in 130..144)
            assertTrue(practice.durationMillis() > 60_000)
            assertEquals(150.0, practice.tempos.first().bpm, 0.0)
            assertTrue(practice.tempos.size > 1)
            assertTrue(PracticeMidi.encode(practice).size > 1000)
        }
    }
}
