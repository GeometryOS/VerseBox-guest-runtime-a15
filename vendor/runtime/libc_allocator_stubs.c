/*
 * Extra definitions the API 35 libc needs at link time.
 *
 * 1. ldexp: in API 35 the guest's libc.so exports `ldexp@@LIBC` while libm.so
 *    only defines `scalbn` (FreeBSD's s_scalbn.c keeps a weak `ldexpl` alias,
 *    not `ldexp`). AOSP's ldexp is exactly a scalbn forward, so the same
 *    one-line forward belongs in libc. libm.so is already a link input of
 *    libc.so, which matches the image (libm -> libc, libc -> libm).
 *
 * 2. je_*: retained as weak no-ops for the allocator diagnostics only. With
 *    `-DUSE_SCUDO` malloc_common.h maps Malloc() to scudo_*, so a correctly
 *    rebuilt tree no longer references them; the weak bodies keep an old
 *    object from breaking the link. They are not on the ART path.
 *
 * `android_get_exported_namespace` is NOT stubbed: it comes from the image's
 * libdl_android.so, a link input in build-libc.sh, exactly as AOSP does it.
 * See guest-runtime-a15/STATUS.md.
 */
#include <math.h>
#include <malloc.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

/* API 35 libc owns ldexp; FreeBSD implements it as scalbn. */
double ldexp(double x, int exponent) {
  return scalbn(x, exponent);
}

__attribute__((weak)) struct mallinfo je_mallinfo(void) {
  struct mallinfo info;
  memset(&info, 0, sizeof(info));
  return info;
}

__attribute__((weak)) int je_malloc_info(int options, FILE* fp) {
  (void)options;
  (void)fp;
  return 0;
}

__attribute__((weak)) int je_mallopt(int command, int value) {
  (void)command;
  (void)value;
  return 0;
}

__attribute__((weak)) void* je_memalign_round_up_boundary(size_t alignment, size_t size) {
  (void)alignment;
  (void)size;
  return NULL;
}

__attribute__((weak)) int je_posix_memalign(void** memptr, size_t alignment, size_t size) {
  (void)memptr;
  (void)alignment;
  (void)size;
  return 12; /* ENOMEM */
}

__attribute__((weak)) void* je_aligned_alloc_wrapper(size_t alignment, size_t size) {
  (void)alignment;
  (void)size;
  return NULL;
}
