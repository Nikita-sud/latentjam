/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.library.tags

import java.io.File
import io.github.nikitasud.latentjam.library.tags.write.WriteFixtures

/** Export actual writer output for an independent reader. No production parser supplies the
 * expected edited text: it comes directly from the requested edits. */
public object TagLibFixtures {
    @JvmStatic public fun main(args: Array<String>) {
        val root = File(args.single()).apply { mkdirs() }
        val rows = mutableListOf<String>()
        fun export(name: String, bytes: ByteArray, edits: TagEdits) {
            val source = ByteArraySource(bytes)
            val codec = checkNotNull(TagCodecs.forSource(source))
            val extension = when (codec) { Id3TagCodec -> "mp3"; FlacTagCodec -> "flac"; OggTagCodec -> if (codec.read(source).format == TagFormat.VORBIS) "ogg" else "opus"; else -> "m4a" }
            val output = checkNotNull(WritePlans.applyInMemory(bytes, codec.plan(source, edits)))
            check(TagVerification.verify(codec, source, ByteArraySource(output), edits).isEmpty())
            check(codec.audioDigest(source) == codec.audioDigest(ByteArraySource(output)))
            check(codec.inventory(source) == codec.inventory(ByteArraySource(output)))
            File(root, "$name.before.$extension").writeBytes(bytes)
            File(root, "$name.after.$extension").writeBytes(output)
            rows += listOf("case", name, extension).joinToString("\t")
            for ((field, value) in listOf("TITLE" to edits.title, "ARTIST" to edits.artist,
                "ALBUM" to edits.album, "ALBUMARTIST" to edits.albumArtist, "LYRICS" to edits.lyrics)) {
                if (value != null) rows += listOf("edit", name, field,
                    value.replace("\u0000", "").let { if (field == "LYRICS") it.trim() else it }
                        .encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }).joinToString("\t")
            }
        }
        for ((index, fixture) in (WriteFixtures.inPlace + WriteFixtures.rewrites).withIndex()) {
            export("original-$index", fixture.original, fixture.edits)
            export("unicode-$index", fixture.original, fixture.edits.copy(title = "Музыка 🎧 – 東京", artist = "Björk / Тест", album = "Álbum", albumArtist = "Ансамбль"))
            export("clear-$index", fixture.original, TagEdits(title = ""))
        }
        val unknown = Id3TestTags.build(4, listOf(
            TestFrame("TIT2", Id3TestTags.utf8Body("Old")),
            TestFrame("TXXX", Id3TestTags.utf8Body("CUSTOM_FIELD\u0000keep me 🎼")),
            Id3TestTags.commentFrame("untouched comment"),
        )) + Id3TestTags.mp3Payload(4096)
        export("unknown-id3", unknown, TagEdits(title = "Changed", lyrics = "Line one\nСтрока два"))
        val keep = "opaque value 🎼"
        val edit = TagEdits(title = "Changed", lyrics = "Line one\nСтрока два")
        export("unknown-flac", FlacFixtures.file(4 to FlacFixtures.comments("TITLE" to "Old", "CUSTOM_FIELD" to keep)), edit)
        export("unknown-opus", OggFixtures.opus("TITLE" to "Old", "CUSTOM_FIELD" to keep), edit)
        export("unknown-vorbis", OggFixtures.vorbis("TITLE" to "Old", "CUSTOM_FIELD" to keep), edit)
        export("unknown-mp4", Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "Old"), Mp4Fixtures.freeform("CUSTOM_FIELD", keep))), edit)
        File(root, "manifest.tsv").writeText(rows.joinToString("\n") + "\n")
        println("Exported ${rows.count { it.startsWith("case\t") }} before/after cases")
    }
}
