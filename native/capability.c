/* SPDX-License-Identifier: GPL-2.0-only */
/* Native v2 has no Query4. Report unsupported hopping, preserve other calls. */
#define _GNU_SOURCE
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <dlfcn.h>
#include <stdatomic.h>
#include <string.h>

typedef AIBinder_Class *(*define_fn)(const char *, AIBinder_Class_onCreate,
                                    AIBinder_Class_onDestroy, AIBinder_Class_onTransact);
static _Atomic(AIBinder_Class_onTransact) original_transaction;

static binder_status_t query_unsupported(AParcel *out)
{
    const int8_t capability[] = {0x08, 0x00}; /* protobuf field1=false */
    AStatus *ok = AStatus_newOk();
    if (!ok) return STATUS_NO_MEMORY;
    binder_status_t rc = AParcel_writeStatusHeader(out, ok);
    AStatus_delete(ok);
    if (rc != STATUS_OK) return rc;
    rc = AParcel_writeInt32(out, 1); /* Non-null typed object. */
    if (rc != STATUS_OK) return rc;
    int32_t start = AParcel_getDataPosition(out);
    rc = AParcel_writeInt32(out, 0); /* Sized stable parcelable. */
    if (rc != STATUS_OK) return rc;
    rc = AParcel_writeByteArray(out, capability, sizeof(capability));
    if (rc != STATUS_OK) return rc;
    int32_t end = AParcel_getDataPosition(out);
    rc = AParcel_setDataPosition(out, start);
    if (rc != STATUS_OK) return rc;
    rc = AParcel_writeInt32(out, end - start);
    if (rc != STATUS_OK) return rc;
    return AParcel_setDataPosition(out, end);
}

static binder_status_t transaction(AIBinder *binder, transaction_code_t code,
                                  const AParcel *in, AParcel *out)
{
    if (code == 4) return query_unsupported(out);
    AIBinder_Class_onTransact original = atomic_load(&original_transaction);
    return original ? original(binder, code, in, out) : STATUS_UNKNOWN_TRANSACTION;
}

AIBinder_Class *AIBinder_Class_define(const char *descriptor,
    AIBinder_Class_onCreate create, AIBinder_Class_onDestroy destroy,
    AIBinder_Class_onTransact on_transact)
{
    define_fn real = (define_fn)dlsym(RTLD_NEXT, "AIBinder_Class_define");
    if (!real) return NULL;
    if (descriptor && !strcmp(descriptor, "com.google.android.moseyservice.IMoseyService")) {
        AIBinder_Class_onTransact previous = atomic_load(&original_transaction);
        if (previous && previous != on_transact) return NULL;
        atomic_store(&original_transaction, on_transact);
        on_transact = transaction;
    }
    return real(descriptor, create, destroy, on_transact);
}
