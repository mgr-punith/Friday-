// Prerecorded PCM test tool. This executable never opens a microphone.
#include <array>
#include <cstdint>
#include <cstdlib>
#include <ctime>
#include <fstream>
#include <iostream>
#include <vector>

extern "C" void* friday_wake_create(const uint8_t*, size_t, float, int, int);
extern "C" int friday_wake_process(void*, const int16_t*, size_t);
extern "C" void friday_wake_destroy(void*);

int main(int argc, char** argv) {
    if (argc != 6) {
        std::cerr << "Usage: friday_wake_eval MODEL PCM CUTOFF WINDOW FEATURE_STEP_MS\n";
        return 2;
    }
    std::ifstream modelFile(argv[1], std::ios::binary | std::ios::ate);
    if (!modelFile || modelFile.tellg() <= 0 || modelFile.tellg() > 2 * 1024 * 1024) return 3;
    size_t size = static_cast<size_t>(modelFile.tellg());
    modelFile.seekg(0);
    std::vector<uint8_t> model(size);
    if (!modelFile.read(reinterpret_cast<char*>(model.data()), size)) return 3;
    void* engine = friday_wake_create(model.data(), size, std::strtof(argv[3], nullptr),
                                     std::atoi(argv[4]), std::atoi(argv[5]));
    if (!engine) return 4;
    std::ifstream audio(argv[2], std::ios::binary);
    if (!audio) {
        friday_wake_destroy(engine);
        return 5;
    }
    std::array<int16_t, 160> pcm{};
    for (int i = 0; i < 400; ++i) {
        if (friday_wake_process(engine, pcm.data(), pcm.size()) < 0) {
            friday_wake_destroy(engine);
            return 7;
        }
    }
    uint64_t samples = 0;
    int events = 0;
    auto start = std::clock();
    std::cout << "{\"event_seconds\":[";
    while (audio.read(reinterpret_cast<char*>(pcm.data()), pcm.size() * 2) || audio.gcount()) {
        if (audio.gcount() % 2) {
            friday_wake_destroy(engine);
            return 6;
        }
        auto count = static_cast<size_t>(audio.gcount() / 2);
        samples += count;
        int result = friday_wake_process(engine, pcm.data(), count);
        if (result < 0) {
            friday_wake_destroy(engine);
            return 7;
        }
        if (result) {
            if (events++) std::cout << ',';
            std::cout << samples / 16000.0;
        }
    }
    auto cpu = static_cast<double>(std::clock() - start) / CLOCKS_PER_SEC;
    friday_wake_destroy(engine);
    std::cout << "],\"events\":" << events << ",\"audio_seconds\":" << samples / 16000.0
              << ",\"cpu_seconds\":" << cpu << "}\n";
    return 0;
}
