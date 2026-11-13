package com.alexskoropletov.sync.cli

import com.alexskoropletov.sync.SetupCard
import com.alexskoropletov.sync.SetupQr
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class QrShowTest {
    @Test
    fun cameraCanReadTheCode() {
        val card = SetupCard(
            server = "http://203.0.113.10:8787",
            token = "a".repeat(48),
            device = "s24u",
        )
        val matrix = qrMatrix(SetupQr.encode(card))
        val pixels = ByteArray(matrix.width * matrix.height)
        var i = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[i++] = if (matrix.get(x, y)) 0 else 0xFF.toByte()
            }
        }
        val source = object : LuminanceSource(matrix.width, matrix.height) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray {
                val line = row?.takeIf { it.size >= matrix.width } ?: ByteArray(matrix.width)
                pixels.copyInto(line, 0, y * matrix.width, (y + 1) * matrix.width)
                return line
            }

            override fun getMatrix(): ByteArray = pixels
        }
        val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        assertEquals(card, SetupQr.decode(text))
    }
}
