# Viral Clips

Android app + Python backend. Upload a video, get the best moments as 9:16 clips with word-highlight captions.

## Backend
Requires Python 3.10+ and FFmpeg on PATH.

    cd backend
    pip install -r requirements.txt
    export ANTHROPIC_API_KEY=sk-ant-...   # optional; without it a speech-density heuristic picks clips
    uvicorn main:app --host 0.0.0.0 --port 8000

Env vars: WHISPER_MODEL (tiny/base/small/medium, default small), WHISPER_DEVICE (auto/cpu/cuda),
WHISPER_COMPUTE (int8/float16), LLM_MODEL (default claude-sonnet-4-6).
Docker: `docker build -t viral-clips . && docker run -p 8000:8000 -e ANTHROPIC_API_KEY=... viral-clips`

## Android
Open the `android/` folder in Android Studio (Koala or newer) and let Gradle sync (it creates the wrapper).
Run on the emulator (server URL http://10.0.2.2:8000) or a real phone on the same Wi-Fi
(use http://<your-PC-LAN-IP>:8000; edit it in the app).

## Before production
- Add auth (API key/JWT) and HTTPS; remove `usesCleartextTraffic` from the manifest.
- Persist jobs (Redis/DB) and use a real queue; JOBS is in-memory.
- Delete old job folders in backend/data.
- Move uploads to WorkManager so they survive the app being backgrounded.
- Very long videos: chunk the transcript before sending it to the LLM.
