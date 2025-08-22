#ifndef INPUT_AUTOCONFIGURE_H
#define INPUT_AUTOCONFIGURE_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Cria um arquivo de configuração baseado em um arquivo base.
 *
 * @param cfg_base       Caminho do arquivo base (ex: autoconfig/PG-9156.cfg)
 * @param new_cfg_path   Caminho do novo arquivo que será gerado
 * @param vendor_id      Vendor ID do dispositivo
 * @param product_id     Product ID do dispositivo
 * @param input_device   Nome do dispositivo de entrada
 *
 * @return 0 em sucesso, -1 em falha
 */
int input_autoconfigure_create_from_base(const char *cfg_base,
                                         const char *new_cfg_path,
                                         int vendor_id,
                                         int product_id,
                                         const char *input_device);

#ifdef __cplusplus
}
#endif

#endif /* INPUT_AUTOCONFIGURE_H */
