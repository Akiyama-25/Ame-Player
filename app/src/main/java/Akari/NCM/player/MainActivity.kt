package Akari.NCM.player

import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.player.AmePlaybackService
import Akari.NCM.player.ui.screen.MainScreen
import Akari.NCM.player.ui.theme.AmePlayerTheme
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerEngine: AmePlayerEngine

    private val openPlayerRequests = kotlinx.coroutines.flow.MutableStateFlow(0)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("AME_DEBUG", "MainActivity onCreate started")
        enableEdgeToEdge()
        
        Akari.NCM.player.data.ThemePrefs.init(this)

        if (intent?.getBooleanExtra("open_player", false) == true) {
            openPlayerRequests.value++
        }

        // 启动前台播放服务，让 ExoPlayer 与 MediaSession 在后台持续存活
        runCatching {
            startService(Intent(this, AmePlaybackService::class.java))
        }

        // 拦截返回手势：将任务移到后台而非销毁 Activity，保持播放状态
        onBackPressedDispatcher.addCallback(this) {
            moveTaskToBack(true)
        }

        setContent {
            AmePlayerTheme {
                MainScreen(
                    playerEngine = playerEngine,
                    openPlayerRequests = openPlayerRequests
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            val sessionToken = SessionToken(this, ComponentName(this, AmePlaybackService::class.java))
            controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        }.onFailure { e ->
            Log.w("AME_DEBUG", "Failed to bind MediaController to AmePlaybackService", e)
        }
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_player", false)) {
            openPlayerRequests.value++
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }
}
