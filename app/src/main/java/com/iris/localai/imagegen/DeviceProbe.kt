package com.iris.localai.imagegen

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Fills [ProbeFacts] for 「生图自检」 from what Java can see: the SoC name, available memory, the QNN
 * runtime folder installed by QualcommNpuRuntime (`filesDir/npu/qnn-<ver>-v<arch>`, full flavor) and the
 * CDSP device nodes. QNN backend init and unsigned-PD support need native code: false until it ships.
 */
object DeviceProbe {
    const val NATIVE_PENDING = "原生探测待接入"

    /** Snapdragon SoC model → Hexagon HTP architecture version. */
    val HTP_ARCH: Map<String, Int> = linkedMapOf(
        "SM8750" to 79, // 8 Elite
        "SM8650" to 75, // 8 Gen 3
        "SM8550" to 73, // 8 Gen 2
    )

    /** HTP arch for [soc] ("SM8750", "sm8750-ab", "SM8650P" …), null when unknown. */
    fun htpArch(soc: String?): Int? {
        val s = soc?.trim()?.uppercase() ?: return null
        return HTP_ARCH.entries.firstOrNull { s.startsWith(it.key) }?.value
    }

    /** A non-empty `qnn-*` folder under `<filesDir>/npu` (QualcommNpuRuntime.dir). */
    fun qnnRuntimePresent(filesDir: File): Boolean =
        File(filesDir, "npu").listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("qnn-") && !it.listFiles().isNullOrEmpty() }

    fun cdspPresent(): Boolean = listOf("/dev/adsprpc-smd", "/dev/cdsprpc-smd", "/vendor/lib64/libcdsprpc.so")
        .any { runCatching { File(it).exists() }.getOrDefault(false) }

    fun socModel(): String = runCatching { Build.SOC_MODEL }.getOrNull()?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        ?: Build.HARDWARE.orEmpty().ifBlank { "未知" }

    fun freeRamMb(context: Context): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        mi.availMem / (1024L * 1024L)
    }.getOrDefault(0L)

    fun probe(context: Context, downloaded: Set<String>): ProbeFacts {
        val soc = socModel()
        return ProbeFacts(
            soc = soc,
            htpArch = htpArch(soc),
            qnnRuntimePresent = qnnRuntimePresent(context.filesDir),
            qnnBackendOk = false,
            cdspPresent = cdspPresent(),
            unsignedPd = false,
            freeRamMb = freeRamMb(context),
            downloaded = downloaded,
        )
    }

    /** [SelfCheck.report] plus the line saying which facts are not probed yet. */
    fun report(facts: ProbeFacts): String =
        SelfCheck.report(facts, SelfCheck.verdicts(facts)) + "\n注：QNN 后端 / Unsigned PD：$NATIVE_PENDING"
}
