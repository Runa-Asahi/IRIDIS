package com.iris.localai.imagegen

enum class Tier { LOW, MID, HIGH }
enum class Backend { QNN, MNN_CPU, HEXAGON }

/** A file inside a downloaded archive, after flattening its top-level folder; [path] is relative to the model folder. */
data class UnpackedFile(val path: String, val size: Long)

/**
 * One download. [path] is relative to the imagegen models root (`<model id>/…`). A Hugging Face url
 * (`https://huggingface.co/…`) is fetched from the chosen [com.iris.localai.data.DownloadSource] host.
 * An archive ([unpacked] non-empty) is extracted into the model folder and deleted afterwards.
 */
data class RemoteFile(val url: String, val size: Long, val path: String, val unpacked: List<UnpackedFile> = emptyList()) {
    val isArchive: Boolean get() = unpacked.isNotEmpty()
}

data class ImageModel(
    val id: String,
    val tier: Tier,
    val name: String,
    val backend: Backend,
    val files: List<RemoteFile>,
    val fixedSize: Int?,
    val defaultSteps: Int,
    val defaultCfg: Float,
    val needsHexagonRuntime: Boolean = false,
    /** HF repo the files come from (informational; when [files] is empty its file list would be resolved at download time). */
    val hfRepo: String? = null,
)

object ImageCatalog {
    /** taobao-mnn/stable-diffusion-v1-5-mnn-general, pinned to the revision checked 2026-10-22. */
    const val MNN_REPO = "taobao-mnn/stable-diffusion-v1-5-mnn-general"
    const val MNN_REV = "56c93a62ac9d6b5d18605632b85e25f87281f317"
    private fun mnnUrl(file: String) = "https://huggingface.co/$MNN_REPO/resolve/$MNN_REV/$file"

    /** CLIP BPE tokenizer (SD 1.5 text encoder = CLIP ViT-L/14), from the MNN repo. */
    private fun tokenizerFiles(modelId: String, sub: String) = listOf(
        RemoteFile(mnnUrl("vocab.json"), 1_059_962L, "$modelId/${sub}vocab.json"),
        RemoteFile(mnnUrl("merges.txt"), 524_619L, "$modelId/${sub}merges.txt"),
    )

    val SD15_QNN = ImageModel(
        id = "sd15-qnn", tier = Tier.LOW, name = "Stable Diffusion 1.5 (NPU)", backend = Backend.QNN,
        files = tokenizerFiles("sd15-qnn", "tokenizer/") + RemoteFile(
            url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/stable_diffusion_v1_5/releases/v0.64.0/stable_diffusion_v1_5-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_for_galaxy.zip",
            size = 709_785_222L,
            path = "sd15-qnn/.part/stable_diffusion_v1_5-qnn_context_binary-w8a16.zip",
            // zip central directory read 2026-10-22 (one top-level folder, flattened away)
            unpacked = listOf(
                UnpackedFile("text_encoder.bin", 163_058_576L),
                UnpackedFile("unet.bin", 880_144_176L),
                UnpackedFile("vae.bin", 60_452_704L),
                UnpackedFile("metadata.json", 4_134L),
            ),
        ),
        fixedSize = 512, defaultSteps = 20, defaultCfg = 7.5f,
    )

    /** Repo listing (api/models/…/tree/main) checked 2026-10-22; .gitattributes / README.md left out. */
    val SD15_MNN = ImageModel(
        id = "sd15-mnn", tier = Tier.LOW, name = "Stable Diffusion 1.5 (CPU)", backend = Backend.MNN_CPU,
        files = listOf(
            "alphas.txt" to 6_999L,
            "merges.txt" to 524_619L,
            "text_encoder.mnn" to 249_944L,
            "text_encoder.mnn.weight" to 238_120_368L,
            "unet.mnn" to 1_353_144L,
            "unet.mnn.weight" to 863_650_252L,
            "vae_decoder.mnn" to 183_960L,
            "vae_decoder.mnn.weight" to 49_639_636L,
            "vocab.json" to 1_059_962L,
        ).map { (f, size) -> RemoteFile(mnnUrl(f), size, "sd15-mnn/$f") },
        fixedSize = 512, defaultSteps = 20, defaultCfg = 7.5f,
        hfRepo = MNN_REPO,
    )

    // TODO: files once the Hexagon runtime ships.
    val FLUX2_KLEIN = ImageModel(
        id = "flux2-klein-4b", tier = Tier.MID, name = "FLUX.2 Klein 4B", backend = Backend.HEXAGON,
        files = emptyList(), fixedSize = null, defaultSteps = 4, defaultCfg = 1f, needsHexagonRuntime = true,
    )

    val Z_IMAGE_TURBO = ImageModel(
        id = "z-image-turbo", tier = Tier.HIGH, name = "Z-Image Turbo", backend = Backend.HEXAGON,
        files = emptyList(), fixedSize = null, defaultSteps = 8, defaultCfg = 1f, needsHexagonRuntime = true,
    )

    val all: List<ImageModel> = listOf(SD15_QNN, SD15_MNN, FLUX2_KLEIN, Z_IMAGE_TURBO)

    fun byId(id: String): ImageModel? = all.firstOrNull { it.id == id }
}
