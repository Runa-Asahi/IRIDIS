package com.iris.localai.imagegen

enum class Tier { LOW, MID, HIGH }
enum class Backend { QNN, MNN_CPU, HEXAGON }

data class RemoteFile(val url: String, val size: Long, val path: String)

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
    /** HF repo whose file list is resolved at download time (when [files] is empty). */
    val hfRepo: String? = null,
)

object ImageCatalog {
    val SD15_QNN = ImageModel(
        id = "sd15-qnn", tier = Tier.LOW, name = "Stable Diffusion 1.5 (NPU)", backend = Backend.QNN,
        files = listOf(
            RemoteFile(
                url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/stable_diffusion_v1_5/releases/v0.64.0/stable_diffusion_v1_5-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_for_galaxy.zip",
                size = 709_785_222L,
                path = "sd15-qnn/stable_diffusion_v1_5-qnn_context_binary-w8a16.zip",
            ),
        ),
        fixedSize = 512, defaultSteps = 20, defaultCfg = 7.5f,
    )

    // TODO: fetch file list from the HF repo at download time.
    val SD15_MNN = ImageModel(
        id = "sd15-mnn", tier = Tier.LOW, name = "Stable Diffusion 1.5 (CPU)", backend = Backend.MNN_CPU,
        files = emptyList(), fixedSize = 512, defaultSteps = 20, defaultCfg = 7.5f,
        hfRepo = "taobao-mnn/stable-diffusion-v1-5-mnn-general",
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
