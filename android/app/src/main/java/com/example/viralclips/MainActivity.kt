package com.example.viralclips

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { HomeScreen(vm) }
            }
        }
    }
}

@Composable
fun HomeScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.videoUri = uri
    }
    val phase = vm.phase
    val busy = phase is Phase.Working

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Viral Clips", style = MaterialTheme.typography.headlineMedium)
        Text("Upload a video. Get the best moments as captioned vertical clips.")

        OutlinedTextField(
            value = vm.serverUrl, onValueChange = { vm.serverUrl = it },
            label = { Text("Server URL") }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text(vm.videoUri?.let { fileInfo(context, it).first } ?: "Choose a video") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Clips:", modifier = Modifier.padding(top = 10.dp))
            for (n in listOf(1, 3, 5)) {
                val selected = vm.maxClips == n
                if (selected) Button(onClick = {}, enabled = !busy) { Text("$n") }
                else OutlinedButton(onClick = { vm.maxClips = n }, enabled = !busy) { Text("$n") }
            }
        }

        when (phase) {
            is Phase.Working -> {
                Text(phase.stage)
                if (phase.progress > 0) LinearProgressIndicator(
                    progress = { phase.progress / 100f }, modifier = Modifier.fillMaxWidth(),
                ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            else -> Button(
                onClick = vm::start, enabled = vm.videoUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Generate clips") }
        }

        if (phase is Phase.Failed) {
            Text(phase.message, color = MaterialTheme.colorScheme.error)
        }

        if (phase is Phase.Done) {
            phase.clips.forEach { clip ->
                ClipCard(
                    clip,
                    onSave = { saveClip(context, clip.file) },
                    onShare = { shareClip(context, clip.file) },
                )
            }
        }
    }
}

@Composable
fun ClipCard(clip: ResultClip, onSave: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    val player = remember(clip.file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(clip.file)))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(clip.info.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "Score ${clip.info.score} · ${formatTime(clip.info.start)}–${formatTime(clip.info.end)}",
                style = MaterialTheme.typography.labelMedium,
            )
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxWidth().height(440.dp),
            )
            if (clip.info.reason.isNotBlank()) Text(clip.info.reason, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("Save") }
                OutlinedButton(onClick = onShare) { Text("Share") }
            }
        }
    }
}
