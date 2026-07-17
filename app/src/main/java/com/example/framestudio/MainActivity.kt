package com.example.framestudio

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnPickVideo: Button
    private lateinit var etFps1: EditText
    private lateinit var btnExtract: Button
    private lateinit var progress1: ProgressBar
    private lateinit var tvStatus1: TextView

    private lateinit var btnPickImages: Button
    private lateinit var btnPickAudio: Button
    private lateinit var etFps2: EditText
    private lateinit var btnBuildVideo: Button
    private lateinit var progress2: ProgressBar
    private lateinit var tvStatus2: TextView

    private var videoUri: Uri? = null
    private var imageUris: List<Uri> = emptyList()
    private var audioUri: Uri? = null

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                videoUri = uri
                tvStatus1.text = "Video tanlandi"
            }
        }

    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                imageUris = uris.sortedBy { getDisplayName(it) }
                tvStatus2.text = "${imageUris.size} ta rasm tanlandi"
            }
        }

    private val pickAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                audioUri = uri
                tvStatus2.text = "Audio tanlandi (${imageUris.size} ta rasm bilan birga)"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPickVideo = findViewById(R.id.btnPickVideo)
        etFps1 = findViewById(R.id.etFps1)
        btnExtract = findViewById(R.id.btnExtract)
        progress1 = findViewById(R.id.progress1)
        tvStatus1 = findViewById(R.id.tvStatus1)

        btnPickImages = findViewById(R.id.btnPickImages)
        btnPickAudio = findViewById(R.id.btnPickAudio)
        etFps2 = findViewById(R.id.etFps2)
        btnBuildVideo = findViewById(R.id.btnBuildVideo)
        progress2 = findViewById(R.id.progress2)
        tvStatus2 = findViewById(R.id.tvStatus2)

        btnPickVideo.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*"))
        }

        btnExtract.setOnClickListener {
            val uri = videoUri
            if (uri == null) {
                Toast.makeText(this, "Avval video tanlang", Toast.LENGTH_SHORT).show()
            } else {
                val fps = etFps1.text.toString().toIntOrNull() ?: 30
                extractFrames(uri, fps)
            }
        }

        btnPickImages.setOnClickListener {
            pickImagesLauncher.launch(arrayOf("image/*"))
        }

        btnPickAudio.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }

        btnBuildVideo.setOnClickListener {
            if (imageUris.isEmpty()) {
                Toast.makeText(this, "Avval rasmlarni tanlang", Toast.LENGTH_SHORT).show()
            } else {
                val fps = etFps2.text.toString().toIntOrNull() ?: 30
                buildVideo(imageUris, audioUri, fps)
            }
        }
    }

    // ============ VIDEO -> FRAMES ============

    private fun extractFrames(uri: Uri, fps: Int) {
        btnExtract.isEnabled = false
        progress1.progress = 0
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                val durationUs = durationMs * 1000L
                val requestedFrames = ((durationMs / 1000.0) * fps).toInt().coerceAtLeast(1)

                val outDir = File(getExternalFilesDir(null), "frames_${System.currentTimeMillis()}")
                outDir.mkdirs()

                var savedCount = 0
                for (i in 0 until requestedFrames) {
                    val timeUs = ((i * 1_000_000L) / fps).coerceAtMost((durationUs - 1000L).coerceAtLeast(0L))
                    var bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bmp == null) {
                        bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    if (bmp != null) {
                        val f = File(outDir, "frame_%05d.png".format(savedCount))
                        FileOutputStream(f).use { fos -> bmp.compress(Bitmap.CompressFormat.PNG, 100, fos) }
                        savedCount++
                    }
                    val pct = ((i + 1) * 100 / requestedFrames)
                    runOnUiThread {
                        progress1.progress = pct
                        tvStatus1.text = "Kadr ${i + 1}/$requestedFrames"
                    }
                }
                retriever.release()

                val zipFile = File(getExternalFilesDir(null), "frames_${System.currentTimeMillis()}.zip")
                zipFolder(outDir, zipFile)

                runOnUiThread {
                    tvStatus1.text = "Tayyor! $savedCount ta kadr saqlandi"
                    btnExtract.isEnabled = true
                    shareFile(zipFile, "application/zip")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus1.text = "Xatolik: ${e.message}"
                    btnExtract.isEnabled = true
                }
            }
        }.start()
    }

    private fun zipFolder(folder: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            folder.listFiles()?.sortedBy { it.name }?.forEach { file ->
                zos.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    // ============ FRAMES -> VIDEO ============

    private fun buildVideo(images: List<Uri>, audio: Uri?, fps: Int) {
        btnBuildVideo.isEnabled = false
        progress2.progress = 0
        Thread {
            try {
                val outFile = File(getExternalFilesDir(null), "output_${System.currentTimeMillis()}.mp4")
                encodeImagesToVideo(images, audio, fps, outFile) { pct, status ->
                    runOnUiThread {
                        progress2.progress = pct
                        tvStatus2.text = status
                    }
                }
                runOnUiThread {
                    tvStatus2.text = "Tayyor!"
                    btnBuildVideo.isEnabled = true
                    shareFile(outFile, "video/mp4")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus2.text = "Xatolik: ${e.message}"
                    btnBuildVideo.isEnabled = true
                }
            }
        }.start()
    }

    private fun encodeImagesToVideo(
        images: List<Uri>,
        audioUriParam: Uri?,
        fps: Int,
        outFile: File,
        onProgress: (Int, String) -> Unit
    ) {
        val firstBmp = loadBitmap(images[0])
        val width = if (firstBmp.width % 2 == 0) firstBmp.width else firstBmp.width - 1
        val height = if (firstBmp.height % 2 == 0) firstBmp.height else firstBmp.height - 1

        val mimeType = "video/avc"
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false

        var audioExtractor: MediaExtractor? = null
        var audioFormat: MediaFormat? = null

        if (audioUriParam != null) {
            val extractor = MediaExtractor()
            extractor.setDataSource(this, audioUriParam, null)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioFormat = f
                    extractor.selectTrack(i)
                    break
                }
            }
            audioExtractor = extractor
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val frameDurationUs = 1_000_000L / fps

        fun drainEncoder(endOfStream: Boolean) {
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null) {
                            audioTrackIndex = muxer.addTrack(audioFormat!!)
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return
                        }
                    }
                }
            }
        }

        val decodedBitmaps = ArrayList<Bitmap>(images.size)
        decodedBitmaps.add(firstBmp)
        for (i in 1 until images.size) {
            decodedBitmaps.add(loadBitmap(images[i]))
            onProgress((i * 10 / images.size), "Kadrlar tayyorlanmoqda ${i + 1}/${images.size}")
        }

        for (i in decodedBitmaps.indices) {
            val bmp = decodedBitmaps[i]
            val scaled = if (bmp.width != width || bmp.height != height)
                Bitmap.createScaledBitmap(bmp, width, height, true) else bmp

            var inputIndex = -1
            while (inputIndex < 0) {
                inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex < 0) drainEncoder(false)
            }
            val image = encoder.getInputImage(inputIndex)
            if (image != null) {
                writeBitmapToImage(scaled, image)
            }
            val frameSize = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)
            encoder.queueInputBuffer(inputIndex, 0, frameSize, i * frameDurationUs, 0)

            drainEncoder(false)
            onProgress(10 + ((i + 1) * 70 / decodedBitmaps.size), "Kadr yozilmoqda ${i + 1}/${decodedBitmaps.size}")
        }

        var eosIndex = -1
        while (eosIndex < 0) {
            eosIndex = encoder.dequeueInputBuffer(10_000)
            if (eosIndex < 0) drainEncoder(false)
        }
        encoder.queueInputBuffer(
            eosIndex, 0, 0,
            decodedBitmaps.size.toLong() * frameDurationUs,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        drainEncoder(true)

        if (audioExtractor != null && audioTrackIndex >= 0) {
            onProgress(85, "Ovoz qo'shilmoqda…")
            val videoDurationUs = decodedBitmaps.size.toLong() * frameDurationUs
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val audioBufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTime = audioExtractor.sampleTime
                if (sampleTime > videoDurationUs) break
                audioBufferInfo.offset = 0
                audioBufferInfo.size = sampleSize
                audioBufferInfo.presentationTimeUs = sampleTime
                audioBufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(audioTrackIndex, buffer, audioBufferInfo)
                audioExtractor.advance()
            }
            audioExtractor.release()
        }

        onProgress(100, "Yakunlanmoqda…")
        muxer.stop()
        muxer.release()
        encoder.stop()
        encoder.release()
    }

    private fun writeBitmapToImage(bitmap: Bitmap, image: android.media.Image) {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride

        for (j in 0 until height) {
            for (i in 0 until width) {
                val argb = pixels[j * width + i]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF

                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(j * yRowStride + i * yPixelStride, yVal.coerceIn(0, 255).toByte())

                if (j % 2 == 0 && i % 2 == 0) {
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uRow = j / 2
                    val uCol = i / 2
                    uBuffer.put(uRow * uRowStride + uCol * uPixelStride, uVal.coerceIn(0, 255).toByte())
                    vBuffer.put(uRow * vRowStride + uCol * vPixelStride, vVal.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        contentResolver.openInputStream(uri).use { input ->
            return BitmapFactory.decodeStream(input)
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: ""
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Saqlash / Ulashish"))
    }
}package com.example.framestudio

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnPickVideo: Button
    private lateinit var etFps1: EditText
    private lateinit var btnExtract: Button
    private lateinit var progress1: ProgressBar
    private lateinit var tvStatus1: TextView

    private lateinit var btnPickImages: Button
    private lateinit var btnPickAudio: Button
    private lateinit var etFps2: EditText
    private lateinit var btnBuildVideo: Button
    private lateinit var progress2: ProgressBar
    private lateinit var tvStatus2: TextView

    private var videoUri: Uri? = null
    private var imageUris: List<Uri> = emptyList()
    private var audioUri: Uri? = null

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                videoUri = uri
                tvStatus1.text = "Video tanlandi"
            }
        }

    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                imageUris = uris.sortedBy { getDisplayName(it) }
                tvStatus2.text = "${imageUris.size} ta rasm tanlandi"
            }
        }

    private val pickAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                audioUri = uri
                tvStatus2.text = "Audio tanlandi (${imageUris.size} ta rasm bilan birga)"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPickVideo = findViewById(R.id.btnPickVideo)
        etFps1 = findViewById(R.id.etFps1)
        btnExtract = findViewById(R.id.btnExtract)
        progress1 = findViewById(R.id.progress1)
        tvStatus1 = findViewById(R.id.tvStatus1)

        btnPickImages = findViewById(R.id.btnPickImages)
        btnPickAudio = findViewById(R.id.btnPickAudio)
        etFps2 = findViewById(R.id.etFps2)
        btnBuildVideo = findViewById(R.id.btnBuildVideo)
        progress2 = findViewById(R.id.progress2)
        tvStatus2 = findViewById(R.id.tvStatus2)

        btnPickVideo.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*"))
        }

        btnExtract.setOnClickListener {
            val uri = videoUri
            if (uri == null) {
                Toast.makeText(this, "Avval video tanlang", Toast.LENGTH_SHORT).show()
            } else {
                val fps = etFps1.text.toString().toIntOrNull() ?: 30
                extractFrames(uri, fps)
            }
        }

        btnPickImages.setOnClickListener {
            pickImagesLauncher.launch(arrayOf("image/*"))
        }

        btnPickAudio.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }

        btnBuildVideo.setOnClickListener {
            if (imageUris.isEmpty()) {
                Toast.makeText(this, "Avval rasmlarni tanlang", Toast.LENGTH_SHORT).show()
            } else {
                val fps = etFps2.text.toString().toIntOrNull() ?: 30
                buildVideo(imageUris, audioUri, fps)
            }
        }
    }

    // ============ VIDEO -> FRAMES ============

    private fun extractFrames(uri: Uri, fps: Int) {
        btnExtract.isEnabled = false
        progress1.progress = 0
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                val durationUs = durationMs * 1000L
                val requestedFrames = ((durationMs / 1000.0) * fps).toInt().coerceAtLeast(1)

                val outDir = File(getExternalFilesDir(null), "frames_${System.currentTimeMillis()}")
                outDir.mkdirs()

                var savedCount = 0
                for (i in 0 until requestedFrames) {
                    val timeUs = ((i * 1_000_000L) / fps).coerceAtMost((durationUs - 1000L).coerceAtLeast(0L))
                    var bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bmp == null) {
                        bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    if (bmp != null) {
                        val f = File(outDir, "frame_%05d.png".format(savedCount))
                        FileOutputStream(f).use { fos -> bmp.compress(Bitmap.CompressFormat.PNG, 100, fos) }
                        savedCount++
                    }
                    val pct = ((i + 1) * 100 / requestedFrames)
                    runOnUiThread {
                        progress1.progress = pct
                        tvStatus1.text = "Kadr ${i + 1}/$requestedFrames"
                    }
                }
                retriever.release()

                val zipFile = File(getExternalFilesDir(null), "frames_${System.currentTimeMillis()}.zip")
                zipFolder(outDir, zipFile)

                runOnUiThread {
                    tvStatus1.text = "Tayyor! $savedCount ta kadr saqlandi"
                    btnExtract.isEnabled = true
                    shareFile(zipFile, "application/zip")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus1.text = "Xatolik: ${e.message}"
                    btnExtract.isEnabled = true
                }
            }
        }.start()
    }

    private fun zipFolder(folder: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            folder.listFiles()?.sortedBy { it.name }?.forEach { file ->
                zos.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    // ============ FRAMES -> VIDEO ============

    private fun buildVideo(images: List<Uri>, audio: Uri?, fps: Int) {
        btnBuildVideo.isEnabled = false
        progress2.progress = 0
        Thread {
            try {
                val outFile = File(getExternalFilesDir(null), "output_${System.currentTimeMillis()}.mp4")
                encodeImagesToVideo(images, audio, fps, outFile) { pct, status ->
                    runOnUiThread {
                        progress2.progress = pct
                        tvStatus2.text = status
                    }
                }
                runOnUiThread {
                    tvStatus2.text = "Tayyor!"
                    btnBuildVideo.isEnabled = true
                    shareFile(outFile, "video/mp4")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus2.text = "Xatolik: ${e.message}"
                    btnBuildVideo.isEnabled = true
                }
            }
        }.start()
    }

    private fun encodeImagesToVideo(
        images: List<Uri>,
        audioUriParam: Uri?,
        fps: Int,
        outFile: File,
        onProgress: (Int, String) -> Unit
    ) {
        val firstBmp = loadBitmap(images[0])
        val width = if (firstBmp.width % 2 == 0) firstBmp.width else firstBmp.width - 1
        val height = if (firstBmp.height % 2 == 0) firstBmp.height else firstBmp.height - 1

        val mimeType = "video/avc"
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false

        var audioExtractor: MediaExtractor? = null
        var audioFormat: MediaFormat? = null

        if (audioUriParam != null) {
            val extractor = MediaExtractor()
            extractor.setDataSource(this, audioUriParam, null)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioFormat = f
                    extractor.selectTrack(i)
                    break
                }
            }
            audioExtractor = extractor
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val frameDurationUs = 1_000_000L / fps

        fun drainEncoder(endOfStream: Boolean) {
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null) {
                            audioTrackIndex = muxer.addTrack(audioFormat!!)
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return
                        }
                    }
                }
            }
        }

        val decodedBitmaps = ArrayList<Bitmap>(images.size)
        decodedBitmaps.add(firstBmp)
        for (i in 1 until images.size) {
            decodedBitmaps.add(loadBitmap(images[i]))
            onProgress((i * 10 / images.size), "Kadrlar tayyorlanmoqda ${i + 1}/${images.size}")
        }

        for (i in decodedBitmaps.indices) {
            val bmp = decodedBitmaps[i]
            val scaled = if (bmp.width != width || bmp.height != height)
                Bitmap.createScaledBitmap(bmp, width, height, true) else bmp

            var inputIndex = -1
            while (inputIndex < 0) {
                inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex < 0) drainEncoder(false)
            }
            val image = encoder.getInputImage(inputIndex)
            if (image != null) {
                writeBitmapToImage(scaled, image)
            }
            val frameSize = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)
            encoder.queueInputBuffer(inputIndex, 0, frameSize, i * frameDurationUs, 0)

            drainEncoder(false)
            onProgress(10 + ((i + 1) * 70 / decodedBitmaps.size), "Kadr yozilmoqda ${i + 1}/${decodedBitmaps.size}")
        }

        var eosIndex = -1
        while (eosIndex < 0) {
            eosIndex = encoder.dequeueInputBuffer(10_000)
            if (eosIndex < 0) drainEncoder(false)
        }
        encoder.queueInputBuffer(
            eosIndex, 0, 0,
            decodedBitmaps.size.toLong() * frameDurationUs,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        drainEncoder(true)

        if (audioExtractor != null && audioTrackIndex >= 0) {
            onProgress(85, "Ovoz qo'shilmoqda…")
            val videoDurationUs = decodedBitmaps.size.toLong() * frameDurationUs
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val audioBufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTime = audioExtractor.sampleTime
                if (sampleTime > videoDurationUs) break
                audioBufferInfo.offset = 0
                audioBufferInfo.size = sampleSize
                audioBufferInfo.presentationTimeUs = sampleTime
                audioBufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(audioTrackIndex, buffer, audioBufferInfo)
                audioExtractor.advance()
            }
            audioExtractor.release()
        }

        onProgress(100, "Yakunlanmoqda…")
        muxer.stop()
        muxer.release()
        encoder.stop()
        encoder.release()
    }

    private fun writeBitmapToImage(bitmap: Bitmap, image: android.media.Image) {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride

        for (j in 0 until height) {
            for (i in 0 until width) {
                val argb = pixels[j * width + i]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF

                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(j * yRowStride + i * yPixelStride, yVal.coerceIn(0, 255).toByte())

                if (j % 2 == 0 && i % 2 == 0) {
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uRow = j / 2
                    val uCol = i / 2
                    uBuffer.put(uRow * uRowStride + uCol * uPixelStride, uVal.coerceIn(0, 255).toByte())
                    vBuffer.put(uRow * vRowStride + uCol * vPixelStride, vVal.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        contentResolver.openInputStream(uri).use { input ->
            return BitmapFactory.decodeStream(input)
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: ""
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Saqlash / Ulashish"))
    }
}
