#include "input_autoconfigure.h"
#include <stdio.h>
#include <string.h>

int input_autoconfigure_create_from_base(const char *cfg_base,
                                         const char *new_cfg_path,
                                         int vendor_id,
                                         int product_id,
                                         const char *input_device)
{
    FILE *src = fopen(cfg_base, "r");
    if (!src) return -1;

    FILE *dst = fopen(new_cfg_path, "w");
    if (!dst) { fclose(src); return -1; }

    char line[512];
    while (fgets(line, sizeof(line), src)) {
        // Substituições básicas de placeholders (opcional)
        char out_line[512];
        strncpy(out_line, line, sizeof(out_line));
        out_line[sizeof(out_line)-1] = '\0';

        // Exemplo: substituir %VENDOR% por vendor_id
        char vendor_str[16], product_str[16];
        snprintf(vendor_str, sizeof(vendor_str), "%d", vendor_id);
        snprintf(product_str, sizeof(product_str), "%d", product_id);

        char *pos;
        while ((pos = strstr(out_line, "%VENDOR%"))) {
            memmove(pos + strlen(vendor_str), pos + 8, strlen(pos + 8) + 1);
            memcpy(pos, vendor_str, strlen(vendor_str));
        }
        while ((pos = strstr(out_line, "%PRODUCT%"))) {
            memmove(pos + strlen(product_str), pos + 9, strlen(pos + 9) + 1);
            memcpy(pos, product_str, strlen(product_str));
        }
        while ((pos = strstr(out_line, "%DEVICE%"))) {
            memmove(pos + strlen(input_device), pos + 8, strlen(pos + 8) + 1);
            memcpy(pos, input_device, strlen(input_device));
        }

        fputs(out_line, dst);
    }

    fclose(src);
    fclose(dst);
    return 0;
}
