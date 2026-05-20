# Anchor ⚓

Anchor is an offline-first, humane digital memory assistant that continuously indexes what you see and makes it instantly searchable. Built with custom local AI models, it enables you to capture screenshots, index their contents using optical character recognition (OCR) and multimodal vision processing, and ask questions through a conversational RAG (Retrieval-Augmented Generation) assistant.

It consists of two synchronized clients:
- **Desktop Application** (Tauri + React/Vite + Python AI sidecar)
- **Android Application** (Kotlin + Jetpack Compose + Local On-device Inference)

---

## 🚀 Key Features

*   **Continuous Screen Indexing**: Capture your screen via a system shortcut (`Alt + S` on Desktop) or hardware buttons (on Android) to run visual analysis.
*   **Local Multimodal AI**:
    *   **Desktop**: Powered by `Gemma-4 Vision` (via `llama-cpp-python`) running fully offline.
    *   **Android**: Powered by a highly optimized mobile variant running on-device.
*   **Vector Semantic Search**: Combines `LanceDB` and `SentenceTransformers` embeddings (`all-MiniLM-L6-v2`) to let you query memories conceptually, rather than just matching keyword exactness.
*   **Cross-Device Synchronization**: Offline pairing over local WiFi networks using Network Service Discovery (mDNS/NSD). Auto-syncs screen captures and OCR database indices between your PC and Android phone.
*   **GPU Acceleration**: Toggleable CPU/GPU execution mode for ultra-fast screenshot vectorization.
*   **Edge-to-Edge Design System**: Standardized on the premium, dark-themed "Funnel Display" visual language across both platforms.

---

## 📱 Tested Hardware & Specifications

*   **Android Client**: Samsung Galaxy S24 (8GB RAM, Snapdragon processor).
*   **Windows Client**: Windows 11 with NVIDIA GPU (CUDA Toolkit v12.4).

---

## ⚡ GPU Acceleration Support

On Windows, Anchor supports hardware acceleration for visual processing.
*   **Supported GPUs**: NVIDIA GPUs with **CUDA Toolkit v12.4**.
*   **Setup for CUDA Acceleration**:
    1. Install [NVIDIA CUDA Toolkit v12.4](https://developer.nvidia.com/cuda-downloads).
    2. Register MSBuild extensions by running:
       ```cmd
       copy_cuda.bat
       ```
    3. Install the GPU-compatible version of `llama-cpp-python`:
       ```bash
       pip uninstall llama-cpp-python -y
       set CMAKE_ARGS="-DGGML_CUDA=on"
       pip install llama-cpp-python --upgrade --force-reinstall --no-cache-dir --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu124
       ```
    4. Enable **GPU Mode** from the settings menu inside the Anchor desktop app.

---

## 💻 Windows Setup & Run Guide

### 1. Prerequisites
Ensure you have the following installed:
*   [Node.js (v18+)](https://nodejs.org/)
*   [Python 3.10+](https://www.python.org/)
*   [Rust Compiler & Cargo](https://www.rust-lang.org/tools/install)
*   [Git](https://git-scm.com/)

### 2. Sidecar AI Dependency Installation
Navigate to the root directory and install the Python backend requirements:
```bash
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124
pip install sentence-transformers lancedb pyarrow pandas pillow xcap requests flask
```

### 3. Model Files Setup
Anchor expects the offline vision models to reside in your local AppData folder:
1. Locate your AppData local directory (typically `C:\Users\<username>\AppData\Local\com.contextmemory.app\models`).
2. Place the following vision models in the `models` folder:
   - `gemma-4-vision.gguf`
   - `gemma-4-vision-mmproj.gguf`

### 4. Running the Desktop App
Install Node packages and launch the Tauri dev environment:
```bash
# Install UI packages
npm install

# Start the application in development mode
npm run tauri dev
```

---

## 🤖 Android Setup & Run Guide

### 1. Prerequisites
*   [Android Studio (Koala or newer)](https://developer.android.com/studio)
*   Android SDK 34+
*   Galaxy S24 connected in Debug Mode (USB Debugging enabled).

### 2. Project Setup
1. Open the `/android` directory in Android Studio.
2. Let Gradle sync and download build dependencies.
3. Build the APK:
   ```bash
   ./gradlew assembleDebug
   ```

### 3. Setup Accessibility Service
Anchor on Android uses an Accessibility Service to capture screen details securely when hardware volume buttons are clicked:
1. Deploy the app to your Galaxy S24.
2. Go to **Settings > Accessibility > Installed Apps > Anchor**.
3. Toggle the permission to **Enabled**.

### 4. Deploying On-Device AI Models
1. Download the mobile variant GGUF/LiteRT models.
2. Place them under the app assets or copy them into the application's internal files directory (`/data/user/0/com.example.contextmemory/files/gemma-4-e2b-int4.litertlm`) as shown in the system logs.

---

## 🔄 Cross-Device Synchronization Guide

To sync screen captures between your desktop app and your Samsung Galaxy S24:

1. Ensure both your **PC** and your **Galaxy S24** are connected to the **same local WiFi network**.
2. On the Windows client, click the **Menu Icon** (top right, three horizontal bars) and select **Sync Devices**.
3. On the Android client, navigate to the **Sync Screen**.
4. The devices will automatically discover each other using local network mDNS.
5. Tap **Pair / Connect** on your mobile screen.
6. Once connected, toggle **Auto-Sync** or click **Sync Now** to copy memories and keep both vaults fully updated.
