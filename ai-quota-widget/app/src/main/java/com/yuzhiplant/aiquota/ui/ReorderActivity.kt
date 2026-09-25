package com.yuzhiplant.aiquota.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.yuzhiplant.aiquota.R
import com.yuzhiplant.aiquota.data.ResultCache
import com.yuzhiplant.aiquota.data.SectionOrder
import com.yuzhiplant.aiquota.widget.QuotaWidgetProvider
import java.util.Collections

/** 拖移調整區塊順序；放開即儲存，並立刻刷新桌面小工具。 */
class ReorderActivity : AppCompatActivity() {

    private val items = mutableListOf<Pair<String, String>>()
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reorder)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        items += SectionOrder.groups(this, ResultCache.load(this))

        val list = findViewById<RecyclerView>(R.id.list)
        val adapter = Adapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                Collections.swap(items, a, b)
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
                // 放開時儲存，並更新編號與小工具
                SectionOrder.save(this@ReorderActivity, items.map { it.first })
                adapter.notifyItemRangeChanged(0, items.size)
                QuotaWidgetProvider.updateAll(this@ReorderActivity)
            }
        })
        touchHelper.attachToRecyclerView(list)
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val index: TextView = view.findViewById(R.id.reorder_index)
        val name: TextView = view.findViewById(R.id.reorder_name)
        val handle: View = view.findViewById(R.id.reorder_handle)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_reorder, parent, false))

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.index.text = "${position + 1}"
            holder.name.text = items[position].second
            // 按住把手立即開始拖移；整列長按也可以
            holder.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                false
            }
        }
    }
}
