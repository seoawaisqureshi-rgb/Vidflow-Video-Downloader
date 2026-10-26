package com.vidflow.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidflow.app.data.DownloadRepository
import com.vidflow.app.data.db.DownloadRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: DownloadRepository,
) : ViewModel() {

    val downloads = repository.getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { repository.deleteRecord(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clearHistory() }
    }
}
