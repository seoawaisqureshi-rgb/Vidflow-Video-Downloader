package com.vidflow.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.vidflow.app.data.DownloadRepository
import com.vidflow.app.ui.history.HistoryViewModel
import com.vidflow.app.ui.home.HomeViewModel

class ViewModelFactory(
    private val application: Application,
    private val repository: DownloadRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java)    -> HomeViewModel(application) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
