# GyroCapture (research logger)

Records 4K rear-camera video while logging gyroscope + accelerometer at the
hardware's maximum rate, all on the same `elapsedRealtimeNanos` clock.
Purpose: dataset collection for IMU-guided video frame interpolation research.

Outputs per session:
- `Movies/GyroCapture/<session>.mp4` — video (UHD, falls back to FHD)
- `Documents/GyroCapture/<session>-imu.csv` — `t_ns,sensor(G|A),x,y,z`
- `Documents/GyroCapture/<session>-meta.json` — sync anchor (recording start ns)

APK is built automatically by GitHub Actions → see Releases.
