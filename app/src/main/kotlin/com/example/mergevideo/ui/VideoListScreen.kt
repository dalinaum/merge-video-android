package com.example.mergevideo.ui

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mergevideo.R
import com.example.mergevideo.data.VideoItem
import java.util.Locale

@Composable
fun VideoListScreen(
    viewModel: VideoListViewModel,
    permissionName: String
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selected by viewModel.selectedUris.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onPermissionGranted()
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, permissionName) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onPermissionGranted()
        } else {
            permissionLauncher.launch(permissionName)
        }
    }

    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is UiState.Done -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.merge_done, s.displayName)
                )
                viewModel.acknowledgeMessage()
            }
            is UiState.Error -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.merge_failed, s.message)
                )
                viewModel.acknowledgeMessage()
            }
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomMergeBar(
                selectedCount = selected.size,
                isMerging = uiState is UiState.Merging,
                mergingProgress = (uiState as? UiState.Merging),
                onMergeClick = viewModel::mergeSelected,
                onClearClick = viewModel::clearSelection
            )
        }
    ) { padding ->
        VideoListContent(
            uiState = uiState,
            selected = selected,
            padding = padding,
            onRequestPermission = { permissionLauncher.launch(permissionName) },
            onToggle = viewModel::toggleSelection
        )
    }
}

@Composable
private fun VideoListContent(
    uiState: UiState,
    selected: List<Uri>,
    padding: PaddingValues,
    onRequestPermission: () -> Unit,
    onToggle: (Uri) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        when (uiState) {
            UiState.NoPermission -> PermissionRequestView(onRequestPermission)
            UiState.Loading -> CircularProgressIndicator()
            is UiState.Loaded -> {
                if (uiState.videos.isEmpty()) {
                    Text(text = stringResource(id = R.string.no_videos))
                } else {
                    VideoList(uiState.videos, selected, onToggle)
                }
            }
            is UiState.Merging -> MergingView(uiState)
            is UiState.Done, is UiState.Error -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun PermissionRequestView(onRequest: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = stringResource(id = R.string.permission_required))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) {
            Text(text = stringResource(id = R.string.grant_permission))
        }
    }
}

@Composable
private fun MergingView(state: UiState.Merging) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text(text = "${state.current} / ${state.total}")
        Text(text = stringResource(id = R.string.merging))
    }
}

@Composable
private fun VideoList(
    videos: List<VideoItem>,
    selected: List<Uri>,
    onToggle: (Uri) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(videos, key = { it.uri }) { item ->
            val orderIndex = selected.indexOf(item.uri)
            VideoRow(
                item = item,
                orderNumber = if (orderIndex >= 0) orderIndex + 1 else null,
                onClick = { onToggle(item.uri) }
            )
        }
    }
}

@Composable
private fun VideoRow(
    item: VideoItem,
    orderNumber: Int?,
    onClick: () -> Unit
) {
    val isSelected = orderNumber != null
    val rowBg = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionBadge(orderNumber)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = item.displayName,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = "${formatDuration(item.durationMs)}   ${formatSize(item.sizeBytes)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SelectionBadge(orderNumber: Int?) {
    val bg = if (orderNumber != null) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (orderNumber != null) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = orderNumber?.toString() ?: "",
            color = fg,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BottomMergeBar(
    selectedCount: Int,
    isMerging: Boolean,
    mergingProgress: UiState.Merging?,
    onMergeClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        if (isMerging && mergingProgress != null) {
            val total = mergingProgress.total.coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { mergingProgress.current.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedCount > 0 && !isMerging) {
                Button(
                    onClick = onClearClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "선택 해제")
                }
            }
            Button(
                onClick = onMergeClick,
                enabled = selectedCount >= 2 && !isMerging,
                modifier = Modifier.weight(2f)
            ) {
                Text(text = stringResource(id = R.string.merge_button, selectedCount))
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = durationMs / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}
