# Fortune Cookie Video Setup Instructions

## Where to Place the Video File

You have **3 options** for placing the `0113 (1).mp4` video file. Listed in order of recommendation:

### ✅ Option 1: Raw Resources (RECOMMENDED - Best for Production)

**Location:** `app/src/main/res/raw/fortune_cookie_video.mp4`

**Steps:**
1. Create the `raw` folder if it doesn't exist: `app/src/main/res/raw/`
2. Copy your video file `0113 (1).mp4` to that folder
3. **Rename it to:** `fortune_cookie_video.mp4` (no spaces, lowercase)
4. The video will be bundled with the app and available immediately

**Advantages:**
- Video is bundled with the app (no runtime copying needed)
- Works offline
- Fastest access
- Best for production apps

---

### Option 2: Assets Folder (Good for Development)

**Location:** `app/src/main/assets/fortune_cookie_video.mp4`

**Steps:**
1. Create the `assets` folder if it doesn't exist: `app/src/main/assets/`
2. Copy your video file to that folder
3. Rename it to: `fortune_cookie_video.mp4`
4. The app will automatically copy it to the internal files directory on first run

**Advantages:**
- Easy to update during development
- App handles copying automatically

---

### Option 3: External Storage (For Testing Only)

**Location:** `/sdcard/Download/0113 (1).mp4` or device Download folder

**Steps:**
1. Copy the video file to your device's Download folder
2. The app will automatically find and copy it to internal storage on first run

**Note:** This is only for testing. The app will copy it to internal storage anyway.

---

## Quick Setup (Recommended Method)

```bash
# 1. Create raw folder (if it doesn't exist)
mkdir -p "PlayerApp - F3 F1/app/src/main/res/raw"

# 2. Copy and rename the video file
# Copy 0113 (1).mp4 from your current location to:
# PlayerApp - F3 F1/app/src/main/res/raw/fortune.mp4.mp4
```

**File path structure:**
```
PlayerApp - F3 F1/
└── app/
    └── src/
        └── main/
            └── res/
                └── raw/
                    └── fortune_cookie_video.mp4  ← Place video here
```

---

## After Placing the Video

1. **Rebuild the app** (the video will be included in the APK)
2. The fortune screen will automatically detect and play the video
3. After the video ends, the modal with the fortune message will appear

---

## Troubleshooting

If the video doesn't play, check the logcat for messages starting with `DEBUG: FortuneScreen -`. The logs will tell you:
- Where the app looked for the video
- Whether it found it or not
- Any errors that occurred

The app checks locations in this order:
1. Raw resources (`res/raw/fortune_cookie_video.mp4`)
2. App's internal files directory
3. Assets folder
4. External storage (Download folder)

---

## Current Status

The video file `0113 (1).mp4` is currently in the `drawable` folder, which is incorrect. **Please move it to the `raw` folder** and rename it to `fortune_cookie_video.mp4`.
