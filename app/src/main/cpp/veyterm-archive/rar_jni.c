/* SPDX-License-Identifier: GPL-3.0-only */
#include <jni.h>
#include <archive.h>
#include <archive_entry.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "rar_alloc.h"

#define JNI_METHOD(name) Java_com_termux_app_files_archive_NativeRar_##name
struct reader {
    struct rar_budget budget;
    struct archive *archive;
    int fd;
    JNIEnv *env;
    jobject self; /* Local reference, valid only during the current JNI call. */
    jmethodID check;
    unsigned char input[32768];
};
static void fail(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, message);
}
static void archive_failure(struct reader *r, const char *operation) {
    char message[768];
    const char *error = r->budget.failed ? NULL : archive_error_string(r->archive);
    snprintf(message, sizeof(message), "RAR %s: %s", operation,
        r->budget.failed ? "configured native memory limit exceeded" :
        (error ? error : "invalid or unsupported archive"));
    fail(r->env, message);
}
static void enter(struct reader *r, JNIEnv *env, jobject self) {
    r->env = env; r->self = self; rar_active_budget = &r->budget;
}
static void leave(struct reader *r) {
    rar_active_budget = NULL; r->env = NULL; r->self = NULL;
}
static int cancelled(struct reader *r) {
    if ((*r->env)->ExceptionCheck(r->env)) return 1;
    (*r->env)->CallVoidMethod(r->env, r->self, r->check);
    return (*r->env)->ExceptionCheck(r->env);
}
static la_ssize_t input_read(struct archive *a, void *client, const void **buffer) {
    struct reader *r = client;
    if (cancelled(r)) return -1;
    ssize_t n;
    do { n = read(r->fd, r->input, sizeof(r->input)); } while (n < 0 && errno == EINTR && !cancelled(r));
    if (n < 0) archive_set_error(a, errno, "RAR input read failed");
    *buffer = r->input;
    return n;
}
static struct reader *get_reader(JNIEnv *env, jlong handle) {
    struct reader *r = (struct reader *)(uintptr_t)handle;
    if (!r) fail(env, "RAR reader is closed");
    return r;
}
JNIEXPORT jlong JNICALL JNI_METHOD(open)(JNIEnv *env, jobject self, jbyteArray path, jlong limit) {
    if (limit < (jlong)sizeof(struct reader) || (uint64_t)limit > SIZE_MAX) {
        fail(env, "RAR native memory limit is invalid for this ABI"); return 0;
    }
    jsize length = (*env)->GetArrayLength(env, path);
    if (!length || length > 1024 * 1024) { fail(env, "Invalid RAR source path"); return 0; }
    char *name = malloc((size_t)length + 1);
    if (!name) { fail(env, "RAR path allocation failed"); return 0; }
    (*env)->GetByteArrayRegion(env, path, 0, length, (jbyte *)name);
    if ((*env)->ExceptionCheck(env)) { free(name); return 0; }
    name[length] = 0;
    if (memchr(name, 0, length)) { free(name); fail(env, "NUL in RAR source path"); return 0; }
    int fd = open(name, O_RDONLY | O_CLOEXEC);
    free(name);
    if (fd < 0) { fail(env, "Cannot open RAR source file"); return 0; }
    struct reader *r = calloc(1, sizeof(*r));
    if (!r) { close(fd); fail(env, "RAR reader allocation failed"); return 0; }
    r->fd = fd; r->budget.limit = (size_t)limit; r->budget.used = sizeof(*r);
    r->budget.peak = sizeof(*r);
    jclass type = (*env)->GetObjectClass(env, self);
    r->check = (*env)->GetMethodID(env, type, "check", "()V");
    (*env)->DeleteLocalRef(env, type);
    enter(r, env, self);
    if (setjmp(r->budget.escape)) {
        archive_failure(r, "open"); rar_free_all(&r->budget);
        leave(r); close(fd); free(r); return 0;
    }
    int ok = r->check != NULL && !cancelled(r);
    if (ok) r->archive = archive_read_new();
    if (ok && r->archive) {
        ok = archive_read_support_filter_none(r->archive) == ARCHIVE_OK &&
             archive_read_support_format_rar(r->archive) == ARCHIVE_OK &&
             archive_read_support_format_rar5(r->archive) == ARCHIVE_OK &&
             archive_read_open(r->archive, r, NULL, input_read, NULL) == ARCHIVE_OK;
    } else ok = 0;
    if (!ok || r->budget.failed) {
        if (r->archive) { archive_failure(r, "open"); archive_read_free(r->archive); }
        else fail(env, "RAR reader initialization/memory failure");
        leave(r); close(fd); free(r); return 0;
    }
    leave(r);
    return (jlong)(uintptr_t)r;
}
JNIEXPORT jobject JNICALL JNI_METHOD(next)(JNIEnv *env, jobject self, jlong handle) {
    struct reader *r = get_reader(env, handle);
    if (!r) return NULL;
    enter(r, env, self);
    if (r->budget.failed || setjmp(r->budget.escape)) {
        archive_failure(r, "header"); leave(r); return NULL;
    }
    struct archive_entry *entry = NULL;
    int status = archive_read_next_header(r->archive, &entry);
    jobject result = NULL;
    if (status != ARCHIVE_OK || r->budget.failed) {
        if (status != ARCHIVE_EOF || r->budget.failed) archive_failure(r, "header");
        goto done;
    }
    int type = archive_entry_filetype(entry);
    int flags = (type == AE_IFDIR ? 1 : 0) |
        (archive_entry_symlink(entry) || archive_entry_hardlink(entry) || type == AE_IFLNK ? 2 : 0) |
        (archive_entry_is_encrypted(entry) ? 4 : 0) |
        (type != 0 && type != AE_IFREG && type != AE_IFDIR && type != AE_IFLNK ? 8 : 0);
    const char *name = archive_entry_pathname_utf8(entry);
    if (!name || r->budget.failed) { archive_failure(r, "filename encoding"); goto done; }
    size_t length = strlen(name);
    if (!length || length > 1024 * 1024) { fail(env, "Invalid RAR member name length"); goto done; }
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (!bytes) goto done;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)name);
    jclass cls = (*env)->FindClass(env, "com/termux/app/files/archive/NativeRar$Member");
    if (cls && !(*env)->ExceptionCheck(env)) {
        jmethodID constructor = (*env)->GetMethodID(env, cls, "<init>", "([BJJI)V");
        int64_t seconds = archive_entry_mtime_is_set(entry) ? archive_entry_mtime(entry) : 0;
        if (seconds > INT64_MAX / 1000 || seconds < INT64_MIN / 1000) {
            fail(env, "RAR timestamp overflow");
        } else if (constructor) {
            result = (*env)->NewObject(env, cls, constructor, bytes,
                (jlong)(archive_entry_size_is_set(entry) ? archive_entry_size(entry) : -1),
                (jlong)(seconds * 1000), (jint)flags);
        }
    }
    if (cls) (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, bytes);
done:
    leave(r); return result;
}
JNIEXPORT jint JNICALL JNI_METHOD(read)(JNIEnv *env, jobject self, jlong handle, jbyteArray buffer) {
    struct reader *r = get_reader(env, handle);
    if (!r) return -1;
    jsize capacity = (*env)->GetArrayLength(env, buffer);
    unsigned char block[32768];
    if (capacity < 1 || capacity > (jsize)sizeof(block)) { fail(env, "Invalid RAR read buffer"); return -1; }
    enter(r, env, self);
    if (r->budget.failed || setjmp(r->budget.escape)) {
        archive_failure(r, "data"); leave(r); return -1;
    }
    la_ssize_t n = archive_read_data(r->archive, block, (size_t)capacity);
    if (n < 0 || r->budget.failed) { archive_failure(r, "data/integrity"); n = -1; }
    else if (n > 0 && !(*env)->ExceptionCheck(env))
        (*env)->SetByteArrayRegion(env, buffer, 0, (jsize)n, (const jbyte *)block);
    leave(r);
    return n > 0 ? (jint)n : -1;
}
JNIEXPORT void JNICALL JNI_METHOD(release)(JNIEnv *env, jobject self, jlong handle) {
    struct reader *r = get_reader(env, handle);
    if (!r) return;
    enter(r, env, self);
    if (!r->budget.failed && !setjmp(r->budget.escape)) {
        int status = archive_read_close(r->archive);
        if (status != ARCHIVE_OK) archive_failure(r, "close");
        archive_read_free(r->archive);
    }
    rar_free_all(&r->budget);
    leave(r);
    close(r->fd);
    if (r->budget.used != sizeof(*r)) fail(env, "RAR reader leaked native allocations");
    free(r);
}
JNIEXPORT jlong JNICALL JNI_METHOD(peak)(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    struct reader *r = get_reader(env, handle);
    return r ? (jlong)r->budget.peak : 0;
}
