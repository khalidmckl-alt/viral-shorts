"""Video in -> captioned vertical viral clips out."""
import json
import os
import statistics
import subprocess
from pathlib import Path

import cv2
from faster_whisper import WhisperModel

_model = None


def get_model():
    global _model
    if _model is None:
        _model = WhisperModel(
            os.getenv("WHISPER_MODEL", "small"),
            device=os.getenv("WHISPER_DEVICE", "auto"),
            compute_type=os.getenv("WHISPER_COMPUTE", "int8"),
        )
    return _model


def run(cmd, cwd=None):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"{cmd[0]} failed: {p.stderr[-600:]}")
    return p.stdout


def probe_duration(path):
    out = run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
               "-of", "json", str(path)])
    return float(json.loads(out)["format"]["duration"])


# ---------- 1. transcription ----------
def transcribe(video, workdir):
    audio = Path(workdir) / "audio.wav"
    run(["ffmpeg", "-y", "-i", str(video), "-vn", "-ac", "1", "-ar", "16000", str(audio)])
    segments, _ = get_model().transcribe(str(audio), word_timestamps=True, vad_filter=True)
    segs, words = [], []
    for s in segments:
        segs.append({"start": s.start, "end": s.end, "text": s.text.strip()})
        for w in s.words or []:
            token = w.word.strip().replace("{", "").replace("}", "")
            if token:
                words.append({"word": token, "start": w.start, "end": w.end})
    return segs, words


# ---------- 2. viral moment selection ----------
PROMPT = """You are an expert short-form video editor (TikTok, Reels, Shorts).
Below is a timestamped transcript of a video. Pick up to {n} segments that would
perform best as standalone clips.

Rules:
- Each clip is {lo}-{hi} seconds long, self-contained, starting on a strong hook
  (bold claim, question, surprise, story start) and ending on a finished thought.
- Prefer emotional peaks, humor, controversy, useful tips, and punchlines.
- Clips must not overlap. Use start/end times that exist in the transcript.
- Return ONLY a JSON array, no prose, no markdown fences:
[{{"start": 12.5, "end": 58.0, "title": "short catchy title", "reason": "why it works", "score": 1-100}}]

Transcript:
{transcript}
"""


def llm_pick(segs, n, lo, hi):
    import anthropic
    transcript = "\n".join(f"[{s['start']:.1f}-{s['end']:.1f}] {s['text']}" for s in segs)
    client = anthropic.Anthropic()
    msg = client.messages.create(
        model=os.getenv("LLM_MODEL", "claude-sonnet-4-6"),
        max_tokens=2000,
        messages=[{"role": "user",
                   "content": PROMPT.format(n=n, lo=lo, hi=hi, transcript=transcript)}],
    )
    text = "".join(b.text for b in msg.content if b.type == "text").strip()
    text = text.replace("```json", "").replace("```", "").strip()
    return json.loads(text)


def heuristic_pick(words, duration, n, hi):
    """Fallback without an LLM: densest-speech windows."""
    win = min(45.0, hi)
    cands = []
    t = 0.0
    while t + 15 <= duration:
        end = min(t + win, duration)
        count = sum(1 for w in words if t <= w["start"] < end)
        cands.append((count / (end - t), t, end))
        t += 5
    cands.sort(reverse=True)
    chosen = []
    for score, s, e in cands:
        if all(e <= c["start"] or s >= c["end"] for c in chosen):
            chosen.append({"start": s, "end": e, "title": f"Clip {len(chosen) + 1}",
                           "reason": "High speech density", "score": int(min(score * 20, 100))})
        if len(chosen) >= n:
            break
    return chosen


def pick_clips(segs, words, duration, n):
    lo, hi = 20, 60
    clips = None
    if os.getenv("ANTHROPIC_API_KEY"):
        try:
            clips = llm_pick(segs, n, lo, hi)
        except Exception as e:  # noqa
            print("LLM selection failed, using heuristic:", e)
    if not clips:
        clips = heuristic_pick(words, duration, n, hi)
    out = []
    for c in clips:
        s, e = float(c["start"]), float(c["end"])
        ws = [w for w in words if w["end"] >= s - 0.3 and w["start"] <= e + 0.3]
        if ws:
            s, e = ws[0]["start"], ws[-1]["end"]
        s, e = max(0.0, s - 0.15), min(duration, e + 0.3)
        if e - s < 8:
            continue
        if any(not (e <= o["start"] or s >= o["end"]) for o in out):
            continue
        out.append({"start": round(s, 2), "end": round(e, 2),
                    "title": str(c.get("title", "Clip"))[:80],
                    "reason": str(c.get("reason", ""))[:200],
                    "score": int(c.get("score", 50))})
    return sorted(out, key=lambda c: -c["score"])[:n]


# ---------- 3. captions (ASS) ----------
HL, WH = "&H0000FFFF&", "&H00FFFFFF&"  # yellow highlight, white (BGR)
ASS_HEADER = """[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920

[V4+ Styles]
Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding
Style: Default,Arial,84,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,1,0,0,0,100,100,0,0,1,7,2,2,60,60,420,1

[Events]
Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
"""


def ass_time(t):
    cs = max(0, round(t * 100))
    return f"{cs // 360000}:{cs // 6000 % 60:02d}:{cs // 100 % 60:02d}.{cs % 100:02d}"


def group_words(words, max_words=3, gap=0.6):
    groups, cur = [], []
    for w in words:
        if cur and (len(cur) >= max_words or w["start"] - cur[-1]["end"] > gap
                    or cur[-1]["word"].endswith((".", "?", "!"))):
            groups.append(cur)
            cur = []
        cur.append(w)
    if cur:
        groups.append(cur)
    return groups


def build_ass(words, start, end, path):
    clip_words = [w for w in words if w["start"] >= start - 0.05 and w["end"] <= end + 0.1]
    lines = [ASS_HEADER]
    for g in group_words(clip_words):
        for i, w in enumerate(g):
            s = w["start"] - start
            e = g[i + 1]["start"] - start if i + 1 < len(g) else w["end"] - start
            if e <= s:
                e = s + 0.05
            parts = []
            for j, x in enumerate(g):
                word = x["word"].upper()
                parts.append(f"{{\\c{HL}}}{word}{{\\c{WH}}}" if j == i else word)
            lines.append(f"Dialogue: 0,{ass_time(s)},{ass_time(e)},Default,,0,0,0,,{' '.join(parts)}\n")
    Path(path).write_text("".join(lines), encoding="utf-8")


# ---------- 4. reframing + render ----------
def face_center_x(video, start, end, samples=12):
    cap = cv2.VideoCapture(str(video))
    cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    xs = []
    for i in range(samples):
        t = start + (end - start) * (i + 0.5) / samples
        cap.set(cv2.CAP_PROP_POS_MSEC, t * 1000)
        ok, frame = cap.read()
        if not ok:
            continue
        h, w = frame.shape[:2]
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = cascade.detectMultiScale(gray, 1.1, 5, minSize=(w // 20, w // 20))
        if len(faces):
            x, _, fw, _ = max(faces, key=lambda f: f[2] * f[3])
            xs.append((x + fw / 2) / w)
    cap.release()
    return statistics.median(xs) if xs else 0.5


def render_clip(video, words, clip, out_path, workdir):
    video = Path(video).resolve()
    start, end = clip["start"], clip["end"]
    ass_name = f"{Path(out_path).stem}.ass"
    build_ass(words, start, end, Path(workdir) / ass_name)
    cx = face_center_x(video, start, end)
    vf = (
        f"crop=w='trunc(min(iw\\,ih*9/16)/2)*2':h=ih:"
        f"x='min(max({cx:.4f}*iw-out_w/2\\,0)\\,iw-out_w)':y=0,"
        f"scale=1080:1920,ass={ass_name}"
    )
    run(["ffmpeg", "-y", "-ss", f"{start:.3f}", "-i", str(video), "-t", f"{end - start:.3f}",
         "-vf", vf, "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
         "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k",
         "-movflags", "+faststart", str(Path(out_path).resolve())], cwd=str(workdir))


# ---------- orchestration ----------
def process(video, workdir, max_clips, progress):
    workdir = Path(workdir)
    progress("Reading video", 5)
    duration = probe_duration(video)
    progress("Transcribing speech", 10)
    segs, words = transcribe(video, workdir)
    if not words:
        raise RuntimeError("No speech detected in this video.")
    progress("Finding viral moments", 55)
    clips = pick_clips(segs, words, duration, max_clips)
    if not clips:
        raise RuntimeError("Could not find suitable clips.")
    results = []
    for i, c in enumerate(clips):
        progress(f"Rendering clip {i + 1} of {len(clips)}", 60 + int(38 * i / len(clips)))
        out = workdir / f"clip_{i}.mp4"
        render_clip(video, words, c, out, workdir)
        results.append({"index": i, **c})
    progress("Done", 100)
    return results
