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

package com.reilandeubank.unprocess.fragments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.reilandeubank.unprocess.CameraActivity
import com.reilandeubank.unprocess.R
import com.reilandeubank.unprocess.databinding.FragmentCameraBinding
import com.reilandeubank.unprocess.utils.OrientationLiveData
import com.reilandeubank.unprocess.utils.computeExifOrientation
import com.reilandeubank.unprocess.utils.getPreviewOutputSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import android.widget.NumberPicker

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            fragmentCameraBinding.overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            fragmentCameraBinding.overlay.postDelayed({
                // Remove white flash animation
                fragmentCameraBinding.overlay.background = null
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    // Add these properties for manual focus
    private var isManualFocusEnabled = false
    private var minFocusDistance = 0f
    private var maxFocusDistance = 0f
    private var currentFocusDistance = 0f
    private var isManualExposureEnabled = false
    private var availableIsoValues = arrayOf<String>()
    private var availableShutterSpeeds = arrayOf<String>()
    private var currentIsoIndex = 0 // 0 = AUTO
    private var currentShutterSpeedIndex = 0 // 0 = AUTO
    private var minIso = 0
    private var maxIso = 0
    private var minExposureTime = 0L
    private var maxExposureTime = 0L

    private var updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private val UPDATE_DELAY = 300L // 300ms debounce



    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentCameraBinding.captureButton.setOnApplyWindowInsetsListener { v, insets ->
            v.translationX = (-insets.systemWindowInsetRight).toFloat()
            v.translationY = (-insets.systemWindowInsetBottom).toFloat()
            insets.consumeSystemWindowInsets()
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                    fragmentCameraBinding.viewFinder.display,
                    characteristics,
                    SurfaceHolder::class.java
                )
                Log.d(
                    TAG,
                    "View finder size: ${fragmentCameraBinding.viewFinder.width} x ${fragmentCameraBinding.viewFinder.height}"
                )
                Log.d(TAG, "Selected preview size: $previewSize")
                fragmentCameraBinding.viewFinder.setAspectRatio(
                    previewSize.width,
                    previewSize.height
                )

                // To ensure that size is set, initialize camera in the view's thread
                view.post { initializeCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Initialize an image reader which will be used to capture still photos
        Log.d(TAG, "Initializing image reader")
        Log.d(TAG, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP.toString())
        val size = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!
            .getOutputSizes(args.pixelFormat).maxByOrNull { it.height * it.width }!!
        imageReader = ImageReader.newInstance(
            size.width, size.height, args.pixelFormat, IMAGE_BUFFER_SIZE
        )

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        ).apply {
            addTarget(fragmentCameraBinding.viewFinder.holder.surface)
            // Start with auto focus
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

        setupManualFocus()
        setupManualExposureControls()

        // Listen to the capture button
        fragmentCameraBinding.captureButton.setOnClickListener {

            // Disable click listener to prevent multiple requests simultaneously in flight
            it.isEnabled = false

            // Perform I/O heavy operations in a different scope
            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { result ->
                    Log.d(TAG, "Result received: $result")

                    // Save the result to disk
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")

                    // Display the photo taken to user
                    lifecycleScope.launch(Dispatchers.Main) {
                        navController.navigate(
                            CameraFragmentDirections
                                .actionCameraToJpegViewer(output.absolutePath)
                                .setOrientation(result.orientation)
                                .setDepth(
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                            result.format == ImageFormat.DEPTH_JPEG
                                )
                        )
                    }
                }

                // Re-enable click listener after photo is taken
                it.post { it.isEnabled = true }
            }
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    private fun setupManualFocus() {
        val focusDistances = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val focusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)

        val supportsManualFocus = focusDistances != null && focusDistances > 0 &&
                focusModes?.contains(CameraMetadata.CONTROL_AF_MODE_OFF) == true

        Log.d(TAG, "Manual focus support: $supportsManualFocus, max distance: $focusDistances")

        if (supportsManualFocus) {
            minFocusDistance = 0f
            maxFocusDistance = focusDistances!! * 1.01f //Allows for closer macro shot. BE CAREFUL! CHANGING THIS VALUE DRASTICALLY COULD BE UNSTABLE!!!
            currentFocusDistance = minFocusDistance

            // Set the initial visibility to GONE since default is AF Mode
            setSeekBarsVisibility(View.GONE)

            fragmentCameraBinding.focusSlider?.let { seekBar ->
                seekBar.max = 100
                seekBar.progress = 0
                setupSeekBarListener(seekBar)
            }

            fragmentCameraBinding.focusSliderDuplicate?.let { seekBar ->
                seekBar.max = 100
                seekBar.progress = 0
                setupSeekBarListener(seekBar)
            }

            fragmentCameraBinding.captureButton.setOnLongClickListener {
                toggleManualControls()
                true
            }
        } else {
            setSeekBarsVisibility(View.GONE)
            Log.w(TAG, "Manual focus not supported on this camera")
        }
    }

    private fun toggleManualFocus() {
        isManualFocusEnabled = !isManualFocusEnabled

        if (isManualFocusEnabled) {
            setSeekBarsVisibility(View.VISIBLE)
            fragmentCameraBinding.focusIndicator?.text = "MF Mode"
            val progress = fragmentCameraBinding.focusSlider?.progress ?: 0
            updateManualFocus(progress)
            Log.d(TAG, "Manual focus enabled")
        } else {
            setSeekBarsVisibility(View.GONE)
            fragmentCameraBinding.focusIndicator?.text = "AF Mode"
            setAutoFocus()
            Log.d(TAG, "Auto focus enabled")
        }

    }

    private fun updateManualFocus(progress: Int) {
        if (!::session.isInitialized) return

        try {
            // Calculate focus distance - inverted. Lowest point at seekbar = nearest/macro focus
            val invertedProgress = 100 - progress
            currentFocusDistance = minFocusDistance + (invertedProgress / 100f) * (maxFocusDistance - minFocusDistance)

            // Clamp the focus distance to valid range
            currentFocusDistance = currentFocusDistance.coerceIn(minFocusDistance, maxFocusDistance)

            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(fragmentCameraBinding.viewFinder.holder.surface)

                // IMPORTANT: Use CONTROL_MODE_AUTO instead of OFF for better compatibility
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

                // Only set AF mode to OFF and focus distance when manual focus is enabled
                if (isManualFocusEnabled) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)

                    // Optional: Set these for more stable manual control
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
            }

            session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)
            Log.d(TAG, "Manual focus set to distance: $currentFocusDistance (progress: $progress)")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting manual focus", e)
            // If manual focus fails, fall back to auto focus
            setAutoFocus()
            isManualFocusEnabled = false
        }
    }

    private fun setAutoFocus() {
        if (!::session.isInitialized) return

        try {
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(fragmentCameraBinding.viewFinder.holder.surface)

                // Use standard auto settings
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }

            session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error setting auto focus", e)
        }
    }

    /** Shutter Speed Control. Check this code if:
     * The picture is black/dark
     * Severe scanline problem (if the lines are thick -> check this code; if the lines are thin, check anti-banding)
     * TBD
     */
    private fun setupManualExposureControls() {
        // Get ISO range
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val availableModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)

        val supportsManualExposure = isoRange != null && exposureTimeRange != null &&
                availableModes?.contains(CameraMetadata.CONTROL_AE_MODE_OFF) == true

        Log.d(TAG, "Manual exposure support: $supportsManualExposure")
        Log.d(TAG, "ISO range: ${isoRange?.lower} - ${isoRange?.upper}")
        Log.d(TAG, "Exposure time range: ${exposureTimeRange?.lower} - ${exposureTimeRange?.upper}")

        if (supportsManualExposure && isoRange != null && exposureTimeRange != null) {
            minIso = isoRange.lower
            maxIso = isoRange.upper
            minExposureTime = exposureTimeRange.lower
            maxExposureTime = exposureTimeRange.upper

            setupIsoValues()
            setupShutterSpeedValues()
            setupNumberPickers()

            // Initially hide the controls (they'll show when manual mode is enabled)
            setManualsVisibility(View.GONE)

        } else {
            setManualsVisibility(View.GONE)
            Log.w(TAG, "Manual exposure control not supported on this camera")
        }
    }
    private fun setupIsoValues() {
        // 1. Define your exact custom ISO table here
        val customIsoTable = listOf(
            10, 25, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 12800
        )

        val finalIsoList = mutableListOf<String>()
        finalIsoList.add("AUTO") // Always add "AUTO" as the first option

        // 2. Iterate through YOUR table and add only the supported values
        for (iso in customIsoTable) {
//            if (iso >= minIso && iso <= maxIso) {
//                finalIsoList.add(iso.toString()) //ENABLE THIS PART IF APP BEHAVES WRONG WHEN USING EXTREME ISO
//            }
            finalIsoList.add(iso.toString())

        }

        // 3. The final list is assigned to the variable used by the NumberPicker
        availableIsoValues = finalIsoList.toTypedArray()
        Log.d(TAG, "Populated picker with custom ISO values: ${availableIsoValues.contentToString()}")
    }


    private lateinit var shutterSpeedMap: Map<String, Long>
    private fun setupShutterSpeedValues() {
        val shutterList = mutableListOf<String>()
        shutterList.add("AUTO")

        // The format is Pair(nanoseconds, "Display Name")
        val proShutterSpeeds = listOf(
            // Fractional speeds: 1,000,000,000 / denominator
            Pair(1_000_000_000L / 4000, "1/4000s"),
            Pair(1_000_000_000L / 3200, "1/3200s"),
            Pair(1_000_000_000L / 2500, "1/2500s"),
            Pair(1_000_000_000L / 1600, "1/1600s"),
            Pair(1_000_000_000L / 1250, "1/1250s"),
            Pair(1_000_000_000L / 1000, "1/1000s"),
            Pair(1_000_000_000L / 800, "1/800s"),
            Pair(1_000_000_000L / 640, "1/640s"),
            Pair(1_000_000_000L / 500, "1/500s"),
            Pair(1_000_000_000L / 400, "1/400s"),
            Pair(1_000_000_000L / 320, "1/320s"),
            Pair(1_000_000_000L / 250, "1/250s"),
            Pair(1_000_000_000L / 200, "1/200s"),
            Pair(1_000_000_000L / 160, "1/160s"),
            Pair(1_000_000_000L / 125, "1/125s"),
            Pair(1_000_000_000L / 100, "1/100s"),
            Pair(1_000_000_000L / 80, "1/80s"),
            Pair(1_000_000_000L / 60, "1/60s"),
            Pair(1_000_000_000L / 50, "1/50s"),
            Pair(1_000_000_000L / 40, "1/40s"),
            Pair(1_000_000_000L / 30, "1/30s"),
            Pair(1_000_000_000L / 25, "1/25s"),
            Pair(1_000_000_000L / 20, "1/20s"),
            Pair(1_000_000_000L / 15, "1/15s"),
            Pair(1_000_000_000L / 10, "1/10s"),
            Pair(1_000_000_000L / 8, "1/8s"),
            Pair(1_000_000_000L / 6, "1/6s"),
            Pair(1_000_000_000L / 4, "1/4s"),
            Pair((1_000_000_000L * 0.3).toLong(), "0.3s"),
            Pair((1_000_000_000L * 0.4).toLong(), "0.4s"),
            Pair((1_000_000_000L * 0.5).toLong(), "0.5s"),
            Pair((1_000_000_000L * 0.6).toLong(), "0.6s"),
            Pair((1_000_000_000L * 0.8).toLong(), "0.8s"),
            Pair(1_000_000_000L * 1, "1s"),
            Pair((1_000_000_000L * 1.3).toLong(), "1.3s"),
            Pair((1_000_000_000L * 1.6).toLong(), "1.6s"),
            Pair(1_000_000_000L * 2, "2s"),
            Pair((1_000_000_000L * 2.5).toLong(), "2.5s"),
            Pair((1_000_000_000L * 3.2).toLong(), "3.2s"),
            Pair(1_000_000_000L * 4, "4s"),
            Pair(1_000_000_000L * 5, "5s"),
            Pair(1_000_000_000L * 6, "6s"),
            Pair(1_000_000_000L * 8, "8s"),
            Pair(1_000_000_000L * 10, "10s"),
            Pair(1_000_000_000L * 13, "13s"),
            Pair(1_000_000_000L * 15, "15s"),
            Pair(1_000_000_000L * 20, "20s"),
            Pair(1_000_000_000L * 25, "25s"),
            Pair(1_000_000_000L * 30, "30s"),
            Pair(1_000_000_000L * 60, "60s")
        )

        for ((exposureTime, displayName) in proShutterSpeeds) {
//            if (exposureTime >= minExposureTime && exposureTime <= maxExposureTime) {
//                shutterList.add(displayName) //USE THIS PART IF APP BEHAVES UNEXPECTEDLY  WHEN USING EXTREME S/S
//            }
                shutterList.add(displayName)
        }

        availableShutterSpeeds = shutterList.toTypedArray()
        Log.d(TAG, "Available shutter speeds: ${availableShutterSpeeds.contentToString()}")

        // --- NEW CODE STARTS HERE ---
        // Create the lookup map from your master list
        // The key is the display name (it.second), the value is the nanoseconds (it.first)
        shutterSpeedMap = proShutterSpeeds.associate { it.second to it.first }
    }

    private fun setupNumberPickers() {
        Log.d(TAG, "Setting up NumberPickers...")

        if (_fragmentCameraBinding == null) {
            Log.e(TAG, "Fragment binding is null!")
            return
        }

        // Define your desired default values here
        val defaultIsoValue = "400"
        val defaultShutterSpeedValue = "1/100s"

        // --- Setup ISO Picker ---
        fragmentCameraBinding.isoPicker?.let { picker ->
            picker.minValue = 0
            picker.maxValue = availableIsoValues.size - 1
            picker.displayedValues = availableIsoValues
            picker.wrapSelectorWheel = false
            picker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS

            // Find the index of your desired default ISO
            val isoIndex = availableIsoValues.indexOf(defaultIsoValue)
            // Set the picker to that index (or 0 if not found)
            val initialIsoIndex = if (isoIndex != -1) isoIndex else 0
            picker.value = initialIsoIndex
            currentIsoIndex = initialIsoIndex // IMPORTANT: Update the state variable

            picker.setOnValueChangedListener { _, _, newVal ->
                Log.d(TAG, "ISO changed to ${availableIsoValues[newVal]}")
                currentIsoIndex = newVal
                debouncedUpdateExposure()
            }
        }

        // --- Setup Shutter Speed Picker ---
        fragmentCameraBinding.shutterSpeedPicker?.let { picker ->
            picker.minValue = 0
            picker.maxValue = availableShutterSpeeds.size - 1
            picker.displayedValues = availableShutterSpeeds
            picker.wrapSelectorWheel = false
            picker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS

            // Find the index of your desired default shutter speed
            val speedIndex = availableShutterSpeeds.indexOf(defaultShutterSpeedValue)
            // Set the picker to that index (or 0 if not found)
            val initialSpeedIndex = if (speedIndex != -1) speedIndex else 0
            picker.value = initialSpeedIndex
            currentShutterSpeedIndex = initialSpeedIndex // IMPORTANT: Update the state variable

            picker.setOnValueChangedListener { _, _, newVal ->
                Log.d(TAG, "Shutter Speed changed to ${availableShutterSpeeds[newVal]}")
                currentShutterSpeedIndex = newVal
                debouncedUpdateExposure()
            }
        }
    }

    private fun debouncedUpdateExposure() {
        // Cancel any pending update
        updateRunnable?.let { updateHandler.removeCallbacks(it) }

        // Schedule a new update after delay
        updateRunnable = Runnable {
            updateExposureSettings()
        }
        updateHandler.postDelayed(updateRunnable!!, UPDATE_DELAY)
    }

    // Modify the existing toggleManualFocus() function to handle exposure as well
    private fun toggleManualControls() {
        isManualFocusEnabled = !isManualFocusEnabled

        if (isManualFocusEnabled) {
            // Show all manual controls
            setSeekBarsVisibility(View.VISIBLE)
            setManualsVisibility(View.VISIBLE)
            fragmentCameraBinding.focusIndicator?.text = "Manual Mode"

            // Update focus
            val progress = fragmentCameraBinding.focusSlider?.progress ?: 0
            updateManualFocus(progress)

            // Keep exposure on AUTO initially in manual focus mode
            updateCameraSettings()

            Log.d(TAG, "Manual controls enabled (Focus: Manual, Exposure: Auto)")
        } else {
            // Hide manual controls and return to full auto
            setSeekBarsVisibility(View.GONE)
            setManualsVisibility(View.GONE)
            fragmentCameraBinding.focusIndicator?.text = "Auto Mode"

            isManualExposureEnabled = false
            setFullAutoMode()

            Log.d(TAG, "Full auto mode enabled")
        }
    }

    private fun updateExposureSettings() {
        if (!::session.isInitialized) return

        // If either ISO or Shutter Speed is not on AUTO, enable manual exposure
        isManualExposureEnabled = currentIsoIndex > 0 || currentShutterSpeedIndex > 0

        updateCameraSettings()
    }

    private fun updateCameraSettings() {
        if (!::session.isInitialized) return

        try {
            // Check if we need to recreate the capture session for significant changes
            val needsSessionRecreation = false // Keep false for now, but this could be optimized further

            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(fragmentCameraBinding.viewFinder.holder.surface)

                // Always use AUTO control mode for better compatibility
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

                // Handle focus settings (less performance impact)
                if (isManualFocusEnabled) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }

                // Handle exposure settings (main performance bottleneck)
                if (isManualExposureEnabled) {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

                    // Set ISO if not AUTO
                    if (currentIsoIndex > 0) {
                        val isoValue = availableIsoValues[currentIsoIndex].toInt()
                        set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
                        Log.d(TAG, "Set manual ISO: $isoValue")
                    }

                    // Set shutter speed if not AUTO
                    if (currentShutterSpeedIndex > 0) {
                        val exposureTime = getExposureTimeFromIndex(currentShutterSpeedIndex)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                        Log.d(TAG, "Set manual exposure time: ${exposureTime}ns (${availableShutterSpeeds[currentShutterSpeedIndex]})")
                    }
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                }

                // Keep AWB on auto
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }

            // Use setRepeatingRequest only when necessary
            session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error updating camera settings", e)
            // Fall back to full auto mode
            setFullAutoMode()
        }
    }

    // Add this optimized exposure time lookup with caching
    private val exposureTimeCache = mutableMapOf<Int, Long>()

    private fun getExposureTimeFromIndex(index: Int): Long {
        // If the index is 0, it's "AUTO"
        if (index == 0) return 0L

        // Get the display string from the picker's current value, e.g., "1/4000s"
        val shutterSpeedString = availableShutterSpeeds[index]

        // Look up the string in the map to get the nanoseconds.
        // Provide a fallback value (like minExposureTime) in case it's not found.
        return shutterSpeedMap[shutterSpeedString] ?: minExposureTime
    }

    private fun setFullAutoMode() {
        if (!::session.isInitialized) return

        try {
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(fragmentCameraBinding.viewFinder.holder.surface)

                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }

            session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)
            Log.d(TAG, "Full auto mode set")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting full auto mode", e)
        }
    }


    /** Seekbar synchronization logic. Check here if:
     * The Seekbar synchronization fails,
     * TBD
    */

    private fun syncSeekBars(sourceSeekBar: SeekBar, targetSeekBar: SeekBar, progress: Int) {
        // Temporarily remove listener to prevent infinite loop
        targetSeekBar.setOnSeekBarChangeListener(null)
        targetSeekBar.progress = progress
        // Restore the listener
        setupSeekBarListener(targetSeekBar)
    }
    // Helper function to set up seekbar listener
    private fun setupSeekBarListener(seekBar: SeekBar) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::session.isInitialized && isManualFocusEnabled) {
                    updateManualFocus(progress)

                    // Sync the other seekbar
                    when (seekBar?.id) {
                        R.id.focus_slider -> {
                            fragmentCameraBinding.focusSliderDuplicate?.let {
                                syncSeekBars(seekBar, it, progress)
                            }
                        }
                        R.id.focus_slider_duplicate -> {
                            fragmentCameraBinding.focusSlider?.let {
                                syncSeekBars(seekBar, it, progress)
                            }
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Don't automatically enable manual focus on touch - wait for toggle
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    // Helper function to set visibility for both seekbars
    private fun setSeekBarsVisibility(visibility: Int) {
        fragmentCameraBinding.focusSlider?.visibility = visibility
        fragmentCameraBinding.focusSliderDuplicate?.visibility = visibility
    }

    private fun setManualsVisibility(visibility: Int) {
        fragmentCameraBinding.isoText?.visibility = visibility
        fragmentCameraBinding.isoPicker?.visibility = visibility
        fragmentCameraBinding.shutterSpeedPicker?.visibility = visibility
        fragmentCameraBinding.shutterSpeedText?.visibility = visibility
    }

    // Helper function to set progress for both seekbars
    private fun setSeekBarsProgress(progress: Int) {
        fragmentCameraBinding.focusSlider?.progress = progress
        fragmentCameraBinding.focusSliderDuplicate?.progress = progress
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto():
            CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE
        ).apply {
            addTarget(imageReader.surface)

            // Use the same strategy as preview: keep CONTROL_MODE_AUTO
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            if (isManualFocusEnabled) {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            if (isManualExposureEnabled) {
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

                if (currentIsoIndex > 0) {
                    val isoValue = availableIsoValues[currentIsoIndex].toInt()
                    set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
                }

                if (currentShutterSpeedIndex > 0) {
                    val exposureTime = getExposureTimeFromIndex(currentShutterSpeedIndex)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                }
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }

        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                fragmentCameraBinding.viewFinder.post(animationTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {
                        val image = imageQueue.take()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp
                        ) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        cont.resume(
                            CombinedCaptureResult(
                                image, result, exifOrientation, imageReader.imageFormat
                            )
                        )
                        break
                    }
                }
            }
        }, cameraHandler)
    }


    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {
            // Only expecting RAW sensor data
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                try {
                    if (args.convertToJpeg) {
                        // Get RAW image data
                        val rawImage = result.image
                        val rawBuffer = rawImage.planes[0].buffer
                        val rawBytes = ByteArray(rawBuffer.remaining())
                        rawBuffer.get(rawBytes)

                        // Create a temporary DNG file
                        val tempDngFile = File(requireContext().cacheDir, "temp.dng")
                        FileOutputStream(tempDngFile).use { outputStream ->
                            dngCreator.writeImage(outputStream, rawImage)
                        }

                        // TODO: Right now, using android's basic bitmap conversion,
                        //  may want to use RenderScript or other RAW processing library
                        val bitmap = BitmapFactory.decodeFile(tempDngFile.absolutePath)
                        tempDngFile.delete() // Clean up temp file

                        // Save as JPEG
                        val filename = "IMG_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(Date())
                        }.jpg"

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "${Environment.DIRECTORY_DCIM}/Camera"
                                )
                            }

                            val resolver = requireContext().contentResolver
                            val uri = resolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            ) ?: throw IOException("Failed to create MediaStore entry")

                            resolver.openOutputStream(uri)?.use { stream ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                            }

                            // Add EXIF orientation data using the URI
                            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                                ExifInterface(pfd.fileDescriptor).apply {
                                    setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                    saveAttributes()
                                }
                            }

                            // Create a reference file in the DCIM directory
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera")
                            val savedFile = File(appFolder, filename)
                            cont.resume(savedFile)
                        } else {
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera").apply {
                                if (!exists()) mkdirs()
                            }
                            val file = File(appFolder, filename)

                            FileOutputStream(file).use { stream ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                            }

                            // Add EXIF orientation data
                            ExifInterface(file.absolutePath).apply {
                                setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                saveAttributes()
                            }

                            cont.resume(file)
                        }

                        bitmap.recycle()
                    } else {
                        dngCreator.setOrientation(result.orientation)
                        val filename = "RAW_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(Date())
                        }.dng"

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // Android 10 and above: Use MediaStore
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "${Environment.DIRECTORY_DCIM}/Camera"
                                )
                            }

                            val resolver = requireContext().contentResolver
                            val uri = resolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            ) ?: throw IOException("Failed to create MediaStore entry")

                            val outputStream = resolver.openOutputStream(uri)
                                ?: throw IOException("Failed to open output stream")

                            outputStream.use { stream ->
                                dngCreator.writeImage(stream, result.image)
                            }

                            // Create a reference file in the DCIM directory
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera")
                            val savedFile = File(appFolder, filename)
                            cont.resume(savedFile)

                        } else {
                            // Below Android 10: Use direct file access
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera").apply {
                                if (!exists()) {
                                    mkdirs()
                                }
                            }
                            val file = File(appFolder, filename)

                            FileOutputStream(file).use { outputStream ->
                                dngCreator.writeImage(outputStream, result.image)
                            }
                        }
                    }

                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to external storage", exc)
                    cont.resumeWithException(exc)
                }
            }

            // No other formats are supported
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cancel any pending updates
        updateRunnable?.let { updateHandler.removeCallbacks(it) }

        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }
    }
}
