package com.example.childsafetyimpl.ux.youtube

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.ui.components.CircleIconButton
import com.example.childsafetyimpl.ui.components.TonalChip
import com.example.childsafetyimpl.ui.components.shadowSoft
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.SafetyRadius

/**
 * The supervised YouTube surface.
 *
 * Restricted Mode is genuinely on here, because this app owns the request path and can set both
 * the preference cookie and the restriction header YouTube honours. Ads are refused before they
 * are fetched, and the Shorts routes are unreachable.
 *
 * This is the answer to "restrict YouTube" that actually holds. The YouTube app itself is
 * restricted the only way Android permits: by switching it off, so it closes every time it opens.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayerScreen(viewModel: YouTubePlayerViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    var webView by remember { mutableStateOf<WebView?>(null) }

    // Set before the first load: YouTube reads the preference on the request that establishes the
    // session, and setting it afterwards has no effect until the next one.
    LaunchedEffect(Unit) {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        viewModel.surface.restrictedCookies.forEach { (domain, value) ->
            cookies.setCookie(domain, value)
        }
        cookies.flush()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    LaunchedEffect(state.pendingUrl) {
        val url = state.pendingUrl ?: return@LaunchedEffect
        webView?.loadUrl(url, viewModel.surface.restrictedHeaders)
        viewModel.onEvent(YouTubePlayerUiEvent.UrlConsumed)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SafetyPalette.Canvas),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SafetyPalette.Surface)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircleIconButton(
                onClick = {
                    val view = webView
                    if (state.canGoBack && view != null) {
                        view.goBack()
                    } else {
                        viewModel.onEvent(YouTubePlayerUiEvent.Back)
                    }
                },
                icon = Icons.Rounded.ArrowBackIosNew,
                tint = SafetyPalette.Indigo,
                container = SafetyPalette.IndigoSoft,
                size = 36,
            )

            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.onEvent(YouTubePlayerUiEvent.QueryChanged(it)) },
                placeholder = { Text("Search videos") },
                singleLine = true,
                shape = SafetyRadius.field,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        viewModel.onEvent(YouTubePlayerUiEvent.Search)
                    },
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SafetyPalette.Canvas,
                    unfocusedContainerColor = SafetyPalette.Canvas,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )

            CircleIconButton(
                onClick = { viewModel.onEvent(YouTubePlayerUiEvent.GoHome) },
                icon = Icons.Rounded.Home,
                tint = SafetyPalette.Indigo,
                container = SafetyPalette.IndigoSoft,
                size = 36,
            )
        }

        AnimatedVisibility(visible = state.loadingProgress in 1..99) {
            LinearProgressIndicator(
                progress = { state.loadingProgress / 100f },
                color = SafetyPalette.Indigo,
                trackColor = SafetyPalette.IndigoSoft,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }

        AnimatedVisibility(visible = state.refusal != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SafetyPalette.DangerSoft)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.Block,
                    contentDescription = null,
                    tint = SafetyPalette.Danger,
                    modifier = Modifier.height(18.dp),
                )
                Spacer(Modifier.padding(horizontal = 5.dp))
                Text(
                    text = state.refusal.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyPalette.Danger,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        // The player is the point of this screen, so it may start on its own.
                        settings.mediaPlaybackRequiresUserGesture = false

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val url = request.url?.toString() ?: return true
                                if (viewModel.surface.allowsNavigation(url)) {
                                    // Reloaded through us rather than returned as allowed, so the
                                    // restriction header travels with every navigation instead of
                                    // only the first.
                                    view.loadUrl(url, viewModel.surface.restrictedHeaders)
                                    return true
                                }
                                viewModel.onEvent(
                                    YouTubePlayerUiEvent.Refused(
                                        if (url.contains("/shorts")) {
                                            "Shorts is not available in the safe player."
                                        } else {
                                            "Links out of YouTube are not opened here."
                                        },
                                    ),
                                )
                                return true
                            }

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = viewModel.surface.interceptRequest(request)

                            override fun onPageFinished(view: WebView, url: String?) {
                                viewModel.onEvent(
                                    YouTubePlayerUiEvent.PageChanged(view.canGoBack()),
                                )
                                view.evaluateJavascript(viewModel.surface.pageScript(), null)
                            }

                            /**
                             * YouTube is a single-page app: tapping a Short changes the URL
                             * through the history API, which never calls
                             * shouldOverrideUrlLoading. This callback is the one the platform
                             * does raise for those, and without it a Short can be reached
                             * inside a player that reports having blocked none.
                             */
                            override fun doUpdateVisitedHistory(
                                view: WebView,
                                url: String?,
                                isReload: Boolean,
                            ) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                url ?: return
                                if (viewModel.surface.isShortsUrl(url)) {
                                    viewModel.surface.countShortsBlocked()
                                    viewModel.onEvent(
                                        YouTubePlayerUiEvent.Refused(
                                            "Shorts is not available in the safe player.",
                                        ),
                                    )
                                    if (view.canGoBack()) {
                                        view.goBack()
                                    } else {
                                        view.loadUrl(
                                            viewModel.surface.homeUrl,
                                            viewModel.surface.restrictedHeaders,
                                        )
                                    }
                                }
                                viewModel.onEvent(
                                    YouTubePlayerUiEvent.PageChanged(view.canGoBack()),
                                )
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                viewModel.onEvent(YouTubePlayerUiEvent.Progress(newProgress))
                            }
                        }

                        webView = this
                    }
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadowSoft(SafetyRadius.sheet)
                .background(SafetyPalette.Surface)
                .padding(horizontal = 16.dp, vertical = 11.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TonalChip(
                text = "Restricted Mode",
                content = SafetyPalette.Success,
                container = SafetyPalette.SuccessSoft,
                icon = Icons.Rounded.Shield,
            )
            TonalChip(
                text = "${state.stats.adsBlocked} ads",
                content = SafetyPalette.IndigoDeep,
                container = SafetyPalette.IndigoSoft,
            )
            TonalChip(
                text = "${state.stats.shortsBlocked} Shorts",
                content = SafetyPalette.Danger,
                container = SafetyPalette.DangerSoft,
            )
        }
    }
}
