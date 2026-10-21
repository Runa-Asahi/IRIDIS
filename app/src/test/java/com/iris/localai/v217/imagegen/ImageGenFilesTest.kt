package com.iris.localai.v217.imagegen

import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageGenFiles
import com.iris.localai.imagegen.ImageRequest
import com.iris.localai.imagegen.PngParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class ImageGenFilesTest {
    private val t = LocalDateTime.of(2026, 10, 22, 2, 4, 5)
    private fun tmp(): File = Files.createTempDirectory("imggen").toFile()

    /** 1×1 PNG: signature + IHDR + IDAT + IEND. */
    private val png: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    ) + PngParams.chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0)) +
        PngParams.chunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x63, 0, 0, 0, 1, 0, 1)) + PngParams.chunk("IEND", ByteArray(0))

    @Test fun outputNameAndPath() {
        assertEquals("IMG_20261022_020405.png", ImageGenFiles.outputName(t))
        val ws = tmp()
        val f = ImageGenFiles.outputFile(ws, t)
        assertEquals(File(File(ws, "images"), "IMG_20261022_020405.png"), f)
        assertEquals("![](images/IMG_20261022_020405.png)", ImageGenFiles.markdownRef(f))
        assertEquals("models/imagegen", ImageGenFiles.MODELS_SUB)
    }

    @Test fun sameSecondGetsSuffix() {
        val ws = tmp()
        ImageGenFiles.write(ImageGenFiles.outputFile(ws, t), png, "a")
        val second = ImageGenFiles.outputFile(ws, t)
        assertEquals("IMG_20261022_020405_2.png", second.name)
        ImageGenFiles.write(second, png, "b")
        assertEquals("IMG_20261022_020405_3.png", ImageGenFiles.outputFile(ws, t).name)
    }

    @Test fun writeEmbedsParametersAndHistoryIsNewestFirst() {
        val ws = tmp()
        val req = ImageRequest("一只猫, cat", negative = "blurry", seed = 42).normalize(ImageCatalog.SD15_QNN)
        val params = ImageGenFiles.parametersText(req, ImageCatalog.SD15_QNN)
        assertTrue(params.startsWith("一只猫, cat\nNegative prompt: blurry\nSteps: 20"))
        assertTrue(params.contains("Seed: 42") && params.contains("Size: 512x512") && params.contains("Model: sd15-qnn"))
        val f = ImageGenFiles.write(ImageGenFiles.outputFile(ws, t), png, params)
        assertEquals(params, PngParams.read(f.readBytes()))
        assertFalse(File(f.path + ".part").exists())
        val later = ImageGenFiles.write(ImageGenFiles.outputFile(ws, t.plusMinutes(1)), png, params)
        File(ws, "images/other.png").writeBytes(png)
        assertEquals(listOf(later.name, f.name), ImageGenFiles.history(ws).map { it.name })
        assertTrue(ImageGenFiles.history(tmp()).isEmpty())
    }

    @Test fun negativeDroppedWithoutCfg() {
        val req = ImageRequest("p", negative = "n", cfg = 1f, seed = 1).normalize(ImageCatalog.SD15_MNN)
        assertFalse(ImageGenFiles.parametersText(req, ImageCatalog.SD15_MNN).contains("Negative prompt"))
    }

    @Test fun downloadedDetection() {
        val root = tmp()
        val qnn = ImageCatalog.SD15_QNN
        assertFalse(ImageGenFiles.isDownloaded(root, qnn))
        val rf = qnn.files.single()
        val f = File(root, rf.path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        assertFalse("size must match", ImageGenFiles.isDownloaded(root, qnn))
        java.io.RandomAccessFile(f, "rw").use { it.setLength(rf.size) }
        assertTrue(ImageGenFiles.isDownloaded(root, qnn))
        assertEquals(File(root, "sd15-qnn"), ImageGenFiles.modelDir(root, qnn))

        val mnn = ImageCatalog.SD15_MNN
        assertFalse(ImageGenFiles.isDownloaded(root, mnn))
        File(root, "sd15-mnn/unet.mnn.part").apply { parentFile!!.mkdirs(); writeText("x") }
        assertFalse(ImageGenFiles.isDownloaded(root, mnn))
        File(root, "sd15-mnn/unet.mnn.part").renameTo(File(root, "sd15-mnn/unet.mnn"))
        assertTrue(ImageGenFiles.isDownloaded(root, mnn))

        File(root, "flux2-klein-4b/x").apply { parentFile!!.mkdirs(); writeText("x") }
        assertFalse("Hexagon models never count", ImageGenFiles.isDownloaded(root, ImageCatalog.FLUX2_KLEIN))
    }

    @Test fun sizes() {
        assertEquals(709_785_222L, ImageGenFiles.sizeBytes(ImageCatalog.SD15_QNN))
        assertNull(ImageGenFiles.sizeBytes(ImageCatalog.SD15_MNN))
    }
}
