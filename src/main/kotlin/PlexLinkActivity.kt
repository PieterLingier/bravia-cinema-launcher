package local.cinema.launcher

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Links a Plex account with the plex.tv/link code flow, so no password is ever typed on
 * the TV: show a 4-character code, wait until it's entered on plex.tv/link, then find the server.
 */
class PlexLinkActivity : Activity() {

    private val scope = MainScope()
    private var job: Job? = null
    private lateinit var prefs: Prefs
    private lateinit var plex: PlexClient
    private lateinit var code: TextView
    private lateinit var status: TextView
    private lateinit var retry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_link)
        prefs = Prefs(this)
        plex = PlexClient(prefs)
        code = findViewById(R.id.linkCode)
        status = findViewById(R.id.linkStatus)
        retry = findViewById(R.id.linkRetry)

        val grotesk = ResourcesCompat.getFont(this, R.font.space_grotesk)
        code.typeface = grotesk
        code.setFontVariationSettings("'wght' 700")
        findViewById<TextView>(R.id.linkHeading).typeface = grotesk
        findViewById<TextView>(R.id.linkUrl).apply {
            typeface = grotesk
            setFontVariationSettings("'wght' 600")
        }

        retry.setOnClickListener { start() }
        findViewById<Button>(R.id.linkCancel).apply {
            setOnClickListener { finish() }
            requestFocus()
        }
        start()
    }

    private fun start() {
        job?.cancel()
        retry.visibility = View.GONE
        code.text = "····"
        status.text = getString(R.string.link_getting_code)
        job = scope.launch {
            val token = try {
                waitForToken()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(getString(R.string.link_failed, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            prefs.plexToken = token
            status.text = getString(R.string.link_finding_server)
            try {
                val server = withContext(Dispatchers.IO) { plex.findServer(token) }
                prefs.server = server
                status.text = getString(R.string.link_done, server.name)
                delay(1500)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The account is linked; the home screen retries the server on its own.
                status.text = getString(R.string.link_server_failed, e.message ?: e.javaClass.simpleName)
                delay(4000)
            }
            finish()
        }
    }

    /** Shows a code and polls until it's entered; a fresh code replaces one that expires. */
    private suspend fun waitForToken(): String {
        var token: String? = null
        while (token == null) {
            val pin = withContext(Dispatchers.IO) { plex.createPin() }
            code.text = pin.code
            status.text = getString(R.string.link_waiting)
            val expires = SystemClock.elapsedRealtime() + (pin.expiresInSec - 5).coerceAtLeast(30) * 1000L
            while (token == null && SystemClock.elapsedRealtime() < expires) {
                delay(2000)
                token = try {
                    withContext(Dispatchers.IO) { plex.checkPin(pin.id) }
                } catch (e: PinExpiredException) {
                    break
                }
            }
        }
        return checkNotNull(token)
    }

    private fun fail(message: String) {
        status.text = message
        retry.visibility = View.VISIBLE
        retry.requestFocus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
