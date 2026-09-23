package com.example.childsafetyimpl.platform.browserimages

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bundled NSFW model: `saved_model.tflite` from GantMan's nsfw_model release 1.2.0
 * (MobileNetV2, 1.4 width, 224×224, float32, about 92% validation accuracy; MIT, licence in
 * `nsfw_model_LICENSE.txt` beside it). Five softmax outputs: drawings, hentai, neutral, porn,
 * sexy. Runs on the CPU through XNNPACK, entirely on device.
 */
class TfliteNsfwClassifier private constructor(private val interpreter: Interpreter) : NsfwClassifier {

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * ModelInput.SIDE * ModelInput.SIDE * 3)
        .order(ByteOrder.nativeOrder())
    private val output = arrayOf(FloatArray(LABELS))

    override fun classify(input: FloatArray): NsfwScores {
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(input)
        interpreter.run(inputBuffer, output)
        return scoresFrom(output[0])
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_ASSET = "nsfw_mobilenet_v2_140_224.tflite"
        private const val LABELS = 5
        private const val THREADS = 4

        /** Null when the model cannot be loaded; the caller then treats photos as unscreenable. */
        fun load(context: Context): TfliteNsfwClassifier? = runCatching {
            // Read into a direct buffer rather than memory-mapping, so it works whether or not
            // the asset was stored compressed.
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
            // A model that reads or writes anything else would be fed garbage or misread; better
            // to have no screening, which keeps every photo covered.
            val fits = interpreter.getInputTensor(0).let {
                it.dataType() == DataType.FLOAT32 && it.shape().contentEquals(INPUT_SHAPE)
            } && interpreter.getOutputTensor(0).let {
                it.dataType() == DataType.FLOAT32 && it.shape().contentEquals(OUTPUT_SHAPE)
            }
            if (!fits) {
                interpreter.close()
                return null
            }
            TfliteNsfwClassifier(interpreter)
        }.getOrNull()

        private val INPUT_SHAPE = intArrayOf(1, ModelInput.SIDE, ModelInput.SIDE, 3)
        private val OUTPUT_SHAPE = intArrayOf(1, LABELS)

        /** Model output, in the model's label order, as scores. */
        fun scoresFrom(output: FloatArray): NsfwScores {
            require(output.size == LABELS) { "expected $LABELS outputs, got ${output.size}" }
            return NsfwScores(
                drawings = output[0],
                hentai = output[1],
                neutral = output[2],
                porn = output[3],
                sexy = output[4],
            )
        }
    }
}
