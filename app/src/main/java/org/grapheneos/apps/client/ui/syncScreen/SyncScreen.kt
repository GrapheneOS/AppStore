package org.grapheneos.apps.client.ui.syncScreen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.databinding.SyncScreenBinding
import org.grapheneos.apps.client.item.MetadataCallBack
import org.grapheneos.apps.client.utils.runOnUiThread
import org.grapheneos.apps.client.utils.showSnackbar

class SyncScreen : Fragment() {

    private lateinit var binding: SyncScreenBinding
    private val appsViewModel by lazy {
        requireContext().applicationContext as App
    }
    private val args by lazy {
        navArgs<SyncScreenArgs>().value
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SyncScreenBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.retrySync.setOnClickListener { refresh() }
        if (args.shouldSync) {
            refresh()
        } else {
            updateUi(isSyncing = false, canRetry = true)
        }
    }

    private fun refresh() {
        updateUi(isSyncing = true, canRetry = false)
        appsViewModel.refreshMetadata {
            updateUi(isSyncing = false, canRetry = !it.isSuccessFull)
            if (it is MetadataCallBack.Success) {
                runOnUiThread {
                    findNavController().popBackStack()
                }
            } else {
                showSnackbar(
                    it.genericMsg + if (it.error != null) "\n${it.error.localizedMessage}" else "",
                    !it.isSuccessFull
                )
            }
        }
    }

    private fun updateUi(isSyncing: Boolean = true, canRetry: Boolean = false) {
        runOnUiThread {
            binding.apply {
                syncing.isVisible = isSyncing
                syncingProgressbar.isVisible = isSyncing
                retrySync.isVisible = !isSyncing && canRetry
            }
        }
    }
}
