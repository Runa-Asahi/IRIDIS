package com.iris.localai.imagegen

import android.content.Context
import com.iris.localai.data.ModelDirs
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure java.io rules of 生图 storage (unit-tested on temp folders):
 * weights in `Download/IRIDIS/models/imagegen/<id>/`, results in `<workspace>/images/IMG_yyyyMMdd_HHmmss.png`.
 */
object ImageGenFiles {
    const val MODELS_SUB = "${ModelDirs.SUB}/imagegen"
    const val IMAGES_SUB = "images"
    const val PREFIX = "IMG_"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

    fun outputName(time: LocalDateTime): String = PREFIX + STAMP.format(time) + ".png"

    /** A free `images/IMG_<stamp>.png` under [workspaceRoot] (`_2`, `_3` … when two land in one second). */
    fun outputFile(workspaceRoot: File, time: LocalDateTime): File {
        val dir = File(workspaceRoot, IMAGES_SUB)
        val base = outputName(time).removeSuffix(".png")
        var f = File(dir, "$base.png")
        var n = 2
        while (f.exists()) f = File(dir, "${base}_${n++}.png")
        return f
    }

    /** Markdown reference to [file] relative to the workspace (`images/IMG_….png`). */
    fun markdownRef(file: File): String = "![](${IMAGES_SUB}/${file.name})"

    fun modelDir(imagegenRoot: File, model: ImageModel): File = File(imagegenRoot, model.id)

    /**
     * Downloaded = every listed file present with its size; for a repo resolved at download time
     * ([ImageModel.files] empty, [ImageModel.hfRepo] set) a non-empty folder without `.part` leftovers.
     * Models waiting for the Hexagon runtime have no files yet, so never count as downloaded.
     */
    fun isDownloaded(imagegenRoot: File, model: ImageModel): Boolean {
        if (model.files.isNotEmpty()) return model.files.all { File(imagegenRoot, it.path).let { f -> f.isFile && f.length() == it.size } }
        if (model.hfRepo == null) return false
        val files = modelDir(imagegenRoot, model).walkTopDown().filter { it.isFile }.toList()
        return files.isNotEmpty() && files.none { it.name.endsWith(".part") }
    }

    /** Total download size, null when the file list is only known at download time. */
    fun sizeBytes(model: ImageModel): Long? = model.files.takeIf { it.isNotEmpty() }?.sumOf { it.size }

    /** A1111-style "parameters" text embedded in the PNG. */
    fun parametersText(req: ImageRequest, model: ImageModel): String = buildString {
        append(req.prompt.trim())
        if (req.effectiveNegative.isNotBlank()) append("\nNegative prompt: ").append(req.effectiveNegative.trim())
        append("\nSteps: ${req.steps}, Sampler: Euler, CFG scale: ${req.cfg}, Seed: ${req.seed}, Size: ${req.width}x${req.height}, Model: ${model.id}")
        if (req.initImagePath != null) append(", Denoising strength: ${req.strength}")
    }

    /** Generated images under [workspaceRoot]/images, newest first. */
    fun history(workspaceRoot: File): List<File> =
        File(workspaceRoot, IMAGES_SUB).listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".png") }
            .sortedByDescending { it.name } // the stamp sorts by time; "IMG_x_2.png" > "IMG_x.png"

    /** Writes [png] with the parameters chunk into [file] (via `.part`, then rename). */
    fun write(file: File, png: ByteArray, parameters: String): File {
        file.parentFile?.mkdirs()
        val bytes = runCatching { PngParams.insert(png, parameters) }.getOrDefault(png)
        val part = File(file.path + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) { part.delete(); throw java.io.IOException("无法写入 ${file.path}") }
        return file
    }
}

/** 生图 state on disk: catalog, which models are downloaded, results and their history. */
class ImageGenRepository(private val context: Context) {
    val catalog: List<ImageModel> = ImageCatalog.all

    private val privateModels: File by lazy {
        File(context.getExternalFilesDir(ModelDirs.SUB) ?: File(context.filesDir, ModelDirs.SUB), "imagegen")
    }

    /** `Download/IRIDIS/models/imagegen` with 全部文件访问, else the app-specific folder. */
    fun modelsLocation(): ModelDirs.Location = ModelDirs.resolve(
        allFilesAccess = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false),
        storageState = runCatching { android.os.Environment.getExternalStorageState() }.getOrNull(),
        downloadsDir = runCatching { android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
        privateDir = privateModels,
        sub = ImageGenFiles.MODELS_SUB,
    )

    /** Ids of the models found complete in the shared or the private folder. */
    fun downloaded(): Set<String> {
        val dirs = modelsLocation().listDirs
        return catalog.filter { m -> dirs.any { ImageGenFiles.isDownloaded(it, m) } }.map { it.id }.toSet()
    }

    private val agent get() = (context.applicationContext as com.iris.localai.IrisApp).container.agent

    /** The agent workspace (Download/IRIDIS/workspace or the private one). */
    fun workspaceRoot(): File = agent.workspace.value.root

    /** Saves the result as `images/IMG_….png`; falls back to the private workspace when Download is not writable. */
    fun save(png: ByteArray, req: ImageRequest, model: ImageModel, time: LocalDateTime = LocalDateTime.now()): File {
        val params = ImageGenFiles.parametersText(req, model)
        return runCatching { ImageGenFiles.write(ImageGenFiles.outputFile(workspaceRoot(), time), png, params) }
            .getOrElse { ImageGenFiles.write(ImageGenFiles.outputFile(agent.privateRoot, time), png, params) }
    }

    fun history(): List<File> {
        val roots = listOf(workspaceRoot(), agent.privateRoot).distinctBy { it.absolutePath }
        return roots.flatMap { ImageGenFiles.history(it) }.distinctBy { it.absolutePath }
            .sortedByDescending { it.name }
    }
}
