#!/usr/bin/env python3
"""Resize MuseGram cassette logo for internal icons."""
from PIL import Image
import os
import shutil

SOURCE = "/home/quartz/.gemini/antigravity/brain/995641ab-b93e-489f-afd6-f215cffc1469/media__1775188939294.png"
RES = "/home/quartz/Desktop/KBGram/TMessagesProj/src/main/res"

print(f"Loading source image from {SOURCE}...")
try:
    img = Image.open(SOURCE).convert("RGBA")
    print(f"Source image loaded successfully: {img.size}")
except Exception as e:
    print(f"Error loading image: {e}")
    exit(1)

# Sizes for internal logos
INTERNAL_SIZES = {
    "drawable-mdpi": 128,
    "drawable-hdpi": 192,
    "drawable-xhdpi": 256,
    "drawable-xxhdpi": 384,
    "drawable-xxxhdpi": 512,
}

# The files we want to replace with the cassette image
TARGET_FILES = [
    "intro_tg_plane.png",
    "telegram_logo.png",
    "telegram_logo_2.png" 
]

def resize_and_save(sizes, filenames):
    for density, size in sizes.items():
        resized = img.resize((size, size), Image.LANCZOS)
        for fname in filenames:
            path = os.path.join(RES, density, fname)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            resized.save(path, "PNG")
            print(f"  Saved: {path} ({size}x{size})")

print("\n=== Internal Logos ===")
resize_and_save(INTERNAL_SIZES, TARGET_FILES)

# Delete existing XML vectors that conflict with PNGs
print("\n=== Cleaning up XML vectors ===")
for xml_file in ["telegram_logo.xml", "telegram_logo_2.xml"]:
    xml_path = os.path.join(RES, "drawable", xml_file)
    if os.path.exists(xml_path):
        try:
            os.remove(xml_path)
            print(f"Deleted vector: {xml_path}")
        except Exception as e:
            print(f"Failed to delete {xml_path}: {e}")

print("\nInternal icons replaced successfully!")
