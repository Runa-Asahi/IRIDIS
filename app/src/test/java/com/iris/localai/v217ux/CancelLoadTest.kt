package com.iris.localai.v217ux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.1.7 #4 弹窗可取消: every model-loading surface carries 「取消」; tapping outside never cancels. */
class CancelLoadTest {
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    @Test fun pickerSheetLoadingBlockHasCancel() {
        val sheet = src("ui/chat/ModelPickerSheet.kt")
        val block = sheet.substringAfter("val loading = engine as? EngineState.Loading").substringBefore("SegmentedRow(")
        assertTrue(block.contains("IrisButton(\"取消\", onCancelLoad"))
        assertTrue("a tier reload is never cancelled", block.contains("l.applyingTier == null"))
        assertTrue(src("ui/chat/ChatScreen.kt").contains("onCancelLoad = vm::cancelLoad,"))
        // dismissing the sheet (outside tap / back) only closes it
        assertTrue(src("ui/chat/ChatScreen.kt").contains("onDismiss = { showPicker = false },"))
    }

    @Test fun cancelAlsoCancelsTheLoadCoroutine() {
        val vm = src("ui/chat/ChatViewModel.kt")
        val cancel = vm.substringAfter("fun cancelLoad() {").substringBefore("\n    }")
        assertTrue(cancel.contains("c.engine.cancelLoad()") && cancel.contains("loadJob?.cancel()"))
        assertTrue(vm.contains(".also { loadJob = it }"))
    }

    @Test fun benchmarkDialogOutsideTapDoesNotCancel() {
        val vm = src("ui/settings/SettingsViewModel.kt")
        assertTrue(vm.contains("fun dismissBenchmark() { if (benchJob?.isActive != true)"))
        assertFalse(vm.substringAfter("fun dismissBenchmark()").substringBefore("\n").contains("cancel"))
    }
}
