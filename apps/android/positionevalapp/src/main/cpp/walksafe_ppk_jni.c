#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "rtklib.h"
#include "walksafe_ppk_engine.h"

#define WALKSAFE_RTKLIB_COMMIT "62d4677ed8425a4e2748c6d390b500d1afb493fc"
#define WALKSAFE_MAX_INPUT_FILES 443

enum {
    WALKSAFE_PPK_OK = 0,
    WALKSAFE_PPK_INVALID_ARGUMENT = 1,
    WALKSAFE_PPK_NATIVE_ALLOCATION_FAILED = 2,
    WALKSAFE_PPK_PROCESSING_FAILED = 3,
    WALKSAFE_PPK_CANCELLED_RESULT = 4,
};

static walksafe_cancel_token_t *token_from_handle(jlong handle) {
    return (walksafe_cancel_token_t *) (intptr_t) handle;
}

JNIEXPORT jstring JNICALL
Java_kr_co_hanium_dreamup_walksafe_positioneval_core_Demo5NativeBridge_nativeVersion(
        JNIEnv *env,
        jobject instance) {
    (void) instance;
    return (*env)->NewStringUTF(
        env,
        "RTKLIB-EX/" PATCH_LEVEL "@" WALKSAFE_RTKLIB_COMMIT
    );
}

JNIEXPORT jintArray JNICALL
Java_kr_co_hanium_dreamup_walksafe_positioneval_core_Demo5NativeBridge_nativeObservationSummary(
        JNIEnv *env,
        jobject instance,
        jstring input_path) {
    (void) instance;
    if (input_path == NULL) return NULL;
    const char *input = (*env)->GetStringUTFChars(env, input_path, NULL);
    if (input == NULL) return NULL;
    int summary[4] = {0};
    const int result = input[0] == '\0' || strlen(input) >= MAXSTRPATH
        ? -1
        : walksafe_rinex_observation_summary(input, summary);
    (*env)->ReleaseStringUTFChars(env, input_path, input);
    if (result != 0) return NULL;

    jintArray output = (*env)->NewIntArray(env, 4);
    if (output != NULL) {
        (*env)->SetIntArrayRegion(env, output, 0, 4, summary);
    }
    return output;
}

JNIEXPORT jlong JNICALL
Java_kr_co_hanium_dreamup_walksafe_positioneval_core_Demo5NativeBridge_nativeCreateRunToken(
        JNIEnv *env,
        jobject instance,
        jlong generation) {
    (void) env;
    (void) instance;
    walksafe_cancel_token_t *token = generation > 0
        ? walksafe_cancel_token_create((uint64_t) generation)
        : NULL;
    return (jlong) (intptr_t) token;
}

JNIEXPORT jboolean JNICALL
Java_kr_co_hanium_dreamup_walksafe_positioneval_core_Demo5NativeBridge_nativeCancelRunToken(
        JNIEnv *env,
        jobject instance,
        jlong handle,
        jlong generation) {
    (void) env;
    (void) instance;
    return walksafe_cancel_token_cancel(
        token_from_handle(handle),
        (uint64_t) generation
    ) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_kr_co_hanium_dreamup_walksafe_positioneval_core_Demo5NativeBridge_nativeDestroyRunToken(
        JNIEnv *env,
        jobject instance,
        jlong handle,
        jlong generation) {
    (void) env;
    (void) instance;
    return walksafe_cancel_token_destroy(
        token_from_handle(handle),
        (uint64_t) generation
    ) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_kr_co_hanium_dreamup_walksafe_positioneval_core_Demo5NativeBridge_nativeRun(
        JNIEnv *env,
        jobject instance,
        jobjectArray input_paths,
        jint base_observation_count,
        jstring output_path,
        jlong token_handle,
        jlong generation) {
    (void) instance;
    walksafe_cancel_token_t *cancel_token = token_from_handle(token_handle);
    if (input_paths == NULL || output_path == NULL || generation <= 0 ||
        !walksafe_cancel_token_matches(cancel_token, (uint64_t) generation)) {
        return WALKSAFE_PPK_INVALID_ARGUMENT;
    }

    const jsize input_count = (*env)->GetArrayLength(env, input_paths);
    if (input_count < 3 || input_count > WALKSAFE_MAX_INPUT_FILES ||
        base_observation_count < 1 || base_observation_count > input_count - 2) {
        return WALKSAFE_PPK_INVALID_ARGUMENT;
    }

    const char **inputs = calloc((size_t) input_count, sizeof(char *));
    jstring *java_inputs = calloc((size_t) input_count, sizeof(jstring));
    if (inputs == NULL || java_inputs == NULL) {
        free(inputs);
        free(java_inputs);
        return WALKSAFE_PPK_NATIVE_ALLOCATION_FAILED;
    }

    jint result = WALKSAFE_PPK_INVALID_ARGUMENT;
    const char *output = NULL;
    jsize acquired = 0;
    for (jsize index = 0; index < input_count; index++) {
        java_inputs[index] = (jstring) (*env)->GetObjectArrayElement(env, input_paths, index);
        if (java_inputs[index] == NULL) goto cleanup;
        inputs[index] = (*env)->GetStringUTFChars(env, java_inputs[index], NULL);
        if (inputs[index] == NULL) goto cleanup;
        acquired++;
        if (inputs[index][0] == '\0' || strlen(inputs[index]) >= MAXSTRPATH) goto cleanup;
    }

    output = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (output == NULL) goto cleanup;
    if (output[0] == '\0' || strlen(output) >= MAXSTRPATH) goto cleanup;

    const int processing_result = walksafe_ppk_run(
        inputs,
        (int) input_count,
        (int) base_observation_count,
        output,
        cancel_token
    );
    result = processing_result == 0
        ? WALKSAFE_PPK_OK
        : processing_result == WALKSAFE_PPK_CANCELLED
            ? WALKSAFE_PPK_CANCELLED_RESULT
            : WALKSAFE_PPK_PROCESSING_FAILED;

cleanup:
    if (output != NULL) {
        (*env)->ReleaseStringUTFChars(env, output_path, output);
    }
    for (jsize index = 0; index < acquired; index++) {
        (*env)->ReleaseStringUTFChars(env, java_inputs[index], inputs[index]);
    }
    for (jsize index = 0; index < input_count; index++) {
        if (java_inputs[index] != NULL) {
            (*env)->DeleteLocalRef(env, java_inputs[index]);
        }
    }
    free(inputs);
    free(java_inputs);
    return result;
}
