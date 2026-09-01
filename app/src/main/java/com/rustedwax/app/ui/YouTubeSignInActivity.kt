package com.rustedwax.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.rustedwax.app.R
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.enrich.WatchHistoryParser
import com.rustedwax.app.enrich.WatchHistoryResolver
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.app.storage.Settings
import com.rustedwax.app.storage.YouTubeSessionVault
import kotlinx.coroutines.launch

/**
 * Connects a YouTube account so watch history can name the video being played.
 *
 * ## The app never handles the password
 *
 * Google's own sign-in page is loaded in a WebView and the **user** signs in on
 * it. RustedWax types nothing, reads no field, and has no code path that can
 * see a password or a second factor. What it takes afterwards is the resulting
 * cookie jar for `youtube.com`, which it moves straight into the encrypted
 * [YouTubeSessionVault] and then deletes from the WebView, so the only copy on
 * the device is the encrypted one.
 *
 * ## Why the disclosure comes first
 *
 * A sign-in prompt with no explanation is indistinguishable from a phishing
 * screen, and this one is asking for the most valuable credential the phone
 * has. So the first screen states plainly what is read, what is stored, where
 * it is sent, and how to undo it — before Google's page is ever loaded.
 *
 * ## Why not the Android account picker
 *
 * The natural ask is "it is an Android phone, use the account that is already
 * signed in". Android does not offer that: a device Google account is not a web
 * session, and converting one into web cookies needs privileged Play Services
 * scopes that a sideloaded app does not have. The WebView starts with an empty
 * cookie jar, so Google shows its ordinary account chooser or sign-in form and
 * the user picks there.
 */
class YouTubeSignInActivity : ComponentActivity() {

	private enum class Stage { DISCLOSURE, SIGNING_IN, CHECKING, DONE }

	@OptIn(ExperimentalMaterial3Api::class)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		EventLog.init(this)
		FinalizationRuntime.init(this)
		val settings = Settings(this)

		setContent {
			// The saved RustedWax appearance, not Material's defaults. This
			// screen used to compose a bare `MaterialTheme {}`, so a phone set to
			// the app's dark theme opened the sign-in flow in purple-on-white and
			// it read as a different app asking for a Google password — which is
			// exactly the impression a sign-in screen must not give. Google's own
			// page inside the WebView is Google's and is left untouched.
			RustedWaxWindow(window, AppTheme.choice(settings.themeChoice)) {
				var stage by remember { mutableStateOf(Stage.DISCLOSURE) }
				var outcome by remember { mutableStateOf<String?>(null) }
				var outcomeIsError by remember { mutableStateOf(false) }

				fun finishWith(message: String, isError: Boolean) {
					outcome = message
					outcomeIsError = isError
					stage = Stage.DONE
				}

				Scaffold(
					containerColor = MaterialTheme.colorScheme.background,
					topBar = {
						TopAppBar(
							title = { Text("YouTube watch history") },
							colors = TopAppBarDefaults.topAppBarColors(
								containerColor = MaterialTheme.colorScheme.background,
								titleContentColor = MaterialTheme.colorScheme.onBackground,
							),
						)
					},
				) { padding ->
					Column(
						Modifier
							.fillMaxSize()
							.padding(padding)
							.padding(horizontal = 16.dp),
					) {
						when (stage) {
							Stage.DISCLOSURE -> Disclosure(
								onContinue = { stage = Stage.SIGNING_IN },
								onCancel = ::finish,
							)

							Stage.SIGNING_IN -> SignInWebView(
								onSessionHarvested = { cookieHeader ->
									stage = Stage.CHECKING
									verify(cookieHeader, ::finishWith)
								},
							)

							Stage.CHECKING -> Column(Modifier.padding(top = 24.dp)) {
								CircularProgressIndicator(
									color = MaterialTheme.colorScheme.primary,
								)
								Spacer(Modifier.height(12.dp))
								Text(
									"Checking what this account's watch history can be read for…",
									style = MaterialTheme.typography.bodyMedium,
								)
							}

							Stage.DONE -> Column(Modifier.padding(top = 24.dp)) {
								Text(
									outcome.orEmpty(),
									style = MaterialTheme.typography.bodyMedium,
									color = if (outcomeIsError) {
										MaterialTheme.colorScheme.error
									} else {
										MaterialTheme.colorScheme.onSurface
									},
								)
								Spacer(Modifier.height(16.dp))
								WaxButton(onClick = ::finish) { Text("Done") }
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Store, then prove. A jar that carries a session cookie is not the same
	 * thing as a session that can read history, and the difference — a paused
	 * history, a brand-new account — is the user's to know immediately.
	 */
	private fun verify(cookieHeader: String, done: (String, Boolean) -> Unit) {
		FinalizationRuntime.connectYouTubeSession(cookieHeader, accountLabel = null)
		clearWebViewSession()
		lifecycleScope.launch {
			when (val probe = FinalizationRuntime.probeWatchHistory()) {
				is WatchHistoryResolver.Probe.Working -> done(
					"Connected" +
						(probe.accountLabel?.let { " as $it" } ?: "") +
						". RustedWax read ${probe.entries} recent entries" +
						(probe.newestTitle?.let { ", the newest being “$it”" } ?: "") +
						".\n\nMake sure the YouTube app on this phone is signed in to the " +
						"same account, and is not playing in incognito — nothing played " +
						"otherwise reaches this history, and RustedWax will stop using it " +
						"and say so.",
					false,
				)

				is WatchHistoryResolver.Probe.Faulted -> {
					// A session that cannot read history is not worth keeping,
					// and keeping it would leave a live credential on the device
					// for no benefit at all.
					if (probe.reason == WatchHistoryParser.Reason.SIGNED_OUT ||
						probe.reason == WatchHistoryParser.Reason.MARKUP_CHANGED
					) {
						FinalizationRuntime.disconnectYouTubeSession()
					}
					done(
						when (probe.reason) {
							WatchHistoryParser.Reason.SIGNED_OUT ->
								"That sign-in did not produce a session YouTube accepts, so " +
									"nothing was stored. ${probe.message}"

							WatchHistoryParser.Reason.HISTORY_PAUSED ->
								"Connected, but watch history is paused for this account, so " +
									"nothing you play is recorded. Turn it back on in YouTube " +
									"settings and RustedWax will pick it up. (${probe.message})"

							WatchHistoryParser.Reason.EMPTY ->
								"Connected, but this account's watch history is empty right " +
									"now. Play something in the YouTube app and check back."

							WatchHistoryParser.Reason.MARKUP_CHANGED ->
								"Nothing was stored: the history page no longer has the shape " +
									"RustedWax reads, and it will not guess. ${probe.message}"

							null -> "Connected, but the check could not run: ${probe.message}"
						},
						probe.reason == WatchHistoryParser.Reason.SIGNED_OUT ||
							probe.reason == WatchHistoryParser.Reason.MARKUP_CHANGED,
					)
				}
			}
		}
	}

	/**
	 * Leave no second copy. The vault holds the session encrypted; the WebView's
	 * own cookie file is plain, so it is emptied the moment it has been read.
	 */
	private fun clearWebViewSession() {
		CookieManager.getInstance().removeAllCookies(null)
		CookieManager.getInstance().flush()
		WebView(this).apply {
			clearCache(true)
			clearHistory()
			destroy()
		}
	}

	@Composable
	private fun Disclosure(onContinue: () -> Unit, onCancel: () -> Unit) {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			Spacer(Modifier.height(8.dp))
			Text(
				stringResource(R.string.watch_history_disclosure_title),
				style = MaterialTheme.typography.titleMedium,
			)
			Spacer(Modifier.height(8.dp))
			Text(
				stringResource(R.string.watch_history_disclosure),
				style = MaterialTheme.typography.bodyMedium,
			)
			Spacer(Modifier.height(16.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				WaxButton(onClick = onContinue, icon = WaxIcons.Key) {
					Text("Sign in with Google")
				}
				WaxOutlinedButton(onClick = onCancel) { Text("Not now") }
			}
			Spacer(Modifier.height(16.dp))
		}
	}

	/**
	 * Google's page, unmodified, driven by the user.
	 *
	 * The only deviation is the user-agent string: Android's WebView appends
	 * `; wv`, and Google refuses to serve its sign-in flow to a user agent that
	 * announces itself as an embedded browser. The rest of the string is left
	 * exactly as the system reports it — this is not pretending to be a
	 * different device, only declining to be refused for being in an app.
	 */
	@SuppressLint("SetJavaScriptEnabled")
	@Composable
	private fun SignInWebView(onSessionHarvested: (String) -> Unit) {
		var note by remember { mutableStateOf("Loading accounts.google.com…") }
		Column(Modifier.fillMaxSize()) {
			Text(
				note,
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier.padding(vertical = 6.dp),
			)
			AndroidView(
				modifier = Modifier.fillMaxWidth().weight(1f),
				factory = { context ->
					CookieManager.getInstance().setAcceptCookie(true)
					WebView(context).apply {
						settings.javaScriptEnabled = true
						settings.domStorageEnabled = true
						settings.userAgentString =
							WebSettings.getDefaultUserAgent(context).replace("; wv", "")
						CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
						webViewClient = object : WebViewClient() {
							override fun onPageStarted(
								view: WebView?,
								url: String?,
								favicon: Bitmap?,
							) {
								// Hosts only. A sign-in URL carries tokens in its
								// query, and none of that belongs in a note on
								// screen or anywhere near the log.
								note = "Signing in at ${hostOf(url)}…"
							}

							override fun onPageFinished(view: WebView?, url: String?) {
								val host = hostOf(url)
								if (!host.endsWith("youtube.com")) return
								val jar = CookieManager.getInstance()
									.getCookie("https://www.youtube.com")
								if (!YouTubeSessionVault.looksSignedIn(jar)) {
									note = "Signed in to Google — waiting for youtube.com…"
									return
								}
								note = "Session established. Storing it encrypted…"
								onSessionHarvested(jar)
							}
						}
						loadUrl(SIGN_IN_URL)
					}
				},
			)
		}
	}

	private fun hostOf(url: String?): String =
		runCatching { java.net.URI(url.orEmpty()).host.orEmpty() }.getOrDefault("")

	private companion object {
		/**
		 * Google's own sign-in, told to land on the history feed afterwards.
		 * `continue` is what turns a Google session into youtube.com cookies,
		 * which is the only thing this screen is here to obtain.
		 */
		const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin" +
			"?service=youtube&hl=en&continue=https%3A%2F%2Fwww.youtube.com%2Ffeed%2Fhistory"
	}
}
