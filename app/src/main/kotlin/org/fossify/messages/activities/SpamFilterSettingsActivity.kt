package org.fossify.messages.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivitySpamFilterSettingsBinding
import org.fossify.messages.extensions.config
import org.fossify.messages.helpers.spam.DecisionModel
import org.fossify.messages.helpers.spam.OpenRouterAccount
import org.fossify.messages.helpers.spam.OpenRouterCatalog

class SpamFilterSettingsActivity : SimpleActivity() {

    private val binding by viewBinding(ActivitySpamFilterSettingsBinding::inflate)
    private val handler = Handler(Looper.getMainLooper())
    private val checkKeyLater = Runnable { checkTypedKey() }
    private var decisionModels: List<DecisionModel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.spamFilterScroll))
        updateTextColors(binding.spamFilterCoordinator)
        setupSettings()
        setupKeyCheck()
        loadDecisionModels()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.spamFilterAppbar, NavigationIcon.Arrow)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(checkKeyLater)
        saveSettings()
    }

    private fun setupSettings() = binding.apply {
        spamFilterEnabled.isChecked = config.spamFilterEnabled
        spamFilterEnabledHolder.setOnClickListener {
            spamFilterEnabled.toggle()
            config.spamFilterEnabled = spamFilterEnabled.isChecked
        }
        autoStopEnabled.isChecked = config.autoStopEnabled
        autoStopHolder.setOnClickListener {
            autoStopEnabled.toggle()
            config.autoStopEnabled = autoStopEnabled.isChecked
        }
        openrouterApiKey.setText(config.openRouterApiKey)
    }

    private fun saveSettings() = binding.apply {
        Log.i(TAG, "save: filter=${spamFilterEnabled.isChecked} autoStop=${autoStopEnabled.isChecked}")
        config.spamFilterEnabled = spamFilterEnabled.isChecked
        config.autoStopEnabled = autoStopEnabled.isChecked
        config.openRouterApiKey = openrouterApiKey.text.toString().trim()
    }

    /** Checks the key shortly after it is pasted or edited, so the user sees a check mark or a rejection. */
    private fun setupKeyCheck() {
        binding.openrouterApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(checkKeyLater)
                handler.postDelayed(checkKeyLater, KEY_CHECK_DELAY_MS)
            }
        })
        checkTypedKey()
    }

    private fun checkTypedKey() {
        val key = binding.openrouterApiKey.text.toString().trim()
        if (key.isEmpty()) {
            binding.openrouterKeyStatus.text = ""
            return
        }
        binding.openrouterKeyStatus.text = getString(R.string.key_checking)
        ensureBackgroundThread {
            val result = runCatching { OpenRouterAccount.verify(key) }
            runOnUiThread {
                // A slow answer for an old key must not overwrite the status of the current one
                if (binding.openrouterApiKey.text.toString().trim() != key) return@runOnUiThread
                binding.openrouterKeyStatus.text = result.fold(
                    onSuccess = { info ->
                        val remaining = info.remaining
                        if (remaining == null) {
                            getString(R.string.key_valid, info.label)
                        } else {
                            getString(R.string.key_valid_limit, info.label, remaining)
                        }
                    },
                    onFailure = { getString(R.string.key_rejected) }
                )
            }
        }
    }

    private fun loadDecisionModels() {
        showSelectedModel()
        binding.decisionModel.setOnClickListener { showModelPicker() }
        ensureBackgroundThread {
            val result = runCatching { OpenRouterCatalog.decisionModels() }
            runOnUiThread {
                result.onSuccess { list ->
                    decisionModels = list
                    showSelectedModel()
                }.onFailure {
                    binding.decisionModelStatus.text = getString(R.string.decision_models_failed)
                }
            }
        }
    }

    private fun showSelectedModel() {
        val current = config.openRouterDecisionModel
        binding.decisionModel.text = decisionModels.firstOrNull { it.id == current }?.let(::label) ?: current
    }

    private fun showModelPicker() {
        if (decisionModels.isEmpty()) {
            toast(R.string.decision_models_failed)
            return
        }
        val current = config.openRouterDecisionModel
        val checked = decisionModels.indexOfFirst { it.id == current }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.decision_model)
            .setSingleChoiceItems(decisionModels.map(::label).toTypedArray(), checked) { dialog, which ->
                config.openRouterDecisionModel = decisionModels[which].id
                showSelectedModel()
                dialog.dismiss()
            }
            .show()
    }

    private fun label(model: DecisionModel) = "${model.id}  ·  ${model.priceLabel}"

    companion object {
        private const val TAG = "SpamFilterSettings"
        private const val KEY_CHECK_DELAY_MS = 800L
    }
}
