package com.sempermechanics.semper.ui.phones

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AccountPhoneDto
import com.sempermechanics.semper.databinding.ActivityPhonesBinding
import com.sempermechanics.semper.databinding.ItemPhoneBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.dialog.ReopenAfterRecreate
import com.sempermechanics.semper.ui.common.setBusy
import com.sempermechanics.semper.ui.limit.SeatRequiredActivity
import kotlinx.coroutines.launch

/**
 * Your phones (ADR-033): where this phone stands on the account's licence for
 * this app, Activate, and every phone the account has registered for it, with
 * Deactivate on each the licence covers. The holder sees only their own
 * phones, never another member's in an institution's pool.
 *
 * Nothing here touches an analysis: a deactivated phone keeps its files, and
 * runs as Demo until it is activated again.
 */
@MainThread
class PhonesActivity : AppCompatActivity() {

    private val viewModel: PhonesViewModel by viewModels()

    private lateinit var binding: ActivityPhonesBinding
    private val adapter = PhoneAdapter()

    /** The Deactivate question, asked again after a rotation (readiness AE1-111). */
    private val reopenDeactivate = ReopenAfterRecreate(this, "phones.deactivate")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhonesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.phonesRoot)

        binding.rvPhones.layoutManager = LinearLayoutManager(this)
        binding.rvPhones.adapter = adapter
        binding.btnPhonesRetry.setOnClickListener { viewModel.load() }
        observeViewModel()
        // Opened from Home's card or the limit screen's Activate: tap it here,
        // once, so the outcome and the list land on this screen.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_ACTIVATE, false)) {
            viewModel.activate(upgrade = intent.getBooleanExtra(EXTRA_UPGRADE, false))
        }
        reopenDeactivate.restored()?.let { state ->
            askDeactivate(
                state.getString(DEVICE_ID).orEmpty(),
                state.getString(MODEL).orEmpty(),
                state.getBoolean(THIS_PHONE),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.screen.collect(::show) }
                launch { viewModel.busy.collect(::setBusy) }
                viewModel.notes.collect { note ->
                    Feedback.toast(this@PhonesActivity, note.text(this@PhonesActivity), long = true)
                }
            }
        }
    }

    private fun show(screen: PhonesViewModel.Screen) {
        binding.progressPhonesList.isVisible = screen is PhonesViewModel.Screen.Loading
        binding.phonesFailed.isVisible = screen is PhonesViewModel.Screen.Failed
        binding.rvPhones.isVisible = screen is PhonesViewModel.Screen.Loaded
        when (screen) {
            PhonesViewModel.Screen.Loading -> Unit
            is PhonesViewModel.Screen.Failed -> binding.tvPhonesFailed.text = screen.note.text(this)
            is PhonesViewModel.Screen.Loaded -> showLoaded(screen)
        }
    }

    private fun showLoaded(screen: PhonesViewModel.Screen.Loaded) {
        val phones = screen.phones
        val count = PhonesView.count(phones.devicesActive, phones.maxDevices, phones.pool)
        binding.tvPhonesCount.isVisible = count != null
        binding.tvPhonesCount.text = count?.text(this).orEmpty()
        val upgrade = screen.pendingPrefix != null
        binding.tvPhonesThis.text = when {
            upgrade && screen.pendingPrefix.isNullOrBlank() -> getString(R.string.phones_upgrade_ready)
            upgrade -> getString(R.string.phones_upgrade_ready_fmt, screen.pendingPrefix)
            else -> getString(screen.thisPhone.statusRes)
        }
        binding.btnPhonesActivate.apply {
            isVisible = upgrade || screen.thisPhone.offersActivate || screen.thisPhone == ThisPhone.FLOATING
            setText(
                when {
                    upgrade -> R.string.phones_activate_upgrade
                    screen.thisPhone == ThisPhone.FLOATING -> R.string.seat_take
                    else -> R.string.phones_activate
                },
            )
            setOnClickListener {
                if (!upgrade && screen.thisPhone == ThisPhone.FLOATING) {
                    startActivity(Intent(this@PhonesActivity, SeatRequiredActivity::class.java))
                } else {
                    viewModel.activate(upgrade)
                }
            }
        }
        adapter.canDeactivate = PhonesView.canDeactivate(phones)
        adapter.submitList(phones.devices)
    }

    private fun setBusy(busy: Boolean) {
        binding.progressPhones.setBusy(busy, binding.btnPhonesActivate, idleVisibility = View.GONE)
        adapter.busy = busy
    }

    private fun askDeactivate(deviceId: String, model: String, thisPhone: Boolean) {
        if (deviceId.isEmpty()) return
        val dialog = Dialogs.confirm(
            this,
            getString(R.string.phones_deactivate_title_fmt, model),
            getText(if (thisPhone) R.string.phones_deactivate_this_body else R.string.phones_deactivate_body),
            R.string.phones_deactivate,
        ) { viewModel.deactivate(deviceId, thisPhone) }
        reopenDeactivate.keep(dialog) { bundleOf(DEVICE_ID to deviceId, MODEL to model, THIS_PHONE to thisPhone) }
    }

    private fun modelOf(phone: AccountPhoneDto): String =
        phone.model.ifBlank { getString(R.string.phones_unknown_model) }

    private inner class PhoneAdapter : ListAdapter<AccountPhoneDto, PhoneAdapter.Holder>(BY_DEVICE) {

        /** A tap is out: Deactivate waits. */
        var busy = false
            set(value) {
                if (field == value) return
                field = value
                @Suppress("NotifyDataSetChanged") // a handful of rows, each with the one button
                notifyDataSetChanged()
            }

        /** The licence deactivates one phone (not a lock licence): Deactivate shows. */
        var canDeactivate = false
            set(value) {
                if (field == value) return
                field = value
                @Suppress("NotifyDataSetChanged") // a handful of rows, each with the one button
                notifyDataSetChanged()
            }

        inner class Holder(val row: ItemPhoneBinding) : RecyclerView.ViewHolder(row.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemPhoneBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val phone = getItem(position)
            val model = modelOf(phone)
            holder.row.tvPhoneModel.text = model
            holder.row.tvPhoneStatus.setText(PhonesView.rowStatus(phone))
            holder.row.btnPhoneDeactivate.apply {
                isVisible = canDeactivate && phone.activated
                isEnabled = !busy
                contentDescription = getString(R.string.phones_deactivate_title_fmt, model)
                setOnClickListener { askDeactivate(phone.deviceId, model, phone.thisDevice) }
            }
        }
    }

    companion object {
        private const val DEVICE_ID = "deviceId"
        private const val MODEL = "model"
        private const val THIS_PHONE = "thisPhone"
        private const val EXTRA_ACTIVATE = "com.sempermechanics.semper.extra.ACTIVATE"
        private const val EXTRA_UPGRADE = "com.sempermechanics.semper.extra.ACTIVATE_UPGRADE"

        private val BY_DEVICE = object : DiffUtil.ItemCallback<AccountPhoneDto>() {
            override fun areItemsTheSame(old: AccountPhoneDto, new: AccountPhoneDto) = old.deviceId == new.deviceId

            override fun areContentsTheSame(old: AccountPhoneDto, new: AccountPhoneDto) = old == new
        }

        /**
         * Opens Your phones; with [activate], taps Activate there at once (on
         * the new licence waiting, with [upgrade]), so the tap runs on the
         * screen's ViewModel and its outcome shows beside the list.
         */
        fun open(context: Context, activate: Boolean = false, upgrade: Boolean = false) {
            context.startActivity(
                Intent(context, PhonesActivity::class.java)
                    .putExtra(EXTRA_ACTIVATE, activate)
                    .putExtra(EXTRA_UPGRADE, upgrade),
            )
        }
    }
}
