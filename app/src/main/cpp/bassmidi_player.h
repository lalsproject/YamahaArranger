#pragma once
#include <string>
#include <mutex>
#include <array>
#include <atomic>
#include <unordered_map>
#include <vector>
#include <bass.h>
#include <bassmidi.h>

class BassMidiPlayer {
public:
    BassMidiPlayer();
    ~BassMidiPlayer();
    bool load(const std::string& path);
    bool loadMelody(const std::string& path);
    bool loadDrum(const std::string& path);
    void unload();
    // Directory for generated working copies (app cache). Optional.
    void setWorkDir(const std::string& dir);
    bool isLoaded() const { return stream_ != 0 && (melodyFont_ != 0 || drumFont_ != 0); }
    bool isMelodyLoaded() const { return stream_ != 0 && melodyFont_ != 0; }
    bool isDrumLoaded() const { return stream_ != 0 && drumFont_ != 0; }
    // Realtime audio thread entry point. Deliberately does NOT take mutex_:
    // preset resolution / font preloading / SF2 loading hold that mutex for a
    // long time and would starve the callback (= dropouts). BASS stream calls
    // are thread-safe, so the callback only needs the stream handle.
    void render(float* out, int numFrames);
    // Called from the audio thread when rendering keeps exceeding its time
    // budget: lowers the BASSMIDI sample-rate-conversion quality one step
    // (8-point sinc -> linear) instead of letting the device glitch. Returns
    // true if a step was taken.
    bool degradeQuality();
    void noteOn(int channel, int key, float velocity);
    void noteOff(int channel, int key);
    void allNotesOff();
    void setChannelPreset(int channel, int bank, int program, const std::string& voiceName = {});
    void setChannelMixer(int channel, int volume, int pan, int expression, int reverbSend, int chorusSend);
    void setChannelExpression(int channel, int expression);
    void setKeyboardSustain(bool enabled);
    void setKeyboardReleaseTime(int releaseTime);
    void setMasterGain(float gain);
    std::string presetList() const;
private:
    struct ChannelState {
        int bankMsb = 0;
        int bankLsb = 0;
        int program = 0;
        bool drum = false;
        bool initialized = false;
    };
    struct DrumPresetEntry {
        int bank = 0;
        int program = 0;
    };
    struct NormalizedBankMap {
        int rawBank = 0;
        int virtualBank = 0;
        int midiMsb = 0;
        int midiLsb = 0;
    };
    struct MelodicPresetEntry {
        int bank = 0;
        int program = 0;
        std::string name;
    };
    bool ensureEngine();
    bool loadRole(const std::string& path, bool drum);
    bool applyFonts();
    bool normalizeMelodySf2(const std::string& sourcePath, std::string& fontPathOut);
    std::string normalizedPathFor(const std::string& sourcePath) const;
    void send(int channel, DWORD event, DWORD param);
    void preloadCurrentPreset(int channel);
    void rebuildDrumPresetCache(const std::string& path,
                                std::vector<DrumPresetEntry>& cache);
    void rebuildMelodyPresetCache(const std::string& path);
    struct ResolvedPreset { int bank; int program; std::string name; };
    mutable std::unordered_map<std::string, ResolvedPreset> resolveCache_;
    bool findMelodicPresetUncached(const std::string& path, int requestedBank, int requestedProgram,
                           const std::string& voiceName, int& sourceBank, int& sourceProgram,
                           std::string& matchedName) const;
    bool findMelodicPreset(const std::string& path, int requestedBank, int requestedProgram,
                           const std::string& voiceName, int& sourceBank, int& sourceProgram,
                           std::string& matchedName) const;
    bool findDrumPreset(const std::string& path, int requestedProgram,
                        int& sourceBank, int& sourceProgram) const;
    HSTREAM stream_ = 0;
    std::atomic<HSTREAM> renderStream_{0};
    HSOUNDFONT melodyFont_ = 0;
    HSOUNDFONT drumFont_ = 0;
    bool bassInitialized_ = false;
    mutable std::mutex mutex_;
    int sampleRate_ = 48000;
    std::atomic<int> interpolation_{2};
    // 256 matches the polyphony of the PSR-SX class instruments. 1000 allowed
    // runaway voice counts (long releases + sustain) that exceed the CPU of
    // mid-range devices and caused sudden dropouts.
    int voices_ = 256;
    float soundFontVolume_ = 0.90f;
    std::array<ChannelState, 16> channels_{};
    std::vector<DrumPresetEntry> melodyDrumPresetCache_;
    std::vector<DrumPresetEntry> drumDrumPresetCache_;
    std::vector<MelodicPresetEntry> melodyPresetCache_;
    std::vector<NormalizedBankMap> normalizedBanks_;
    std::string melodyPath_;
    std::string melodyBassPath_;
    std::string drumPath_;
    std::string workDir_;
};
