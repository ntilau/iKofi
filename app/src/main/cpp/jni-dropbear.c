#include <ctype.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <errno.h>
#include <syslog.h>

#include "openssh/version.h"
#include "rsync/version.h"

#include "jni-dropbear.h"
#include "dbrandom.h"
#include "session.h"

/*
 * =============================================================================
 * Global state — set by start_sshd() JNI call before fork(), read by Dropbear
 * callbacks during authentication and session setup.
 * =============================================================================
 */

/** Shell binary path for SSH sessions (e.g. /system/bin/sh). */
const char *ikofi_shell_exe = "";
/** Home directory for SSH logins. */
const char *ikofi_home_path = "";

/** Native lib directory (app's jniLibs path). */
const char *lib_path = "";
/** Directory for iKofi config files (host keys, authorized_keys, passwords). */
const char *conf_path = "";
/** User-configured environment variables (name=value lines). */
const char *env_var_list = "";

/** Whether SSH shell access is enabled (from prefs). */
int enable_service_shell_access = JNI_TRUE;
/** Whether public-key authentication is enabled (from prefs). */
int enable_public_key_auth = JNI_TRUE;
/** Whether single-use password generation is enabled (from prefs). */
int enable_single_use_passwords = JNI_TRUE;

/*
 * =============================================================================
 * Config file helpers
 * =============================================================================
 */

/**
 * Check if a config file exists and has at least min_chars of content.
 * Used to gate authentication methods — e.g. public key auth is only offered
 * when authorized_keys exists with sufficient content.
 *
 * @param filename  relative filename (e.g. "authorized_keys")
 * @param min_chars minimum number of readable characters required
 * @return 1 if file exists and meets the threshold, 0 otherwise
 */
int config_file_exists(const char *filename, const int min_chars) {
    char *fn = ikofi_conf_file(filename);
    FILE *f = fopen(fn, "r");
    m_free(fn); /* match "m_malloc()" from ikofi_conf_file */
    if (!f) {
        return 0;
    }
    for (int i = 0; i < min_chars; i++) {
        if (fgetc(f) == EOF) {
            fclose(f);
            return 0;
        }
    }
    fclose(f);
    return 1;
}

/**
 * Build the full filesystem path for a config file inside conf_path.
 * Caller must m_free() the returned pointer.
 */
char *ikofi_conf_file(const char *fn) {
    char *ret = m_malloc(strlen(conf_path) + strlen(fn) + 1 + /* '\0' */ 1);
    sprintf(ret, "%s/%s", conf_path, fn);
    return ret;
}

/*
 * =============================================================================
 * Executable redirection
 *
 * The native binaries (scp, rsync, sftp-server) are compiled as shared
 * libraries (.so) and extracted to the app's native lib directory at install
 * time.  When Dropbear tries to exec() one of these commands, we redirect the
 * path to the corresponding .so file so it can run as a standalone executable.
 *
 * This works because Android's linker treats .so files as ELF executables
 * when invoked directly.
 * =============================================================================
 */

/**
 * Translate a command name into the path to its bundled .so executable.
 *
 * Handles: scp, rsync, sftp-server.  Other commands pass through unchanged.
 * The caller must NOT free the returned pointer if it matches the input cmd
 * (pass-through case), but MUST free it when it's a newly allocated string.
 *
 * @param cmd  the command line string from Dropbear
 * @return     pointer to the translated command path (may be newly allocated)
 */
char *ikofi_exe_to_lib(const char *cmd) {
    if (cmd && !strncmp(cmd, "scp ", 4)) {
        char *t = m_malloc(strlen(lib_path) + 11 + strlen(cmd) + /* '\0' */ 1);
        sprintf(t, "%s/libscp.so %s", lib_path, cmd + 4);
        return t;

    } else if (cmd && !strncmp(cmd, "rsync ", 6)) {
        char *t = m_malloc(strlen(lib_path) + 13 + strlen(cmd) - 6 + /* '\0' */ 1);
        sprintf(t, "%s/librsync.so %s", lib_path, cmd + 6);
        return t;

    } else if (cmd && !strncmp(cmd, "sftp-server", 11)) {
        char *t = m_malloc(strlen(lib_path) + 18 + /* '\0' */ 1);
        sprintf(t, "%s/libsftp-server.so", lib_path);
        return t;
    }

    return (char *) cmd;
}

/*
 * =============================================================================
 * Environment setup
 * =============================================================================
 */

/**
 * Parse and apply user-configured environment variables.
 *
 * The env_var_list string contains one "name=value" pair per line.  Each is
 * applied via setenv().  Also exports IKOFI_CONF_DIR so rsync can find its
 * config files.
 */
void ikofi_set_env() {
    const char *s = env_var_list;
    if (!s) {
        return;
    }
    while (*s) {
        const char *name, *val;
        long name_len, val_len;

        name = s;
        while (*s && (*s != '=') && (*s != '\r') && (*s != '\n')) {
            s++;
        }
        name_len = s - name;
        if (*s == '=') {
            s++;
            val = s;
            while (*s && (*s != '\r') && (*s != '\n')) {
                s++;
            }
            val_len = s - val;
        } else {
            val = "";
            val_len = 0;
        }
        while ((*s == '\r') || (*s == '\n')) {
            s++;
        }

        if (name_len) {
            char *n = m_malloc(name_len + 1);
            char *v = m_malloc(val_len + 1);
            memcpy(n, name, name_len);
            memcpy(v, val, val_len);
            n[name_len] = 0;
            v[val_len] = 0;
            setenv(n, v, /*overwrite=*/1);    /* might fail *shrug* */
            m_free(n);
            m_free(v);
        }
    }

    /* make available to rsync */
    setenv(IKOFI_CONF_DIR, conf_path, /*overwrite=*/ 1);
}

/*
 * =============================================================================
 * Authentication hook — called by Dropbear during login
 *
 * These functions are declared in jni-dropbear.h and called from patched
 * Dropbear source files (svr-auth.c, svr-authpasswd.c, svr-authpubkey.c,
 * dbutil.c, common-session.c, svr-chansession.c) via the
 * IKOFI_EXTEND_AUTHENTICATION compile-time flag.
 *
 * Three authentication methods are supported:
 *   1. Public key — must have authorized_keys file with >= 10 bytes
 *   2. Fixed user/password — read from "master_password" file (user:hash)
 *   3. Single-use passwords — auto-generated on each login attempt
 * =============================================================================
 */

int ikofi_enable_service_shell_access() {
    return enable_service_shell_access;
}

int ikofi_enable_public_key_auth() {
    return enable_public_key_auth
    /* MIN_AUTHKEYS_LINE (10 bytes) as defined in "dropbear/svr-authpubkey.c */
    && config_file_exists(AUTHORIZED_KEYS_FILE, 10);
}

int ikofi_enable_single_use_passwords() {
    return enable_single_use_passwords;
}

/**
 * Generate and log an 8-character single-use password.
 *
 * Uses a 64-char alphabet that excludes visually ambiguous characters
 * (I, l, 1, O, 0).  The generated password is written to the dropbear log
 * so the user can read it from the app's on-screen log viewer.
 *
 * @param gen_pass  output: the generated password (caller must m_free)
 */
void ikofi_generate_single_use_password(char **gen_pass) {
    /* Don't use Il1O0 because they're visually ambiguous */
    static const char tab64[64] =
            "abcdefghijk!mnopqrstuvwxyzABCDEFGH@JKLMN#PQRSTUVWXYZ$%23456789^&";
    char pw[9];
    int i;
    genrandom((unsigned char *) pw, 8);
    for (i = 0; i < 8; i++) {
        pw[i] = tab64[pw[i] & 63];
    }
    pw[8] = 0;
    dropbear_log(LOG_WARNING, "Single-use password:");
    dropbear_log(LOG_ALERT, "--------");
    dropbear_log(LOG_ALERT, "%s", pw);
    dropbear_log(LOG_ALERT, "--------");

    *gen_pass = m_strdup(pw);
}

int ikofi_enable_password_file() {
    /* 3: u:p
     * In reality the Java UI takes care of minimum length.
     * BUT.... if the user opens an ssh shell,
     * they can of course manually edit/replace the password file.
     * TODO: Maybe we should sign the base64 password with an internal key.
     *  but considering that when you CAN login... you CAN read the app's files...
     *  not that much point.
     *  Unless such a private key to sign is kept of-device, this is merely
     *  obfuscation and not encryption of course.
     */
    return config_file_exists(AUTHORIZED_USERS_FILE, 3);
}

/**
 * Read the first "username:password_hash" line from the master_password file.
 *
 * The file format is: username:SHA-512-base64(password)
 * Only the first line is read; subsequent lines are ignored.
 *
 * @param user      output: allocated username string (caller must m_free)
 * @param password  output: allocated password hash string (caller must m_free)
 * @return 1 on success, 0 if file is missing or empty
 */
int ikofi_get_user_password(char **user, char **password) {
    char *fn = ikofi_conf_file(AUTHORIZED_USERS_FILE);
    FILE *f = fopen(fn, "r");
    m_free(fn); /* match m_malloc from ikofi_conf_file */
    if (!f) {
        return 0;
    }

    int ret_value = 0;

    char *line = NULL;
    size_t len = 0;
    ssize_t read;

    read = getline(&line, &len, f);
    if (read > 0 && strstr(line, ":") != NULL) {
        char *p;
        char *saveptr;

        p = strtok_r(line, ":", &saveptr);
        if (p != NULL) {
            *user = strdup(p);
        }

        p = strtok_r(NULL, ":", &saveptr);
        if (p != NULL) {
            *password = strdup(p);
        }

        ret_value = 1;
    }
    /* match realloc() from getline(..) */
    free(line);
    fclose(f);
    return ret_value;
}

/**
 * Dropbear password authentication callback.
 *
 * Two modes:
 *   A) Fixed user/password from master_password file:
 *      The stored SHA-512 hash is set as the expected password (passwdcrypt).
 *      The incoming password is SHA-512'd and placed in testcrypt.
 *      Dropbear compares the two to authenticate.
 *
 *   B) Single-use password fallback:
 *      The current single-use password (already set in ses.authstate.pw_passwd
 *      by ikofi_svr_authinitialise) is used as-is, and the incoming password is
 *      passed through directly.
 *
 * @param password      the password sent by the SSH client
 * @param passwordlen   length of the password
 * @param passwdcrypt   [out] expected password/hash for comparison
 * @param testcrypt     [out] transformed version of the incoming password
 *                      (caller must m_free this when non-NULL)
 */
void ikofi_svr_auth_password(const char *password, unsigned int passwordlen,
                              char **passwdcrypt, char **testcrypt) {

    char *ikofi_username = NULL;
    char *ikofi_password = NULL;
    int has_password_file = ikofi_get_user_password(&ikofi_username, &ikofi_password);

    /* If we have an AUTHORIZED_USERS_FILE user/pass */
    if (has_password_file && *ikofi_username && *ikofi_password
        /* and the user name matches */
        && strcmp(ikofi_username, ses.authstate.username) == 0) {
        /* then we will expect to receive that password. */
        ses.authstate.pw_passwd = m_strdup(ikofi_password);
        *passwdcrypt = ses.authstate.pw_passwd;

        if (passwordlen == strlen(password)) {
            unsigned long hashSize = sha512_desc.hashsize;
            unsigned char *hashResult = m_malloc(hashSize);
            hash_state md;

            sha512_init(&md);
            sha512_process(&md, (const unsigned char *) password, passwordlen);
            sha512_done(&md, hashResult);

            /* 128 is to large for base64, but suits hex should we need it. */
            unsigned long base64_len = 2 * hashSize;
            *testcrypt = m_malloc(base64_len);
            base64_encode(hashResult, hashSize,
                          (unsigned char *) *testcrypt, &base64_len);

            m_free(hashResult);
        } else {
            *testcrypt = NULL;
        }
    } else {
        /* Not the password from the authorized_users file, we'll test for a single use password */
        *passwdcrypt = ses.authstate.pw_passwd;

        if (passwordlen == strlen(password)) {
            *testcrypt = m_malloc(passwordlen + 1);
            strcpy(*testcrypt, password);
        } else {
            *testcrypt = NULL;
        }
    }

    /* match strdup malloc's from ikofi_get_user_password */
    if (ikofi_username) {
        m_free(ikofi_username);
    }
    if (ikofi_password) {
        m_free(ikofi_password);
    }
}

/**
 * Configure authentication types on the Dropbear session object.
 *
 * Called once during session initialisation.  Depending on the JNI flags
 * and config file presence, enables/disables:
 *   - AUTH_TYPE_PUBKEY  (when authorized_keys exists with content)
 *   - AUTH_TYPE_PASSWORD (when master_password exists OR single-use is on)
 *
 * Single-use passwords are also generated here so the user sees them in the
 * log before attempting a login.
 */
void ikofi_svr_authinitialise() {
    /* explicitly set/unset, one less place to add #ifdef */
    if (ikofi_enable_public_key_auth()) {
        ses.authstate.authtypes |= AUTH_TYPE_PUBKEY;
    } else {
        ses.authstate.authtypes &= ~AUTH_TYPE_PUBKEY;
    }

    if (ikofi_enable_password_file()) {
        ses.authstate.authtypes |= AUTH_TYPE_PASSWORD;
    }
    /* Check and generate at this time, as the user MUST be able to see the message
     * in the logfile before they start a login attempt.
     */
    if (ikofi_enable_single_use_passwords()) {
        char *gen_pass = NULL;
        ikofi_generate_single_use_password(&gen_pass);
        ses.authstate.authtypes |= AUTH_TYPE_PASSWORD;
        ses.authstate.pw_passwd = m_strdup(gen_pass);
    }

}
/*
 * This makes sure that no previously-added atexit gets called (some users have
 * an atexit registered by libGLESv2_adreno.so)
 */
static void null_atexit(void) {
    _Exit(0);
}

const char *from_java_string(JNIEnv *env, jstring str) {
    if (!str) {
        return "";
    }
    const char *tmp = (*env)->GetStringUTFChars(env, str, NULL);
    if (!tmp) {
        return "";
    }
    const char *value = strdup(tmp);
    (*env)->ReleaseStringUTFChars(env, str, tmp);
    return value;
}

/**
 * Main entry point; start dropbear in-process.
 *
 * @param env
 * @param cl
 * @param j_lib_path                 native library directory
 * @param j_dropbear_args            arguments to pass to the dropbear main
 * @param j_conf_path                directory for dropbear configuration files
 * @param j_home_path                home directory for an ssh login
 * @param j_shell_exe                shell executable
 * @param j_env_var_list             list of environment variables
 * @param j_enableServiceShellAccess whether a shell can be opened
 * @param j_enablePublickeyAuth      enable public key logins
 * @param j_enableSingleUsePasswords enable generating single-use passwords
 *
 * @return On success, the PID of the dropbear process.  On failure, -1.
 */
JNIEXPORT jint JNICALL
Java_com_hardbacknutter_sshd_SshdService_start_1sshd(
        JNIEnv *env,
        jobject thiz,
        jstring j_lib_path,
        jobjectArray j_dropbear_args,
        jstring j_conf_path,
        jstring j_home_path,
        jstring j_shell_exe,
        jstring j_env_var_list,
        jboolean j_enableServiceShellAccess,
        jboolean j_enablePublickeyAuth,
        jboolean j_enableSingleUsePasswords) {

    pid_t pid = fork();
    if (pid == 0) {
        /* child */
        atexit(null_atexit);

        lib_path = from_java_string(env, j_lib_path);
        conf_path = from_java_string(env, j_conf_path);
        ikofi_home_path = from_java_string(env, j_home_path);

        ikofi_shell_exe = from_java_string(env, j_shell_exe);
        env_var_list = from_java_string(env, j_env_var_list);

        enable_service_shell_access = j_enableServiceShellAccess;
        enable_public_key_auth = j_enablePublickeyAuth;
        enable_single_use_passwords = j_enableSingleUsePasswords;

        const jsize argc = (*env)->GetArrayLength(env, j_dropbear_args);
        const char *argv[argc];
        for (jint i = 0; i < argc; i++) {
            const jstring j_value = (jstring)
                    ((*env)->GetObjectArrayElement(env, j_dropbear_args, i));
            argv[i] = from_java_string(env, j_value);
        }

        const char *log_fn = ikofi_conf_file("dropbear.err");
        const char *log_fn_old = ikofi_conf_file("dropbear.err.old");
        unlink(log_fn_old);
        rename(log_fn, log_fn_old);
        unlink(log_fn);

        const int log_fd = open(log_fn, O_CREAT | O_WRONLY, 0666);
        if (log_fd != -1) {
            /* replace stderr with our logfile */
            dup2(log_fd, 2);
        }
        for (int i = 3; i < 255; i++) {
            /* make sure only stdin/stdout/stderr are left open. */
            close(i);
        }

        // Force the dropbear.err file into existence...
        // The monitoring java thread assumes the file always exists!
        fprintf(stderr, "Starting dropbear\n");

        dropbear_main(argc, (char **) argv, NULL);
        /* not reachable */
        exit(0);
    }

    /* parent */
    if (pid == -1) {
        fprintf(stderr, "Failed to start dropbear errno=%u\n", errno);
    }

    return pid;
}

JNIEXPORT void JNICALL
Java_com_hardbacknutter_sshd_SshdService_kill(
        JNIEnv *env,
        jobject thiz,
        jint pid) {
    kill(pid, SIGKILL);
}

JNIEXPORT int JNICALL
Java_com_hardbacknutter_sshd_SshdService_waitpid(
        JNIEnv *env,
        jobject thiz,
        jint pid) {
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    return 0;
}

/* static */
JNIEXPORT jstring JNICALL
Java_com_hardbacknutter_sshd_SshdService_getDropbearVersion(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, DROPBEAR_VERSION);
}

/* static */
JNIEXPORT jstring JNICALL
Java_com_hardbacknutter_sshd_SshdService_getOpensshVersion(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, SSH_RELEASE);
}

/* static */
JNIEXPORT jstring JNICALL
Java_com_hardbacknutter_sshd_SshdService_getRsyncVersion(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, RSYNC_VERSION);
}