package com.example.myapplication.ui.home

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.myapplication.databinding.FragmentHomeBinding
import com.example.myapplication.state.AppState
import com.example.myapplication.state.LightState
import com.example.myapplication.utils.Permissions
import android.speech.tts.TextToSpeech
import java.util.Locale
import com.example.myapplication.ai.VisionPipeline

class HomeFragment : Fragment(), SensorEventListener {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var tts: TextToSpeech? = null
    private lateinit var cameraManager: CameraManager

    private var appState: AppState = AppState.IDLE

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var stepsSinceStart: Int = 0
    private val targetSteps = 14

    // --- VOICEOVER STATE MANAGEMENT ---
    private var hasAnnouncedTrafficLight = false
    private var lastSpokenLightState = LightState.NONE
    private var lastLightStateTimestamp = 0L
    private val lightStateCooldownMs = 5000L
    private var lastPresenceAnnounceTime = 0L
    private val presenceCooldownMs = 4000L  // 4 secunde anti-spam

    private val lastStates = ArrayDeque<LightState>()
    private val smoothingSize = 5

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        setupTts()

        VisionPipeline.init(requireContext())

        VisionPipeline.setTrafficLightPresenceListener { found ->
            if (found) {
                val now = System.currentTimeMillis()

                if (now - lastPresenceAnnounceTime > presenceCooldownMs) {
                    speak("Am detectat un semafor.")
                    lastPresenceAnnounceTime = now
                }
            }
        }
// 🔥 AICI adaugi callback-ul pentru culoare:
        VisionPipeline.setTrafficLightColorListener { color ->
            handleTrafficLightState(color)
        }
        cameraManager = CameraManager(
            fragment = this,
            previewView = binding.cameraPreview
        ) { state ->
            Log.d("HomeFragment", "State primit din AI = $state")
            handleTrafficLightState(state)
        }

        binding.startButton.setOnClickListener { onStartAssistantClicked() }

        updateStatusText()
    }

    private fun setupTts() {
        tts = TextToSpeech(requireContext()) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ro", "RO")
            }
        }
    }

    private fun onStartAssistantClicked() {
        Log.d("HomeFragment", "Start button apăsat")

        if (!Permissions.allRequiredPermissionsGranted(requireContext())) {
            Log.e("HomeFragment", "Permisiuni lipsă")
            Permissions.requestAllPermissions(this)
            return
        }

        cameraManager.startCamera()

        appState = AppState.WALKING
        stepsSinceStart = 0
        hasAnnouncedTrafficLight = false
        lastSpokenLightState = LightState.NONE
        updateStatusText()

        speak("Ghidarea a început. Mergi drept aproximativ zece metri.")
    }

    private fun updateStatusText() {
        binding.statusText.text = appState.toString()
    }

    private fun speak(text: String) {
        Log.d("HomeFragment", "TTS: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_id")
    }

    // --- STEP SENSOR ---
    override fun onResume() {
        super.onResume()
        stepSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        cameraManager.stop()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_DETECTOR) return

        if (appState == AppState.WALKING) {
            stepsSinceStart++
            Log.d("HomeFragment", "Step detected: $stepsSinceStart/$targetSteps")

            if (stepsSinceStart >= targetSteps) {
                appState = AppState.CHECKING_TRAFFIC_LIGHT
                updateStatusText()
                speak("Ai mers aproximativ zece metri. Caut semaforul din față.")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- TRAFFIC LIGHT LOGIC ---
    fun handleTrafficLightState(state: LightState) {

        // 1. Add to buffer
        lastStates.addLast(state)
        if (lastStates.size > smoothingSize) lastStates.removeFirst()

        // 2. Find dominant state
        val stableState = lastStates
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }!!.key

        // 3. If stable state is NONE, do nothing
        if (stableState == LightState.NONE) return

        // 4. Announce "semafor detectat" only once
        if (!hasAnnouncedTrafficLight) {
            speak("Am detectat un semafor.")
            hasAnnouncedTrafficLight = true
        }

        val now = System.currentTimeMillis()

        // 5. Cooldown
        if (stableState == lastSpokenLightState &&
            now - lastLightStateTimestamp < lightStateCooldownMs) {
            return
        }

        lastSpokenLightState = stableState
        lastLightStateTimestamp = now

        // 6. Speak color
        when (stableState) {
            LightState.RED -> speak("Semafor roșu. Așteaptă.")

            LightState.GREEN -> {
                speak("Semafor verde. Poți traversa.")
                appState = AppState.DONE
                updateStatusText()
            }

            else -> Unit
        }
    }

    override fun onDestroyView() {
        tts?.shutdown()
        _binding = null
        super.onDestroyView()
    }
}
