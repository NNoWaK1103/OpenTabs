package org.opentabs.core

import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

data class GuitarTrack(val id: String, val name: String, val tuning: List<Int>, val capo: Int,
    val tags: Set<ArrangementTag>, internal val barOffset: Int, val staves: Int)
internal data class GpNote(val string: Int?, val fret: Int?)
internal data class GpBeat(val notes: List<String>, val rhythm: String)

/** A bounded GP7 ZIP/GPIF reader. Displays authored positions; never invents fingering from pitches. */
class GuitarProScore internal constructor(
    val title: String, val artist: String, val copyright: String, val tracks: List<GuitarTrack>,
    private val measures: List<Pair<String, List<String>>>,
    private val bars: Map<String, List<String>>, private val voices: Map<String, List<String>>,
    private val beats: Map<String, GpBeat>, private val notes: Map<String, GpNote>, private val rhythms: Map<String, String>,
) {
    fun nativeSupported(track: GuitarTrack) = track.tuning.size == 6 && track.staves == 1 &&
        runCatching { document(track.id) }.isSuccess

    fun document(trackId: String): TabDocument {
        val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown arrangement.")
        require(track.tuning.size == 6 && track.staves == 1) { "Native GP7 preview currently supports single-staff, six-string arrangements only." }
        val lines = mutableListOf(title, "$artist · ${track.name}", copyright, "Tuning: ${tuningLabel(track.tuning)} · Capo ${track.capo}",
            "GP7 position preview: written measures, not playback notation.",
            "Repeats, expressive techniques, and timing are not interpreted.", "")
        var noteCount = 0
        measures.forEachIndexed { index, (time, ids) ->
            val barId = ids.getOrNull(track.barOffset) ?: error("Invalid score bar references.")
            if (barId == "-1") return@forEachIndexed
            val voiceIds = bars[barId] ?: error("Missing score bar.")
            voiceIds.filter { it != "-1" }.forEachIndexed { voiceNumber, voiceId ->
                val beatIds = voices[voiceId] ?: error("Missing score voice.")
                require(beatIds.size <= 128) { "This measure is too complex for the native preview." }
                lines += "Measure ${index + 1} · $time · Voice ${voiceNumber + 1}"
                val rows = track.tuning.reversed().map { StringBuilder("${pitchLabel(it)}|") }
                beatIds.forEach { beatId ->
                    val beat = beats[beatId] ?: error("Missing score beat.")
                    val values = mutableMapOf<Int, Int>()
                    beat.notes.forEach { noteId ->
                        val note = notes[noteId] ?: error("Missing score note.")
                        val string = note.string ?: error("This arrangement has notes without authored string positions.")
                        val fret = note.fret ?: error("This arrangement has notes without authored fret positions.")
                        require(string in 0..5 && fret in 0..36) { "Unsupported string or fret position." }
                        require(values.put(5 - string, fret) == null) { "Simultaneous notes on the same string cannot be previewed." }
                        noteCount++
                    }
                    val width = maxOf(4, (values.values.maxOrNull()?.toString()?.length ?: 1) + 2)
                    rows.forEachIndexed { string, row -> row.append((values[string]?.toString() ?: "-").padEnd(width, '-')) }
                }
                rows.forEach { lines += it.append('|').toString() }
                lines += ""
            }
        }
        require(noteCount > 0) { "No authored string/fret notes are present in this arrangement." }
        return PlainTextTabDecoder.decode("preview.txt", lines.joinToString("\n").byteInputStream())
    }
}

object GuitarProPreview {
    const val MAX_ARCHIVE = 1024 * 1024
    const val MAX_EXPANDED = 8 * 1024 * 1024

    fun decode(input: InputStream): GuitarProScore {
        val archive = readBounded(input, MAX_ARCHIVE)
        require(archive.size >= 4 && archive[0] == 'P'.code.toByte() && archive[1] == 'K'.code.toByte()) {
            "Only ZIP-based Guitar Pro 7 .gp files are supported. GP3–GP5 and GPX are not implemented."
        }
        var expanded = 0
        var count = 0
        var gpif: ByteArray? = null
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 64) { "Too many entries in this score archive." }
                if (!entry.isDirectory) {
                    val data = readBounded(zip, MAX_EXPANDED - expanded)
                    expanded += data.size
                    if (entry.name == "Content/score.gpif") {
                        require(gpif == null) { "Ambiguous score archive." }
                        gpif = data
                    }
                }
                zip.closeEntry()
            }
        }
        val xml = gpif ?: error("This archive does not contain a supported GPIF score.")
        val handler = ScoreHandler()
        val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
        runCatching { reader.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(xml.inputStream()))
        return handler.result()
    }

    private class Node(val name: String, val attributes: Map<String, String>) {
        val children = mutableListOf<Node>()
        val text = StringBuilder()
        fun child(name: String) = children.firstOrNull { it.name == name }
        fun value(name: String) = child(name)?.text?.toString()?.trim().orEmpty()
        fun list(name: String) = value(name).split(Regex("\\s+")).filter { it.isNotBlank() }
        fun property(name: String): Node? = child("Properties")?.children?.firstOrNull { it.attributes["name"] == name }
    }

    /** Keeps only one XML record tree at a time; all retained score data is small typed records. */
    private class ScoreHandler : DefaultHandler2() {
        private val path = mutableListOf<String>()
        private val stack = mutableListOf<Node>()
        private var totalElements = 0
        private var title = ""
        private var artist = ""
        private var copyright = ""
        private var offset = 0
        private val tracks = mutableListOf<GuitarTrack>()
        private val measures = mutableListOf<Pair<String, List<String>>>()
        private val bars = mutableMapOf<String, List<String>>()
        private val voices = mutableMapOf<String, List<String>>()
        private val beats = mutableMapOf<String, GpBeat>()
        private val notes = mutableMapOf<String, GpNote>()
        private val rhythms = mutableMapOf<String, String>()
        private val records = mapOf("GPIF" to setOf("Score"), "Tracks" to setOf("Track"),
            "MasterBars" to setOf("MasterBar"), "Bars" to setOf("Bar"), "Voices" to setOf("Voice"),
            "Beats" to setOf("Beat"), "Notes" to setOf("Note"), "Rhythms" to setOf("Rhythm"))

        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DTDs and entities are not allowed in scores.") }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("External XML entities are not allowed.") }
        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            require(++totalElements <= 200_000 && path.size < 48) { "Score XML is too complex." }
            if (path.isEmpty()) require(qName == "GPIF") { "Invalid GPIF root element." }
            val parent = path.lastOrNull()
            if (stack.isNotEmpty() || (path.size <= 2 && qName in records[parent].orEmpty())) {
                val node = Node(qName, (0 until attrs.length).associate { attrs.getQName(it) to attrs.getValue(it) })
                stack.lastOrNull()?.children?.add(node)
                stack += node
            }
            path += qName
        }
        override fun characters(ch: CharArray, start: Int, length: Int) { stack.lastOrNull()?.text?.append(ch, start, length) }
        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) record(node)
            }
            path.removeAt(path.lastIndex)
        }
        private fun record(node: Node) {
            val id = node.attributes["id"].orEmpty()
            when (node.name) {
                "Score" -> { title = node.value("Title").take(160); artist = node.value("Artist").take(160); copyright = node.value("Copyright").take(500) }
                "Track" -> {
                    val staves = node.child("Staves")?.children?.filter { it.name == "Staff" }.orEmpty()
                    val staff = staves.firstOrNull()
                    val tuning = staff?.property("Tuning")?.list("Pitches")?.map { it.toInt() }.orEmpty()
                    require(tuning.size <= 12 && tuning.all { it in 0..127 }) { "Invalid score tuning." }
                    val name = node.value("Name").take(120)
                    val program = node.child("Sounds")?.child("Sound")?.child("MIDI")?.value("Program")?.toIntOrNull()
                    val tags = buildSet {
                        if (program in 24..31 || name.contains("gtr", true) || name.contains("guitar", true)) add(ArrangementTag.GUITAR)
                        if (program in 32..39 || name.contains("bass", true)) add(ArrangementTag.BASS)
                        if (program in 24..25) add(ArrangementTag.ACOUSTIC)
                        if (name.contains("lead", true)) add(ArrangementTag.LEAD)
                        if (name.contains("rhythm", true)) add(ArrangementTag.RHYTHM)
                    }
                    tracks += GuitarTrack(id, name, tuning, staff?.property("CapoFret")?.value("Fret")?.toIntOrNull() ?: 0, tags, offset, staves.size)
                    offset += staves.size.coerceAtLeast(1)
                }
                "MasterBar" -> measures += node.value("Time") to node.list("Bars")
                "Bar" -> bars[id] = node.list("Voices")
                "Voice" -> voices[id] = node.list("Beats")
                "Beat" -> beats[id] = GpBeat(node.list("Notes"), node.child("Rhythm")?.attributes?.get("ref").orEmpty())
                "Note" -> notes[id] = GpNote(node.property("String")?.value("String")?.toIntOrNull(), node.property("Fret")?.value("Fret")?.toIntOrNull())
                "Rhythm" -> rhythms[id] = node.value("NoteValue")
            }
        }
        fun result(): GuitarProScore {
            require(title.isNotBlank() && tracks.isNotEmpty() && measures.isNotEmpty()) { "Incomplete GPIF score." }
            require(tracks.size <= 32 && measures.size <= 500 && notes.size <= 25_000) { "Score exceeds native preview limits." }
            return GuitarProScore(title, artist, copyright, tracks, measures, bars, voices, beats, notes, rhythms)
        }
    }
}

fun pitchLabel(midi: Int) = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12]
fun tuningLabel(tuning: List<Int>) = tuning.joinToString(" ") { "${pitchLabel(it)}${it / 12 - 1}" }
