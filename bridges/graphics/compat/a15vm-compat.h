/*
 * Compatibility shims for building the pinned goldfish graphics sources against
 * the Android 15 (API 35) headers. Force-included by build-graphics-a15.sh.
 */
#ifndef A15VM_GFX_COMPAT_H
#define A15VM_GFX_COMPAT_H

#include <unistd.h>

/*
 * Android 15's bionic no longer exposes PAGE_SIZE (it was an implementation
 * detail that only survived in the old sys/user.h). The pinned goldfish gralloc
 * rounds one ashmem allocation with it, so map it to the page size of the
 * running kernel instead of hard-coding 4096 - the same guest may run on a
 * 16 KiB page kernel.
 *
 * Evidence: bridges/graphics/derived/gralloc/gralloc.cpp:708 is the only use.
 */
#ifndef PAGE_SIZE
#define PAGE_SIZE (getpagesize())
#endif

#endif /* A15VM_GFX_COMPAT_H */
