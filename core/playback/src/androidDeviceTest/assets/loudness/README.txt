Synthetic 3-second stereo tones generated for this project's decoder integration tests.
No recorded or third-party music. Test data: CC0-1.0.

Source: 48 kHz, left = 0.1*sin(2*pi*1000*t), right = -left.
Generated with FFmpeg (flac, libmp3lame 192k, native aac 192k):
ffmpeg -f lavfi -i 'aevalsrc=0.1*sin(2*PI*1000*t)|-0.1*sin(2*PI*1000*t):s=48000:d=3' -c:a CODEC [ -b:a 192k ] tone.EXT

Expected integrated loudness is approximately -20 LUFS. Lossy codec tolerances are
explicit in IntegratedLoudnessDeviceTest. These assets are in the TEST APK only.
