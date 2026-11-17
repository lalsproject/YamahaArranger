#pragma once
// Master effects applied to the final stereo mix in the realtime callback:
//   3-band EQ (low shelf / peaking mid / high shelf)  ->  chorus  ->  reverb
//
// Pure C++17, no Android/BASS dependency, so it is unit-tested on a host.
// Parameters are written from any thread (atomics); the audio thread picks up
// changes at the start of each block. No allocation, locks or logging happen in
// process().

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace fx {

constexpr float kPi = 3.14159265358979323846f;

// Parameter ids shared with Kotlin (AudioEngineManager.FX_*).
enum Param : int {
    kEnabled = 0,      // 0 / 1
    kReverbLevel,      // 0..127   wet send
    kReverbTime,       // 0..127   room size / decay
    kReverbDamp,       // 0..127   high-frequency damping
    kChorusLevel,      // 0..127   wet mix
    kChorusRate,       // 0..127   LFO 0.1 .. 5 Hz
    kChorusDepth,      // 0..127   modulation depth
    kEqLow,            // -12..+12 dB (100 Hz shelf)
    kEqMid,            // -12..+12 dB (1 kHz peak)
    kEqHigh,           // -12..+12 dB (8 kHz shelf)
    kParamCount
};

// ---- RBJ biquad (transposed direct form II) ------------------------------
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1[2] = {0, 0}, z2[2] = {0, 0};

    void setIdentity() { b0 = 1; b1 = b2 = a1 = a2 = 0; }

    void setLowShelf(float sr, float f0, float gainDb) {
        const float A = std::pow(10.0f, gainDb / 40.0f);
        const float w = 2.0f * kPi * f0 / sr, cw = std::cos(w), sw = std::sin(w);
        const float alpha = sw / 2.0f * std::sqrt(2.0f);  // S = 1
        const float sA = 2.0f * std::sqrt(A) * alpha;
        const float a0 = (A + 1) + (A - 1) * cw + sA;
        b0 = A * ((A + 1) - (A - 1) * cw + sA) / a0;
        b1 = 2 * A * ((A - 1) - (A + 1) * cw) / a0;
        b2 = A * ((A + 1) - (A - 1) * cw - sA) / a0;
        a1 = -2 * ((A - 1) + (A + 1) * cw) / a0;
        a2 = ((A + 1) + (A - 1) * cw - sA) / a0;
    }
    void setHighShelf(float sr, float f0, float gainDb) {
        const float A = std::pow(10.0f, gainDb / 40.0f);
        const float w = 2.0f * kPi * f0 / sr, cw = std::cos(w), sw = std::sin(w);
        const float alpha = sw / 2.0f * std::sqrt(2.0f);
        const float sA = 2.0f * std::sqrt(A) * alpha;
        const float a0 = (A + 1) - (A - 1) * cw + sA;
        b0 = A * ((A + 1) + (A - 1) * cw + sA) / a0;
        b1 = -2 * A * ((A - 1) + (A + 1) * cw) / a0;
        b2 = A * ((A + 1) + (A - 1) * cw - sA) / a0;
        a1 = 2 * ((A - 1) - (A + 1) * cw) / a0;
        a2 = ((A + 1) - (A - 1) * cw - sA) / a0;
    }
    void setPeaking(float sr, float f0, float gainDb, float q) {
        const float A = std::pow(10.0f, gainDb / 40.0f);
        const float w = 2.0f * kPi * f0 / sr, cw = std::cos(w), sw = std::sin(w);
        const float alpha = sw / (2.0f * q);
        const float a0 = 1 + alpha / A;
        b0 = (1 + alpha * A) / a0;
        b1 = -2 * cw / a0;
        b2 = (1 - alpha * A) / a0;
        a1 = -2 * cw / a0;
        a2 = (1 - alpha / A) / a0;
    }
    inline float process(int ch, float x) {
        const float y = b0 * x + z1[ch];
        z1[ch] = b1 * x - a1 * y + z2[ch];
        z2[ch] = b2 * x - a2 * y;
        return y;
    }
    void reset() { z1[0] = z1[1] = z2[0] = z2[1] = 0; }
};

// Denormals make ARM float math 10-100x slower; reverb tails decay straight
// into them. Flush anything below the audible floor.
inline float flushDenormal(float x) { return std::fabs(x) < 1e-20f ? 0.0f : x; }

// ---- Freeverb building blocks -------------------------------------------
struct Comb {
    std::vector<float> buf;
    size_t idx = 0;
    float store = 0;
    void init(size_t n) { buf.assign(n, 0.0f); idx = 0; store = 0; }
    inline float process(float in, float feedback, float damp) {
        const float out = buf[idx];
        store = flushDenormal(out * (1.0f - damp) + store * damp);
        buf[idx] = flushDenormal(in + store * feedback);
        if (++idx >= buf.size()) idx = 0;
        return out;
    }
};
struct Allpass {
    std::vector<float> buf;
    size_t idx = 0;
    void init(size_t n) { buf.assign(n, 0.0f); idx = 0; }
    inline float process(float in) {
        const float b = buf[idx];
        const float out = -in + b;
        buf[idx] = flushDenormal(in + b * 0.5f);
        if (++idx >= buf.size()) idx = 0;
        return out;
    }
};

class MasterFx {
public:
    void init(int sampleRate) {
        sr_ = static_cast<float>(std::max(8000, sampleRate));
        const float scale = sr_ / 44100.0f;
        static const int combTune[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int apTune[kAllpass] = {556, 441, 341, 225};
        static const int stereoSpread = 23;
        for (int i = 0; i < kCombs; ++i) {
            combL_[i].init(static_cast<size_t>(combTune[i] * scale));
            combR_[i].init(static_cast<size_t>((combTune[i] + stereoSpread) * scale));
        }
        for (int i = 0; i < kAllpass; ++i) {
            apL_[i].init(static_cast<size_t>(apTune[i] * scale));
            apR_[i].init(static_cast<size_t>((apTune[i] + stereoSpread) * scale));
        }
        chorusLen_ = static_cast<size_t>(sr_ * 0.060f) + 4;   // 60 ms max
        for (int c = 0; c < 2; ++c) chorusBuf_[c].assign(chorusLen_, 0.0f);
        chorusWrite_ = 0;
        chorusPhase_ = 0.0f;
        dirty_.store(true, std::memory_order_release);
        for (auto& b : eq_) b.reset();
    }

    // Any thread.
    void setParam(int id, float value) {
        if (id < 0 || id >= kParamCount) return;
        params_[id].store(value, std::memory_order_relaxed);
        dirty_.store(true, std::memory_order_release);
    }
    float getParam(int id) const {
        return (id < 0 || id >= kParamCount) ? 0.0f : params_[id].load(std::memory_order_relaxed);
    }

    // Audio thread only. `io` = interleaved stereo float.
    void process(float* io, int frames) {
        if (dirty_.exchange(false, std::memory_order_acq_rel)) refresh();
        if (!enabled_) return;

        for (int n = 0; n < frames; ++n) {
            float l = io[2 * n], r = io[2 * n + 1];

            if (eqActive_) {
                for (auto& b : eq_) { l = b.process(0, l); r = b.process(1, r); }
            }

            if (chorusMix_ > 0.0f) {
                chorusBuf_[0][chorusWrite_] = l;
                chorusBuf_[1][chorusWrite_] = r;
                // Two LFOs in quadrature for a wide stereo image.
                const float lfoL = std::sin(chorusPhase_);
                const float lfoR = std::sin(chorusPhase_ + 1.5707963f);
                const float baseMs = 12.0f;
                const float dL = (baseMs + chorusDepthMs_ * lfoL) * 0.001f * sr_;
                const float dR = (baseMs + chorusDepthMs_ * lfoR) * 0.001f * sr_;
                const float wl = readDelay(0, dL), wr = readDelay(1, dR);
                l = l * (1.0f - 0.5f * chorusMix_) + wl * chorusMix_;
                r = r * (1.0f - 0.5f * chorusMix_) + wr * chorusMix_;
                if (++chorusWrite_ >= chorusLen_) chorusWrite_ = 0;
                chorusPhase_ += chorusInc_;
                if (chorusPhase_ > 2.0f * kPi) chorusPhase_ -= 2.0f * kPi;
            }

            if (reverbWet_ > 0.0f) {
                const float in = (l + r) * 0.015f;   // Freeverb fixed input gain
                float oL = 0, oR = 0;
                for (int i = 0; i < kCombs; ++i) {
                    oL += combL_[i].process(in, reverbFeedback_, reverbDamp_);
                    oR += combR_[i].process(in, reverbFeedback_, reverbDamp_);
                }
                for (int i = 0; i < kAllpass; ++i) { oL = apL_[i].process(oL); oR = apR_[i].process(oR); }
                l += oL * reverbWet_;
                r += oR * reverbWet_;
            }

            io[2 * n] = l;
            io[2 * n + 1] = r;
        }
    }

private:
    static constexpr int kCombs = 8;
    static constexpr int kAllpass = 4;

    float readDelay(int ch, float delaySamples) const {
        float pos = static_cast<float>(chorusWrite_) - delaySamples;
        while (pos < 0.0f) pos += static_cast<float>(chorusLen_);
        const size_t i0 = static_cast<size_t>(pos) % chorusLen_;
        const size_t i1 = (i0 + 1) % chorusLen_;
        const float frac = pos - std::floor(pos);
        return chorusBuf_[ch][i0] * (1.0f - frac) + chorusBuf_[ch][i1] * frac;
    }

    void refresh() {
        auto p = [&](int id) { return params_[id].load(std::memory_order_relaxed); };
        enabled_ = p(kEnabled) >= 0.5f;

        const float rl = std::clamp(p(kReverbLevel), 0.0f, 127.0f) / 127.0f;
        reverbWet_ = rl * 0.45f;
        // Feedback 0.70 .. 0.98 : short room .. long hall (stable < 1).
        reverbFeedback_ = 0.70f + 0.28f * (std::clamp(p(kReverbTime), 0.0f, 127.0f) / 127.0f);
        reverbDamp_ = 0.05f + 0.75f * (std::clamp(p(kReverbDamp), 0.0f, 127.0f) / 127.0f);

        chorusMix_ = std::clamp(p(kChorusLevel), 0.0f, 127.0f) / 127.0f * 0.7f;
        const float rate = 0.1f + 4.9f * (std::clamp(p(kChorusRate), 0.0f, 127.0f) / 127.0f);
        chorusInc_ = 2.0f * kPi * rate / sr_;
        chorusDepthMs_ = 0.5f + 7.5f * (std::clamp(p(kChorusDepth), 0.0f, 127.0f) / 127.0f);

        const float lo = std::clamp(p(kEqLow), -12.0f, 12.0f);
        const float mi = std::clamp(p(kEqMid), -12.0f, 12.0f);
        const float hi = std::clamp(p(kEqHigh), -12.0f, 12.0f);
        eqActive_ = std::fabs(lo) > 0.05f || std::fabs(mi) > 0.05f || std::fabs(hi) > 0.05f;
        eq_[0].setLowShelf(sr_, 100.0f, lo);
        eq_[1].setPeaking(sr_, 1000.0f, mi, 0.9f);
        eq_[2].setHighShelf(sr_, 8000.0f, hi);
    }

    float sr_ = 48000.0f;
    std::atomic<float> params_[kParamCount] = {};
    std::atomic<bool> dirty_{true};
    bool enabled_ = false;
    bool eqActive_ = false;
    Biquad eq_[3];

    Comb combL_[kCombs], combR_[kCombs];
    Allpass apL_[kAllpass], apR_[kAllpass];
    float reverbWet_ = 0, reverbFeedback_ = 0.84f, reverbDamp_ = 0.2f;

    std::vector<float> chorusBuf_[2];
    size_t chorusLen_ = 0, chorusWrite_ = 0;
    float chorusPhase_ = 0, chorusInc_ = 0, chorusMix_ = 0, chorusDepthMs_ = 3.0f;
};

}  // namespace fx
