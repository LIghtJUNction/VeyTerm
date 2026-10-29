/* Minimal read-only configuration shared by Linux and Android API >= 21.
 * No external filters, iconv, crypto, filesystem writers, subprocesses or threads.
 * zlib supplies only CRC32; decoder allocations use rar_alloc.h below.
 */
#pragma once
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_UNISTD_H 1
#define HAVE_FCNTL_H 1
#define HAVE_ERRNO_H 1
#define HAVE_LIMITS_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDARG_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_WCHAR_H 1
#define HAVE_WCTYPE_H 1
#define HAVE_LOCALE_H 1
#define HAVE_LANGINFO_H 1
#ifndef __ANDROID__
#define HAVE_NL_LANGINFO 1
#endif
#define HAVE_ZLIB_H 1
#define HAVE_LIBZ 1
#define HAVE_STRERROR 1
#define HAVE_STRCHR 1
#define HAVE_STRDUP 1
#define HAVE_MEMMOVE 1
#define HAVE_WCSLEN 1
#define HAVE_WMEMCMP 1
#define HAVE_WMEMCPY 1
#define HAVE_WMEMMOVE 1
#define HAVE_MBRTOWC 1
#define HAVE_WCRTOMB 1
#define HAVE_MBSRTOWCS 1
#define HAVE_WCSRTOMBS 1
#define HAVE_LOCALTIME_R 1
#define HAVE_GMTIME_R 1
#define HAVE_TIMEGM 1
#define HAVE_TZSET 1
#define HAVE_READLINK 1
#define HAVE_LSTAT 1
#define HAVE_GETEUID 1
#define HAVE_GETEGID 1
#define HAVE_DECL_INT32_MIN 1
#define HAVE_DECL_UINTMAX_MAX 1
#define HAVE_DECL_INTMAX_MAX 1
#define HAVE_DECL_INTMAX_MIN 1
#define HAVE_DECL_SIZE_MAX 1
#define HAVE_DECL_SSIZE_MAX 1
#define HAVE_DECL_UINT32_MAX 1
#define HAVE_DECL_INT32_MAX 1
#define HAVE_DECL_INT64_MAX 1
#define HAVE_DECL_INT64_MIN 1
#define HAVE_DECL_UINT64_MAX 1
#define HAVE_STRUCT_STAT_ST_MTIM_TV_NSEC 1
#define HAVE_STRUCT_STAT_ST_CTIM_TV_NSEC 1
#define HAVE_STRUCT_STAT_ST_ATIM_TV_NSEC 1
#define SIZEOF_WCHAR_T 4
#define SIZEOF_INT 4
#define SIZEOF_LONG __SIZEOF_LONG__
#define SIZEOF_SIZE_T __SIZEOF_SIZE_T__
#define SIZEOF_VOID_P __SIZEOF_POINTER__
#define __LIBARCHIVE_CONFIG_H_INCLUDED 1
#ifdef VEYTERM_ARCHIVE_BUDGET
#include "rar_alloc.h"
#endif
