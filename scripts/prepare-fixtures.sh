#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/fixtures/wav
# macOS system voices; generated speech only, never user media or microphone recordings.
say -v Daniel -r 145 -o artifacts/fixtures/en.aiff \
  'Good subtitles give you time to read. We are testing local speech recognition and translation.'
say -v Tingting -r 150 -o artifacts/fixtures/zh.aiff \
  '今天天气很好，我们去公园散步。这个应用可以在手机上识别语音并翻译字幕。'
say -v Kyoko -r 150 -o artifacts/fixtures/ja.aiff \
  '今日はいい天気です。公園を散歩しましょう。このアプリは日本語の音声を翻訳します。'
for language in en zh ja; do
  ffmpeg -hide_banner -loglevel error -y -i "artifacts/fixtures/$language.aiff" \
    -af apad=pad_dur=1.6 -ar 16000 -ac 1 -c:a pcm_s16le "artifacts/fixtures/wav/$language.wav"
done
