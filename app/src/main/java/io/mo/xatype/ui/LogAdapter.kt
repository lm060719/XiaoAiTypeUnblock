package io.mo.xatype.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.mo.xatype.R
import io.mo.xatype.data.LogEntry

class LogAdapter(private var items: List<LogEntry> = emptyList()) :
    RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    fun updateData(newItems: List<LogEntry>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvBadge: TextView = itemView.findViewById(R.id.tvLogBadge)
        private val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvLogTitle)
        private val tvDetail: TextView = itemView.findViewById(R.id.tvLogDetail)

        fun bind(entry: LogEntry) {
            tvBadge.text = entry.type.displayName
            tvTime.text = entry.getFormattedTime()
            tvTitle.text = entry.title
            tvDetail.text = entry.detail

            try {
                val color = Color.parseColor(entry.type.colorHex)
                tvBadge.setTextColor(color)
                val bg = tvBadge.background
                if (bg is GradientDrawable) {
                    val alphaColor = Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))
                    bg.setColor(alphaColor)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
