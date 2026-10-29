/*
 * thread_store_* implementation for the Android 15 graphics bridge.
 *
 * Same behaviour as the libcutils version the pinned goldfish sources were
 * written against: the pthread key is created on first set, and the store's
 * mutex makes the lazy creation race-free.
 */
#include <cutils/threads.h>

void* thread_store_get(thread_store_t* store) {
    if (!store || !store->has_tls) return NULL;
    return pthread_getspecific(store->tls);
}

void thread_store_set(thread_store_t* store, void* value,
                      thread_store_destruct_t destroy) {
    if (!store) return;
    pthread_mutex_lock(&store->lock);
    if (!store->has_tls) {
        if (pthread_key_create(&store->tls, destroy) != 0) {
            pthread_mutex_unlock(&store->lock);
            return;
        }
        store->has_tls = 1;
    }
    pthread_mutex_unlock(&store->lock);
    pthread_setspecific(store->tls, value);
}
