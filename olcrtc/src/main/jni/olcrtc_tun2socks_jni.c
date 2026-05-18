/*
 * olcrtc_tun2socks_jni.c — JNI bridge for hev-socks5-tunnel
 *
 * Package: com.wireguard.android.olcrtc
 * Class: OlcRtcVpnService
 *
 * Links against libhev-socks5-tunnel.so for tun2socks functionality.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <android/log.h>

#include "hev-main.h"

#define TAG "OlcRtcVpnService-JNI"

/*
 * Class:     com_wireguard_android_olcrtc_OlcRtcVpnService
 * Method:    startTun2socksNative
 * Signature: (Ljava/lang/String;I)I
 */
JNIEXPORT jint JNICALL
Java_com_wireguard_android_olcrtc_OlcRtcVpnService_startTun2socksNative(
    JNIEnv *env, jobject thiz, jstring config_path, jint tun_fd)
{
    const char *path;
    int result;

    if (config_path == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "config_path is null");
        return -1;
    }

    path = (*env)->GetStringUTFChars(env, config_path, NULL);
    if (path == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "Failed to get config path string");
        return -1;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Starting tun2socks: config=%s, fd=%d", path, (int)tun_fd);

    result = hev_socks5_tunnel_main_from_file(path, (int)tun_fd);

    (*env)->ReleaseStringUTFChars(env, config_path, path);

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "tun2socks exited: result=%d", result);

    return (jint)result;
}

/*
 * Class:     com_wireguard_android_olcrtc_OlcRtcVpnService
 * Method:    stopTun2socksNative
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_com_wireguard_android_olcrtc_OlcRtcVpnService_stopTun2socksNative(
    JNIEnv *env, jobject thiz)
{
    __android_log_print(ANDROID_LOG_INFO, TAG, "Stopping tun2socks");
    hev_socks5_tunnel_quit();
}

/*
 * Class:     com_wireguard_android_olcrtc_OlcRtcVpnService
 * Method:    getTun2socksStatsNative
 * Signature: ()[J
 *
 * Returns a long array: [tx_packets, tx_bytes, rx_packets, rx_bytes]
 */
JNIEXPORT jlongArray JNICALL
Java_com_wireguard_android_olcrtc_OlcRtcVpnService_getTun2socksStatsNative(
    JNIEnv *env, jobject thiz)
{
    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    jlong stats[4];
    jlongArray result;

    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);

    stats[0] = (jlong)tx_packets;
    stats[1] = (jlong)tx_bytes;
    stats[2] = (jlong)rx_packets;
    stats[3] = (jlong)rx_bytes;

    result = (*env)->NewLongArray(env, 4);
    if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, 4, stats);
    }

    return result;
}
