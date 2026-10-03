package com.sempermechanics.semper.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AccessStatus
import com.sempermechanics.semper.data.net.AdminUserDto
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.databinding.ActivityAdminBinding
import com.sempermechanics.semper.databinding.ItemAdminUserBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.setBusy
import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.launch

/**
 * Admin-only "Access requests" screen: lists PENDING users and approves/denies
 * them via the /v1/admin endpoints. Reached from the Home settings sheet, and
 * only shown to accounts whose backend role is `admin`.
 */
@MainThread
class AdminActivity : AppCompatActivity() {

    private val api by lazy { SemperApi.get(applicationContext) }

    private lateinit var binding: ActivityAdminBinding
    private val adapter = RequestAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.adminRoot)

        binding.rvRequests.layoutManager = LinearLayoutManager(this)
        binding.rvRequests.adapter = adapter

        load()
    }

    private fun load() {
        setLoading(true)
        lifecycleScope.launch {
            val token = TokenProvider.usableIdToken()
            if (token == null) {
                setLoading(false)
                Feedback.toast(this@AdminActivity, R.string.error_generic, long = true)
                finish()
                return@launch
            }
            // Cancellation (the screen closed) propagates: it is not a load error.
            suspendRunCatching { api.listUsers(token, AccessStatus.PENDING) }
                .onSuccess { users ->
                    adapter.submitList(users)
                    binding.tvEmpty.isVisible = users.isEmpty()
                }
                .onFailure { e ->
                    val message = getString(R.string.admin_load_error_fmt, e.message ?: "")
                    Feedback.toast(this@AdminActivity, message, long = true)
                }
            setLoading(false)
        }
    }

    private fun act(user: AdminUserDto, action: String) {
        setLoading(true)
        lifecycleScope.launch {
            val token = TokenProvider.usableIdToken()
            if (token == null) {
                setLoading(false)
                return@launch
            }
            suspendRunCatching { api.setUserStatus(token, user.uid, action) }
                .onSuccess {
                    val label = user.email ?: user.uid
                    val message = if (action == "approve") {
                        getString(R.string.admin_approved_toast_fmt, label)
                    } else {
                        getString(R.string.admin_denied_toast_fmt, label)
                    }
                    Feedback.toast(this@AdminActivity, message)
                    load() // refresh the list
                }
                .onFailure { e ->
                    setLoading(false)
                    Feedback.toast(
                        this@AdminActivity,
                        getString(R.string.admin_action_error_fmt, e.message ?: ""),
                        long = true,
                    )
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressAdmin.setBusy(loading, idleVisibility = View.GONE)
    }

    private inner class RequestAdapter : ListAdapter<AdminUserDto, RequestAdapter.Holder>(BY_UID) {

        inner class Holder(val row: ItemAdminUserBinding) : RecyclerView.ViewHolder(row.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemAdminUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val u = getItem(position)
            holder.row.tvEmail.text = u.email ?: u.uid
            holder.row.tvName.text = u.displayName ?: ""
            holder.row.tvName.isVisible = !u.displayName.isNullOrBlank()
            holder.row.btnApprove.setOnClickListener { act(u, "approve") }
            holder.row.btnDeny.setOnClickListener { act(u, "revoke") }
        }
    }

    private companion object {
        val BY_UID = object : DiffUtil.ItemCallback<AdminUserDto>() {
            override fun areItemsTheSame(oldItem: AdminUserDto, newItem: AdminUserDto) = oldItem.uid == newItem.uid

            override fun areContentsTheSame(oldItem: AdminUserDto, newItem: AdminUserDto) = oldItem == newItem
        }
    }
}
