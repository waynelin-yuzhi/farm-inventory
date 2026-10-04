package com.yuzhiplant.aiquota.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.yuzhiplant.aiquota.R
import com.yuzhiplant.aiquota.data.Settings

/** 小工具可捲動清單的資料來源：每個平台區塊是清單的一列。 */
class QuotaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var sections: List<QuotaWidgetProvider.Companion.Section> = emptyList()
        private var styleA = false

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            sections = QuotaWidgetProvider.currentSections(context)
            styleA = Settings(context).widgetStyle == "A"
        }

        override fun onDestroy() {
            sections = emptyList()
        }

        override fun getCount() = sections.size

        override fun getViewAt(position: Int): RemoteViews =
            sections.getOrNull(position)?.let { QuotaWidgetProvider.sectionView(context, position, it, styleA) }
                ?: RemoteViews(context.packageName, R.layout.widget_section_a).apply { removeAllViews(R.id.section_root) }

        override fun getLoadingView(): RemoteViews? = null

        // 兩種外框版面：widget_section（B）與 widget_section_a（A，也用於備用空白項）
        override fun getViewTypeCount() = 2

        override fun getItemId(position: Int) = position.toLong()

        override fun hasStableIds() = false
    }
}
