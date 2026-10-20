package com.iris.localai.npu

import com.iris.localai.engine.NpuLog
import org.junit.Assert.assertEquals
import org.junit.Test

class NpuLogTest {
    @Test fun keepsOnlyNpuErrorsAndWarnings() {
        val lines = listOf(
            "I/IrisLoad( 123): loading",
            "E/QNN     ( 123): QnnDsp <E> Failed to allocate shared memory",
            "D/litert  ( 123): debug noise",
            "W/adsprpc ( 123): remote_handle open failed",
            "E/QNN     ( 123): QnnDsp <E> Failed to allocate shared memory",
            "I/qnn     ( 123): graph error code 1002",
        )
        assertEquals(
            "E/QNN     ( 123): QnnDsp <E> Failed to allocate shared memory\nW/adsprpc ( 123): remote_handle open failed\nI/qnn     ( 123): graph error code 1002",
            NpuLog.relevant(lines, 10),
        )
        assertEquals("I/qnn     ( 123): graph error code 1002", NpuLog.relevant(lines, 1))
    }
}

class NpuRetryTest {
    @Test fun retriesSmallerKvOnlyBelowCompiledSize() {
        assertEquals(listOf(null, 2048, 1536), com.iris.localai.engine.NpuRetry.kvAttempts(null))
        assertEquals(listOf(4096, 2048, 1536), com.iris.localai.engine.NpuRetry.kvAttempts(4096))
        assertEquals(listOf(1280), com.iris.localai.engine.NpuRetry.kvAttempts(1280))
    }
    @Test fun allocFailureDetected() {
        org.junit.Assert.assertTrue(com.iris.localai.engine.NpuRetry.isAllocFailure("Inference warmup run for Text Decoder (prefill) failed"))
        org.junit.Assert.assertFalse(com.iris.localai.engine.NpuRetry.isAllocFailure("QNN System library version mismatched"))
    }
    @Test fun fallbackKeepsErrors() {
        assertEquals("E/foo( 1): boom", com.iris.localai.engine.NpuLog.fallback(listOf("I/foo( 1): ok", "E/foo( 1): boom"), 5))
    }
}
