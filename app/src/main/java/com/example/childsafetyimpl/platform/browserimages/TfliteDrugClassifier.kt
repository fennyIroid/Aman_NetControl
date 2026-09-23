package com.example.childsafetyimpl.platform.browserimages

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bundled drug check: Apple's MobileCLIP-S0 image encoder (`mobileclip_s0_image_fp16.tflite`,
 * converted from Xenova/mobileclip_s0's ONNX export with onnx2tf, float16 weights; Apple sample
 * code licence in `mobileclip_LICENSE.txt` beside it) and the prompt embeddings in
 * `drug_prompts.bin`. Runs on the CPU through XNNPACK, entirely on device.
 *
 * Checked against the ONNX original on 87 photos: cosine similarity of the embeddings 0.99997 or
 * better, and the same drug photos caught and others flagged at the default threshold.
 */
class TfliteDrugClassifier private constructor(
    private val interpreter: Interpreter,
    private val prompts: DrugPrompts,
) : DrugClassifier {

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * ModelInput.CLIP_SIDE * ModelInput.CLIP_SIDE * 3)
        .order(ByteOrder.nativeOrder())
    private val output = arrayOf(FloatArray(prompts.dimension))

    override fun drugScore(input: FloatArray): Float {
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(input)
        interpreter.run(inputBuffer, output)
        return prompts.drugShare(output[0])
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_ASSET = "mobileclip_s0_image_fp16.tflite"
        const val PROMPTS_ASSET = "drug_prompts.bin"
        private const val THREADS = 4

        /** Null when the model or its prompts cannot be loaded; drug searches then stay covered. */
        fun load(context: Context): TfliteDrugClassifier? = runCatching {
            val prompts = DrugPrompts.parse(context.assets.open(PROMPTS_ASSET).use { it.readBytes() })
            // Read into a direct buffer rather than memory-mapping, so it works whether or not
            // the asset was stored compressed.
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
            val fits = interpreter.getInputTensor(0).let {
                it.dataType() == DataType.FLOAT32 && it.shape().contentEquals(INPUT_SHAPE)
            } && interpreter.getOutputTensor(0).let {
                it.dataType() == DataType.FLOAT32 && it.shape().contentEquals(intArrayOf(1, prompts.dimension))
            }
            if (!fits) {
                interpreter.close()
                return null
            }
            TfliteDrugClassifier(interpreter, prompts)
        }.getOrNull()

        private val INPUT_SHAPE = intArrayOf(1, ModelInput.CLIP_SIDE, ModelInput.CLIP_SIDE, 3)
    }
}
