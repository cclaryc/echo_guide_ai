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
import com.example.myapplication.ai.LocationHelper
import com.example.myapplication.ai.VisionPipeline

import org.json.JSONObject

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

    private var lastSpokenLightState = LightState.NONE
    private var lastLightStateTimestamp = 0L
    private val lightStateCooldownMs = 5000L

    private lateinit var locationHelper: LocationHelper


    data class RouteStep(
        val lat: Double,
        val lon: Double,
        val instruction: String
    )

    private var routeLoaded = false
    private var routeSteps: List<RouteStep> = emptyList()
    private var currentStepIndex = 0


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    private fun handleLocationUpdate(lat: Double, lon: Double) {
        Log.d("NAV", "Locație actualizată: $lat, $lon")

        if (!routeLoaded) return

        val nextStep = routeSteps[currentStepIndex]

        val stepLat = nextStep.lat
        val stepLon = nextStep.lon

        val dist = distanceInMeters(lat, lon, stepLat, stepLon)

        if (dist < 10) {
            speak("Ai ajuns la pasul ${currentStepIndex + 1}")
            currentStepIndex++
            return
        }

        // verificăm dacă utilizatorul a deviat prea mult
        if (dist > 30) {
            speak("Ai deviat de la traseu, recalculez ruta.")
            recalcRoute(lat, lon)
        }
    }

    private fun distanceInMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371000.0 // raza Pământului în metri
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) *
                Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    private fun recalcRoute(currentLat: Double, currentLon: Double) {

        Log.d("NAV", "Recalculez ruta din: $currentLat, $currentLon")

        // Destinație exemplu – Palatul Parlamentului
        val destLat = 47.4275
        val destLon = 26.0875

        // AICI VINE URL-ul CORECT
        val urlStr =
            "https://router.project-osrm.org/route/v1/foot/" +
                    "$currentLon,$currentLat;" +
                    "$destLon,$destLat" +
                    "?overview=false&steps=true"
        Thread {
            try {
                val url = java.net.URL(urlStr)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connect()

                val stream = conn.inputStream.bufferedReader().use { it.readText() }

                val json = org.json.JSONObject(stream)
                val routes = json.getJSONArray("routes")
                val legs = routes.getJSONObject(0).getJSONArray("legs")
                val steps = legs.getJSONObject(0).getJSONArray("steps")

                val newSteps = mutableListOf<RouteStep>()

                for (i in 0 until steps.length()) {
                    val stepObj = steps.getJSONObject(i)
                    val maneuver = stepObj.getJSONObject("maneuver")
                    val loc = maneuver.getJSONArray("location")

                    val lon = loc.getDouble(0)
                    val lat = loc.getDouble(1)

                    val instruction = stepObj.getString("instruction") // AICI e magia

                    newSteps += RouteStep(lat, lon, instruction)
                }

                requireActivity().runOnUiThread {
                    routeSteps = newSteps
                    routeLoaded = true
                    currentStepIndex = 0
                    speak("Ruta a fost recalculată.")
                    Log.d("NAV", "Rută recalculată – ${newSteps.size} pași.")
                }

            } catch (e: Exception) {
                Log.e("NAV", "Eroare recalculare rută: ${e.message}")
            }
        }.start()
    }






    private fun handleLocation(lat: Double, lon: Double) {
        Log.d("HomeFragment", "Locație primită: lat=$lat  lon=$lon")
        binding.statusText.text = "Locație: $lat , $lon"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        locationHelper = LocationHelper(requireContext())

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        setupTts()
        VisionPipeline.init(requireContext())

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
            if (it == TextToSpeech.SUCCESS) tts?.language = Locale("ro", "RO")
        }
    }

    private fun onStartAssistantClicked() {
        Log.d("HomeFragment", "Start button apăsat")

        if (!Permissions.allRequiredPermissionsGranted(requireContext())) {
            Permissions.requestAllPermissions(this)
            return
        }

        // PORNIM Locația doar la Start
        locationHelper.getLocation { lat, lon ->
            handleLocation(lat, lon)
            recalcRoute(lat, lon)
        }

        cameraManager.startCamera()

        appState = AppState.WALKING
        stepsSinceStart = 0
        updateStatusText()

        speak("Ghidarea a început. Mergi drept aproximativ zece metri.")
    }

    private fun updateStatusText() {
        binding.statusText.text = appState.toString()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_id")
    }

    override fun onResume() {
        super.onResume()
        VisionPipeline.init(requireContext())

        locationHelper.getLocation { lat, lon ->
            handleLocationUpdate(lat, lon)
            recalcRoute(lat, lon)
        }

        stepSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }


    override fun onPause() {
        super.onPause()
        locationHelper.stop()
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

    private fun handleTrafficLightState(state: LightState) {
        if (appState != AppState.CHECKING_TRAFFIC_LIGHT) return

        binding.statusText.text = "Semafor detectat: $state"

        val now = System.currentTimeMillis()
        if (state == lastSpokenLightState && now - lastLightStateTimestamp < lightStateCooldownMs) return

        lastSpokenLightState = state
        lastLightStateTimestamp = now

        when (state) {
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
