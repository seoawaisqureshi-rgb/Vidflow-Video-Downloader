package com.vidflow.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidflow.app.VidFlowApplication
import com.vidflow.app.data.extractor.VideoExtractor
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import com.vidflow.app.service.DownloadEntry
import com.vidflow.app.service.DownloadRequest
import com.vidflow.app.service.DownloadService
import com.vidflow.app.service.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val urlInput: String = "",
    val isLoading: Boolean = false,
    val videoInfo: VideoInfo? = null,
    val selectedFormat: VideoFormat? = null,
    val error: String? = null,
    val snackbarMessage: String? = null,
    val clipboardUrl: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Only show ACTIVE downloads (Downloading + Paused) on the main screen.
     * Completed/Error/Cancelled entries auto-dismiss via DownloadService after 5s,
     * but we also filter them out here for instant cleanup.
     */
    val activeDownloads: StateFlow<Map<String, DownloadEntry>> = DownloadService.downloads
        .map { all ->
            all.filter { (_, entry) ->
                entry.state is ServiceState.Downloading ||
                entry.state is ServiceState.Paused ||
                entry.state is ServiceState.Success ||  // show briefly during 5s window
                entry.state is ServiceState.Error ||    // show error so user knows
                entry.state is ServiceState.Cancelled   // show cancelled briefly
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val repository get() = (getApplication<VidFlowApplication>()).repository

    fun onUrlChanged(url: String) {
        _uiState.update {
            it.copy(urlInput = url, error = null, videoInfo = null, selectedFormat = null)
        }
    }

    // Track the last URL that was auto-analyzed to avoid re-analysis on tab navigation
    private var lastAutoAnalyzedUrl: String? = null
    // Track the last URL that was successfully analyzed — don't re-analyze same URL
    private var lastAnalyzedUrl: String? = null

    fun onClipboardDetected(raw: String?) {
        val url = extractHttpUrl(raw) ?: return
        if (url == lastAutoAnalyzedUrl) return
        val current = _uiState.value
        // If we're already showing results for this URL, don't trigger again
        if (url == current.videoInfo?.url) return
        if (url == current.urlInput && (current.isLoading || current.videoInfo != null)) return
        if (current.isLoading) return

        lastAutoAnalyzedUrl = url
        _uiState.update {
            it.copy(
                urlInput = url,
                clipboardUrl = null,
                error = null,
                videoInfo = null,
                selectedFormat = null,
            )
        }
        fetchInfo()
    }

    fun dismissActiveDownload(id: String) {
        DownloadService.dismiss(id)
    }

    fun acceptClipboardUrl() {
        val url = _uiState.value.clipboardUrl ?: return
        _uiState.update {
            it.copy(
                urlInput = url,
                clipboardUrl = null,
                error = null,
                videoInfo = null,
                selectedFormat = null,
            )
        }
        fetchInfo()
    }

    fun dismissClipboard() {
        _uiState.update { it.copy(clipboardUrl = null) }
    }

    fun fetchInfo() {
        val url = _uiState.value.urlInput.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(error = "Please paste a video URL first.") }
            return
        }

        // If we already have results for this exact URL, don't re-analyze
        if (url == lastAnalyzedUrl && _uiState.value.videoInfo != null) {
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, videoInfo = null, selectedFormat = null, clipboardUrl = null)
            }
            when (val result = repository.fetchVideoInfo(url)) {
                is VideoExtractor.Result.Success -> {
                    lastAnalyzedUrl = url
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            videoInfo = result.info,
                            selectedFormat = result.info.formats.firstOrNull(),
                        )
                    }
                }
                is VideoExtractor.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    fun onFormatSelected(format: VideoFormat) {
        _uiState.update { it.copy(selectedFormat = format) }
    }

    fun startDownload() {
        val info = _uiState.value.videoInfo ?: return
        val format = _uiState.value.selectedFormat ?: return

        DownloadService.start(
            getApplication(),
            DownloadRequest(info = info, format = format, downloadUrl = format.downloadUrl),
        )

        // Clear the analyzed video so user can paste a new link immediately
        // The download continues in DownloadService independently
        _uiState.update {
            it.copy(
                videoInfo = null,
                selectedFormat = null,
                urlInput = "",
                error = null,
                snackbarMessage = "Download started! Paste next link to queue more.",
            )
        }
        lastAnalyzedUrl = null
    }

    fun pauseDownload(id: String) {
        DownloadService.pause(getApplication(), id)
    }

    fun resumeDownload(id: String) {
        DownloadService.resume(getApplication(), id)
    }

    fun cancelDownload(id: String) {
        DownloadService.cancel(getApplication(), id)
    }

    fun clearResult() {
        lastAnalyzedUrl = null
        _uiState.update {
            it.copy(
                videoInfo = null,
                selectedFormat = null,
                error = null,
                snackbarMessage = null,
                urlInput = "",
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s"'<>]+""")

        fun extractHttpUrl(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val match = URL_REGEX.find(raw) ?: return null
            return match.value.trim().trimEnd('.', ',', '!', ';', ')')
        }
    }
}
