package com.opencapture.openpocketcine.football

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spelers en bal in een live-beeld, met MediaPipe Object Detection (EfficientDet-Lite0, COCO-klassen
 * `person` en `sports ball`). Het model staat in `assets/football/` en wordt bij de build gedownload.
 *
 * Eén detectie tegelijk op een eigen thread; een beeld dat binnenkomt terwijl de vorige nog loopt,
 * wordt overgeslagen (het nieuwste beeld telt, niet een wachtrij).
 */
class PlayerDetector(private val context: Context, private val preferGpu: Boolean = false) {
    /** Minimale tussentijd tussen detecties; instelbaar vanuit het paneel. */
    @Volatile var minIntervalMs: Long = MIN_INTERVAL_MS
    // Eén achtergrondthread met lage prioriteit: de HEVC-decoder en de Vulkan-weergave van het
    // live-beeld moeten altijd voorgaan, anders hapert het beeld.
    private val executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "football-detector")
        }
    private val busy = AtomicBoolean(false)
    private var detector: ObjectDetector? = null
    private var initFailed = false
    private var lastStartedAt = 0L
    @Volatile var lastLatencyMs: Long = 0
        private set
    @Volatile var delegateLabel: String = "–"
        private set
    @Volatile var error: String? = null
        private set

    /** Laad het model alvast (bij het openen van het paneel), zodat START niet 10 s stil is. */
    fun warmUp() {
        if (detector != null || initFailed) return
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                ensureDetector()
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * Of een nieuw beeld nu zin heeft: detector vrij en de tussentijd voorbij. De tussentijd past
     * zich aan de gemeten rekentijd aan, zodat de detector hoogstens ~⅓ van de tijd bezig is en
     * het uitlezen van het beeld (een GPU→CPU-kopie die de weergave even stilzet) niet vaker dan
     * nodig gebeurt.
     */
    fun wantsFrame(): Boolean {
        if (initFailed) return false
        if (busy.get()) return false
        val interval = maxOf(minIntervalMs, lastLatencyMs * 3)
        return SystemClock.elapsedRealtime() - lastStartedAt >= interval
    }

    /**
     * NV21 (zoals het Vulkan-pad levert) → detectie. Kopieert de bytes meteen; de aanroeper mag zijn
     * buffer daarna hergebruiken. Het resultaat komt op de detector-thread.
     */
    fun detectNv21(nv21: ByteArray, width: Int, height: Int, done: (SceneObservation) -> Unit): Boolean {
        if (!wantsFrame()) return false
        val copy = nv21.copyOf()
        return submit(done) { nv21ToBitmap(copy, width, height) }
    }

    /** Bitmap (GLES-pad). Leest de pixels meteen uit; de aanroeper mag de bitmap daarna recyclen. */
    fun detectBitmap(bitmap: Bitmap, done: (SceneObservation) -> Unit): Boolean {
        if (!wantsFrame()) return false
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return false
        return submit(done) { copy }
    }

    private fun submit(done: (SceneObservation) -> Unit, makeBitmap: () -> Bitmap): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        lastStartedAt = SystemClock.elapsedRealtime()
        executor.execute {
            try {
                val d = ensureDetector() ?: return@execute
                val bitmap = makeBitmap()
                val t0 = SystemClock.elapsedRealtime()
                val result = d.detect(BitmapImageBuilder(bitmap).build())
                lastLatencyMs = SystemClock.elapsedRealtime() - t0
                val w = bitmap.width.toDouble()
                val h = bitmap.height.toDouble()
                val persons = ArrayList<Detection>()
                val balls = ArrayList<Detection>()
                for (det in result.detections()) {
                    val cat = det.categories().maxByOrNull { it.score() } ?: continue
                    val box = det.boundingBox()
                    val item =
                        Detection(
                            cx = ((box.left + box.right) / 2.0 / w).coerceIn(0.0, 1.0),
                            cy = ((box.top + box.bottom) / 2.0 / h).coerceIn(0.0, 1.0),
                            w = (box.width() / w).coerceIn(0.0, 1.0),
                            h = (box.height() / h).coerceIn(0.0, 1.0),
                            score = cat.score().toDouble(),
                        )
                    when (cat.categoryName()) {
                        "person" -> persons += item
                        "sports ball" -> balls += item
                    }
                }
                bitmap.recycle()
                done(SceneObservation(persons, balls, null, t0 / 1000.0))
            } catch (t: Throwable) {
                Log.w(TAG, "detectie mislukt", t)
                error = t.message
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    private fun ensureDetector(): ObjectDetector? {
        detector?.let { return it }
        if (initFailed) return null
        // Standaard CPU (XNNPACK, ~30–50 ms op een snelle telefoon, stabiel). GPU alleen op verzoek:
        // de GPU-delegate kan op sommige toestellen native crashen, en dat is niet op te vangen.
        val delegates = if (preferGpu) listOf(Delegate.GPU, Delegate.CPU) else listOf(Delegate.CPU)
        for (delegate in delegates) {
            try {
                val options =
                    ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(
                            BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(delegate).build(),
                        )
                        .setRunningMode(RunningMode.IMAGE)
                        .setScoreThreshold(SCORE_THRESHOLD)
                        .setMaxResults(MAX_RESULTS)
                        // Géén categorie-allowlist: de GPU-variant van MediaPipe's
                        // TensorsToDetections breekt daar hard op af ("2 vs. 90"). We filteren zelf op naam.
                        .build()
                val d = ObjectDetector.createFromOptions(context, options)
                detector = d
                delegateLabel = delegate.name
                // Pas ná het laden omlaag: het laden zelf mag even de volle CPU hebben.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                return d
            } catch (t: Throwable) {
                Log.w(TAG, "detector init ($delegate) mislukt", t)
                error = t.message
            }
        }
        initFailed = true
        return null
    }

    fun close() {
        executor.execute {
            runCatching { detector?.close() }
            detector = null
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "FootballDetector"
        const val MODEL_ASSET = "football/efficientdet_lite0.tflite"
        const val MIN_INTERVAL_MS = 160L
        const val SCORE_THRESHOLD = 0.28f
        const val MAX_RESULTS = 24

        /** NV21 → ARGB bitmap, rechttoe rechtaan (320×180 kost ~1 ms). */
        fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap {
            val pixels = IntArray(width * height)
            val frameSize = width * height
            var yp = 0
            for (j in 0 until height) {
                var uvp = frameSize + (j shr 1) * width
                var u = 0
                var v = 0
                for (i in 0 until width) {
                    val y = (0xff and nv21[yp].toInt()) - 16
                    val yy = if (y < 0) 0 else y
                    if ((i and 1) == 0) {
                        v = (0xff and nv21[uvp].toInt()) - 128
                        u = (0xff and nv21[uvp + 1].toInt()) - 128
                        uvp += 2
                    }
                    val y1192 = 1192 * yy
                    var r = y1192 + 1634 * v
                    var g = y1192 - 833 * v - 400 * u
                    var b = y1192 + 2066 * u
                    r = r.coerceIn(0, 262143)
                    g = g.coerceIn(0, 262143)
                    b = b.coerceIn(0, 262143)
                    pixels[yp] = -0x1000000 or ((r shl 6) and 0xff0000) or ((g shr 2) and 0xff00) or ((b shr 10) and 0xff)
                    yp++
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
