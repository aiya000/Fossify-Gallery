package org.fossify.gallery.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.updatePadding
import org.fossify.commons.databinding.ItemBreadcrumbBinding
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.getDialogBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.helpers.MEDIUM_ALPHA

// A row of chips to pick a storage from, one of them highlighted, drawn like the root crumb
// of commons' Breadcrumbs so that it sits next to a folder list without looking foreign.
// The folder pickers show the device's storages and pCloud in it
class StorageChips(context: Context, attrs: AttributeSet) : HorizontalScrollView(context, attrs) {
    class Chip(val tag: Any, val label: String)

    private val inflater = LayoutInflater.from(context)
    private val itemsLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val textColors = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()),
        intArrayOf(context.getProperPrimaryColor(), context.getProperTextColor())
    )

    // handed the tag of the chip that was tapped, highlighted or not
    var onChipClicked: ((tag: Any) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        addView(itemsLayout, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setChips(chips: List<Chip>, selectedTag: Any?) {
        val fontSize = context.getTextSize()
        val horizontalPadding = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.normal_margin)
        val spacing = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
        val strokeWidth = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.one_dp)
        val strokeColor = context.getProperPrimaryColor().adjustAlpha(MEDIUM_ALPHA)
        val fillColor = context.getDialogBackgroundColor()

        itemsLayout.removeAllViews()
        chips.forEach { chip ->
            ItemBreadcrumbBinding.inflate(inflater, itemsLayout, false).apply {
                breadcrumbText.text = chip.label
                breadcrumbText.setTextColor(textColors)
                breadcrumbText.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                breadcrumbText.updatePadding(left = horizontalPadding, right = horizontalPadding)
                (breadcrumbText.background.mutate() as? RippleDrawable)?.let { ripple ->
                    (ripple.getDrawable(0) as? GradientDrawable)?.apply {
                        setColor(fillColor)
                        setStroke(strokeWidth, strokeColor)
                    }
                }

                breadcrumbText.setOnClickListener { onChipClicked?.invoke(chip.tag) }
                root.tag = chip.tag
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.marginEnd = spacing
                itemsLayout.addView(root, params)
            }
        }

        select(selectedTag)
    }

    fun select(tag: Any?) {
        for (i in 0 until itemsLayout.childCount) {
            val chip = itemsLayout.getChildAt(i)
            chip.isActivated = chip.tag == tag
        }
    }
}
