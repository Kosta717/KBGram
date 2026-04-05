#!/usr/bin/env python3
"""Resize MuseGram cassette logo for Android launcher icons."""
from PIL import Image
import os

SOURCE = "/home/quartz/.gemini/antigravity/brain/995641ab-b93e-489f-afd6-f215cffc1469/media__1775188939294.png"
RES = "/home/quartz/Desktop/KBGram/TMessagesProj/src/main/res"

# Android launcher icon sizes per density
LAUNCHER_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# Adaptive icon foreground sizes (108dp * density)
FOREGROUND_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

# ic_launcher_dr sizes (notification-style, smaller)
DR_SIZES = {
    "drawable-mdpi": 48,
    "drawable-hdpi": 72,
    "drawable-xhdpi": 96,
    "drawable-xxhdpi": 144,
    "drawable-xxxhdpi": 192,
}

print(f"Loading source image from {SOURCE}...")
try:
    img = Image.open(SOURCE).convert("RGBA")
    print(f"Source image loaded successfully: {img.size}")
except Exception as e:
    print(f"Error loading image: {e}")
    print("Please make sure the image exists at that path.")
    exit(1)

def resize_and_save(sizes, filenames):
    for density, size in sizes.items():
        resized = img.resize((size, size), Image.LANCZOS)
        for fname in filenames:
            path = os.path.join(RES, density, fname)
            # Ensure directory exists
            os.makedirs(os.path.dirname(path), exist_ok=True)
            resized.save(path, "PNG")
            print(f"  Saved: {path} ({size}x{size})")

print("\n=== Launcher icons ===")
resize_and_save(LAUNCHER_SIZES, ["ic_launcher.png", "ic_launcher_round.png"])

print("\n=== Foreground icons ===")
resize_and_save(FOREGROUND_SIZES, ["icon_foreground.png", "icon_foreground_round.png", "icon_foreground_sa.png"])

print("\n=== DR icons ===")
resize_and_save(DR_SIZES, ["ic_launcher_dr.png"])

# Also update background clip images to a solid dark blue matching the cassette
print("\n=== Background clips ===")
for density, size in FOREGROUND_SIZES.items():
    for fname in ["icon_background_clip.png", "icon_background_clip_round.png"]:
        path = os.path.join(RES, density, fname)
        if os.path.exists(os.path.dirname(path)):
            bg = Image.new("RGBA", (size, size), (59, 55, 113, 255))  # dark purple-blue from cassette
            bg.save(path, "PNG")
            print(f"  Saved: {path} ({size}x{size})")

print("\nAll icons resized and saved successfully!")
