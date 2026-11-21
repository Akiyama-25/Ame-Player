package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.data.UserSessionManager
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object NeteaseWebLoginHelper {
    const val MOBILE_LOGIN_URL = "https://y.music.163.com/m/login"
    const val DESKTOP_LOGIN_URL = "https://music.163.com/login"
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val FALLBACK_MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    val COOKIE_LOOKUP_URLS = listOf(
        "https://music.163.com",
        "https://y.music.163.com",
        "https://interface.music.163.com"
    )

    private val WV_TOKEN_REGEX = Regex(";\\s*wv\\b", RegexOption.IGNORE_CASE)
    private val EXTRA_SPACES_REGEX = Regex("\\s{2,}")

    fun sanitizeUserAgent(defaultUserAgent: String?): String {
        if (defaultUserAgent.isNullOrBlank()) return FALLBACK_MOBILE_USER_AGENT
        return defaultUserAgent
            .replace(WV_TOKEN_REGEX, "")
            .replace(EXTRA_SPACES_REGEX, " ")
            .trim()
            .ifBlank { FALLBACK_MOBILE_USER_AGENT }
    }

    fun isAllowedNavigationScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.startsWith("https://") || trimmed.startsWith("http://")
    }

    /**
     * Merges raw Cookie header strings from NetEase domains, cleans them via [UserSessionManager.cleanCookieString],
     * and returns the cleaned cookie string only if a valid, non-empty `MUSIC_U` token is present.
     */
    fun extractAuthenticatedCookie(vararg rawCookieStrings: String?): String? {
        val combined = rawCookieStrings
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString("; ")
        if (combined.isBlank()) return null

        val cleaned = UserSessionManager.cleanCookieString(combined)
        if (cleaned.isBlank()) return null

        val musicU = cleaned.split(";")
            .mapNotNull { segment ->
                val parts = segment.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .lastOrNull { it.first == "MUSIC_U" }
            ?.second

        if (musicU.isNullOrBlank() ||
            musicU.equals("null", ignoreCase = true) ||
            musicU.equals("deleted", ignoreCase = true) ||
            musicU.equals("undefined", ignoreCase = true)
        ) {
            return null
        }
        return cleaned
    }

    fun maskCookieForLog(cookie: String): String {
        val keys = cookie.split(";")
            .mapNotNull { segment ->
                val key = segment.trim().substringBefore("=", "").trim()
                key.takeIf { it.isNotEmpty() }
            }
            .distinct()
        return "keys=$keys, length=${cookie.length}"
    }

    fun clearWebViewCookies(onComplete: (() -> Unit)? = null) {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies {
                runCatching { cookieManager.flush() }
                onComplete?.invoke()
            }
        }.onFailure {
            onComplete?.invoke()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewLoginScreen(
    sessionManager: UserSessionManager,
    ncmApi: NcmApi,
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentOnLoginSuccess by rememberUpdatedState(onLoginSuccess)
    val currentOnBack by rememberUpdatedState(onBack)

    var pageTitle by remember { mutableStateOf("网易云网页登录") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var isDesktopMode by remember { mutableStateOf(false) }
    var isReadyToLoad by remember { mutableStateOf(false) }
    var isCompletingLogin by remember { mutableStateOf(false) }
    var canWebGoBack by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var mobileUserAgent by remember { mutableStateOf<String?>(null) }

    val completionStarted = remember { AtomicBoolean(false) }

    fun tryCaptureLoginCookie(currentUrl: String? = null) {
        if (!isReadyToLoad || completionStarted.get()) return
        val rawCookies = runCatching {
            val cookieManager = CookieManager.getInstance()
            buildList {
                NeteaseWebLoginHelper.COOKIE_LOOKUP_URLS.forEach { domainUrl ->
                    add(cookieManager.getCookie(domainUrl))
                }
                if (NeteaseWebLoginHelper.isAllowedNavigationScheme(currentUrl)) {
                    add(cookieManager.getCookie(currentUrl))
                }
            }
        }.getOrDefault(emptyList())

        val authenticatedCookie = NeteaseWebLoginHelper.extractAuthenticatedCookie(*rawCookies.toTypedArray())
            ?: return

        if (!completionStarted.compareAndSet(false, true)) return

        isCompletingLogin = true
        AppLogger.i(
            "AME_LOGIN_DEBUG",
            "WebView login captured valid MUSIC_U (${NeteaseWebLoginHelper.maskCookieForLog(authenticatedCookie)})"
        )

        scope.launch {
            runCatching { CookieManager.getInstance().flush() }
            sessionManager.saveCookie(authenticatedCookie)
            val profile = withContext(Dispatchers.IO) {
                runCatching { ncmApi.getUserProfile() }.getOrNull()
            }
            if (profile != null) {
                AppLogger.i(
                    "AME_LOGIN_DEBUG",
                    "WebView login synced user profile: userId=${profile.userId}, nickname=${profile.nickname}"
                )
            } else {
                AppLogger.w("AME_LOGIN_DEBUG", "WebView login saved cookie, but profile sync returned null")
            }
            currentOnLoginSuccess()
        }
    }

    // Clear stale WebView cookies before starting a fresh login session so account switching always works cleanly.
    LaunchedEffect(Unit) {
        withTimeoutOrNull(800L) {
            suspendCancellableCoroutine<Unit> { cont ->
                NeteaseWebLoginHelper.clearWebViewCookies {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        isReadyToLoad = true
    }

    // Poll CookieManager while active to immediately catch AJAX/SPA logins without waiting for a full page reload.
    LaunchedEffect(isReadyToLoad) {
        if (!isReadyToLoad) return@LaunchedEffect
        while (isActive && !completionStarted.get()) {
            delay(800L)
            tryCaptureLoginCookie(webViewRef?.url)
        }
    }

    BackHandler(enabled = canWebGoBack && !isCompletingLogin) {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            canWebGoBack = wv.canGoBack()
        } else {
            canWebGoBack = false
            currentOnBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { webView ->
                runCatching {
                    webView.stopLoading()
                    webView.webChromeClient = null
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    webView.removeAllViews()
                    webView.destroy()
                }
            }
            webViewRef = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = pageTitle.ifBlank { "网易云网页登录" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isDesktopMode) "桌面版 · 登录成功后自动返回" else "移动版 · 登录成功后自动返回",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { currentOnBack() },
                        modifier = Modifier.testTag("btn_web_login_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val nextDesktop = !isDesktopMode
                            isDesktopMode = nextDesktop
                            webViewRef?.let { wv ->
                                wv.settings.userAgentString = if (nextDesktop) {
                                    NeteaseWebLoginHelper.DESKTOP_USER_AGENT
                                } else {
                                    mobileUserAgent ?: NeteaseWebLoginHelper.sanitizeUserAgent(wv.settings.userAgentString)
                                }
                                wv.loadUrl(
                                    if (nextDesktop) NeteaseWebLoginHelper.DESKTOP_LOGIN_URL
                                    else NeteaseWebLoginHelper.MOBILE_LOGIN_URL
                                )
                            }
                        },
                        modifier = Modifier.testTag("btn_web_login_mode_toggle")
                    ) {
                        Icon(
                            imageVector = if (isDesktopMode) Icons.Rounded.Smartphone else Icons.Rounded.Computer,
                            contentDescription = if (isDesktopMode) "切换移动版" else "切换桌面版"
                        )
                    }
                    IconButton(
                        onClick = {
                            webViewRef?.reload()
                            tryCaptureLoginCookie(webViewRef?.url)
                        },
                        modifier = Modifier.testTag("btn_web_login_refresh")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "刷新网页"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .navigationBarsPadding()
                .imePadding()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isReadyToLoad) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("webview_netease_login"),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            val sanitizedUa = NeteaseWebLoginHelper.sanitizeUserAgent(settings.userAgentString)
                            mobileUserAgent = sanitizedUa

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.setSupportMultipleWindows(false)
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.userAgentString = if (isDesktopMode) {
                                NeteaseWebLoginHelper.DESKTOP_USER_AGENT
                            } else {
                                sanitizedUa
                            }

                            runCatching {
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString()
                                    if (!NeteaseWebLoginHelper.isAllowedNavigationScheme(url)) {
                                        AppLogger.d("AME_LOGIN_DEBUG", "Blocked non-http WebView redirect: $url")
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    canWebGoBack = view?.canGoBack() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    loadProgress = 100
                                    canWebGoBack = view?.canGoBack() == true
                                    tryCaptureLoginCookie(url)
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView?,
                                    url: String?,
                                    isReload: Boolean
                                ) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    canWebGoBack = view?.canGoBack() == true
                                    tryCaptureLoginCookie(url)
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    loadProgress = newProgress
                                    isLoading = newProgress < 100
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    super.onReceivedTitle(view, title)
                                    if (!title.isNullOrBlank() && !title.startsWith("http")) {
                                        pageTitle = title
                                    }
                                }
                            }

                            webViewRef = this
                            loadUrl(
                                if (isDesktopMode) NeteaseWebLoginHelper.DESKTOP_LOGIN_URL
                                else NeteaseWebLoginHelper.MOBILE_LOGIN_URL
                            )
                        }
                    }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            if (isLoading && loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter)
                )
            }

            if (isCompletingLogin) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Text(
                                text = "登录成功，正在同步账号信息...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
