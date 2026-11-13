package io.mo.xatype.hooks

/** AOSP has one radius per corner; HyperOS has x/y pairs and additional trailing fields.
 * The template is serialized by the running framework with sentinel corners 11, 22, 33, 44.
 */
internal object GlassBlurRegion {
    fun encode(template: FloatArray, blur: Int, left: Int, top: Int, width: Int, height: Int,
               topRadius: Float, bottomRadius: Float): FloatArray {
        val paired = template.size >= 14 && template.sliceArray(6..13)
            .contentEquals(floatArrayOf(11f, 11f, 22f, 22f, 33f, 33f, 44f, 44f))
        require(paired || (template.size >= 10 && template.sliceArray(6..9)
            .contentEquals(floatArrayOf(11f, 22f, 33f, 44f)))) { "Unknown platform blur region layout" }
        return template.copyOf().apply {
            this[0] = blur.toFloat(); this[1] = 1f
            this[2] = left.toFloat(); this[3] = top.toFloat()
            this[4] = (left + width).toFloat(); this[5] = (top + height).toFloat()
            val radii = if (paired) floatArrayOf(topRadius, topRadius, topRadius, topRadius,
                bottomRadius, bottomRadius, bottomRadius, bottomRadius)
            else floatArrayOf(topRadius, topRadius, bottomRadius, bottomRadius)
            radii.copyInto(this, destinationOffset = 6)
        }
    }
}
