package org.fossify.gallery.views

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import org.fossify.commons.databinding.ItemBreadcrumbBinding
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.setDrawablesRelativeWithIntrinsicBounds

// The path inside a storage as a row of crumbs, one per folder below the storage root, for
// FolderPickerDialog. The storage itself is not among them: the dialog shows the storages as
// chips of their own above this row, so at the root of a storage the row is empty and hidden.
// Commons' Breadcrumbs cannot be used here, it insists on the storage as its first crumb and
// only knows the device's paths, not a pCloud pseudo path
class FolderPickerBreadcrumbs(context: Context, attrs: AttributeSet) : HorizontalScrollView(context, attrs) {
    private val inflater = LayoutInflater.from(context)
    private val itemsLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val textColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()),
        intArrayOf(context.getProperPrimaryColor(), context.getProperTextColor())
    )

    // handed the full path of the crumb that was tapped
    var listener: ((path: String) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        addView(itemsLayout, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    // shows the part of fullPath below storageRoot, the last crumb highlighted and scrolled to.
    // Both "pcloud:" + "pcloud:/A/B" and "/storage/emulated/0" + "/storage/emulated/0/A" work
    fun setPath(storageRoot: String, fullPath: String) {
        itemsLayout.removeAllViews()
        val relativePath = fullPath.removePrefix(storageRoot).trim('/')
        beVisibleIf(relativePath.isNotEmpty())
        if (relativePath.isEmpty()) {
            return
        }

        val fontSize = context.getTextSize()
        val names = relativePath.split('/')
        var path = storageRoot.trimEnd('/')
        names.forEachIndexed { index, name ->
            path = "$path/$name"
            val crumbPath = path
            ItemBreadcrumbBinding.inflate(inflater, itemsLayout, false).apply {
                breadcrumbText.text = name
                breadcrumbText.isActivated = index == names.lastIndex
                breadcrumbText.setTextColor(textColorStateList)
                breadcrumbText.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                if (index > 0) {
                    breadcrumbText.setDrawablesRelativeWithIntrinsicBounds(
                        start = AppCompatResources.getDrawable(context, org.fossify.commons.R.drawable.ic_chevron_right_vector)
                    )
                    TextViewCompat.setCompoundDrawableTintList(breadcrumbText, textColorStateList)
                }

                breadcrumbText.setOnClickListener { listener?.invoke(crumbPath) }
                itemsLayout.addView(root)
            }
        }

        post { fullScroll(FOCUS_RIGHT) }
    }
}
