/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <setjmp.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
struct rar_budget {
    size_t used, peak, limit;
    int failed;
    void *head;
    jmp_buf escape;
};
extern _Thread_local struct rar_budget *rar_active_budget;
void *rar_malloc(size_t);
void *rar_calloc(size_t, size_t);
void *rar_realloc(void *, size_t);
void rar_free(void *);
char *rar_strdup(const char *);
void rar_free_all(struct rar_budget *);
#ifdef VEYTERM_ARCHIVE_BUDGET
#define malloc rar_malloc
#define calloc rar_calloc
#define realloc rar_realloc
#define free rar_free
#define strdup rar_strdup
#endif
