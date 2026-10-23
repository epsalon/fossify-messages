package org.fossify.messages.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
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
        autoStopEnabled.isChecked = config.autoStopEnabled
        openrouterApiKey.setText(config.openRouterApiKey)
    }

    private fun saveSettings() = binding.apply {
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
        val current = config.openRouterDecisionModel
        binding.decisionModel.setText(current, false)
        ensureBackgroundThread {
            val result = runCatching { OpenRouterCatalog.decisionModels() }
            runOnUiThread {
                result.onSuccess { list ->
                    decisionModels = list
                    binding.decisionModel.setAdapter(
                        ArrayAdapter(this, android.R.layout.simple_list_item_1, list.map(::label))
                    )
                    binding.decisionModel.setText(list.firstOrNull { it.id == current }?.let(::label) ?: current, false)
                    binding.decisionModel.setOnItemClickListener { _, _, position, _ ->
                        config.openRouterDecisionModel = decisionModels[position].id
                    }
                }.onFailure {
                    binding.decisionModelStatus.text = getString(R.string.decision_models_failed)
                }
            }
        }
    }

    private fun label(model: DecisionModel) = "${model.id}  ·  ${model.priceLabel}"

    companion object {
        private const val KEY_CHECK_DELAY_MS = 800L
    }
}
