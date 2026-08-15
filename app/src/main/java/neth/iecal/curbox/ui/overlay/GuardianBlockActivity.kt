package neth.iecal.curbox.ui.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.activity.FragmentActivity

/**
 * Full-screen block shown when Guardian detects NSFW content (M4).
 *
 * Deliberately NOT the AppBlocker WarningActivity — that screen is config- and
 * mode-driven; Guardian needs a simple, predictable screen: why it was blocked,
 * a "go home" action and a link to the Guardian settings.
 */
class GuardianBlockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REASON = "guardian_reason"
        const val EXTRA_PACKAGE = "guardian_package"
        const val EXTRA_TIME = "guardian_time"

        fun start(context: Context, reason: String, pkg: String) {
            val intent = Intent(context, GuardianBlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_TIME, System.currentTimeMillis())
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_block)

        val reason = intent.getStringExtra(EXTRA_REASON) ?: "NSFW content"
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: ""

        findViewById<TextView>(R.id.guardian_block_reason).text =
            getString(R.string.guardian_block_reason_fmt, reason)
        findViewById<TextView>(R.id.guardian_block_package).text =
            getString(R.string.guardian_block_package_fmt, pkg)

        findViewById<MaterialButton>(R.id.btn_go_home).setOnClickListener {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(home)
            finish()
        }

        findViewById<MaterialButton>(R.id.btn_guardian_settings).setOnClickListener {
            val settings = Intent(this, FragmentActivity::class.java).apply {
                putExtra(
                    "fragment",
                    neth.iecal.curbox.ui.fragments.main.reducers.guardian.GuardianFragment.FRAGMENT_ID,
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(settings)
            finish()
        }
    }
}
