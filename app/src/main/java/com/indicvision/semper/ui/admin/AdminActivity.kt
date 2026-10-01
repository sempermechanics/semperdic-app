package com.indicvision.semper.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.indicvision.semper.R
import com.indicvision.semper.data.account.AccessStatus
import com.indicvision.semper.data.net.AdminUserDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.launch

/**
 * Admin-only "Access requests" screen: lists PENDING users and approves/denies
 * them via the /v1/admin endpoints. Reached from the Home settings sheet, and
 * only shown to accounts whose backend role is `admin`.
 */
@MainThread
class AdminActivity : AppCompatActivity() {

    private val api by lazy { IndicApi.get(applicationContext) }

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar
    private val adapter = RequestAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        Insets.padVertical(findViewById(R.id.adminRoot))

        rv = findViewById(R.id.rvRequests)
        tvEmpty = findViewById(R.id.tvEmpty)
        progress = findViewById(R.id.progressAdmin)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        load()
    }

    private fun load() {
        setLoading(true)
        lifecycleScope.launch {
            val token = TokenProvider.usableIdToken()
            if (token == null) {
                setLoading(false)
                Toast.makeText(this@AdminActivity, R.string.error_generic, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            // Cancellation (the screen closed) propagates: it is not a load error.
            suspendRunCatching { api.listUsers(token, AccessStatus.PENDING) }
                .onSuccess { users ->
                    adapter.submit(users)
                    tvEmpty.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
                }
                .onFailure { e ->
                    Toast.makeText(
                        this@AdminActivity,
                        getString(R.string.admin_load_error, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
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
                    val msg = if (action == "approve") {
                        getString(R.string.admin_approved_toast, label)
                    } else {
                        getString(R.string.admin_denied_toast, label)
                    }
                    Toast.makeText(this@AdminActivity, msg, Toast.LENGTH_SHORT).show()
                    load() // refresh the list
                }
                .onFailure { e ->
                    setLoading(false)
                    Toast.makeText(
                        this@AdminActivity,
                        getString(R.string.admin_action_error, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private inner class RequestAdapter : RecyclerView.Adapter<RequestAdapter.Holder>() {
        private var items: List<AdminUserDto> = emptyList()

        fun submit(newItems: List<AdminUserDto>) {
            items = newItems
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val email: TextView = v.findViewById(R.id.tvEmail)
            val name: TextView = v.findViewById(R.id.tvName)
            val approve: MaterialButton = v.findViewById(R.id.btnApprove)
            val deny: MaterialButton = v.findViewById(R.id.btnDeny)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_user, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val u = items[position]
            holder.email.text = u.email ?: u.uid
            holder.name.text = u.displayName ?: ""
            holder.name.visibility = if (u.displayName.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.approve.setOnClickListener { act(u, "approve") }
            holder.deny.setOnClickListener { act(u, "revoke") }
        }

        override fun getItemCount(): Int = items.size
    }
}
