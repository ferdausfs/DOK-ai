package neth.iecal.curbox.ui.fragments.main.reducers.guardian

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.guardian.GuardianConstants
import neth.iecal.curbox.guardian.GuardianModelImportManager
import neth.iecal.curbox.blockers.GuardianBlocker

/**
 * Guardian (NSFW content blocking) settings — M3.
 *
 * Controls: enable toggle, user gender, three detection thresholds, grid vote
 * count, keyword rules and the three TFLite model imports. Configuration is
 * persisted in the SharedPreferences store that GuardianBlocker reads live.
 */
class GuardianFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "guardian"
        private const val PREFS = "guardian_prefs"
    }

    private var importingModel: String? = null

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            val model = importingModel ?: return@registerForActivityResult
            importingModel = null
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val result = GuardianModelImportManager.get(requireContext())
                    .importModel(uri, model)
                val msg = result.fold(
                    onSuccess = { getString(R.string.guardian_model_imported, model) },
                    onFailure = { getString(R.string.guardian_model_import_failed, model) },
                )
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_guardian, container, false)
        val context = requireContext()
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

        // ── enable ────────────────────────────────────────────────────────────
        val enableSwitch = view.findViewById<MaterialSwitch>(R.id.guardian_enable)
        enableSwitch.isChecked = prefs.getBoolean("enabled", false)
        enableSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("enabled", checked).apply()
            GuardianBlocker.refresh(context)
        }

        // ── gender ────────────────────────────────────────────────────────────
        val gender = prefs.getString("gender", "NONE") ?: "NONE"
        fun bindGender() {
            val current = prefs.getString("gender", "NONE") ?: "NONE"
            view.findViewById<MaterialButton>(R.id.btn_gender_none).isEnabled = current != "NONE"
            view.findViewById<MaterialButton>(R.id.btn_gender_male).isEnabled = current != "MALE"
            view.findViewById<MaterialButton>(R.id.btn_gender_female).isEnabled = current != "FEMALE"
            view.findViewById<TextView>(R.id.txt_gender_value).text = current
        }
        bindGender()
        view.findViewById<MaterialButton>(R.id.btn_gender_none).setOnClickListener {
            prefs.edit().putString("gender", "NONE").apply()
            GuardianBlocker.refresh(context)
            bindGender()
        }
        view.findViewById<MaterialButton>(R.id.btn_gender_male).setOnClickListener {
            prefs.edit().putString("gender", "MALE").apply()
            GuardianBlocker.refresh(context)
            bindGender()
        }
        view.findViewById<MaterialButton>(R.id.btn_gender_female).setOnClickListener {
            prefs.edit().putString("gender", "FEMALE").apply()
            GuardianBlocker.refresh(context)
            bindGender()
        }

        // ── thresholds ────────────────────────────────────────────────────────
        fun slider(label: TextView, slider: Slider, key: String, def: Float, fmt: (Float) -> String) {
            slider.value = prefs.getFloat(key, def)
            label.text = fmt(slider.value)
            slider.addOnChangeListener { _, value, _ ->
                prefs.edit().putFloat(key, value).apply()
                label.text = fmt(value)
                GuardianBlocker.refresh(context)
            }
        }
        slider(
            view.findViewById(R.id.txt_ai_threshold),
            view.findViewById(R.id.slider_ai_threshold),
            "ai_threshold", 0.72f,
        ) { "%.2f".format(it) }
        slider(
            view.findViewById(R.id.txt_nsfw_threshold),
            view.findViewById(R.id.slider_nsfw_threshold),
            "nsfw_gate", 0.68f,
        ) { "%.2f".format(it) }
        slider(
            view.findViewById(R.id.txt_gender_threshold),
            view.findViewById(R.id.slider_gender_threshold),
            "gender_threshold", 0.78f,
        ) { "%.2f".format(it) }
        val votesSlider = view.findViewById<Slider>(R.id.slider_grid_votes)
        val votesLabel = view.findViewById<TextView>(R.id.txt_grid_votes)
        votesSlider.value = prefs.getInt("grid_votes", 2).toFloat()
        votesLabel.text = prefs.getInt("grid_votes", 2).toString()
        votesSlider.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            prefs.edit().putInt("grid_votes", v).apply()
            votesLabel.text = v.toString()
            GuardianBlocker.refresh(context)
        }

        // ── keywords ──────────────────────────────────────────────────────────
        val keywordInput = view.findViewById<TextInputEditText>(R.id.guardian_keyword_input)
        val keywordList = view.findViewById<LinearLayout>(R.id.guardian_keyword_list)
        fun refreshKeywords() {
            keywordList.removeAllViews()
            val kws = prefs.getStringSet("keywords", emptySet()) ?: emptySet()
            for (kw in kws.sorted()) {
                val row = inflater.inflate(R.layout.item_guardian_keyword, keywordList, false)
                row.findViewById<TextView>(R.id.txt_keyword).text = kw
                row.findViewById<MaterialButton>(R.id.btn_keyword_remove).setOnClickListener {
                    prefs.edit().putStringSet("keywords", kws - kw).apply()
                    GuardianBlocker.refresh(context)
                    refreshKeywords()
                }
                keywordList.addView(row)
            }
        }
        refreshKeywords()
        view.findViewById<MaterialButton>(R.id.btn_keyword_add).setOnClickListener {
            val raw = keywordInput.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) return@setOnClickListener
            val kws = (prefs.getStringSet("keywords", emptySet()) ?: emptySet()).toMutableSet()
            kws.add(raw)
            prefs.edit().putStringSet("keywords", kws).apply()
            GuardianBlocker.refresh(context)
            keywordInput.text?.clear()
            refreshKeywords()
        }

        // ── model import ──────────────────────────────────────────────────────
        fun bindModel(label: TextView, name: String) {
            val mgr = GuardianModelImportManager.get(context)
            label.text = if (mgr.isModelImported(name)) {
                getString(R.string.guardian_model_ready, mgr.modelSizeBytes(name) / 1024)
            } else {
                getString(R.string.guardian_model_missing)
            }
        }
        bindModel(view.findViewById(R.id.txt_model_legacy), GuardianConstants.MODEL_LEGACY)
        bindModel(view.findViewById(R.id.txt_model_nsfw), GuardianConstants.MODEL_NSFW)
        bindModel(view.findViewById(R.id.txt_model_gender), GuardianConstants.MODEL_GENDER)
        view.findViewById<MaterialButton>(R.id.btn_import_legacy).setOnClickListener {
            importingModel = GuardianConstants.MODEL_LEGACY
            openDocument.launch("*/*")
        }
        view.findViewById<MaterialButton>(R.id.btn_import_nsfw).setOnClickListener {
            importingModel = GuardianConstants.MODEL_NSFW
            openDocument.launch("*/*")
        }
        view.findViewById<MaterialButton>(R.id.btn_import_gender).setOnClickListener {
            importingModel = GuardianConstants.MODEL_GENDER
            openDocument.launch("*/*")
        }

        // ── activity log ────────────────────────────────────────────────────────
        val logView = view.findViewById<TextView>(R.id.guardian_block_log)
        fun refreshLog() {
            val log = prefs.getString("block_log", "") ?: ""
            logView.text = if (log.isBlank()) getString(R.string.guardian_log_empty) else log
        }
        refreshLog()
        view.findViewById<MaterialButton>(R.id.btn_log_clear).setOnClickListener {
            prefs.edit().putString("block_log", "").apply()
            refreshLog()
        }

        view.findViewById<MaterialButton>(R.id.btn_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        return view
    }
}
