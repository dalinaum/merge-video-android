package com.example.mergevideo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mergevideo.data.MergedVideoSink
import com.example.mergevideo.data.VideoItem
import com.example.mergevideo.data.VideoRepository
import com.example.mergevideo.merge.VideoMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository(application)
    private val merger = VideoMerger(application)
    private val sink = MergedVideoSink(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.NoPermission)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _selectedUris = MutableStateFlow<List<Uri>>(emptyList())
    val selectedUris: StateFlow<List<Uri>> = _selectedUris.asStateFlow()

    fun onPermissionGranted() {
        loadVideos()
    }

    fun loadVideos() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) { repository.loadCameraVideos() }
            _uiState.value = UiState.Loaded(videos)
        }
    }

    fun toggleSelection(uri: Uri) {
        val current = _selectedUris.value.toMutableList()
        if (!current.remove(uri)) current.add(uri)
        _selectedUris.value = current
    }

    fun clearSelection() {
        _selectedUris.value = emptyList()
    }

    fun mergeSelected() {
        val uris = _selectedUris.value
        if (uris.size < 2) return

        _uiState.value = UiState.Merging(0, uris.size)
        viewModelScope.launch {
            try {
                val resultName = withContext(Dispatchers.IO) {
                    val target = sink.create()
                    try {
                        merger.merge(uris, target.fd) { current, total ->
                            _uiState.value = UiState.Merging(current, total)
                        }
                    } finally {
                        target.close()
                    }
                    target.displayName
                }
                _selectedUris.value = emptyList()
                _uiState.value = UiState.Done(resultName)
                loadVideos()
            } catch (t: Throwable) {
                _uiState.value = UiState.Error(t.message ?: t::class.java.simpleName)
            }
        }
    }

    fun acknowledgeMessage() {
        when (_uiState.value) {
            is UiState.Done, is UiState.Error -> loadVideos()
            else -> Unit
        }
    }
}

sealed interface UiState {
    data object NoPermission : UiState
    data object Loading : UiState
    data class Loaded(val videos: List<VideoItem>) : UiState
    data class Merging(val current: Int, val total: Int) : UiState
    data class Done(val displayName: String) : UiState
    data class Error(val message: String) : UiState
}
