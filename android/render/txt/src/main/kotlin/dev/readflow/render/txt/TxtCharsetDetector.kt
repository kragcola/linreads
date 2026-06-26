package dev.readflow.render.txt

import java.io.File
import java.nio.charset.Charset
import org.mozilla.universalchardet.UniversalDetector

internal enum class TxtCharsetDetectionSource {
    Bom,
    Detector,
    Fallback,
}

internal data class TxtCharsetDetection(
    val charset: Charset,
    val source: TxtCharsetDetectionSource,
    val detectedName: String? = null,
    val fallbackReason: String? = null,
)

internal object TxtCharsetDetector {
    fun detect(file: File, sampleBytes: Int = DEFAULT_SAMPLE_BYTES): TxtCharsetDetection {
        require(sampleBytes > 0)
        if (file.length() == 0L) return utf8Fallback("empty file")
        val sample = file.readSample(sampleBytes)
        if (sample.isEmpty()) return utf8Fallback("empty sample")

        UniversalDetector.detectCharsetFromBOM(sample)?.toSupportedCharset()?.let { charset ->
            return TxtCharsetDetection(
                charset = charset,
                source = TxtCharsetDetectionSource.Bom,
                detectedName = charset.name(),
            )
        }

        val detector = UniversalDetector()
        detector.handleData(sample, 0, sample.size)
        detector.dataEnd()
        val detectedName = detector.detectedCharset
        val detected = detectedName?.toSupportedCharset()
        return if (detected != null) {
            TxtCharsetDetection(
                charset = detected,
                source = TxtCharsetDetectionSource.Detector,
                detectedName = detectedName,
            )
        } else {
            utf8Fallback("juniversalchardet returned ${detectedName ?: "no charset"}")
        }
    }

    private fun utf8Fallback(reason: String): TxtCharsetDetection =
        TxtCharsetDetection(
            charset = Charsets.UTF_8,
            source = TxtCharsetDetectionSource.Fallback,
            detectedName = null,
            fallbackReason = reason,
        )

    private const val DEFAULT_SAMPLE_BYTES = 256 * 1024
}

private fun File.readSample(sampleBytes: Int): ByteArray {
    val targetSize = length().coerceAtMost(sampleBytes.toLong()).toInt()
    val buffer = ByteArray(targetSize)
    var total = 0
    inputStream().use { input ->
        while (total < targetSize) {
            val read = input.read(buffer, total, targetSize - total)
            if (read < 0) break
            total += read
        }
    }
    return if (total == buffer.size) buffer else buffer.copyOf(total)
}

private fun String.toSupportedCharset(): Charset? =
    runCatching { Charset.forName(this) }.getOrNull()
