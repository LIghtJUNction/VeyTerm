// SPDX-License-Identifier: BSD-2-Clause
// Copyright (c) 2026 VeyTerm contributors. See LICENSE.guard.
#pragma once
#ifdef __ANDROID__
// Enable upstream's POSIX utimensat path (available at API 21), avoiding
// lutimes, which Bionic only introduced at API 26.
#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif
// Bionic has no obsolete getpass(3). Preserve the ordinary command's terminal
// mode too; Java uses the existing upstream redirected-stdin code instead.
extern "C" char *getpass(const char *prompt);
#endif
