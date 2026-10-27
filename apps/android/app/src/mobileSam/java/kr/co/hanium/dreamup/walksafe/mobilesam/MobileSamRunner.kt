package kr.co.hanium.dreamup.walksafe.mobilesam

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kr.co.hanium.dreamup.walksafe.inference.RgbLetterboxPreprocessor
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Single worker only. Encoder embeddings are shared by all boxes in one photo. */
class MobileSamRunner(context: Context) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession

    init {
        val manifest = JSONObject(context.assets.open("models/mobilesam/mobile_sam_manifest.json")
            .bufferedReader().use { it.readText() }).getJSONObject("files")
        fun model(name: String): String {
            val expected = manifest.getJSONObject(name).getString("sha256")
            val directory = File(context.noBackupFilesDir, "mobilesam").apply { mkdirs() }
            val destination = File(directory, name)
            fun digest(file: File): String = MessageDigest.getInstance("SHA-256").let { md ->
                file.inputStream().use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            if (!destination.isFile || digest(destination) != expected) {
                val temporary = File(directory, "$name.tmp")
                context.assets.open("models/mobilesam/$name").use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                check(digest(temporary) == expected) { "MobileSAM 모델 파일 검증 실패" }
                check(temporary.renameTo(destination)) { "MobileSAM 모델 저장 실패" }
            }
            return destination.absolutePath
        }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            encoder = environment.createSession(model("mobile_sam_encoder.onnx"), options)
            try {
                decoder = environment.createSession(model("mobile_sam_decoder.onnx"), options)
            } catch (error: Exception) { encoder.close(); throw error }
        }
    }

    fun segment(bitmap: Bitmap, boxes: List<SamBox>): List<BooleanArray> {
        require(boxes.size <= 3) { "사진당 최대 3개 영역을 비교합니다." }
        if (boxes.isEmpty()) return emptyList()
        val size = MobileSamGeometry.SIZE
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rgb = FloatArray(size * size * 3)
        val buffer = ByteBuffer.allocateDirect(rgb.size * 4).order(ByteOrder.nativeOrder())
        RgbLetterboxPreprocessor.write(pixels, bitmap.width, bitmap.height, size, buffer, rgb)
        val (rw, rh) = MobileSamGeometry.resizedShape(bitmap.width, bitmap.height)
        val left = Math.rint((size - rw) / 2.0 - 0.1).toInt()
        val top = Math.rint((size - rh) / 2.0 - 0.1).toInt()
        val mean = floatArrayOf(123.675f, 116.28f, 103.53f)
        val std = floatArrayOf(58.395f, 57.12f, 57.375f)
        val input = buffer.asFloatBuffer()
        // Reuse the app's tested uint8 resize, moving center padding to SAM's bottom/right.
        for (c in 0..2) for (y in 0 until size) for (x in 0 until size) {
            val value = if (x < rw && y < rh) rgb[((y + top) * size + x + left) * 3 + c] * 255f else 114f
            input.put((value - mean[c]) / std[c])
        }
        input.rewind()
        OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { image ->
            encoder.run(mapOf("image" to image)).use { encoded ->
                val embedding = encoded[0] as OnnxTensor
                return boxes.map { box ->
                    OnnxTensor.createTensor(environment,
                        FloatBuffer.wrap(MobileSamGeometry.prompt(box, bitmap.width, bitmap.height)), longArrayOf(1, 4)).use { prompt ->
                        decoder.run(mapOf("image_embeddings" to embedding, "box" to prompt)).use { decoded ->
                            val logits = FloatArray(size * size)
                            (decoded[0] as OnnxTensor).floatBuffer.get(logits)
                            MobileSamGeometry.restoreMask(logits, bitmap.width, bitmap.height)
                        }
                    }
                }
            }
        }
    }

    override fun close() { decoder.close(); encoder.close() }
}
