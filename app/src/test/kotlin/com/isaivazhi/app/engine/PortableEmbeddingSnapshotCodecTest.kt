package com.isaivazhi.app.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class PortableEmbeddingSnapshotCodecTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedRowCount() {
        PortableEmbeddingSnapshotCodec.parse(fixture(), expectedRows = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedVectorBlob() {
        val bytes = fixture()
        PortableEmbeddingSnapshotCodec.parse(bytes.copyOf(bytes.size - 1))
    }

    private fun fixture(): ByteArray {
        val metadata = """
            {"entries":[
              {"contentHash":"h1","filename":"one.flac","filepath":"/one.flac"},
              {"contentHash":"h2","filename":"two.flac","filepath":"/two.flac"}
            ]}
        """.trimIndent().toByteArray()
        val vectors = ByteBuffer.allocate(2 * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1f).putFloat(2f).putFloat(3f)
            .putFloat(4f).putFloat(5f).putFloat(6f)
            .array()
        return ByteBuffer.allocate(20 + metadata.size + vectors.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('I'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), '1'.code.toByte()))
            .putInt(1)
            .putInt(3)
            .putInt(2)
            .putInt(metadata.size)
            .put(metadata)
            .put(vectors)
            .array()
    }
}
