#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "libfieldnotes.h"

// Byte arrays preserve full UTF-8 (including emoji); JNI modified-UTF strings do
// not. Native secrets are never logged. All returned Go/C buffers are released.
static jbyteArray output(JNIEnv *env, char *bytes) {
    if (!bytes) return NULL;
    size_t size = strlen(bytes);
    jbyteArray result = (*env)->NewByteArray(env, (jsize)size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (jbyte *)bytes);
    memset(bytes, 0, size);
    FieldFree(bytes);
    return result;
}
static jbyteArray call(JNIEnv *env, jbyteArray input, char *(*function)(char *)) {
    if (!input) return NULL;
    jsize size = (*env)->GetArrayLength(env, input);
    if (size < 0 || size > 6600000) return NULL;
    char *raw = calloc((size_t)size + 1, 1);
    if (!raw) return NULL;
    (*env)->GetByteArrayRegion(env, input, 0, size, (jbyte *)raw);
    if ((*env)->ExceptionCheck(env)) { free(raw); return NULL; }
    char *value = function(raw);
    memset(raw, 0, (size_t)size);
    free(raw);
    return output(env, value);
}
JNIEXPORT jbyteArray JNICALL Java_org_fieldnotes_platform_NativeBridge_keys(JNIEnv *env, jclass type) { (void)type; return output(env, FieldKeys()); }
JNIEXPORT jbyteArray JNICALL Java_org_fieldnotes_platform_NativeBridge_configure(JNIEnv *env, jclass type, jbyteArray input) { (void)type; return call(env, input, FieldConfigure); }
JNIEXPORT jbyteArray JNICALL Java_org_fieldnotes_platform_NativeBridge_exchange(JNIEnv *env, jclass type, jbyteArray input) { (void)type; return call(env, input, FieldExchange); }
JNIEXPORT void JNICALL Java_org_fieldnotes_platform_NativeBridge_stop(JNIEnv *env, jclass type) { (void)env; (void)type; FieldStop(); }
