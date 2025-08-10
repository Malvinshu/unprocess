/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reilandeubank.unprocess.utils

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * A [SurfaceView] that can be adjusted to a specified aspect ratio and
 * performs center-crop transformation of input frames.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var aspectRatio = 0f

    /**
     * Sets the aspect ratio for this view. The size of the view will be
     * measured based on the ratio calculated from the parameters.
     *
     * @param width  Camera resolution horizontal size
     * @param height Camera resolution vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative" }
        aspectRatio = width.toFloat() / height.toFloat()
        holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val viewWidth = MeasureSpec.getSize(widthMeasureSpec)
        val viewHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (aspectRatio == 0f) {
            setMeasuredDimension(viewWidth, viewHeight)
        } else {
            var newWidth: Int
            var newHeight: Int

            // Determine the orientation of the view relative to the aspect ratio
            // This assumes aspectRatio is always width/height of the camera sensor
            val isViewLandscape = viewWidth > viewHeight
            val isPreviewLandscape = aspectRatio > 1f

            if (isViewLandscape == isPreviewLandscape) {
                // Orientations match, scale to fit
                if (viewWidth / viewHeight.toFloat() > aspectRatio) {
                    // View is wider than preview, fit by height
                    newHeight = viewHeight
                    newWidth = (viewHeight * aspectRatio).roundToInt()
                } else {
                    // View is taller than preview (or same aspect ratio), fit by width
                    newWidth = viewWidth
                    newHeight = (viewWidth / aspectRatio).roundToInt()
                }
            } else {
                // Orientations mismatch (e.g., landscape view, portrait preview)
                // We need to use the inverse of the aspectRatio for calculation
                if (viewWidth / viewHeight.toFloat() > (1f / aspectRatio)) {
                    // View is wider than (inverted) preview, fit by height
                    newHeight = viewHeight
                    newWidth = (viewHeight * (1f/aspectRatio)).roundToInt()
                } else {
                    // View is taller than (inverted) preview, fit by width
                    newWidth = viewWidth
                    newHeight = (viewWidth / (1f/aspectRatio)).roundToInt()
                }
            }

            Log.d(TAG, "Measured dimensions set: $newWidth x $newHeight for view size $viewWidth x $viewHeight")
            setMeasuredDimension(newWidth, newHeight)
        }
    }


    companion object {
        private val TAG = AutoFitSurfaceView::class.java.simpleName
    }
}
