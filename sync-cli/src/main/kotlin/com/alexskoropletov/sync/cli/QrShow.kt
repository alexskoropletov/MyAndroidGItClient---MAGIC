package com.alexskoropletov.sync.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import javax.imageio.ImageIO

fun qrMatrix(text: String): BitMatrix {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 4,
    )
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
}

fun printQr(matrix: BitMatrix) {
    val dark = "\u001B[40m  "
    val light = "\u001B[107m  "
    val reset = "\u001B[0m"
    for (y in 0 until matrix.height) {
        val line = StringBuilder()
        for (x in 0 until matrix.width) {
            line.append(if (matrix.get(x, y)) dark else light)
        }
        println(line.append(reset))
    }
}

fun writeQrPng(matrix: BitMatrix, file: File, scale: Int = 10) {
    val image = BufferedImage(matrix.width * scale, matrix.height * scale, BufferedImage.TYPE_INT_RGB)
    val canvas = image.createGraphics()
    canvas.color = Color.WHITE
    canvas.fillRect(0, 0, image.width, image.height)
    canvas.color = Color.BLACK
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) canvas.fillRect(x * scale, y * scale, scale, scale)
        }
    }
    canvas.dispose()
    file.parentFile?.mkdirs()
    require(ImageIO.write(image, "png", file)) { "не записал png" }
    try {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    } catch (_: Exception) {
    }
}
