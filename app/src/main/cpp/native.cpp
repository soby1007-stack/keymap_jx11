#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/input.h>

// 입력 장치를 열고 EVIOCGRAB 으로 독점한다.
// 독점 중에는 이 fd 로만 이벤트가 오고, 안드로이드 입력 시스템(원래 터치/스크롤 동작)에는 전달되지 않는다.
// fd 를 닫거나 프로세스가 죽으면 독점은 자동 해제된다.
// 반환: fd(>=0) / 열기 실패 -errno / 독점 실패 -(1000+errno)
extern "C" JNIEXPORT jint JNICALL
Java_com_soby_jx11keymapper_NativeGrab_grabOpen(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    int e = errno;
    env->ReleaseStringUTFChars(path, p);
    if (fd < 0) return -e;
    if (ioctl(fd, EVIOCGRAB, 1) < 0) {
        e = errno;
        close(fd);
        return -(1000 + e);
    }
    return fd;
}
