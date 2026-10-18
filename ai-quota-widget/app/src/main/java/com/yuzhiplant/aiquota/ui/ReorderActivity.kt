package com.yuzhiplant.aiquota.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.yuzhiplant.aiquota.R
import com.yuzhiplant.aiquota.data.ResultCache
import com.yuzhiplant.aiquota.data.SectionOrder
import com.yuzhiplant.aiquota.data.WidgetVisibility
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.widget.QuotaWidgetProvider
import java.util.Collections

/**
 * 排序與顯示：
 * - 拖移 ≡ 調整平台順序（App 與小工具共用），放開即儲存
 * - 平台開關、額度勾選：決定桌面小工具顯示哪些（App 內一律全部顯示）
 */
class ReorderActivity : AppCompatActivity() {

    private data class ItemEntry(val key: String, val label: String, val defaultVisible: Boolean)
    private data class Group(val key: String, val name: String, val items: List<ItemEntry>)

    private val groups = mutableListOf<Group>()
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reorder)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        groups += buildGroups(SectionOrder.apply(this, ResultCache.load(this)))

        val list = findViewById<RecyclerView>(R.id.list)
        val adapter = Adapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                Collections.swap(groups, a, b)
                adapter.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) viewHolder?.itemView?.alpha = 0.85f
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.alpha = 1f
                SectionOrder.save(this@ReorderActivity, groups.map { it.key })
                adapter.notifyItemRangeChanged(0, groups.size)
                QuotaWidgetProvider.updateAll(this@ReorderActivity)
            }
        })
        touchHelper.attachToRecyclerView(list)
    }

    /** 從最近一次抓取結果整理出「平台 → 額度清單」。 */
    private fun buildGroups(results: List<ProviderResult>): List<Group> {
        val byGroup = LinkedHashMap<String, MutableList<ProviderResult>>()
        for (r in results) byGroup.getOrPut(SectionOrder.groupKey(r.id)) { mutableListOf() } += r
        return byGroup.map { (key, rs) ->
            val isSupabase = key == "supabase"
            val items = rs.flatMap { r ->
                val project = r.name.removePrefix("Supabase・")
                r.items.filter { it.label != "專案狀態" }.map { item ->
                    ItemEntry(
                        key = WidgetVisibility.itemKey(r.id, item.label),
                        label = if (isSupabase && !r.id.startsWith("supabase_org_")) "$project・${item.label}" else item.label,
                        defaultVisible = WidgetVisibility.defaultVisible(r.id, item),
                    )
                }
            }
            Group(key, SectionOrder.groupName(rs.first()), items)
        }
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val index: TextView = view.findViewById(R.id.reorder_index)
        val name: TextView = view.findViewById(R.id.reorder_name)
        val toggle: MaterialSwitch = view.findViewById(R.id.reorder_switch)
        val hint: TextView = view.findViewById(R.id.reorder_hint)
        val items: LinearLayout = view.findViewById(R.id.reorder_items)
        val handle: View = view.findViewById(R.id.reorder_handle)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_reorder, parent, false))

        override fun getItemCount() = groups.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val ctx = this@ReorderActivity
            val group = groups[position]
            val vis = WidgetVisibility.load(ctx)
            holder.index.text = "${position + 1}"
            holder.name.text = group.name

            val groupOn = WidgetVisibility.groupVisible(vis, group.key)
            holder.toggle.setOnCheckedChangeListener(null)
            holder.toggle.isChecked = groupOn
            holder.toggle.setOnCheckedChangeListener { _, checked ->
                WidgetVisibility.set(ctx, WidgetVisibility.groupKey(group.key), checked)
                QuotaWidgetProvider.updateAll(ctx)
                notifyItemChanged(holder.bindingAdapterPosition)
            }

            holder.hint.visibility = if (group.key == "supabase" || !groupOn) View.VISIBLE else View.GONE
            holder.hint.text = if (!groupOn) {
                "小工具不顯示這個平台"
            } else {
                "小工具固定顯示專案狀態摘要；以下未設定的項目，用量超過 50% 才會出現"
            }

            holder.items.removeAllViews()
            for (item in group.items) {
                val cb = MaterialCheckBox(ctx)
                cb.text = item.label
                cb.minHeight = (44 * resources.displayMetrics.density).toInt()
                cb.isChecked = vis[item.key] ?: item.defaultVisible
                cb.isEnabled = groupOn
                cb.setOnCheckedChangeListener { _, checked ->
                    WidgetVisibility.set(ctx, item.key, checked)
                    QuotaWidgetProvider.updateAll(ctx)
                }
                holder.items.addView(cb)
            }

            holder.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                false
            }
        }
    }
}
