// SPDX-License-Identifier: BSD-2-Clause
// Copyright (c) 2026 VeyTerm contributors. See LICENSE.guard.
// This file belongs only to the independently licensed command, never JNI.
#include <sys/resource.h>
#include <unistd.h>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include "android_compat.hpp"
#ifdef __ANDROID__
#include <termios.h>
#include <cstdio>
extern "C" char *getpass(const char *prompt) {
    static char password[2048];
    struct termios previous = {};
    const bool terminal = tcgetattr(STDIN_FILENO, &previous) == 0;
    struct termios hidden = previous;
    hidden.c_lflag &= static_cast<tcflag_t>(~ECHO);
    if (terminal && tcsetattr(STDIN_FILENO, TCSAFLUSH, &hidden) != 0) _exit(8);
    if (terminal && prompt != nullptr) std::fputs(prompt, stderr);
    std::memset(password, 0, sizeof(password));
    char *result = std::fgets(password, sizeof(password), stdin);
    if ((terminal && tcsetattr(STDIN_FILENO, TCSAFLUSH, &previous) != 0) || result == nullptr) _exit(8);
    password[std::strcspn(password, "\r\n")] = 0;
    return password;
}
#endif

int unrar_main(int argc, char **argv);
#ifdef main
#undef main
#endif

static bool limit(int resource, rlim_t value) {
    struct rlimit existing;
    if (getrlimit(resource, &existing) != 0) return false;
    if (existing.rlim_max != RLIM_INFINITY && value > existing.rlim_max)
        value = existing.rlim_max;
    struct rlimit bound = {value, value};
    return setrlimit(resource, &bound) == 0;
}

int main(int argc, char **argv) {
    // The ordinary public -mdx switch retains its documented dictionary-limit
    // meaning. Also bound total child allocations to 2x this value, capped at
    // 256 MiB. No new IPC, environment protocol, or private output format.
    uint64_t dictionary = 32ULL * 1024 * 1024;
    for (int i = 1; i < argc && std::strcmp(argv[i], "--") != 0; ++i) {
        if (std::strncmp(argv[i], "-mdx", 4) != 0) continue;
        char *end = nullptr;
        unsigned long long value = std::strtoull(argv[i] + 4, &end, 10);
        if (end == argv[i] + 4 || end[0] == 0 || end[1] != 0) return 7;
        uint64_t unit = *end == 'k' ? 1024 : *end == 'm' ? 1048576 : 0;
        if (unit == 0 || value == 0 || value > 128 * 1048576ULL / unit) return 7;
        dictionary = value * unit;
    }
    const uint64_t budget = dictionary * 2;
    uint64_t addressLimit = budget + 128ULL * 1024 * 1024;
#ifdef __ANDROID__
    // Bionic/Scudo reserves large PROT_NONE regions before main. Count those
    // reservations as baseline; RLIMIT_DATA still bounds writable allocations.
    FILE *statm = std::fopen("/proc/self/statm", "r");
    unsigned long long pages = 0;
    if (statm == nullptr) return 8;
    const bool measured = std::fscanf(statm, "%llu", &pages) == 1;
    std::fclose(statm);
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (!measured || pageSize <= 0 || pages > (UINT64_MAX - addressLimit) / static_cast<uint64_t>(pageSize)) return 8;
    addressLimit += pages * static_cast<uint64_t>(pageSize);
    if (addressLimit >= static_cast<uint64_t>(RLIM_INFINITY)) return 8;
#endif
    if (budget > 256ULL * 1024 * 1024 ||
        !limit(RLIMIT_DATA, static_cast<rlim_t>(budget)) ||
        !limit(RLIMIT_AS, static_cast<rlim_t>(addressLimit)) ||
        !limit(RLIMIT_CPU, 120) || !limit(RLIMIT_NOFILE, 32) ||
        !limit(RLIMIT_CORE, 0) || !limit(RLIMIT_FSIZE, 0)) return 8;
    return unrar_main(argc, argv);
}
