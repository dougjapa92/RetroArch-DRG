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

baixar_core() {
  local ARCH=$1
  local CORES_DIR=$2
  local TEMP_DIR=$3
  local CORE_FILE=$4
  local BASE_URL="https://buildbot.libretro.com/nightly/android/latest/$ARCH/"
  local MAX_RETRIES=3
  local RETRY=0
  local WAIT=2

  mkdir -p "$CORES_DIR" "$TEMP_DIR"

  while [[ $RETRY -lt $MAX_RETRIES ]]; do
    echo "[$ARCH] Baixando $CORE_FILE (tentativa $((RETRY+1)))..."
    if curl -sS -fL "${BASE_URL}${CORE_FILE}" -o "$TEMP_DIR/$CORE_FILE"; then
      unzip -oq "$TEMP_DIR/$CORE_FILE" -d "$TEMP_DIR"
      local SO_FILE="$TEMP_DIR/${CORE_FILE%.zip}"
      local DEST_FILE="$CORES_DIR/${CORE_FILE%.zip}"

      # Evita sobrescrever caso já exista
      if [[ ! -f "$DEST_FILE" ]]; then
        cp "$SO_FILE" "$DEST_FILE"
      fi

      # Marca sucesso criando arquivo temporário
      touch "$TEMP_DIR/$CORE_FILE.success"

      echo "[$ARCH] $CORE_FILE atualizado com sucesso."
      rm -f "$TEMP_DIR/$CORE_FILE"
      return 0
    else
      echo "[$ARCH] Falha ao baixar $CORE_FILE (tentativa $((RETRY+1)))."
      ((RETRY++))
      sleep $WAIT
      WAIT=$((WAIT * 2))
    fi
  done

  echo "[$ARCH] Erro crítico: não foi possível baixar $CORE_FILE após $MAX_RETRIES tentativas."
  return 1
}

baixar_cores() {
  local ARCH=$1
  local CORES_DIR=$2
  local TEMP_DIR=$3
  local MAX_JOBS=4

  mkdir -p "$CORES_DIR" "$TEMP_DIR"

  # Inicia downloads em paralelo
  for CORE_FILE in "${CORES_LIST[@]}"; do
    baixar_core "$ARCH" "$CORES_DIR" "$TEMP_DIR" "$CORE_FILE" &
    
    # Limita o número de jobs simultâneos
    while [[ $(jobs -r | wc -l) -ge $MAX_JOBS ]]; do
      sleep 1
    done
  done

  wait  # Aguarda todos terminarem

  local SUCCESSFUL=()
  local FAILED=()

  # Verifica sucesso pela existência do arquivo .success
  for CORE_FILE in "${CORES_LIST[@]}"; do
    if [[ -f "$TEMP_DIR/$CORE_FILE.success" ]]; then
      SUCCESSFUL+=("$CORE_FILE")
    else
      FAILED+=("$CORE_FILE")
    fi
  done

  rm -rf "$TEMP_DIR"

  # Resumo final
  echo "===== Resumo do download ====="
  echo "Total de cores: ${#CORES_LIST[@]}"
  echo "Sucesso: ${#SUCCESSFUL[@]}"
  echo "Falha: ${#FAILED[@]}"

  if [[ ${#FAILED[@]} -gt 0 ]]; then
    echo "Cores que falharam:"
    printf '%s\n' "${FAILED[@]}"
  else
    echo "Todos os cores foram baixados com sucesso."
  fi
}

# Exemplo de uso:
# baixar_cores "arm64-v8a" "/caminho/para/cores" "/caminho/temporario" 
