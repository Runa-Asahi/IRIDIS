package com.iris.localai.data

/**
 * Hook for tools that post images into chat (a future `generate_image`): an image is just a
 * markdown image in the reply, so it renders, opens full screen, saves and shares like any other.
 * [source] is an https URL, a workspace path (`images/cat.png`), content:// or a data: URI.
 */
data class ChatImage(val source: String, val alt: String = "") {
    fun markdown(): String = "![" + alt.replace(Regex("[\\[\\]\\n]"), " ").trim() + "](" + source.replace(" ", "%20").replace(")", "%29") + ")"

    companion object {
        /** An image written by a tool into the agent workspace, e.g. `fromWorkspace("images/a.png")`. */
        fun fromWorkspace(relPath: String, alt: String = ""): ChatImage = ChatImage(relPath.trimStart('/'), alt)
    }
}
