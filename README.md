<p align="center">
  <img src="logo.png" width="180" alt="EchoGuide Logo">
</p>

<h1 align="center">EchoGuide – AI Urban Accessibility Assistant</h1>

<p align="center">
  <b>Transformăm camera telefonului într-un al doilea set de ochi.</b><br>
  Aplicație Android bazată pe AI pentru ghidarea persoanelor cu deficiențe de vedere.
</p>

---

## 📸 Preview

<p align="center">
  <img src="ss" width="300" alt="EchoGuide Screenshot">
</p>

---

# 🧠 Descriere

EchoGuide este o aplicație Android care folosește **Computer Vision**, **Text-to-Speech**,  
**Recunoaștere Vocală** și **rutare pietonală în timp real** pentru a oferi ghidare accesibilă  
persoanelor cu deficiențe de vedere.

Aplicația detectează:

- 🟥 **semafoare și culoarea lor**
- ⚠️ **obstacole din față**
- 🗺️ **ruta către o destinație vorbită**
- 🏙️ **strada pe care se află utilizatorul**
- 🔊 **instrucțiuni vocale pas cu pas**

Totul este procesat **on-device**, fără cloud.

---

# 🚀 Funcționalități

### 🔴 Detecție semafoare (YOLOv5 + ONNX)
- Model AI optimizat pentru mobil  
- Inferență în timp real (30 FPS)  
- Temporal smoothing: fereastră glisantă pe 5 frame-uri  
- Feedback vocal adaptiv

### ⚠️ Detecție obstacole
- Filtru temporal (≥ 2 detecții în 5 frame-uri)  
- Cooldown inteligent (anti-spam)  
- Avertizări naturale: *„Atenție, obstacol în față.”*

### 🧭 Navigație vocală
- Utilizatorul spune destinația → aplicația o înțelege  
- Geocoding + rutare prin OSRM  
- Instrucțiuni vocale în limba română (NLG)

### 🗺️ Identificarea străzii
- Reverse geocoding GPS → numele străzii  
- Voice feedback: *„Ești pe Strada X, în Y.”*

### 🔄 Multi-language (în lucru)
- Input vocal în mai multe limbi  
- Output vocal generat natural

### 🚦 Detectarea trecerilor nesemaforizate (în lucru)

### 🚨 Emergency Mode (în lucru)
- Locație trimisă automat  
- Buton rapid de apel urgență

---

# 🧩 Arhitectură & Pipeline AI



CameraX → Preprocessing → ONNX Runtime (YOLOv5)
→ Temporal Smoothing (5 frames)
→ Light/Obstacle State
→ TTS Output

### 🔍 Preprocessing
- RGB → tensor  
- Normalizare optimizată  
- Memorie reciclată (evită GC spikes)  
- Edge inferență fără latență

### 🧠 Temporal AI Layer
- Fereastră glisantă  
- Vot majoritar (pentru semafoare)  
- Prag minim (pentru obstacole)  
- Cooldown pentru vorbit

### 📝 Natural Language Generation
- Interpretăm `type` + `modifier` OSRM  
- Generăm fraze naturale în română  
- Ex.: „Faceți la stânga”, „Continuați înainte”.

---

# 🛠️ Tehnologii

* **Kotlin**
* **CameraX**
* **YOLOv5 + ONNX Runtime**
* **OSRM API**
* **TextToSpeech**
* **Android SpeechRecognizer**
* **Geocoder API**

---

# 👥 Echipa & Hackathon

Acest proiect a fost realizat în cadrul unui **Hackathon Adobe**,
de o echipă dedicată să utilizeze AI-ul pentru incluziune și accesibilitate.

**Echipă:**
[Greedy Gang]

* Braviceanu-Badea Clarisse 
* Otilia Rudnic
* Banescu Ema

