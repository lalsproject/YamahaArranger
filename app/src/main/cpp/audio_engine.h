#pragma once
#include <oboe/Oboe.h>
#include <array>
#include <memory>
#include <mutex>
#include <string>
#include "voice.h"
#include "bassmidi_player.h"
#include "master_fx.h"

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    static constexpr int kMaxPolyphony=64;
    bool start(); void stop();
    bool loadSoundFont(const std::string& path);
    bool loadMelodySoundFont(const std::string& path);
    bool loadDrumSoundFont(const std::string& path);
    bool isSoundFontLoaded() const{return soundFont_.isLoaded();}
    bool isMelodySoundFontLoaded() const{return soundFont_.isMelodyLoaded();}
    bool isDrumSoundFontLoaded() const{return soundFont_.isDrumLoaded();}
    void unloadSoundFont();
    void setSoundFontWorkDir(const std::string& dir){soundFont_.setWorkDir(dir);}
    void setFxParam(int id,float value){masterFx_.setParam(id,value);}
    void noteOn(int midiNote,int rootNote,float velocity01,const float* sampleData,size_t sampleFrames,int sampleRateHz);
    void noteOff(int midiNote); void allNotesOff();
    void sfNoteOnChannel(int channel,int midiNote,float velocity01);
    void sfNoteOffChannel(int channel,int midiNote);
    void sfSetChannelPreset(int channel,int bank,int program);
    void sfSetChannelPresetWithName(int channel,int bank,int program,const std::string& voiceName);
    void sfSetChannelMixer(int channel,int volume,int pan,int expression,int reverbSend,int chorusSend);
    void sfSetChannelExpression(int channel,int expression);
    void sfSetKeyboardSustain(bool enabled);
    void sfSetKeyboardReleaseTime(int releaseTime);
    void sfSetMasterGain(float gain);
    std::string sfPresetList() const;
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*,void*,int32_t) override;
private:
    std::shared_ptr<oboe::AudioStream> stream_;
    // Grows the buffer by one burst whenever the stream under-runs, so a slow
    // moment becomes a (tiny) latency increase instead of audible crackle.
    std::unique_ptr<oboe::LatencyTuner> latencyTuner_;
    int overBudget_ = 0;   // audio-callback only
    fx::MasterFx masterFx_; // EQ -> chorus -> reverb on the final mix
    std::array<Voice,kMaxPolyphony> voices_;
    std::mutex voiceMutex_;
    int outputSampleRate_=48000;
    BassMidiPlayer soundFont_;
    float dcLastInL_=0,dcLastOutL_=0,dcLastInR_=0,dcLastOutR_=0;
};
