#!/bin/bash

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

baixar_cores() {
  local ARCH=$1
  local CORES_DIR=$2
  local TEMP_DIR=$3

  BASE_URL="https://buildbot.libretro.com/nightly/android/latest/$ARCH/"

  mkdir -p "$CORES_DIR" "$TEMP_DIR"

  total=${#CORES_LIST[@]}
  current=0

  for CORE_FILE in "${CORES_LIST[@]}"; do
    attempt=1
    success=false
    while [ $attempt -le 3 ]; do
      echo "[$ARCH] Baixando $CORE_FILE (tentativa $attempt)..."
      if curl -s -L "$BASE_URL$CORE_FILE" -o "$TEMP_DIR/$CORE_FILE"; then
        unzip -o "$TEMP_DIR/$CORE_FILE" -d "$TEMP_DIR" >/dev/null
        for SO_FILE in "$TEMP_DIR"/*.so; do
          cp -f "$SO_FILE" "$CORES_DIR/" || true
          echo "[$ARCH] Atualizado $(basename "$SO_FILE")"
        done
        rm -f "$TEMP_DIR/$CORE_FILE"
        success=true
        break
      else
        echo "[$ARCH] Falha ao baixar $CORE_FILE"
        ((attempt++))
      fi
    done

    if [ "$success" = false ]; then
      echo "[$ARCH] Erro: não foi possível baixar $CORE_FILE após 3 tentativas"
    fi

    ((current++))
    percent=$((current * 100 / total))
    echo "[$ARCH] Progresso: $current/$total ($percent%)"
  done

  rm -rf "$TEMP_DIR"
}
