// Test-only C interface. Built outside the app against pinned upstream sources.
#include "MicroWakeWordEngine.h"
#include <cmath>
#include <new>

extern "C" {
void* friday_wake_create(const uint8_t* model, size_t size, float cutoff, int window, int step) {
    if (!model || size == 0 || size > 2 * 1024 * 1024 || !std::isfinite(cutoff)
        || cutoff <= 0 || cutoff > 1 || window < 1 || window > 100 || step != 10) {
        return nullptr;
    }
    auto* engine = new (std::nothrow) MicroWakeWordEngine(model, size, 16000, step, cutoff, window);
    if (!engine) return nullptr;
    if (!engine->isInitialized()) {
        delete engine;
        return nullptr;
    }
    return engine;
}

int friday_wake_process(void* engine, const int16_t* pcm, size_t samples) {
    if (!engine || !pcm || samples > 480) return -1;
    return static_cast<MicroWakeWordEngine*>(engine)->processAudio(pcm, samples) ? 1 : 0;
}

void friday_wake_destroy(void* engine) {
    delete static_cast<MicroWakeWordEngine*>(engine);
}
}
