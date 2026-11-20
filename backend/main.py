import shutil
import traceback
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse

import pipeline

DATA = Path("data")
DATA.mkdir(exist_ok=True)
app = FastAPI(title="Viral Clips API")
JOBS: dict = {}
pool = ThreadPoolExecutor(max_workers=1)  # one video at a time (GPU/CPU heavy)


def work(job_id: str, video: Path, max_clips: int):
    job = JOBS[job_id]

    def progress(stage, pct):
        job["stage"], job["progress"] = stage, pct

    try:
        job["status"] = "processing"
        job["clips"] = pipeline.process(video, video.parent, max_clips, progress)
        job["status"] = "done"
    except Exception as e:  # noqa
        traceback.print_exc()
        job["status"], job["error"] = "failed", str(e)


@app.post("/jobs")
def create_job(file: UploadFile = File(...), max_clips: int = Form(3)):
    job_id = uuid.uuid4().hex[:12]
    folder = DATA / job_id
    folder.mkdir(parents=True)
    video = folder / "input.mp4"
    with open(video, "wb") as f:
        shutil.copyfileobj(file.file, f)
    JOBS[job_id] = {"status": "queued", "stage": "Queued", "progress": 0,
                    "error": None, "clips": []}
    pool.submit(work, job_id, video, max(1, min(max_clips, 8)))
    return {"job_id": job_id}


@app.get("/jobs/{job_id}")
def job_status(job_id: str):
    if job_id not in JOBS:
        raise HTTPException(404, "Unknown job")
    return JOBS[job_id]


@app.get("/jobs/{job_id}/clips/{index}")
def get_clip(job_id: str, index: int):
    path = DATA / job_id / f"clip_{index}.mp4"
    if not path.exists():
        raise HTTPException(404, "Clip not found")
    return FileResponse(path, media_type="video/mp4", filename=f"clip_{index + 1}.mp4")


@app.get("/health")
def health():
    return {"ok": True}
