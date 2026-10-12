package com.photofind.embed

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class ModelMissingException(message: String) : IllegalStateException(message)

/**
 * EmbeddingGemma 2 (text + vision) running on-device through MediaPipe.
 *
 * Every call runs on one dedicated thread: the GPU accelerator is bound to the thread that
 * created it, and the engine itself is not thread safe.
 */
class Embedder(private val context: Context) {

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "embedder") }
        .asCoroutineDispatcher()
    private var engine: UniversalEmbedder? = null

    @Volatile
    var backend: String? = null
        private set

    fun modelLocations(): List<File> = listOfNotNull(
        File(context.filesDir, MODEL_NAME),
        context.getExternalFilesDir(null)?.let { File(it, MODEL_NAME) },
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            MODEL_NAME,
        ),
    )

    fun modelFile(): File? = modelLocations().firstOrNull { it.isFile && it.length() > 0 }

    suspend fun warmUp() {
        withContext(thread) { engine() }
    }

    suspend fun embedText(text: String): FloatArray = withContext(thread) {
        engine().embedText(text).embeddings().first().floatEmbedding()
    }

    suspend fun embedImage(bitmap: Bitmap): FloatArray = withContext(thread) {
        engine().embedImage(BitmapImageBuilder(bitmap).build()).embeddings().first().floatEmbedding()
    }

    private fun engine(): UniversalEmbedder = engine ?: open().also { engine = it }

    private fun open(): UniversalEmbedder {
        val file = modelFile() ?: throw ModelMissingException(
            "Model $MODEL_NAME not found. Looked in:\n" +
                modelLocations().joinToString("\n") { it.absolutePath },
        )
        return try {
            create(file, Delegate.GPU)
        } catch (e: Exception) {
            Log.w(TAG, "GPU embedder failed, falling back to CPU", e)
            create(file, Delegate.CPU)
        }
    }

    private fun create(file: File, delegate: Delegate): UniversalEmbedder {
        val startedAt = System.currentTimeMillis()
        val created = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val options = UniversalEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetFileDescriptor(pfd.fd)
                        .setDelegate(delegate)
                        .build(),
                )
                .setCacheDir(context.cacheDir.absolutePath)
                .setL2Normalize(true)
                .build()
            UniversalEmbedder.createFromOptions(context, options)
        }
        backend = delegate.name
        Log.i(TAG, "Embedder ready on $delegate in ${System.currentTimeMillis() - startedAt} ms")
        return created
    }

    companion object {
        private const val TAG = "Embedder"
        const val MODEL_NAME = "embeddinggemma-2-text-vision-440m.litertlm"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/" +
                "resolve/main/$MODEL_NAME"

        /** EmbeddingGemma 2's retrieval-query prompt; photos are embedded without a prompt. */
        const val QUERY_PREFIX = "task: search result | query: "
    }
}
