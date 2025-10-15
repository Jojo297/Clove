/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mediapipe.examples.objectdetection

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.min

class OverlayViewWebcam(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: ObjectDetectorResult? = null
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var scaleFactor: Float = 1f
    private var bounds = Rect()
    private var outputWidth = 0
    private var outputHeight = 0
    private var outputRotate = 0
    private var runningMode: RunningMode = RunningMode.IMAGE
    private var previewWidth = 0
    private var previewHeight = 0
    private var previewLeft = 0f
    private var previewTop = 0f


    init {
        initPaints()
    }


    /**
     * Resets the view by clearing the last detection results, resetting the paint objects,
     * and forcing a redraw. It then re-initializes the paints for future use.
     */
    fun clear() {
        results = null
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        invalidate()
        initPaints()
    }

    /**
     * Sets the operational mode of the overlay (e.g., IMAGE, VIDEO, LIVE_STREAM).
     * This can affect how scaling is calculated.
     */
    fun setRunningMode(runningMode: RunningMode) {
        this.runningMode = runningMode
    }

    /**
     * Initializes the Paint objects used for drawing the bounding boxes and labels.
     * This includes setting colors, styles, and text sizes.
     */
    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.color = ContextCompat.getColor(context!!, R.color.mp_primary)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    /**
     * The main drawing function. This method is called by the Android framework to render the view.
     * It iterates through each detected object from the ObjectDetectorResult, transforms the bounding
     * box coordinates from the model's input space to the screen's display space, and then draws
     * the bounding box and its corresponding label (category and score) onto the Canvas.
     */
    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        results?.detections()?.forEach { detection ->
            val originalImageWidth = outputWidth.toFloat()
            val originalImageHeight = outputHeight.toFloat()

            val croppedImageSize = minOf(originalImageWidth, originalImageHeight)

            val modelInputSize = 384f

            val boxRect = RectF(
                detection.boundingBox().left,
                detection.boundingBox().top,
                detection.boundingBox().right,
                detection.boundingBox().bottom
            )

            val modelToCropScale = croppedImageSize / modelInputSize
            boxRect.left *= modelToCropScale
            boxRect.right *= modelToCropScale
            boxRect.top *= modelToCropScale
            boxRect.bottom *= modelToCropScale

            val cropOffsetX = (originalImageWidth - croppedImageSize) / 2f
            val cropOffsetY = (originalImageHeight - croppedImageSize) / 2f
            boxRect.offset(cropOffsetX, cropOffsetY)

            val matrix = Matrix()
            matrix.postRotate(
                outputRotate.toFloat(),
                originalImageWidth / 2f,
                originalImageHeight / 2f
            )
            matrix.mapRect(boxRect)

            boxRect.left *= scaleFactor
            boxRect.right *= scaleFactor
            boxRect.top *= scaleFactor
            boxRect.bottom *= scaleFactor

            boxRect.offset(previewLeft, previewTop)

            val finalDrawableRect = boxRect

            canvas.drawRect(finalDrawableRect, boxPaint)

            val category = detection.categories()[0]
            val drawableText = category.categoryName() + " " + String.format("%.2f", category.score())

            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textHeight = bounds.height()
            val textWidth = textBackgroundPaint.measureText(drawableText)

            val labelX = finalDrawableRect.left
            val labelY = finalDrawableRect.top

            canvas.drawRect(
                labelX,
                labelY - textHeight - (BOUNDING_RECT_TEXT_PADDING * 2),
                labelX + textWidth + (BOUNDING_RECT_TEXT_PADDING * 2),
                labelY,
                textBackgroundPaint
            )
            canvas.drawText(
                drawableText,
                labelX + BOUNDING_RECT_TEXT_PADDING,
                labelY - BOUNDING_RECT_TEXT_PADDING,
                textPaint
            )
        }
    }

    /**
     * Sets the object detection results to be displayed. This function also receives the
     * dimensions and rotation of the input image. It calculates the `scaleFactor` needed
     * to map the detection results from the image's coordinate system to the view's
     * coordinate system and then triggers a redraw by calling `invalidate()`.
     */
    fun setResults(
        detectionResults: ObjectDetectorResult,
        outputHeight: Int,
        outputWidth: Int,
        imageRotation: Int
    ) {
        results = detectionResults
        this.outputWidth = outputWidth
        this.outputHeight = outputHeight
        this.outputRotate = imageRotation

        val rotatedWidthHeight = when (imageRotation) {
            0, 180 -> Pair(outputWidth, outputHeight)
            90, 270 -> Pair(outputHeight, outputWidth)
            else -> return
        }

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO,
            RunningMode.LIVE_STREAM -> { // Terapkan logika yang sama untuk semua mode
                min(
                    previewWidth * 1f / rotatedWidthHeight.first,
                    previewHeight * 1f / rotatedWidthHeight.second
                )
            }
        }
        invalidate()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }

    /**
     * Stores the layout dimensions and position of the camera preview. This is essential for
     * correctly positioning the drawn bounding boxes on the screen, aligning them with the
     * underlying camera image.
     */
    fun setPreviewLayout(left: Int, top: Int, width: Int, height: Int) {
        previewLeft = left.toFloat()
        previewTop = top.toFloat()
        previewWidth = width
        previewHeight = height
    }

}
