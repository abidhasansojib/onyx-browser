package com.onyx.browser.ui.player

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.onyx.browser.R
import com.onyx.browser.databinding.ActivityInternalPlayerBinding
import com.onyx.browser.ui.downloads.DownloadPromptBottomSheet

/**
 * Dedicated, hardware-accelerated internal media player for Onyx Browser.
 * Plays web video streams, MP4, WebM, and media files directly in an immersive,
 * distraction-free full-screen environment.
 */
class InternalPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_PAGE_URL = "extra_page_url"

        fun createIntent(context: Context, videoUrl: String, title: String? = null, pageUrl: String? = null): Intent {
            return Intent(context, InternalPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_VIDEO_TITLE, title)
                putExtra(EXTRA_PAGE_URL, pageUrl)
            }
        }
    }

    private lateinit var binding: ActivityInternalPlayerBinding
    private var videoUrl: String = ""
    private var videoTitle: String = "Video"
    private var pageUrl: String? = null

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var isTrackingTouch = false
    private var isControlsVisible = true
    private var isFitMode = true

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && !isTrackingTouch && binding.videoView.isPlaying) {
                val current = binding.videoView.currentPosition
                binding.seekBar.progress = current
                binding.tvCurrentPosition.text = formatTime(current)
            }
            handler.postDelayed(this, 400)
        }
    }

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()

        binding = ActivityInternalPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video"
        pageUrl = intent.getStringExtra(EXTRA_PAGE_URL)

        if (videoUrl.isBlank()) {
            Toast.makeText(this, "No video URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.tvPlayerTitle.text = videoTitle

        setupListeners()
        startVideoPlayback()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayerDownload.setOnClickListener {
            val sheet = DownloadPromptBottomSheet.newInstance(
                url = videoUrl,
                userAgent = null,
                contentDisposition = null,
                mimeType = "video/*",
                contentLength = 0L,
                pageUrl = pageUrl
            )
            sheet.show(supportFragmentManager, "DownloadPromptSheet")
        }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
            resetAutoHideControls()
        }

        binding.btnRewind10.setOnClickListener {
            val newPos = (binding.videoView.currentPosition - 10000).coerceAtLeast(0)
            binding.videoView.seekTo(newPos)
            binding.seekBar.progress = newPos
            binding.tvCurrentPosition.text = formatTime(newPos)
            resetAutoHideControls()
        }

        binding.btnForward10.setOnClickListener {
            val newPos = (binding.videoView.currentPosition + 10000).coerceAtMost(binding.videoView.duration)
            binding.videoView.seekTo(newPos)
            binding.seekBar.progress = newPos
            binding.tvCurrentPosition.text = formatTime(newPos)
            resetAutoHideControls()
        }

        binding.btnAspectRatio.setOnClickListener {
            toggleAspectRatio()
            resetAutoHideControls()
        }

        binding.playerRootView.setOnClickListener {
            if (isControlsVisible) {
                hideControls()
            } else {
                showControls()
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentPosition.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isTrackingTouch = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                binding.videoView.seekTo(progress)
                isTrackingTouch = false
                resetAutoHideControls()
            }
        })

        binding.btnRetryOrExternal.setOnClickListener {
            try {
                val externalIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(videoUrl), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(externalIntent, "Play video with"))
            } catch (_: Exception) {
                Toast.makeText(this, "No external video player found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVideoPlayback() {
        binding.progressBar.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE

        try {
            binding.videoView.setVideoURI(Uri.parse(videoUrl))
            binding.videoView.setOnPreparedListener { mp ->
                mediaPlayer = mp
                binding.progressBar.visibility = View.GONE
                val duration = binding.videoView.duration
                binding.seekBar.max = duration
                binding.tvDuration.text = formatTime(duration)

                binding.videoView.start()
                isPlaying = true
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)

                handler.post(progressRunnable)
                resetAutoHideControls()
            }

            binding.videoView.setOnCompletionListener {
                isPlaying = false
                binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
                showControls()
            }

            binding.videoView.setOnErrorListener { _, _, _ ->
                binding.progressBar.visibility = View.GONE
                binding.layoutError.visibility = View.VISIBLE
                showControls()
                true
            }
        } catch (_: Exception) {
            binding.progressBar.visibility = View.GONE
            binding.layoutError.visibility = View.VISIBLE
        }
    }

    private fun togglePlayPause() {
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            isPlaying = false
            binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
        } else {
            binding.videoView.start()
            isPlaying = true
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
        }
    }

    private fun toggleAspectRatio() {
        isFitMode = !isFitMode
        val lp = binding.videoView.layoutParams as FrameLayout.LayoutParams
        if (isFitMode) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            Toast.makeText(this, "Fit to screen", Toast.LENGTH_SHORT).show()
        } else {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            Toast.makeText(this, "Original aspect ratio", Toast.LENGTH_SHORT).show()
        }
        binding.videoView.layoutParams = lp
    }

    private fun showControls() {
        isControlsVisible = true
        binding.controlsOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .withStartAction { binding.controlsOverlay.visibility = View.VISIBLE }
            .start()
        resetAutoHideControls()
    }

    private fun hideControls() {
        if (!isControlsVisible) return
        isControlsVisible = false
        binding.controlsOverlay.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction { binding.controlsOverlay.visibility = View.GONE }
            .start()
    }

    private fun resetAutoHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (isPlaying) {
            handler.postDelayed(hideControlsRunnable, 3500)
        }
    }

    private fun formatTime(millis: Int): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            isPlaying = false
            binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            binding.videoView.stopPlayback()
        } catch (_: Exception) {}
    }
}
