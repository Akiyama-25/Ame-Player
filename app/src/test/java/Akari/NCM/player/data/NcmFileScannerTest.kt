package Akari.NCM.player.data

import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.PlayableMedia
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NcmFileScannerTest {

    @Test
    fun `读取非NCM文件不做处理保留原始文件名且不区分艺术家`() {
        val uri = Uri.parse("file:///storage/emulated/0/Music/01. 周杰伦 - 晴天.flac")
        val media = NcmFileScanner.scanUri(uri, "01. 周杰伦 - 晴天.flac") as? PlayableMedia.LocalNcm

        assertNotNull(media)
        assertEquals("01. 周杰伦 - 晴天", media?.title)
        assertEquals("", media?.artist)
        assertEquals("", media?.album)
        assertEquals(AudioFormat.FLAC, media?.format)
        assertFalse(media?.isNcm ?: true)
    }

    @Test
    fun `读取非NCM单名称MP3文件不做分割`() {
        val uri = Uri.parse("file:///storage/emulated/0/Music/Stay.mp3")
        val media = NcmFileScanner.scanUri(uri, "Stay.mp3") as? PlayableMedia.LocalNcm

        assertNotNull(media)
        assertEquals("Stay", media?.title)
        assertEquals("", media?.artist)
        assertFalse(media?.isNcm ?: true)
    }

    @Test
    fun `不支持的扩展名返回null`() {
        val uri = Uri.parse("file:///storage/emulated/0/Music/doc.txt")
        val media = NcmFileScanner.scanUri(uri, "doc.txt")
        assertNull(media)
    }
}