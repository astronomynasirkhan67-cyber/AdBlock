# Run the supplied Qwen assembly engine from Windows

**Important:** the uploaded project is Qwen3.5-0.8B written for Linux x86-64, with AVX2/FMA. It is not a native Windows engine and it is not an Android ARM64 engine. The Windows ULTRON desktop app calls it through WSL2 Ubuntu.

## 1. Install WSL2 (PowerShell as Administrator)

```powershell
wsl --install -d Ubuntu
```

Restart if Windows asks, then launch Ubuntu once and create its Linux username/password.

## 2. Put the repository at C:\ULTRON-AI

Extract the project folder to `C:\ULTRON-AI` (the desktop launcher currently expects this exact location).

## 3. Build the Qwen engine inside Ubuntu

Open Ubuntu (WSL) and run:

```bash
cd /mnt/c/ULTRON-AI/pc-engine/qwen3_5-assembly
sudo apt update
sudo apt install -y build-essential make curl python3
bash scripts/download_model.sh
make
```

The checkpoint download is about 1.75 GB, plus tokenizer data. Do not commit the downloaded `model/` or compiled `build/` directory to Git; `.gitignore` excludes them.

Check CPU support before running: the engine requires x86-64 AVX2 and FMA. If WSL reports an illegal instruction, the host CPU/virtualization environment may not expose those features.

## 4. Start the Windows app

Build/download `ULTRON-AI.exe` from the `ULTRON Windows EXE` GitHub Actions artifact. Keep the repository at `C:\ULTRON-AI`, start WSL2 Ubuntu, and open the EXE. It sends each prompt to the local WSL Qwen executable.

The desktop launcher is a basic text-chat bridge. It does not yet include Whisper/Piper voice, plugin integrations, or persistent chat memory.
