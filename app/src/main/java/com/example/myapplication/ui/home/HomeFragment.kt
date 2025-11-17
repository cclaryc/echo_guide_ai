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
import com.example.myapplication.ai.LocationHelper
import com.example.myapplication.ai.VisionPipeline
import android.location.Geocoder
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

import com.example.myapplication.state.ObstacleState

import org.json.JSONObject

class HomeFragment : Fragment(), SensorEventListener {
    companion object {
        private const val TAG = "HomeFragment"
        private const val REQ_RECORD_AUDIO = 1001
    }
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


    private lateinit var locationHelper: LocationHelper
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechIntent: Intent
    private val handler = Handler(Looper.getMainLooper())


    data class RouteStep(
        val lat: Double,
        val lon: Double,
        val instruction: String
    )

    private var routeLoaded = false
    private var routeSteps: List<RouteStep> = emptyList()
    private var currentStepIndex = 0

    private val lastStates = ArrayDeque<LightState>()
    private val smoothingSize = 5
    private var destinationLat: Double? = null
    private var destinationLon: Double? = null
    // dacă e false: ignorăm semafoare/obstacole (nu vorbim despre ele)
    private var envAnnouncementsEnabled: Boolean = false
    // PENTRU OBSTACOLE!
    private val obstacleHistory = ArrayDeque<ObstacleState>()
    private val obstacleSmoothingSize = 5  // luăm 5 frame-uri recente

    private var lastObstacleSpeakTime = 0L
    private val obstacleCooldown = 5000L // 5 secunde

    private fun handleObstacleState(state: ObstacleState) {

        // 1. Add to buffer
        obstacleHistory.addLast(state)
        if (obstacleHistory.size > obstacleSmoothingSize) {
            obstacleHistory.removeFirst()
        }

        // 2. Need 2 detections in the last 5 frames
        val count = obstacleHistory.count { it == ObstacleState.OBSTACLE_AHEAD }
        if (count < 2) return

        val now = System.currentTimeMillis()

        // 3. Cooldown anti-spam
        if (now - lastObstacleSpeakTime < obstacleCooldown) {
            return
        }

        lastObstacleSpeakTime = now

        // 4. Announce
        speak("Atenție, obstacol în față.")
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    private fun handleLocationUpdate(lat: Double, lon: Double) {
        Log.d("NAV", "Locație actualizată: $lat, $lon")

        lastLatitude = lat
        lastLongitude = lon
        if (!routeLoaded) return

        val nextStep = routeSteps[currentStepIndex]

        val stepLat = nextStep.lat
        val stepLon = nextStep.lon

        val dist = distanceInMeters(lat, lon, stepLat, stepLon)

        if (dist < 10) {
            speak("Ai ajuns la pasul ${currentStepIndex + 1}")
            currentStepIndex++

            if (currentStepIndex < routeSteps.size) {
                val nextStep = routeSteps[currentStepIndex]
                Log.d("NAV", "Următorul pas: ${nextStep.instruction}")
                speak("Următorul pas: ${nextStep.instruction}")
            } else {
                Log.d("NAV", "Nu mai sunt pași în rută – probabil ai ajuns la destinație.")
                speak("Ai ajuns la destinație.")
                appState = AppState.DONE
                updateStatusText()
            }

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

        val destLat = destinationLat
        val destLon = destinationLon

        if (destLat == null || destLon == null) {
            Log.w("NAV", "Destination not set yet, cannot recalc route.")
            speak("Destinația nu este setată încă.")
            return
        }
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

//                    // 🔥 AICI e modificarea importantă:
//                    val instruction =
//                        if (stepObj.has("instruction")) {
//                            stepObj.getString("instruction")
//                        } else {
//                            val type = maneuver.optString("type", "")
//                            val modifier = maneuver.optString("modifier", "")
//
//                            when (type) {
//                                "turn" -> "Fă o întoarcere spre $modifier"
//                                "depart" -> "Pornire spre $modifier"
//                                "arrive" -> "Ai ajuns la destinație"
//                                "new name" -> "Continuă pe strada următoare"
//                                else -> "Continuați înainte"
//                            }
//                        }
//
//                    newSteps += RouteStep(lat, lon, instruction)

                    val type = maneuver.optString("type", "")
                    val modifier = maneuver.optString("modifier", "")

                    val instruction = buildRomanianInstruction(type, modifier)

                    newSteps += RouteStep(lat, lon, instruction)


                }

                requireActivity().runOnUiThread {
                    routeSteps = newSteps
                    routeLoaded = true
                    currentStepIndex = 0
                    speak("Ruta a fost recalculată. Are ${newSteps.size} pași.")

                    if (routeSteps.isNotEmpty()) {
                        val firstInstruction = routeSteps[0].instruction
                        Log.d("NAV", "Primul pas: $firstInstruction")
                        speak("Primul pas: $firstInstruction")
                    }
                    Log.d("NAV", "Rută recalculată – ${newSteps.size} pași.")
                    // 🔥 abia ACUM permitem semafoare / obstacole
                    envAnnouncementsEnabled = true
                }

            } catch (e: Exception) {
                Log.e("NAV", "Eroare recalculare rută: ${e.message}")
            }
        }.start()

    }


    private fun handleLocation(lat: Double, lon: Double) {
        Log.d("HomeFragment", "Locație primită: lat=$lat  lon=$lon")

        lastLatitude = lat
        lastLongitude = lon

        binding.statusText.text = "Locație: $lat , $lon"
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
//        binding.adLabel.text = "Demo ad • v1.0"

        locationHelper = LocationHelper(requireContext())

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        setupTts()

        // 👇👇👇 AICI INIT SPEECH RECOGNIZER 👇👇👇
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
            }

            override fun onError(error: Int) {
                Log.e(TAG, "SpeechRecognizer error: $error")
                speak("Nu am înțeles destinația. Vă rog să încercați din nou.")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                Log.d(TAG, "onResults: matches=$matches, chosen='$text'")

                if (text.isNullOrBlank()) {
                    speak("Nu am înțeles destinația. Vă rog să încercați din nou.")
                    return
                }

                handleSpokenDestination(text)
            }

            override fun onPartialResults(partialResults: Bundle?) { }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
        // 👆👆👆 PÂNĂ AICI NOUL COD 👆👆👆

        VisionPipeline.init(requireContext())

        VisionPipeline.setTrafficLightColorListener { color ->
            handleTrafficLightState(color)
        }

        cameraManager = CameraManager(
            fragment = this,
            previewView = binding.cameraPreview,
            onLightDetected = { state: LightState ->
                Log.d("HomeFragment", "LightState din AI = $state")
                handleTrafficLightState(state)
            },
            onObstacleDetected = { obstacleState: ObstacleState ->
                Log.d("HomeFragment", "ObstacleState din AI = $obstacleState")
            }
        )

        binding.startButton.setOnClickListener {
            // aici ar fi bine să chemi flow-ul de voce:
//            checkMicPermissionAndStartVoiceFlow()
            onStartAssistantClicked()
            binding.startButton.visibility = View.GONE
        }

        binding.locationButton.setOnClickListener {
            speakCurrentLocation()
        }

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
            Permissions.requestAllPermissions(this)
            return
        }
        // la început, blocăm anunțurile de mediu
        envAnnouncementsEnabled = false
        // Pornim locația ca să avem lastLatitude/lastLongitude
        locationHelper.getLocation { lat, lon ->
            handleLocation(lat, lon)
            // NU mai chemăm recalcRoute aici – așteptăm destinația vocală
        }

        cameraManager.startCamera()

        appState = AppState.WALKING
        stepsSinceStart = 0
        hasAnnouncedTrafficLight = false
        lastSpokenLightState = LightState.NONE
        updateStatusText()
//        speak("Aplicație pornită. Bine v-am găsit. Spuneți unde vreți să mergeți.")

        // Pornim locația ca să avem lastLatitude/lastLongitude
//        locationHelper.getLocation { lat, lon ->
//            handleLocation(lat, lon)
//            // NU mai chemăm recalcRoute aici – așteptăm destinația vocală
//        }
        // De-abia acum pornim flow-ul vocal (va spune și „Bine v-am găsit” acolo)
        checkMicPermissionAndStartVoiceFlow()
    }
    private fun checkMicPermissionAndStartVoiceFlow() {
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "RECORD_AUDIO permission not granted. Requesting...")
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_AUDIO
            )
        } else {
            startVoiceDestinationFlow()
        }
    }

    private fun startVoiceDestinationFlow() {
        Log.d(TAG, "startVoiceDestinationFlow()")

        // Spunem tot mesajul aici
        speak("Aplicație pornită. Bine v-am găsit. Spuneți unde vreți să mergeți.", flush = true)

        handler.postDelayed({
            Log.d(TAG, "Starting SpeechRecognizer listening for destination...")
//            if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
//                Log.e(TAG, "Speech recognition not available on this device.")
//                speak("Recunoașterea vocală nu este disponibilă pe acest dispozitiv.")
//                return@postDelayed
//            }
            startListeningForDestination()
        }, 4000L) // poți ajusta 3000–4500 în funcție de cât de repede vorbește TTS-ul
    }
    private fun handleSpokenDestination(destinationText: String) {
        Log.d(TAG, "handleSpokenDestination(): '$destinationText'")

        speak("Am înțeles. Mergem la adresa $destinationText.")

        Thread {
            try {
                val geocoder = Geocoder(requireContext(), Locale("ro", "RO"))
                Log.d(TAG, "Geocoding for text='$destinationText'")
                val results = geocoder.getFromLocationName(destinationText, 1)
                Log.d(TAG, "Geocoder returned ${results?.size ?: 0} result(s)")

                if (!results.isNullOrEmpty()) {
                    val addr = results[0]
                    val destLat = addr.latitude
                    val destLon = addr.longitude

                    Log.d(TAG, "Destination geocoded: lat=$destLat, lon=$destLon, addr=$addr")

                    // salvăm destinația global
                    destinationLat = destLat
                    destinationLon = destLon

                    requireActivity().runOnUiThread {
                        startRouteToDestination(destLat, destLon)
                    }
                } else {
                    Log.w(TAG, "No geocoding results for '$destinationText'")
                    requireActivity().runOnUiThread {
                        speak("Nu am găsit adresa $destinationText. Vă rog să încercați din nou.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during geocoding for '$destinationText'", e)
                requireActivity().runOnUiThread {
                    speak("A apărut o eroare la căutarea adresei. Vă rog să încercați din nou.")
                }
            }
        }.start()
    }
    private fun startRouteToDestination(destLat: Double, destLon: Double) {
        val srcLat = lastLatitude
        val srcLon = lastLongitude

        if (srcLat == null || srcLon == null) {
            Log.w(TAG, "Source location not available yet for routing. Requesting one-shot location...")
            locationHelper.getLocation { lat, lon ->
                lastLatitude = lat
                lastLongitude = lon
                Log.d(TAG, "Got fresh source location: $lat, $lon. Recalculating route...")
                recalcRoute(lat, lon)
                appState = AppState.WALKING
                updateStatusText()
//                speak("Ghidarea a început. Mergi drept aproximativ zece metri.")
            }
        } else {
            Log.d(TAG, "Starting route from ($srcLat, $srcLon) to ($destLat, $destLon)")
            recalcRoute(srcLat, srcLon)
            appState = AppState.WALKING
            updateStatusText()
//            speak("Ghidarea a început. Mergi drept aproximativ zece metri.")
        }
    }

    private fun updateStatusText() {
        binding.statusText.text = appState.toString()
    }

//    private fun speak(text: String) {
//        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_id")
//    }
private fun speak(text: String, flush: Boolean = false) {
    val queueMode = if (flush) {
        TextToSpeech.QUEUE_FLUSH   // taie ce se spunea înainte
    } else {
        TextToSpeech.QUEUE_ADD     // adaugă la coadă, spune după ce termină
    }

    tts?.speak(text, queueMode, null, System.currentTimeMillis().toString())
}

    private fun speakCurrentLocation() {
        val lat = lastLatitude
        val lon = lastLongitude

        Log.d(TAG, "speakCurrentLocation() called. lastLatitude=$lat, lastLongitude=$lon")

        if (lat == null || lon == null) {
            Log.w(TAG, "Location not available yet. lastLatitude or lastLongitude is null.")
            speak("Locația nu este încă disponibilă. Te rog să încerci din nou peste câteva secunde.")
            return
        }

        Thread {
            try {
                Log.d(TAG, "Starting reverse geocoding for lat=$lat, lon=$lon")
                val geocoder = Geocoder(requireContext(), Locale("ro", "RO"))
                val results = geocoder.getFromLocation(lat, lon, 1)

                Log.d(TAG, "Geocoder returned ${results?.size ?: 0} results")

                if (!results.isNullOrEmpty()) {
                    val addr = results[0]
                    Log.d(TAG, "Raw address from geocoder: $addr")

                    val street = addr.thoroughfare ?: addr.featureName ?: "stradă necunoscută"
                    val city = addr.locality ?: addr.subAdminArea ?: ""

                    Log.d(TAG, "Parsed street='$street', city='$city'")

                    val spokenText = if (city.isNotEmpty()) {
                        "Ești pe $street, în $city."
                    } else {
                        "Ești pe $street."
                    }

                    requireActivity().runOnUiThread {
                        Log.d(TAG, "Speaking address: $spokenText")
                        binding.statusText.text = spokenText
                        speak(spokenText)
                    }
                } else {
                    Log.w(TAG, "No address found for lat=$lat, lon=$lon")
                    requireActivity().runOnUiThread {
                        val fallback = "Nu pot determina numele străzii. Coordonatele sunt $lat, $lon."
                        binding.statusText.text = fallback
                        speak(fallback)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during reverse geocoding for lat=$lat, lon=$lon", e)
                requireActivity().runOnUiThread {
                    val fallback = "A apărut o eroare la determinarea adresei. Coordonatele sunt $lat, $lon."
                    binding.statusText.text = fallback
                    speak(fallback)
                }
            }
        }.start()
    }


    // --- STEP SENSOR ---
    override fun onResume() {
        super.onResume()
        VisionPipeline.init(requireContext())

        locationHelper.getLocation { lat, lon ->
            handleLocationUpdate(lat, lon)
//            recalcRoute(lat, lon)
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

    // --- TRAFFIC LIGHT LOGIC ---
    fun handleTrafficLightState(state: LightState) {
        // dacă nu vrem încă să vorbim despre semafoare, ieșim imediat
        if (!envAnnouncementsEnabled) {
            Log.d(TAG, "Traffic light state ignored (envAnnouncementsEnabled=false), state=$state")
            return
        }
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO permission granted by user.")
                startVoiceDestinationFlow()
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied by user.")
                speak("Nu pot asculta destinația fără acces la microfon.")
            }
        }
    }

    override fun onDestroyView() {
        tts?.shutdown()
        _binding = null
        super.onDestroyView()
    }

    private fun startListeningForDestination() {
        Log.d(TAG, "startListeningForDestination()")

        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Log.e(TAG, "Speech recognition not available on this device.")
            speak("Recunoașterea vocală nu este disponibilă pe acest dispozitiv.")
            return
        }

        try {
            // Oprim orice TTS activ ca să nu ne auzim pe noi înșine
//            tts?.stop()

            Log.d(TAG, "Starting SpeechRecognizer listening for destination...")
            speechRecognizer?.startListening(speechIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            speak("A apărut o eroare la pornirea recunoașterii vocale.")
            return
        }

        // Oprim ascultarea după ~5 secunde
        handler.postDelayed({
            Log.d(TAG, "Stopping SpeechRecognizer after 5 seconds.")
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition", e)
            }
        }, 5000L)
    }
    private fun modifierToRomanian(modifier: String?): String {
        return when (modifier?.lowercase(Locale.ROOT)) {
            "right" -> "dreapta"
            "slight right" -> "dreapta ușor"
            "sharp right" -> "dreapta brusc"

            "left" -> "stânga"
            "slight left" -> "stânga ușor"
            "sharp left" -> "stânga brusc"

            "uturn" -> "înapoi"
            "straight", null, "" -> "înainte"

            else -> modifier // fallback – în caz că vine ceva ciudat
        }
    }
    private fun buildRomanianInstruction(type: String?, modifier: String?): String {
        val m = modifierToRomanian(modifier)

        return when (type?.lowercase(Locale.ROOT)) {
            "depart" -> "Porniți $m."
            "turn" -> {
                if (modifier?.lowercase(Locale.ROOT) == "uturn") {
                    "Întoarceți-vă în direcția opusă."
                } else {
                    "Faceți la $m."
                }
            }
            "new name" -> "Continuați pe strada următoare."
            "arrive" -> "Ați ajuns la destinație."
            "roundabout" -> "Intrați în sensul giratoriu și ieșiți la ieșirea indicată."
            else -> "Continuați $m."
        }
    }

}