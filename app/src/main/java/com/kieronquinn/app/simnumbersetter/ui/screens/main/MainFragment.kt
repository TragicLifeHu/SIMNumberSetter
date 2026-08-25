package com.kieronquinn.app.simnumbersetter.ui.screens.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kieronquinn.app.simnumbersetter.R
import com.kieronquinn.app.simnumbersetter.databinding.FragmentMainBinding
import com.kieronquinn.app.simnumbersetter.ui.screens.main.MainViewModel.*
import com.kieronquinn.app.simnumbersetter.utils.extensions.hideIme
import com.kieronquinn.app.simnumbersetter.utils.extensions.onChanged
import com.kieronquinn.app.simnumbersetter.utils.extensions.onClicked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.lang.NullPointerException

class MainFragment: Fragment() {

    private val createDebugLogDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val content = viewModel.consumeDebugLogExport() ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openOutputStream(uri, "w")?.use {
                        it.writer().use { writer -> writer.write(content) }
                    } ?: error("The selected document could not be opened")
                }
            }
            val error = result.exceptionOrNull()
            viewModel.onDebugLogExportResult(error)
            Toast.makeText(
                requireContext(),
                if (error == null) R.string.debug_log_saved else R.string.debug_log_save_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private var _binding: FragmentMainBinding? = null
    private var updatingPhoneSelector = false
    private val binding get() = _binding
        ?: throw NullPointerException("Unable to access binding before onViewCreated or after onDestroyView")

    private val viewModel by viewModel<MainViewModel>()
    private val bottomPadding by lazy {
        resources.getDimension(R.dimen.margin_16).toInt()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInsets()
        setupState()
        setupToolbar()
        setupPhoneSelector()
        setupNumber()
        setupSaveButton()
        setupRefreshButton()
        setupCloseButton()
        setupExportDebugLogButton()
        setupInput()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainToolbar) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = topInset)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.includeMainLoaded.root) { view, insets ->
            val bottomInset = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            ).bottom
            view.updatePadding(bottom = bottomPadding + bottomInset)
            insets
        }
    }

    private fun setupToolbar() = with(binding.mainToolbar) {
        MenuInflater(requireContext()).inflate(R.menu.menu, menu)
        setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_export_debug_log) {
                launchDebugLogExport()
            } else {
                viewModel.onMenuItemClicked(requireContext(), it.itemId)
            }
            true
        }
    }

    private fun setupSaveButton() = launchAndRepeatWithViewLifecycle {
        with(binding.includeMainLoaded.includeMainLoadedCardEdit.mainLoadedCardEditInputSave) {
            onClicked().collect {
                it.hideIme(requireActivity().window)
                viewModel.onSaveClicked()
            }
        }
    }

    private fun setupRefreshButton() = launchAndRepeatWithViewLifecycle {
        with(binding.includeMainLoaded.includeMainLoadedCardEdit.mainLoadedCardEditRefresh) {
            onClicked().collect {
                it.hideIme(requireActivity().window)
                viewModel.onRefreshClicked()
            }
        }
    }

    private fun setupCloseButton() = launchAndRepeatWithViewLifecycle {
        with(binding.includeMainError.mainErrorClose) {
            onClicked().collect {
                requireActivity().finish()
            }
        }
    }

    private fun setupInput() = launchAndRepeatWithViewLifecycle {
        with(binding.includeMainLoaded.includeMainLoadedCardEdit.mainLoadedCardEditEdit){
            onChanged().collect {
                viewModel.onNumberChanged(it?.toString() ?: "")
            }
        }
    }

    private fun setupPhoneSelector() = with(
        binding.includeMainLoaded.includeMainLoadedCardEdit.mainLoadedCardEditPhone
    ) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingPhoneSelector) {
                    val state = viewModel.state.value as? State.Loaded ?: return
                    state.activePhoneIds.getOrNull(position)?.let(viewModel::onPhoneSelected)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupState() {
        handleState(viewModel.state.value)
        launchAndRepeatWithViewLifecycle {
            viewModel.state.collect {
                handleState(it)
            }
        }
    }

    private fun handleState(state: State) {
        binding.includeMainLoading.root.isVisible = state is State.Loading
        binding.includeMainLoaded.root.isVisible = state is State.Loaded
        binding.includeMainError.root.isVisible = state is State.Error
        when(state) {
            is State.Loading -> setupWithLoading(state.loadType)
            is State.Error -> setupWithError(state.errorType)
            is State.Loaded -> setupWithLoaded(state)
        }
    }

    private fun setupNumber() {
        launchAndRepeatWithViewLifecycle {
            viewModel.number.filterNotNull().collect {
                handleNumber(it)
            }
        }
    }

    private fun handleNumber(number: String) = with(binding.includeMainLoaded.includeMainLoadedCardEdit) {
        val editText = mainLoadedCardEditEdit
        if (editText.text?.toString() != number) {
            val previousSelectionStart = editText.selectionStart
            val previousSelectionEnd = editText.selectionEnd
            editText.setText(number, TextView.BufferType.EDITABLE)
            if (editText.hasFocus() && previousSelectionStart >= 0 && previousSelectionEnd >= 0) {
                editText.setSelection(
                    previousSelectionStart.coerceAtMost(number.length),
                    previousSelectionEnd.coerceAtMost(number.length),
                )
            }
        }
    }

    private fun setupWithLoading(loadType: LoadType) = with(binding.includeMainLoading) {
        mainLoading.setText(loadType.contentRes)
    }

    private fun setupWithError(errorType: ErrorType) = with(binding.includeMainError) {
        mainErrorContent.setText(errorType.messageRes)
    }

    private fun setupWithLoaded(state: State.Loaded) = with(binding.includeMainLoaded) {
        val formattedNumber = state.number.ifEmpty {
            getString(R.string.main_loaded_card_edit_number_empty)
        }
        val editCard = includeMainLoadedCardEdit
        editCard.mainLoadedCardEditCurrent.text =
            getString(R.string.main_loaded_card_edit_content, formattedNumber)
        editCard.mainLoadedCardEditPhoneContainer.isVisible = state.activePhoneIds.size > 1
        val labels = state.activePhoneIds.map { phoneId ->
            val number = state.phoneNumbers[phoneId].orEmpty().ifEmpty {
                getString(R.string.main_loaded_card_edit_number_empty)
            }
            getString(R.string.main_loaded_card_edit_sim_value, phoneId + 1, number)
        }
        updatingPhoneSelector = true
        try {
            editCard.mainLoadedCardEditPhone.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
            editCard.mainLoadedCardEditPhone.setSelection(
                state.activePhoneIds.indexOf(state.phoneId).coerceAtLeast(0),
                false,
            )
        } finally {
            updatingPhoneSelector = false
        }
    }

    private fun setupExportDebugLogButton() = launchAndRepeatWithViewLifecycle {
        binding.includeMainError.mainErrorExportDebugLog.onClicked().collect {
            launchDebugLogExport()
        }
    }

    private fun launchDebugLogExport() {
        createDebugLogDocument.launch(viewModel.prepareDebugLogExport())
    }

    private fun launchAndRepeatWithViewLifecycle(
        block: suspend CoroutineScope.() -> Unit,
    ) = viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED, block)
    }

}
