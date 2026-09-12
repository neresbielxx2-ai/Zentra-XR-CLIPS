package com.zentra.clip

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppPickerActivity : AppCompatActivity() {

    private val apps = ArrayList<AppItem>()
    private lateinit var adapter: AppAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        emptyView = findViewById(R.id.emptyView)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter()
        recycler.adapter = adapter

        val search = findViewById<EditText>(R.id.etSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString() ?: "")
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        Thread {
            val list = loadApps()
            runOnUiThread {
                apps.clear()
                apps.addAll(list)
                adapter.filter(search.text?.toString() ?: "")
            }
        }.start()
    }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ris = try {
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }
        val out = ArrayList<AppItem>()
        for (ri in ris) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            val label = try {
                ri.loadLabel(pm)?.toString() ?: pkg
            } catch (e: Exception) {
                pkg
            }
            val icon = try {
                ri.loadIcon(pm)
            } catch (e: Exception) {
                null
            } ?: continue
            out.add(AppItem(label, pkg, icon))
        }
        return out.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {

        private val shown = ArrayList<AppItem>()

        fun filter(queryRaw: String) {
            val q = queryRaw.trim().lowercase()
            shown.clear()
            if (q.isEmpty()) {
                shown.addAll(apps)
            } else {
                shown.addAll(apps.filter {
                    it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                })
            }
            emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.tvName)
            val pkg: TextView = v.findViewById(R.id.tvPackage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = shown[position]
            holder.icon.setImageDrawable(item.icon)
            holder.name.text = item.label
            holder.pkg.text = item.packageName
            holder.itemView.setOnClickListener {
                Prefs.setSelectedApp(this@AppPickerActivity, item.packageName, item.label)
                finish()
            }
        }
    }
}
