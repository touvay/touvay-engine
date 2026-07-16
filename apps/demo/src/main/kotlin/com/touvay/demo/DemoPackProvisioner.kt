package com.touvay.demo

import android.content.Context
import android.util.Base64
import java.io.File

/** Copies only signed metadata and prompt data; model weights remain an offline local input. */
internal object DemoPackProvisioner {
    const val DIRECTORY = "touvay-demo-pack"
    const val EXPECTED_WEIGHTS_BYTES = 491_400_032L

    fun prepare(context: Context): DemoPackState {
        val external = requireNotNull(context.getExternalFilesDir(null))
        val root = File(external, DIRECTORY)
        prepareMetadata(root) { asset, destination -> decodeAsset(context, asset, destination) }
        val files = File(root, "files")
        val weights = File(files, "weights.gguf")
        return DemoPackState(
            sourceRoot = root,
            weightsPresent = weights.isFile,
            weightsBytes = weights.takeIf(File::isFile)?.length() ?: 0L,
        )
    }

    internal fun prepareMetadata(root: File, decode: (String, File) -> Unit) {
        val prompts = File(root, "files/prompts")
        check(prompts.mkdirs() || prompts.isDirectory)
        val assets = linkedMapOf(
            "manifest.pb.b64" to File(root, "manifest.pb"),
            "manifest.sig.b64" to File(root, "manifest.sig"),
            "prompt.pb.b64" to File(prompts, "text-rewrite-v1.pb"),
        )
        val existing = assets.values.count(File::isFile)
        check(existing == 0 || existing == assets.size) {
            "offline signed pack metadata is incomplete"
        }
        if (existing == 0) {
            assets.forEach(decode)
        }
    }

    private fun decodeAsset(context: Context, asset: String, destination: File) {
        val encoded = context.assets.open(asset).bufferedReader(Charsets.US_ASCII).use {
            it.readText().trim()
        }
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.outputStream().use { it.write(bytes) }
        check(temporary.renameTo(destination) || run {
            destination.delete() && temporary.renameTo(destination)
        })
    }
}

internal data class DemoPackState(
    val sourceRoot: File,
    val weightsPresent: Boolean,
    val weightsBytes: Long,
)
