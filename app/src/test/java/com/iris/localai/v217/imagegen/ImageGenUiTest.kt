package com.iris.localai.v217.imagegen

import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.ui.imagegen.ImageGenUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageGenUiTest {
    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }
    private fun src(p: String) = file("src/main/java/com/iris/localai/$p").readText()

    @Test fun requestRules() {
        val qnn = ImageGenUi(prompt = "cat", initImage = "/x.png", width = 1024, height = 768, seed = "7")
        assertFalse(qnn.img2imgSupported)
        val r = qnn.request()
        assertNull("QNN model has no img2img", r.initImagePath)
        assertEquals(512, r.width); assertEquals(512, r.height); assertEquals(7L, r.seed)

        val hex = ImageGenUi(modelId = ImageCatalog.FLUX2_KLEIN.id, prompt = "cat")
        assertFalse(hex.canGenerate)
        assertFalse(ImageGenUi().canGenerate)
        assertTrue(ImageGenUi(prompt = "x").canGenerate)
        assertTrue(ImageGenUi(prompt = "x").request().seed >= 0)
    }

    @Test fun pageUsesIrisIconsAndMotionOnly() {
        val page = src("ui/imagegen/ImageGenScreen.kt")
        assertFalse(page.contains("material.icons"))
        assertFalse(page.contains("tween("))
        assertTrue(page.contains("IrisIcons."))
    }

    @Test fun wiredIntoDrawerAndNavigation() {
        assertTrue(src("ui/chat/HistoryDrawer.kt").contains("IrisIcons.Image, DrawerNavText.IMAGEGEN, onClick = onImageGen)"))
        val main = src("ui/MainActivity.kt")
        assertTrue(main.contains("Route.IMAGEGEN -> com.iris.localai.ui.imagegen.ImageGenScreen("))
        assertTrue(main.contains("chat.attachGeneratedImage(path)"))
    }
}
