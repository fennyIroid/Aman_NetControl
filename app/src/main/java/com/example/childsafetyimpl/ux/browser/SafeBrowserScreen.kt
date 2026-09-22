package com.example.childsafetyimpl.ux.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.childsafetyimpl.navigation.HandleNavigation
import com.example.childsafetyimpl.navigation.LocalNavController
import com.example.childsafetyimpl.platform.browser.SafeBrowserEngine
import com.example.childsafetyimpl.ui.components.CircleIconButton
import com.example.childsafetyimpl.ui.components.PrimaryButton
import com.example.childsafetyimpl.ui.components.SecondaryButton
import com.example.childsafetyimpl.ui.components.TonalChip
import com.example.childsafetyimpl.ui.components.shadowSoft
import com.example.childsafetyimpl.ui.theme.SafetyPalette
import com.example.childsafetyimpl.ui.theme.SafetyRadius

/**
 * The built-in browser.
 *
 * Every request the page makes passes through the rule engine before the web view is allowed to
 * load it, which is what makes this the strongest content protection in the product: nothing gets
 * to the screen without having been looked at first.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SafeBrowserScreen(viewModel: SafeBrowserViewModel) {
    HandleNavigation(viewModel, LocalNavController.current)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    var webView by remember { mutableStateOf<WebView?>(null) }

    // The web view outlives recomposition but not the screen. Destroying it here is what stops a
    // page carrying on loading, and running scripts, after the parent has navigated away.
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
        webView?.loadUrl(url)
        viewModel.onEvent(SafeBrowserUiEvent.UrlConsumed)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SafetyPalette.Canvas),
    ) {
        // --- address bar -------------------------------------------------------------------
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
                        viewModel.onEvent(SafeBrowserUiEvent.Back)
                    }
                },
                icon = Icons.Rounded.ArrowBackIosNew,
                tint = SafetyPalette.Indigo,
                container = SafetyPalette.IndigoSoft,
                size = 36,
            )

            OutlinedTextField(
                value = state.address,
                onValueChange = { viewModel.onEvent(SafeBrowserUiEvent.AddressChanged(it)) },
                placeholder = { Text("Search or type an address") },
                singleLine = true,
                shape = SafetyRadius.field,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        keyboard?.hide()
                        viewModel.onEvent(SafeBrowserUiEvent.Go)
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
                onClick = { viewModel.onEvent(SafeBrowserUiEvent.GoHome) },
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

        // --- the page ------------------------------------------------------------------------
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
                        settings.setSupportZoom(true)
                        // File and content access are off: a browser a child uses has no business
                        // reading the device's own storage through a page.
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mediaPlaybackRequiresUserGesture = true

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val url = request.url?.toString() ?: return false
                                return when (
                                    val decision = viewModel.engine.evaluateNavigation(
                                        url,
                                        viewModel.uiState.value.safeSearchEnabled,
                                    )
                                ) {
                                    is SafeBrowserEngine.NavigationDecision.Allow -> false

                                    is SafeBrowserEngine.NavigationDecision.Redirect -> {
                                        view.loadUrl(decision.url)
                                        true
                                    }

                                    is SafeBrowserEngine.NavigationDecision.Block -> true
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = viewModel.engine.interceptRequest(request)

                            override fun onPageStarted(
                                view: WebView,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                viewModel.onEvent(
                                    SafeBrowserUiEvent.PageChanged(
                                        url = url.orEmpty(),
                                        title = view.title.orEmpty(),
                                        canGoBack = view.canGoBack(),
                                    ),
                                )
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                viewModel.onEvent(
                                    SafeBrowserUiEvent.PageChanged(
                                        url = url.orEmpty(),
                                        title = view.title.orEmpty(),
                                        canGoBack = view.canGoBack(),
                                    ),
                                )
                                // Hides the slots the refused ad requests would have filled, so
                                // the page does not end up full of empty rectangles.
                                view.evaluateJavascript(adHidingScript(), null)
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                viewModel.onEvent(SafeBrowserUiEvent.Progress(newProgress))
                            }
                        }

                        webView = this
                    }
                },
            )

            // --- the block screen ----------------------------------------------------------
            val blocked = state.blocked
            if (blocked != null) {
                BlockedPageCover(
                    blocked = blocked,
                    onBack = {
                        val view = webView
                        viewModel.onEvent(SafeBrowserUiEvent.DismissBlock)
                        if (view?.canGoBack() == true) view.goBack()
                    },
                    onHome = { viewModel.onEvent(SafeBrowserUiEvent.GoHome) },
                )
            }
        }

        // --- what the page cost --------------------------------------------------------------
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
                text = "${state.stats.imagesCovered} blurred",
                content = SafetyPalette.IndigoDeep,
                container = SafetyPalette.IndigoSoft,
            )
            TonalChip(
                text = "${state.stats.adsBlocked} ads",
                content = SafetyPalette.Success,
                container = SafetyPalette.SuccessSoft,
            )
            TonalChip(
                text = "${state.stats.blockedNavigations} blocked",
                content = SafetyPalette.Danger,
                container = SafetyPalette.DangerSoft,
            )
            Spacer(Modifier.weight(1f))
            if (state.safeSearchEnabled) {
                TonalChip(
                    text = "Safe search",
                    content = SafetyPalette.Slate,
                    container = SafetyPalette.Canvas,
                    icon = Icons.Rounded.Lock,
                )
            }
        }
    }
}

@Composable
private fun BlockedPageCover(
    blocked: SafeBrowserEngine.BlockedPage,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SafetyPalette.Canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(SafetyPalette.DangerSoft, SafetyRadius.chip),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = SafetyPalette.Danger,
                    modifier = Modifier.size(32.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            Text(
                text = "This site is blocked",
                style = MaterialTheme.typography.headlineMedium,
                color = SafetyPalette.Ink,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = blocked.host,
                style = MaterialTheme.typography.titleSmall,
                color = SafetyPalette.Indigo,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = blocked.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = SafetyPalette.Slate,
                textAlign = TextAlign.Center,
            )
            if (blocked.category != null) {
                Spacer(Modifier.height(12.dp))
                TonalChip(
                    text = blocked.category!!,
                    content = SafetyPalette.Danger,
                    container = SafetyPalette.DangerSoft,
                )
            }

            Spacer(Modifier.height(28.dp))

            PrimaryButton(
                text = "Go back",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(9.dp))
            SecondaryButton(
                text = "Start again",
                onClick = onHome,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Injects the ad-slot hiding rules once a page has settled. */
private fun adHidingScript(): String = """
    (function () {
      var style = document.getElementById('halo-ad-style');
      if (!style) {
        style = document.createElement('style');
        style.id = 'halo-ad-style';
        document.documentElement.appendChild(style);
      }
      style.textContent = ${
    com.example.childsafetyimpl.platform.network.AdBlockList.hidingCss
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .let { "\"$it\"" }
};
    })();
""".trimIndent()
