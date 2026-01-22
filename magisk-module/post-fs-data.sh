#!/system/bin/sh
# WhisperPair Magisk Module - Post FS Data Script
# Runs early during boot, before zygote starts

MODDIR=${0%/*}

# Grant runtime permissions via pm grant (runs after system is ready)
# This script runs too early for pm, so we use service.sh instead
