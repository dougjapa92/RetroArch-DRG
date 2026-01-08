#!/bin/bash

# Lista de cores para download
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

# Função para baixar e preparar um único core
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
      # Descompacta o zip original do buildbot
      unzip -oq "$TEMP_DIR/$CORE_FILE" -d "$TEMP_DIR"
      local SO_FILE="$TEMP_DIR/${CORE_FILE%.zip}"
      local DEST_FILE="$CORES_DIR/${CORE_FILE%.zip}"

      # Move o .so para a pasta final de coleta
      if [[ -f "$SO_FILE" ]]; then
        mv "$SO_FILE" "$DEST_FILE"
        touch "$TEMP_DIR/$CORE_FILE.success"
        echo "[$ARCH] $CORE_FILE processado."
      fi
      
      rm -f "$TEMP_DIR/$CORE_FILE"
      return 0
    else
      echo "[$ARCH] Falha ao baixar $CORE_FILE (tentativa $((RETRY+1)))."
      ((RETRY++))
      sleep $WAIT
      WAIT=$((WAIT * 2))
    fi
  done
  return 1
}

# Função principal de processamento por arquitetura
processar_arquitetura() {
  local ARCH_LIBRETRO=$1 # "arm64-v8a" ou "armeabi-v7a"
  local ZIP_NAME=$2      # "cores64.zip" ou "cores32.zip"
  local ASSETS_DIR="app/src/main/assets" # Ajuste para o seu diretório de assets
  
  local CORES_TEMP_DIR="temp_cores_$ARCH_LIBRETRO"
  local DOWNLOAD_TEMP="temp_download_$ARCH_LIBRETRO"
  local MAX_JOBS=4

  echo "=============================================="
  echo " Iniciando Processamento: $ARCH_LIBRETRO -> $ZIP_NAME"
  echo "=============================================="

  mkdir -p "$CORES_TEMP_DIR" "$DOWNLOAD_TEMP"

  # Inicia downloads em paralelo
  for CORE_FILE in "${CORES_LIST[@]}"; do
    baixar_core "$ARCH_LIBRETRO" "$CORES_TEMP_DIR" "$DOWNLOAD_TEMP" "$CORE_FILE" &
    
    # Controle de jobs simultâneos
    while [[ $(jobs -r | wc -l) -ge $MAX_JOBS ]]; do
      sleep 1
    done
  done

  wait # Aguarda todos os downloads e unzips terminarem

  # Verifica se a pasta tem arquivos antes de zipar
  if [ "$(ls -A $CORES_TEMP_DIR)" ]; then
    echo "Gerando $ZIP_NAME sem compressão (Stored)..."
    mkdir -p "$ASSETS_DIR"
    
    # Remove o zip antigo se existir para evitar acumular arquivos
    rm -f "$ASSETS_DIR/$ZIP_NAME"
    
    # Entra na pasta para que o zip não tenha caminhos relativos
    (cd "$CORES_TEMP_DIR" && zip -0 -j "../../$ASSETS_DIR/$ZIP_NAME" *.so)
    
    echo "Sucesso: $ZIP_NAME criado em $ASSETS_DIR"
  else
    echo "Erro: Nenhum core foi baixado para $ARCH_LIBRETRO"
  fi

  # Limpeza
  rm -rf "$CORES_TEMP_DIR" "$DOWNLOAD_TEMP"
}

# Execução do script
# Certifique-se de estar na raiz do seu projeto Android ao executar
processar_arquitetura "arm64-v8a" "cores64.zip"
processar_arquitetura "armeabi-v7a" "cores32.zip"

echo "=============================================="
echo " Processo Finalizado!"
echo "=============================================="
