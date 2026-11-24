package local.cinema.launcher

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.leanback.widget.VerticalGridView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every launchable app, including the non-media ones kept out of the home row. */
class AllAppsActivity : Activity() {

    private val scope = MainScope()
    private lateinit var repo: AppRepo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_apps)
        repo = AppRepo(this, Prefs(this))
        findViewById<TextView>(R.id.allTitle).typeface = ResourcesCompat.getFont(this, R.font.space_grotesk)

        val grid = findViewById<VerticalGridView>(R.id.allGrid)
        grid.setNumColumns(COLUMNS)
        grid.itemAnimator = null
        grid.horizontalSpacing = dp(14)
        grid.verticalSpacing = dp(14)
        val adapter = AppsAdapter(
            onFocus = {},
            onClick = { repo.launch(this, it) },
            onLongClick = ::toggleInRow,
            tileWidthDp = 132,
            tileHeightDp = 74,
            focusScale = 1.08f,
        )
        grid.adapter = adapter

        scope.launch {
            adapter.apps = withContext(Dispatchers.IO) { repo.allApps() }
            grid.requestFocus()
        }
    }

    private fun toggleInRow(app: AppEntry) {
        val inRow = app.packageName in repo.rowPackages()
        val choice = getString(if (inRow) R.string.remove_from_row else R.string.add_to_row)
        AlertDialog.Builder(this, R.style.Theme_Cinema_Dialog)
            .setTitle(app.label)
            .setItems(arrayOf(choice)) { _, _ ->
                if (inRow) repo.remove(app.packageName) else repo.add(app.packageName)
            }
            .show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val COLUMNS = 6
    }
}
