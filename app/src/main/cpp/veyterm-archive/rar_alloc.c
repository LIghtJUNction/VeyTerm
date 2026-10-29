/* SPDX-License-Identifier: GPL-3.0-only */
#include "rar_alloc.h"
#include <errno.h>
/* Every allocation is owned by one reader. The list permits complete disposal
 * after an allocation failure, even inside libarchive's fatal-on-OOM string APIs.
 * Escape only across C frames; no Java callback is active at allocation sites.
 */
typedef union allocation allocation;
union allocation {
    max_align_t alignment;
    struct { size_t bytes; struct rar_budget *owner; allocation *prev, *next; } info;
};
_Thread_local struct rar_budget *rar_active_budget;
static _Noreturn void exhausted(struct rar_budget *b) {
    b->failed = 1;
    longjmp(b->escape, 1);
}
void *rar_malloc(size_t bytes) {
    struct rar_budget *b = rar_active_budget;
    if (bytes > SIZE_MAX - sizeof(allocation) ||
        bytes + sizeof(allocation) > b->limit - b->used) exhausted(b);
    allocation *p = malloc(sizeof(*p) + bytes);
    if (!p) exhausted(b);
    p->info.bytes = sizeof(*p) + bytes;
    p->info.owner = b;
    p->info.prev = NULL;
    p->info.next = b->head;
    if (p->info.next) p->info.next->info.prev = p;
    b->head = p;
    b->used += p->info.bytes;
    if (b->used > b->peak) b->peak = b->used;
    return p + 1;
}
void rar_free(void *ptr) {
    if (!ptr) return;
    allocation *p = (allocation *)ptr - 1;
    struct rar_budget *b = p->info.owner;
    if (p->info.prev) p->info.prev->info.next = p->info.next;
    else b->head = p->info.next;
    if (p->info.next) p->info.next->info.prev = p->info.prev;
    b->used -= p->info.bytes;
    free(p);
}
void rar_free_all(struct rar_budget *b) {
    while (b->head) rar_free((allocation *)b->head + 1);
}
void *rar_calloc(size_t n, size_t bytes) {
    if (bytes && n > SIZE_MAX / bytes) exhausted(rar_active_budget);
    void *p = rar_malloc(n * bytes);
    memset(p, 0, n * bytes);
    return p;
}
void *rar_realloc(void *ptr, size_t bytes) {
    if (!ptr) return rar_malloc(bytes);
    if (!bytes) { rar_free(ptr); return NULL; }
    allocation *old = (allocation *)ptr - 1;
    size_t previous = old->info.bytes - sizeof(*old);
    if (bytes == previous) return ptr;
    /* Charge both old and new storage before allocating. A libc realloc may
     * temporarily retain both, which would otherwise evade a strict budget. */
    void *next = rar_malloc(bytes);
    memcpy(next, ptr, previous < bytes ? previous : bytes);
    rar_free(ptr);
    return next;
}
char *rar_strdup(const char *s) {
    size_t n = strlen(s) + 1;
    char *p = rar_malloc(n);
    memcpy(p, s, n);
    return p;
}
