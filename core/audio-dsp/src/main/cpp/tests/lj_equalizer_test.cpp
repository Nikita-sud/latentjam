/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
#include "lj_equalizer.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <thread>
#include <vector>

static std::atomic<bool> watch{false};
static std::atomic<int> allocations{0};
void *operator new(std::size_t size) { if (watch.load()) ++allocations; if (void *p = std::malloc(size)) return p; throw std::bad_alloc(); }
void operator delete(void *p) noexcept { std::free(p); }
void operator delete(void *p, std::size_t) noexcept { std::free(p); }
static void require(bool value, const char *message) { if (!value) { std::fprintf(stderr,"FAIL: %s\n",message); std::abort(); } }

int main() {
    int32_t levels[10] = {};
    for (int rate : {8000,16000,44100,48000,96000,192000}) for (int channels : {1,2,6}) {
        auto *eq = lj_eq_create(rate,channels); require(eq,"create");
        lj_eq_set_settings(eq,0,levels,0); lj_eq_reset(eq);
        const int latency=lj_eq_latency_frames(eq), frames=latency+1000;
        std::vector<float> input(frames*channels), output;
        for (int i=0;i<1000*channels;++i) input[i]=float((i%137)-68)/128;
        output=input; lj_eq_process_f32(eq,output.data(),frames);
        for (int i=0;i<1000*channels;++i) require(output[latency*channels+i]==input[i],"float bypass must be bit-exact");
        lj_eq_reset(eq);
        std::vector<int16_t> pcm(4000*channels), decoded(pcm.size());
        for (size_t i=0;i<pcm.size();++i) pcm[i]=int16_t(i%65536-32768);
        int written=0;
        for (int offset=0;offset<4000;offset+=137) {
            int count=std::min(137,4000-offset);
            written+=lj_eq_render_s16(eq,pcm.data()+offset*channels,decoded.data()+written*channels,count);
        }
        written+=lj_eq_drain_s16(eq,decoded.data()+written*channels,4000-written);
        require(written==4000 && pcm==decoded,"PCM16 chunking/EOS must preserve frames and bits");
        lj_eq_destroy(eq);
    }
    auto *eq=lj_eq_create(48000,2);
    for (auto &level:levels) level=1200;
    lj_eq_set_settings(eq,1,levels,1000); lj_eq_reset(eq);
    std::vector<float> signal(48000*2);
    for (int i=0;i<48000;++i) { signal[2*i]=float(3*std::sin(2*3.141592653589793*40*i/48000)); signal[2*i+1]=-signal[2*i]/4; }
    watch=true;
    for (int i=0;i<48000;i+=512) lj_eq_process_f32(eq,signal.data()+2*i,std::min(512,48000-i));
    lj_eq_set_settings(eq,1,levels,0);
    watch=false;
    require(allocations.load()==0,"processing and publication must not allocate");
    for (int i=0;i<48000;++i) {
        require(std::isfinite(signal[2*i]) && std::abs(signal[2*i])<=0.944062f,"sample ceiling");
        require(std::abs(signal[2*i+1]+signal[2*i]/4)<0.000001f,"stereo linking");
    }
    lj_eq_reset(eq);
    std::atomic<bool> start{false};
    std::thread producer([&] {
        while (!start.load()) {}
        for (int i=0;i<20000;++i) {
            int32_t curve[10]; for (int j=0;j<10;++j) curve[j]=(i+j)%2?1200:-1200;
            lj_eq_set_settings(eq,i%3!=0,curve,i%1001);
        }
        int32_t flat[10]={}; lj_eq_set_settings(eq,0,flat,0);
    });
    float block[256*2]={}; start=true;
    for (int i=0;i<20000;++i) {
        for (int j=0;j<512;++j) block[j]=j%2?0.2f:-0.2f;
        lj_eq_process_f32(eq,block,256);
        for (float sample:block) require(std::isfinite(sample) && std::abs(sample)<=1,"live concurrent publication");
    }
    producer.join();
    for (int i=0;i<400;++i) { for (float &sample:block) sample=0.1f; lj_eq_process_f32(eq,block,256); }
    for (float sample:block) require(sample==0.1f,"latest bypass command must not be lost");
    lj_eq_destroy(eq);
    std::printf("PASS: bypass, layouts/rates, EOS, stereo ceiling, zero allocations, concurrent settings\n");
}
