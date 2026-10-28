package com.wildcreator.sonorus.background

import com.wildcreator.sonorus.data.background.ChatBackgroundSettings
import com.wildcreator.sonorus.data.background.backgroundSampleSize
import com.wildcreator.sonorus.data.background.importBackgroundFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

class ChatBackgroundFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingAndNonFiniteOpacityUseDefault() {
        for (value in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0.25f, ChatBackgroundSettings.fromStored(null, value).opacity, 0f)
        }
    }

    @Test fun finiteOpacityIsClampedWithoutChangingValidValues() {
        assertEquals(0f, ChatBackgroundSettings.fromStored(null, -1f).opacity, 0f)
        assertEquals(1f, ChatBackgroundSettings.fromStored(null, 2f).opacity, 0f)
        assertEquals(0.6f, ChatBackgroundSettings.fromStored(null, 0.6f).opacity, 0f)
    }

    @Test fun blankPathIsDefaultButRealPathIsPreserved() {
        assertNull(ChatBackgroundSettings.fromStored("  ", null).path)
        assertEquals("/private/a b.image", ChatBackgroundSettings.fromStored("/private/a b.image", null).path)
    }

    @Test fun copiesAtLimitToIndependentStableFileAndClosesSource() {
        val directory = temporary.newFolder()
        val bytes = byteArrayOf(1, 2, 3, 4)
        var closed = false
        val source = object : java.io.ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        val file = importBackgroundFile(directory, { source }, { assertArrayEquals(bytes, it.readBytes()) }, 4)
        assertTrue(closed)
        assertTrue(file.isAbsolute)
        assertTrue(file.isFile)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun oversizedStreamDoesNotReplaceOldBackgroundOrLeavePartialFile() {
        val directory = temporary.newFolder()
        val old = File(directory, "old.image").apply { writeText("old") }
        assertThrows(IOException::class.java) {
            importBackgroundFile(directory, { byteArrayOf(1, 2, 3, 4, 5).inputStream() }, {}, 4)
        }
        assertEquals("old", old.readText())
        assertEquals(listOf(old), directory.listFiles()!!.toList())
    }

    @Test fun emptySourceIsRejectedBeforeValidation() {
        val directory = temporary.newFolder()
        var validated = false
        assertThrows(IOException::class.java) {
            importBackgroundFile(directory, { byteArrayOf().inputStream() }, { validated = true }, 4)
        }
        assertFalse(validated)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun decoderFailureLeavesOldBackgroundIntact() {
        val directory = temporary.newFolder()
        val old = File(directory, "old.image").apply { writeText("old") }
        assertThrows(IOException::class.java) {
            importBackgroundFile(directory, { byteArrayOf(1).inputStream() }, { throw IOException("invalid image") }, 4)
        }
        assertEquals("old", old.readText())
        assertEquals(listOf(old), directory.listFiles()!!.toList())
    }

    @Test fun interruptedCopyClosesSourceAndRemovesPartialFile() {
        val directory = temporary.newFolder()
        var closed = false
        val source = object : InputStream() {
            var reads = 0
            override fun read(): Int = if (reads++ == 0) 1 else throw IOException("unreadable")
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) {
            importBackgroundFile(directory, { source }, {}, 4)
        }
        assertTrue(closed)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun sourceOpenFailureLeavesNoTemporaryFile() {
        val directory = temporary.newFolder()
        assertThrows(SecurityException::class.java) {
            importBackgroundFile(directory, { throw SecurityException("denied") }, {}, 4)
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun secondImportHasNewPathWithoutOverwritingFirst() {
        val directory = temporary.newFolder()
        val first = importBackgroundFile(directory, { byteArrayOf(1).inputStream() }, {}, 4)
        val second = importBackgroundFile(directory, { byteArrayOf(2).inputStream() }, {}, 4)
        assertNotEquals(first.absolutePath, second.absolutePath)
        assertArrayEquals(byteArrayOf(1), first.readBytes())
        assertArrayEquals(byteArrayOf(2), second.readBytes())
    }

    @Test fun samplingBoundsBothDimensionsIncludingExtremeAspectRatios() {
        assertEquals(1, backgroundSampleSize(2048, 1000))
        assertEquals(2, backgroundSampleSize(2049, 1000))
        assertEquals(4, backgroundSampleSize(4000, 8000))
        assertEquals(1048576, backgroundSampleSize(Int.MAX_VALUE, 1))
    }

    @Test fun samplingRejectsInvalidImageDimensions() {
        assertThrows(IOException::class.java) { backgroundSampleSize(0, 100) }
        assertThrows(IOException::class.java) { backgroundSampleSize(100, -1) }
    }
}
