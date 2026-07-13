#ifndef IKOFI_JNI_DROPBEAR_H
#define IKOFI_JNI_DROPBEAR_H

#define IKOFI_LIB_DIR  "IKOFI_LIB_DIR"
#define IKOFI_CONF_DIR "IKOFI_CONF_DIR"

#define AUTHORIZED_USERS_FILE "master_password"
#define AUTHORIZED_KEYS_FILE "authorized_keys"

extern const char *ikofi_shell_exe;
extern const char *ikofi_home_path;

char *ikofi_conf_file(const char *fn);
char *ikofi_exe_to_lib(const char *cmd);

void ikofi_set_env();

int ikofi_enable_service_shell_access();

void ikofi_svr_authinitialise();
void ikofi_svr_auth_password(const char *password, unsigned int passwordlen,
                              char **passwdcrypt, char **testcrypt);

#endif /* IKOFI_JNI_DROPBEAR_H */
