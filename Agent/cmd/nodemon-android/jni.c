#include <jni.h>
#include "_cgo_export.h"

JNIEXPORT jstring JNICALL Java_cn_nodemon_app_TailnetBridge_requestNative(JNIEnv *env, jclass cls, jstring action, jstring directory, jstring address, jstring token) {
    (void)cls;
    const char *a = (*env)->GetStringUTFChars(env, action, NULL);
    const char *d = (*env)->GetStringUTFChars(env, directory, NULL);
    const char *h = (*env)->GetStringUTFChars(env, address, NULL);
    const char *t = (*env)->GetStringUTFChars(env, token, NULL);
    if (!a || !d || !h || !t) { if (a) (*env)->ReleaseStringUTFChars(env,action,a); if (d) (*env)->ReleaseStringUTFChars(env,directory,d); if (h) (*env)->ReleaseStringUTFChars(env,address,h); if (t) (*env)->ReleaseStringUTFChars(env,token,t); return NULL; }
    char *result = nmRequest((char*)a, (char*)d, (char*)h, (char*)t);
    jstring output = result ? (*env)->NewStringUTF(env, result) : NULL;
    if (result) nmFree(result);
    (*env)->ReleaseStringUTFChars(env,action,a); (*env)->ReleaseStringUTFChars(env,directory,d); (*env)->ReleaseStringUTFChars(env,address,h); (*env)->ReleaseStringUTFChars(env,token,t);
    return output;
}
