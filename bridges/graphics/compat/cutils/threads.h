/*
 * Legacy cutils/threads.h for the Android 15 graphics bridge.
 *
 * Android 15's libcutils no longer ships cutils/threads.h (or the
 * thread_store_* symbols): the API moved out of libcutils long ago and the
 * header was removed. The pinned goldfish sources still use it
 * (system/OpenglSystemCommon/ThreadInfo.cpp), so the A15 bridge provides the
 * same tiny TLS-key API itself instead of linking an API 28 libcutils.
 *
 * The semantics match the original libcutils implementation: a lazily created
 * pthread key guarded by the store's own mutex.
 */
#ifndef A15VM_COMPAT_CUTILS_THREADS_H
#define A15VM_COMPAT_CUTILS_THREADS_H

#include <pthread.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*thread_store_destruct_t)(void* value);

typedef struct {
    pthread_mutex_t lock;
    int             has_tls;
    pthread_key_t   tls;
} thread_store_t;

#define THREAD_STORE_INITIALIZER { PTHREAD_MUTEX_INITIALIZER, 0, 0 }

extern void* thread_store_get(thread_store_t* store);
extern void  thread_store_set(thread_store_t* store, void* value,
                              thread_store_destruct_t destroy);

#ifdef __cplusplus
}
#endif

#endif /* A15VM_COMPAT_CUTILS_THREADS_H */
