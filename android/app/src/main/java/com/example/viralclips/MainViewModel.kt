package com.example.viralclips

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

data class ResultClip(val info: ClipInfo, val file: File)

sealed interface Phase {
    data object Idle : Phase
    data class Working(val stage: String, val progress: Int) : Phase
    data class Done(val clips: List<ResultClip>) : Phase
    data class Failed(val message: String) : Phase
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val api = ClipApi(app)
    private var job: Job? = null

    // 10.0.2.2 = host machine from the Android emulator. On a real phone use your PC's LAN IP.
    var serverUrl by mutableStateOf(prefs.getString("server", "http://10.0.2.2:8000")!!)
    var videoUri by mutableStateOf<Uri?>(null)
    var maxClips by mutableIntStateOf(3)
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set

    fun start() {
        val uri = videoUri ?: return
        val url = serverUrl.trim().trimEnd('/')
        prefs.edit().putString("server", url).apply()
        job?.cancel()
        job = viewModelScope.launch {
            try {
                phase = Phase.Working("Uploading video…", 0)
                val id = api.upload(url, uri, maxClips)
                while (true) {
                    val s = api.status(url, id)
                    when (s.status) {
                        "failed" -> throw IOException(s.error ?: "Processing failed")
                        "done" -> {
                            phase = Phase.Working("Downloading clips…", 100)
                            val clips = s.clips.map { ResultClip(it, api.download(url, id, it.index)) }
                            phase = Phase.Done(clips)
                            return@launch
                        }
                        else -> phase = Phase.Working(s.stage, s.progress)
                    }
                    delay(2000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = Phase.Failed(e.message ?: "Something went wrong")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        phase = Phase.Idle
    }

    fun reset() {
        phase = Phase.Idle
    }
}
