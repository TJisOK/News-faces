package com.tjisok.newsfaces

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records whatever is drawn into its Surface to an MP4 (H.264) in Movies/NewsFaces. */
class MosaicRecorder(private val ctx: Context, val width: Int, val height: Int, fps: Int) {
    private val recorder: MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
    private val uri: Uri
    private val pfd: ParcelFileDescriptor
    val surface: Surface
    val startedAt = System.currentTimeMillis()
    var frames = 0; private set

    init {
        val name = "news-face-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NewsFaces"); put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("cannot open output")
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(pfd.fileDescriptor)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(width, height)
        recorder.setVideoFrameRate(fps.coerceIn(1, 30))
        recorder.setVideoEncodingBitRate(10_000_000)
        recorder.prepare()
        surface = recorder.surface
        recorder.start()
    }

    fun frame(draw: (Canvas) -> Unit) {
        val c = try { surface.lockHardwareCanvas() } catch (_: Exception) { surface.lockCanvas(null) }
        try { draw(c) } finally { surface.unlockCanvasAndPost(c) }
        frames++
    }

    /** Stops and finalises the file; returns its content Uri. */
    fun stop(): Uri {
        try { recorder.stop() } catch (_: Exception) {}
        recorder.release(); surface.release(); pfd.close()
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
