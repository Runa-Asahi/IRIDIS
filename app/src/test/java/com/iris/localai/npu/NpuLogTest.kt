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
