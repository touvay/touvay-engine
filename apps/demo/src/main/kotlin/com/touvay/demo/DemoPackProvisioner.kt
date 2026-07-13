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
        val files = File(root, "files")
        val prompts = File(files, "prompts")
        check(prompts.mkdirs() || prompts.isDirectory)
        decodeAsset(context, "manifest.pb.b64", File(root, "manifest.pb"))
        decodeAsset(context, "manifest.sig.b64", File(root, "manifest.sig"))
        decodeAsset(context, "prompt.pb.b64", File(prompts, "text-rewrite-v1.pb"))
        val weights = File(files, "weights.gguf")
        return DemoPackState(
            sourceRoot = root,
            weightsPresent = weights.isFile,
            weightsBytes = weights.takeIf(File::isFile)?.length() ?: 0L,
        )
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
