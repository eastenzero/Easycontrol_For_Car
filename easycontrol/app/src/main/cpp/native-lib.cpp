#include <jni.h>
#include <oboe/Oboe.h>
#include <oboe/FifoBuffer.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <algorithm>

namespace {

class OboePlayer;

class PlayerCallback : public oboe::AudioStreamCallback {
public:
    explicit PlayerCallback(OboePlayer *player) : mPlayer(player) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;

private:
    OboePlayer *mPlayer;
};

class OboePlayer {
public:
    OboePlayer(int32_t sampleRate, int32_t channelCount)
            : mSampleRate(sampleRate), mChannelCount(channelCount), mBytesPerFrame(channelCount * sizeof(int16_t)),
              mFifo(mBytesPerFrame, sampleRate / 5 /* ~200ms */), mCallback(this) {}

    bool start() {
        if (mStream) {
            return mStream->requestStart() == oboe::Result::OK;
        }

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Exclusive);
        builder.setSampleRate(mSampleRate);
        builder.setChannelCount(mChannelCount);
        builder.setFormat(oboe::AudioFormat::I16);
        builder.setCallback(&mCallback);

        oboe::Result result = builder.openStream(mStream);
        if (result != oboe::Result::OK || !mStream) {
            return false;
        }

        mPlaying.store(true);
        return mStream->requestStart() == oboe::Result::OK;
    }

    void stop() {
        mPlaying.store(false);
        if (mStream) {
            mStream->requestStop();
        }
    }

    void setPlaying(bool playing) {
        mPlaying.store(playing);
    }

    bool isPlaying() const {
        return mPlaying.load();
    }

    int32_t write(const uint8_t *data, int32_t numBytes) {
        if (!data || numBytes <= 0) {
            return 0;
        }
        int32_t frames = numBytes / static_cast<int32_t>(mBytesPerFrame);
        if (frames <= 0) {
            return 0;
        }
        return mFifo.write(data, frames);
    }

    int32_t readNow(void *audioData, int32_t numFrames) {
        return mFifo.readNow(audioData, numFrames);
    }

    void release() {
        mPlaying.store(false);
        if (mStream) {
            mStream->requestStop();
            mStream->close();
            mStream.reset();
        }
    }

private:
    friend class PlayerCallback;

    int32_t mSampleRate;
    int32_t mChannelCount;
    uint32_t mBytesPerFrame;

    oboe::FifoBuffer mFifo;
    PlayerCallback mCallback;

    std::shared_ptr<oboe::AudioStream> mStream;
    std::atomic<bool> mPlaying{false};
};

oboe::DataCallbackResult PlayerCallback::onAudioReady(oboe::AudioStream * /*audioStream*/, void *audioData, int32_t numFrames) {
    if (!mPlayer->isPlaying()) {
        std::memset(audioData, 0, static_cast<size_t>(numFrames) * mPlayer->mBytesPerFrame);
        return oboe::DataCallbackResult::Continue;
    }

    int32_t framesRead = mPlayer->readNow(audioData, numFrames);
    if (framesRead < numFrames) {
        auto *out = static_cast<uint8_t *>(audioData);
        std::memset(out + static_cast<size_t>(framesRead) * mPlayer->mBytesPerFrame, 0,
                    static_cast<size_t>(numFrames - framesRead) * mPlayer->mBytesPerFrame);
    }
    return oboe::DataCallbackResult::Continue;
}

static OboePlayer *fromHandle(jlong handle) {
    return reinterpret_cast<OboePlayer *>(handle);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_top_eiyooooo_easycontrol_app_client_AudioPlayer_nativeCreate(JNIEnv * /*env*/, jclass /*clazz*/, jint sampleRate, jint channelCount) {
    auto *player = new OboePlayer(sampleRate, channelCount);
    return reinterpret_cast<jlong>(player);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_eiyooooo_easycontrol_app_client_AudioPlayer_nativeStart(JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    auto *player = fromHandle(handle);
    if (!player) return JNI_FALSE;
    return player->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_top_eiyooooo_easycontrol_app_client_AudioPlayer_nativeStop(JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    auto *player = fromHandle(handle);
    if (!player) return;
    player->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_top_eiyooooo_easycontrol_app_client_AudioPlayer_nativeSetPlaying(JNIEnv * /*env*/, jclass /*clazz*/, jlong handle, jboolean playing) {
    auto *player = fromHandle(handle);
    if (!player) return;
    player->setPlaying(playing == JNI_TRUE);
}

extern "C" JNIEXPORT jint JNICALL
Java_top_eiyooooo_easycontrol_app_client_AudioPlayer_nativeWrite(JNIEnv *env, jclass /*clazz*/, jlong handle, jobject buffer, jint size) {
    auto *player = fromHandle(handle);
    if (!player || !buffer || size <= 0) return 0;

    auto *data = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (!data) return 0;

    return player->write(data, size);
}

extern "C" JNIEXPORT void JNICALL
Java_top_eiyooooo_easycontrol_app_client_AudioPlayer_nativeWriteAudio(JNIEnv *env, jclass /*clazz*/, jlong handle, jbyteArray data, jint size) {
    auto *player = fromHandle(handle);
    if (!player || !data || size <= 0) return;

    jsize len = env->GetArrayLength(data);
    if (len <= 0) return;
    jint n = std::min(size, static_cast<jint>(len));

    jboolean isCopy = JNI_FALSE;
    jbyte *bytes = env->GetByteArrayElements(data, &isCopy);
    if (!bytes) return;

    player->write(reinterpret_cast<const uint8_t *>(bytes), n);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_top_eiyooooo_easycontrol_app_client_AudioPlayer_writeAudio(JNIEnv *env, jobject thiz, jbyteArray data, jint size) {
    if (!thiz) return;
    jclass cls = env->GetObjectClass(thiz);
    if (!cls) return;
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    if (!fid) return;
    jlong handle = env->GetLongField(thiz, fid);
    Java_top_eiyooooo_easycontrol_app_client_AudioPlayer_nativeWriteAudio(env, cls, handle, data, size);
}

extern "C" JNIEXPORT void JNICALL
Java_top_eiyooooo_easycontrol_app_client_AudioPlayer_nativeRelease(JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    auto *player = fromHandle(handle);
    if (!player) return;
    player->release();
    delete player;
}
