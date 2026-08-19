package Akari.NCM.player

import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.player.AmePlaybackService
import Akari.NCM.player.ui.screen.MainScreen
import Akari.NCM.player.ui.theme.AmePlayerTheme
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerEngine: AmePlayerEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("AME_DEBUG", "MainActivity onCreate started")
        enableEdgeToEdge()
        
        Akari.NCM.player.data.ThemePrefs.init(this)

        // 启动前台播放服务，让 ExoPlayer 在后台持续存活
        startService(Intent(this, AmePlaybackService::class.java))

        // 拦截返回手势：将任务移到后台而非销毁 Activity，保持播放状态
        onBackPressedDispatcher.addCallback(this) {
            moveTaskToBack(true)
        }

        setContent {
            AmePlayerTheme {
                MainScreen(playerEngine = playerEngine)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 只有用户从最近任务列表完全关闭 App（isFinishing=true 且非配置变更）时才释放引擎
        if (isFinishing) {
            playerEngine.release()
        }
    }
}
