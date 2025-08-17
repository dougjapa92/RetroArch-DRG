#!/bin/bash

# Lista única de núcleos usados (não precisa duplicar entre 32/64 bits)
CORES_LIST=(
  "81_libretro_android.so.zip"
  "a5200_libretro_android.so.zip"
  "bluemsx_libretro_android.so.zip"
  "cap32_libretro_android.so.zip"
  "fbalpha2012_cps1_libretro_android.so.zip"
  "fbalpha2012_cps2_libretro_android.so.zip"
  "fbalpha2012_cps3_libretro_android.so.zip"
  "fbalpha2012_libretro_android.so.zip"
  "fbalpha2012_neogeo_libretro_android.so.zip"
  "fbneo_libretro_android.so.zip"
  "fceumm_libretro_android.so.zip"
  "flycast_libretro_android.so.zip"
  "freeintv_libretro_android.so.zip"
  "fuse_libretro_android.so.zip"
  "gambatte_libretro_android.so.zip"
  "gearsystem_libretro_android.so.zip"
  "genesis_plus_gx_libretro_android.so.zip"
  "handy_libretro_android.so.zip"
  "mame2000_libretro_android.so.zip"
  "mame2003_plus_libretro_android.so.zip"
  "mame2010_libretro_android.so.zip"
  "mednafen_ngp_libretro_android.so.zip"
  "mednafen_pce_fast_libretro_android.so.zip"
  "mednafen_supergrafx_libretro_android.so.zip"
  "mednafen_vb_libretro_android.so.zip"
  "mednafen_wswan_libretro_android.so.zip"
  "mgba_libretro_android.so.zip"
  "neocd_libretro_android.so.zip"
  "pcsx_rearmed_libretro_android.so.zip"
  "picodrive_libretro_android.so.zip"
  "ppsspp_libretro_android.so.zip"
  "prosystem_libretro_android.so.zip"
  "snes9x2002_libretro_android.so.zip"
  "snes9x2005_libretro_android.so.zip"
  "snes9x2010_libretro_android.so.zip"
  "snes9x_libretro_android.so.zip"
  "stella2014_libretro_android.so.zip"
  "swanstation_libretro_android.so.zip"
  "vecx_libretro_android.so.zip"
  "vice_x64_libretro_android.so.zip"
)

# Função para baixar e atualizar núcleos
baixar_cores() {
  local ARCH=$1       # armeabi-v7a ou arm64-v8a
  local CORES_DIR=$2  # pasta de destino
  local TEMP_DIR=$3   # pasta temporária

  BASE_URL="https://buildbot.libretro.com/nightly/android/latest/$ARCH/"

  mkdir -p "$CORES_DIR" "$TEMP_DIR"

  for CORE_FILE in "${CORES_LIST[@]}"; do
    echo "[$ARCH] Baixando $CORE_FILE..."
    curl -L "${BASE_URL}${CORE_FILE}" -o "$TEMP_DIR/$CORE_FILE"
    unzip -o "$TEMP_DIR/$CORE_FILE" -d "$TEMP_DIR"

    # Copia o núcleo atualizado para a pasta
    for SO_FILE in "$TEMP_DIR"/*.so; do
      cp -f "$SO_FILE" "$CORES_DIR/" || true
      echo "[$ARCH] Atualizado $(basename "$SO_FILE")"
    done

    rm -f "$TEMP_DIR/$CORE_FILE"
  done

  rm -rf "$TEMP_DIR"
}
