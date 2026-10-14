// Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0
#include <fileref.h>
#include <tfile.h>
#include <tpropertymap.h>
#include <mpegfile.h>
#include <id3v1tag.h>
#include <iostream>
#include <string>

static std::string hex(const std::string &text) {
    static const char digits[] = "0123456789abcdef";
    std::string result;
    for (unsigned char c : text) { result += digits[c >> 4]; result += digits[c & 15]; }
    return result;
}
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    // Read metadata only: synthetic codec fixtures deliberately don't contain playable audio.
    TagLib::FileRef file(argv[1], false);
    if (file.isNull() || !file.file()->isValid()) { std::cerr << "TagLib refused " << argv[1] << '\n'; return 3; }
    auto properties = file.file()->properties();
    // MPEG::properties() selects the ID3v2 map wholesale. Our writer migrates fields found only
    // in ID3v1 before dropping the trailer, so independently read that trailer too. Otherwise
    // successful preservation would appear as an unexpected new field after the write.
    if (auto *mpeg = dynamic_cast<TagLib::MPEG::File *>(file.file())) {
        if (mpeg->hasID3v1Tag()) {
            const auto legacy = mpeg->ID3v1Tag()->properties();
            for (auto it = legacy.begin(); it != legacy.end(); ++it)
                if (!properties.contains(it->first)) properties.insert(it->first, it->second);
        }
    }
    for (auto it = properties.begin(); it != properties.end(); ++it)
        for (const auto &value : it->second)
            std::cout << hex(it->first.to8Bit(true)) << '\t' << hex(value.to8Bit(true)) << '\n';
}
